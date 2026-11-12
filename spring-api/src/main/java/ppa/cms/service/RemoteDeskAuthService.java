package ppa.cms.service;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ppa.cms.persistence.RemoteDeskChallenge;
import ppa.cms.persistence.RemoteDeskChallengeRepository;
import ppa.cms.persistence.RemoteDeskCredential;
import ppa.cms.persistence.RemoteDeskCredentialRepository;
import ppa.cms.persistence.RemoteDeskGrant;
import ppa.cms.persistence.RemoteDeskGrantRepository;
import ppa.cms.persistence.UserAccount;
import ppa.cms.persistence.UserAccountRepository;
import ppa.cms.security.Access;
import ppa.cms.security.AttemptWindow;
import ppa.cms.security.WebAuthnCeremony;
import ppa.cms.support.Texts;
import ppa.cms.web.ApiException;

@Service
public class RemoteDeskAuthService {

    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
    private static final Duration GRANT_TTL = Duration.ofMinutes(2);
    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(10);
    private static final int MAX_ATTEMPTS = 8;

    private final UserAccountRepository users;
    private final RemoteDeskCredentialRepository credentials;
    private final RemoteDeskChallengeRepository challenges;
    private final RemoteDeskGrantRepository grants;
    private final PasswordEncoder passwords;
    private final AttemptWindow attempts;
    private final SecureRandom random = new SecureRandom();

    public RemoteDeskAuthService(
            UserAccountRepository users,
            RemoteDeskCredentialRepository credentials,
            RemoteDeskChallengeRepository challenges,
            RemoteDeskGrantRepository grants,
            PasswordEncoder passwords,
            AttemptWindow attempts
    ) {
        this.users = users;
        this.credentials = credentials;
        this.challenges = challenges;
        this.grants = grants;
        this.passwords = passwords;
        this.attempts = attempts;
    }

    public Map<String, Object> status(UserAccount actor) {
        return Map.of("pin_set", hasPin(actor));
    }

    @Transactional
    public Map<String, Object> challenge(UserAccount actor, HttpServletRequest request) {
        String rpId = WebAuthnCeremony.rpId(request.getHeader("Origin"));
        List<RemoteDeskCredential> saved = credentials.findByUserId(actor.getId());
        byte[] challenge = new byte[32];
        random.nextBytes(challenge);
        challenges.deleteByUserId(actor.getId());
        RemoteDeskChallenge stored = new RemoteDeskChallenge();
        stored.setUserId(actor.getId());
        stored.setPurpose(saved.isEmpty() ? "register" : "authenticate");
        stored.setChallenge(challenge);
        stored.setExpiresAt(Instant.now().plus(CHALLENGE_TTL));
        challenges.save(stored);

        Map<String, Object> publicKey = new LinkedHashMap<>();
        publicKey.put("challenge", WebAuthnCeremony.base64Url(challenge));
        publicKey.put("timeout", 60_000);
        publicKey.put("userVerification", "required");
        if (saved.isEmpty()) {
            byte[] userId = new byte[8];
            long id = actor.getId();
            for (int i = 7; i >= 0; i--) {
                userId[i] = (byte) (id & 0xff);
                id >>= 8;
            }
            publicKey.put("rp", Map.of("name", "CMS-PPA", "id", rpId));
            publicKey.put("user", Map.of(
                    "id", WebAuthnCeremony.base64Url(userId),
                    "name", actor.getUsername(),
                    "displayName", actor.getName()
            ));
            publicKey.put("pubKeyCredParams", List.of(
                    Map.of("type", "public-key", "alg", -7),
                    Map.of("type", "public-key", "alg", -257)
            ));
            publicKey.put("authenticatorSelection", Map.of(
                    "authenticatorAttachment", "platform",
                    "userVerification", "required",
                    "residentKey", "preferred"
            ));
            publicKey.put("attestation", "none");
        } else {
            publicKey.put("rpId", rpId);
            publicKey.put("allowCredentials", saved.stream().map(credential -> Map.of(
                    "type", "public-key",
                    "id", WebAuthnCeremony.base64Url(credential.getCredentialId())
            )).toList());
        }
        return Map.of("mode", stored.getPurpose(), "publicKey", publicKey);
    }

    @Transactional
    public Map<String, Object> authorize(UserAccount actor, long systemId, String type, Map<String, Object> body, HttpServletRequest request) {
        String permission = switch (type == null ? "" : type) {
            case "anydesk" -> "can-connect-anydesk";
            case "rustdesk" -> "can-connect-rustdesk";
            case "rdp" -> "can-connect-rdp";
            case "ssh" -> "can-connect-ssh";
            default -> "can-view-remote-access";
        };
        Access.requirePermission(actor, permission, actor != null && !actor.disabled());
        String key = "remote-desk-auth:" + actor.getId();
        if (attempts.tooMany(key, MAX_ATTEMPTS, ATTEMPT_WINDOW)) {
            throw ApiException.message(429, "Too many attempts. Wait " + attempts.retryAfterSeconds(key, ATTEMPT_WINDOW) + " seconds and try again.");
        }
        String method = Texts.text(body.get("method"));
        if ("fingerprint".equals(method)) {
            verifyFingerprint(actor, body, request);
        } else if ("pin".equals(method)) {
            verifyPin(actor, body, key);
        } else if ("password".equals(method)) {
            verifyPassword(actor, body, key);
        } else {
            throw ApiException.errors(Map.of("method", List.of("Choose fingerprint, PIN, or your account password.")));
        }
        attempts.clear(key);
        return Map.of("grant", issueGrant(actor, systemId, type));
    }

    /** Checks a PIN or account password, then allows one view of that system's credentials. */
    @Transactional
    public Map<String, Object> confirmDetails(UserAccount actor, long systemId, Map<String, Object> body) {
        Access.requirePermission(actor, "can-view-remote-access", actor != null && !actor.disabled());
        String key = "remote-desk-auth:" + actor.getId();
        if (attempts.tooMany(key, MAX_ATTEMPTS, ATTEMPT_WINDOW)) {
            throw ApiException.message(429, "Too many attempts. Wait " + attempts.retryAfterSeconds(key, ATTEMPT_WINDOW) + " seconds and try again.");
        }
        String method = Texts.text(body.get("method"));
        if ("pin".equals(method)) {
            verifyPin(actor, body, key);
        } else if ("password".equals(method)) {
            verifyPassword(actor, body, key);
        } else {
            throw ApiException.errors(Map.of("method", List.of("Choose a PIN or your account password.")));
        }
        attempts.clear(key);
        return Map.of("grant", issueGrant(actor, systemId, "details"));
    }

    /** Replaces this account's PIN. Update checks the current PIN. Reset checks the account password. */
    @Transactional
    public Map<String, Object> changePin(UserAccount actor, Map<String, Object> body) {
        if (actor == null || actor.disabled()) {
            throw ApiException.message(403, actor != null && actor.disabled() ? Access.DISABLED : "This action is unauthorized.");
        }
        if (!hasPin(actor)) {
            throw ApiException.errors(Map.of("pin", List.of("Set a PIN from this dialog before changing it.")));
        }
        String key = "remote-desk-auth:" + actor.getId();
        if (attempts.tooMany(key, MAX_ATTEMPTS, ATTEMPT_WINDOW)) {
            throw ApiException.message(429, "Too many attempts. Wait " + attempts.retryAfterSeconds(key, ATTEMPT_WINDOW) + " seconds and try again.");
        }
        String action = Texts.text(body.get("action"));
        if ("update".equals(action)) {
            String current = Texts.text(body.get("pin"));
            if (current == null || !current.matches("\\d{4,8}") || !passwords.matches(current, actor.getRemotePin())) {
                attempts.hit(key, ATTEMPT_WINDOW);
                throw ApiException.errors(Map.of("pin", List.of("That PIN is incorrect.")));
            }
        } else if ("reset".equals(action)) {
            verifyPassword(actor, body, key);
        } else {
            throw ApiException.errors(Map.of("action", List.of("Choose update or reset.")));
        }
        String newPin = readNewPin(body);
        if ("update".equals(action) && newPin.equals(Texts.text(body.get("pin")))) {
            throw ApiException.errors(Map.of("new_pin", List.of("Choose a different PIN.")));
        }
        actor.setRemotePin(passwords.encode(newPin));
        users.save(actor);
        attempts.clear(key);
        return Map.of("message", "update".equals(action) ? "Your PIN has been updated." : "Your PIN has been reset.");
    }

    @Transactional
    public void consume(UserAccount actor, long systemId, Map<String, Object> body) {
        String grant = Texts.text(body.get("grant"));
        String type = Texts.text(body.get("type"));
        boolean details = "details".equals(type);
        if (grant == null) {
            throw ApiException.errors(Map.of("grant", List.of(details
                    ? "Confirm it's you with your PIN or account password before viewing these credentials."
                    : "Confirm it's you before opening this remote desk.")));
        }
        RemoteDeskGrant stored = grants.findByTokenHash(sha256(grant)).orElse(null);
        if (stored == null
                || !actor.getId().equals(stored.getUserId())
                || stored.getSystemId() != systemId
                || type == null
                || !type.equals(stored.getDeskType())
                || !stored.getExpiresAt().isAfter(Instant.now())) {
            throw ApiException.errors(Map.of("grant", List.of(details
                    ? "That confirmation expired. Enter your PIN or account password again."
                    : "That confirmation expired. Confirm it's you again.")));
        }
        grants.delete(stored);
    }

    private String issueGrant(UserAccount actor, long systemId, String type) {
        byte[] token = new byte[32];
        random.nextBytes(token);
        String grant = WebAuthnCeremony.base64Url(token);
        RemoteDeskGrant stored = new RemoteDeskGrant();
        stored.setUserId(actor.getId());
        stored.setSystemId(systemId);
        stored.setDeskType(type);
        stored.setTokenHash(sha256(grant));
        stored.setExpiresAt(Instant.now().plus(GRANT_TTL));
        grants.save(stored);
        return grant;
    }

    private void verifyPin(UserAccount actor, Map<String, Object> body, String attemptKey) {
        Map<String, List<String>> errors = Texts.errors();
        String pin = Texts.text(body.get("pin"));
        boolean pinSet = hasPin(actor);
        if (pin == null || !pin.matches("\\d{4,8}")) {
            Texts.add(errors, "pin", "Enter a PIN of 4 to 8 digits.");
        } else if (!pinSet) {
            String confirmation = Texts.text(body.get("pin_confirmation"));
            if (confirmation == null) {
                Texts.add(errors, "pin_confirmation", "Enter the same PIN again.");
            } else if (!pin.equals(confirmation)) {
                Texts.add(errors, "pin_confirmation", "Those PINs don't match.");
            }
        }
        Texts.finish(errors);
        if (pinSet && !passwords.matches(pin, actor.getRemotePin())) {
            attempts.hit(attemptKey, ATTEMPT_WINDOW);
            throw ApiException.errors(Map.of("pin", List.of("That PIN is incorrect.")));
        }
        if (!pinSet) {
            actor.setRemotePin(passwords.encode(pin));
            users.save(actor);
        }
    }

    private static String readNewPin(Map<String, Object> body) {
        Map<String, List<String>> errors = Texts.errors();
        String pin = Texts.text(body.get("new_pin"));
        String confirmation = Texts.text(body.get("new_pin_confirmation"));
        if (pin == null || !pin.matches("\\d{4,8}")) {
            Texts.add(errors, "new_pin", "Enter a PIN of 4 to 8 digits.");
        } else if (confirmation == null) {
            Texts.add(errors, "new_pin_confirmation", "Enter the same PIN again.");
        } else if (!pin.equals(confirmation)) {
            Texts.add(errors, "new_pin_confirmation", "Those PINs don't match.");
        }
        Texts.finish(errors);
        return pin;
    }

    private void verifyPassword(UserAccount actor, Map<String, Object> body, String attemptKey) {
        String password = body.get("password") == null ? "" : String.valueOf(body.get("password"));
        if (password.isBlank()) {
            throw ApiException.errors(Map.of("password", List.of("Enter your account password.")));
        }
        if (!passwords.matches(password, actor.getPassword())) {
            attempts.hit(attemptKey, ATTEMPT_WINDOW);
            throw ApiException.errors(Map.of("password", List.of("That password is incorrect.")));
        }
    }

    private void verifyFingerprint(UserAccount actor, Map<String, Object> body, HttpServletRequest request) {
        Object raw = body.get("credential");
        if (!(raw instanceof Map<?, ?> credential)) {
            throw ApiException.errors(Map.of("fingerprint", List.of("Scan your fingerprint before opening this remote desk.")));
        }
        Object responseRaw = credential.get("response");
        if (!(responseRaw instanceof Map<?, ?> response)) {
            throw ApiException.errors(Map.of("fingerprint", List.of("Scan your fingerprint before opening this remote desk.")));
        }
        RemoteDeskChallenge challenge = challenges.findByUserId(actor.getId()).stream()
                .filter(item -> item.getExpiresAt().isAfter(Instant.now()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> ApiException.errors(Map.of("fingerprint", List.of("The fingerprint check expired. Scan again."))));
        String origin = request.getHeader("Origin");
        byte[] clientData = WebAuthnCeremony.base64Url(Texts.text(response.get("clientDataJSON")));
        if ("register".equals(challenge.getPurpose())) {
            WebAuthnCeremony.VerifiedCredential verified = WebAuthnCeremony.registration(
                    clientData,
                    WebAuthnCeremony.base64Url(Texts.text(response.get("attestationObject"))),
                    challenge.getChallenge(),
                    origin
            );
            RemoteDeskCredential saved = new RemoteDeskCredential();
            saved.setUserId(actor.getId());
            saved.setCredentialId(verified.credentialId());
            saved.setPublicKey(verified.publicKey());
            saved.setAlgorithm(verified.algorithm());
            saved.setSignCount(verified.signCount());
            credentials.save(saved);
        } else {
            byte[] credentialId = WebAuthnCeremony.base64Url(Texts.text(credential.get("id")));
            RemoteDeskCredential saved = credentials.findByUserId(actor.getId()).stream()
                    .filter(item -> Arrays.equals(item.getCredentialId(), credentialId))
                    .findFirst()
                    .orElseThrow(() -> ApiException.errors(Map.of("fingerprint", List.of("This fingerprint isn't enrolled for your account."))));
            long signCount = WebAuthnCeremony.assertion(
                    clientData,
                    WebAuthnCeremony.base64Url(Texts.text(response.get("authenticatorData"))),
                    WebAuthnCeremony.base64Url(Texts.text(response.get("signature"))),
                    challenge.getChallenge(),
                    origin,
                    saved.getPublicKey(),
                    saved.getAlgorithm(),
                    saved.getSignCount()
            );
            saved.setSignCount(signCount);
            credentials.save(saved);
        }
        challenges.deleteByUserId(actor.getId());
    }

    private static boolean hasPin(UserAccount actor) {
        return actor.getRemotePin() != null && !actor.getRemotePin().isBlank();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte piece : digest) {
                hex.append(String.format("%02x", piece));
            }
            return hex.toString();
        } catch (Exception exception) {
            throw ApiException.message(500, "The confirmation couldn't be saved.");
        }
    }
}
