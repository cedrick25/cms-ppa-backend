package ppa.cms.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ppa.cms.config.CmsProperties;
import ppa.cms.domain.CmsAuditAction;
import ppa.cms.domain.Role;
import ppa.cms.persistence.ApiToken;
import ppa.cms.persistence.ApiTokenRepository;
import ppa.cms.persistence.SignInEvent;
import ppa.cms.persistence.SignInEventRepository;
import ppa.cms.persistence.StoredNotification;
import ppa.cms.persistence.StoredNotificationRepository;
import ppa.cms.persistence.UserAccount;
import ppa.cms.persistence.UserAccountRepository;
import ppa.cms.security.Access;
import ppa.cms.security.AttemptWindow;
import ppa.cms.security.TokenAuthenticationFilter;
import ppa.cms.support.PageSlice;
import ppa.cms.support.Texts;
import ppa.cms.support.TimeFormats;
import ppa.cms.web.ApiException;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private static final String DUMMY_HASH = "$2y$12$nnQ3Ny8bMZCwsNpwtzykdOZ1F9.3SOrNfBgdzHqSNlsxcjLdE6OOK";
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final Duration SIGN_IN_WINDOW = Duration.ofSeconds(60);

    private final UserAccountRepository users;
    private final ApiTokenRepository tokens;
    private final SignInEventRepository signIns;
    private final StoredNotificationRepository notifications;
    private final PasswordEncoder passwords;
    private final AttemptWindow attempts;
    private final CmsAudit audit;
    private final JdbcTemplate jdbc;
    private final EntityManager entities;
    private final CmsProperties properties;
    private final SecureRandom random = new SecureRandom();

    public AccountService(
            UserAccountRepository users,
            ApiTokenRepository tokens,
            SignInEventRepository signIns,
            StoredNotificationRepository notifications,
            PasswordEncoder passwords,
            AttemptWindow attempts,
            CmsAudit audit,
            JdbcTemplate jdbc,
            EntityManager entities,
            CmsProperties properties
    ) {
        this.users = users;
        this.tokens = tokens;
        this.signIns = signIns;
        this.notifications = notifications;
        this.passwords = passwords;
        this.attempts = attempts;
        this.audit = audit;
        this.jdbc = jdbc;
        this.entities = entities;
        this.properties = properties;
    }

    public Map<String, Object> signIn(Map<String, Object> body, HttpServletRequest request) {
        String username = lower(body.get("username"));
        String password = body.get("password") == null ? "" : String.valueOf(body.get("password"));
        Map<String, List<String>> errors = Texts.errors();
        if (username == null) {
            Texts.add(errors, "username", "Username is required.");
        } else if (username.length() < 3) {
            Texts.add(errors, "username", "Username must be at least 3 characters.");
        } else if (username.length() > 50) {
            Texts.add(errors, "username", "Username must be 50 characters or fewer.");
        } else if (!username.matches("[a-z0-9._-]+")) {
            Texts.add(errors, "username", "Username may only contain letters, numbers, dots, underscores, and hyphens.");
        }
        if (body.get("password") == null || String.valueOf(body.get("password")).isEmpty()) {
            Texts.add(errors, "password", "Password is required.");
        } else if (password.length() > 255) {
            Texts.add(errors, "password", "Password must be 255 characters or fewer.");
        }
        Texts.finish(errors);

        String key = username + "|" + (request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr());
        if (attempts.tooMany(key, 5, SIGN_IN_WINDOW)) {
            int seconds = attempts.retryAfterSeconds(key, SIGN_IN_WINDOW);
            throw ApiException.message(429, "Too many sign-in attempts. Try again in " + seconds + " seconds.");
        }
        UserAccount user = users.findByUsername(username).orElse(null);
        boolean matches = passwords.matches(password, user == null ? DUMMY_HASH : user.getPassword());
        if (user == null || !matches) {
            attempts.hit(key, SIGN_IN_WINDOW);
            throw ApiException.message(422, "Incorrect username or password.");
        }
        if (user.disabled()) {
            throw ApiException.message(403, Access.DISABLED);
        }
        attempts.clear(key);
        String token = issueToken(user);
        SignInEvent event = new SignInEvent();
        event.setUserId(user.getId());
        event.setSignedInAt(Instant.now());
        signIns.save(event);
        audit.record(user, CmsAuditAction.SIGNED_IN, "Signed in.", null, null, null, null);
        return Map.of(
                "token", token,
                "token_type", "Bearer",
                "user", signInAccount(user)
        );
    }

    public Map<String, Object> signOut(UserAccount user, ApiToken token) {
        audit.record(user, CmsAuditAction.SIGNED_OUT, "Signed out.", null, null, null, null);
        if (token != null) {
            tokens.delete(token);
        }
        return Map.of("message", "Signed out.");
    }

    public Map<String, Object> show(UserAccount user) {
        return accountDetails(user, user);
    }

    @Transactional
    public Map<String, Object> updateOwn(UserAccount user, ApiToken current, Map<String, Object> body) {
        AccountInput input = accountInput(body, false);
        if (body.get("current_password") == null || String.valueOf(body.get("current_password")).isEmpty()) {
            throw ApiException.errors(Map.of("current_password", List.of("Enter your current password to save these changes.")));
        }
        if (!passwords.matches(String.valueOf(body.get("current_password")), user.getPassword())) {
            throw ApiException.errors(Map.of("current_password", List.of("Your current password is incorrect.")));
        }
        String newPassword = Texts.text(body.get("password"));
        boolean changesPassword = newPassword != null;
        if (changesPassword) {
            Map<String, List<String>> errors = Texts.errors();
            if (newPassword.length() > 255) {
                Texts.add(errors, "password", "Password must be 255 characters or fewer.");
            }
            if (passwords.matches(newPassword, user.getPassword())) {
                Texts.add(errors, "password", "Choose a new password that is different from your current one.");
            }
            if (Texts.text(body.get("password_confirmation")) == null) {
                Texts.add(errors, "password_confirmation", "Re-enter the new password to confirm it.");
            } else if (!newPassword.equals(String.valueOf(body.get("password_confirmation")))) {
                Texts.add(errors, "password_confirmation", "Passwords don't match.");
            }
            Texts.finish(errors);
        }
        boolean detailsChanged = applyAccount(user, input, false);
        if (changesPassword) {
            user.setPassword(passwords.encode(newPassword));
        }
        saveUser(user);
        if (changesPassword) {
            tokens.deleteByUserIdAndIdNot(user.getId(), current.getId());
        }
        if (detailsChanged || changesPassword) {
            log.info("User updated their own account. user_id={} username={} password_changed={}", user.getId(), user.getUsername(), changesPassword);
        }
        String message = detailsChanged && changesPassword
                ? "Your account details and password have been updated. Any other devices signed in to your account have been signed out."
                : changesPassword
                ? "Your password has been changed. Any other devices signed in to your account have been signed out."
                : detailsChanged
                ? "Your account details have been updated."
                : "No changes were needed.";
        return Map.of("message", message, "user", accountDetails(user, user));
    }

    public Map<String, Object> list(UserAccount actor, HttpServletRequest request) {
        Access.requireManageUsers(actor);
        String search = bounded(request.getParameter("search"), "search", 100);
        String status = request.getParameter("status");
        if (status == null || status.isBlank()) {
            status = "all";
        }
        if (!status.equals("all") && !status.equals("active") && !status.equals("disabled")) {
            throw ApiException.errors(Map.of("status", List.of("The selected status is invalid.")));
        }
        int page = page(request);
        int perPage = 20;
        CriteriaBuilder builder = entities.getCriteriaBuilder();
        var countQuery = builder.createQuery(Long.class);
        Root<UserAccount> countRoot = countQuery.from(UserAccount.class);
        countQuery.select(builder.count(countRoot)).where(userPredicates(builder, countRoot, search, status));
        long total = entities.createQuery(countQuery).getSingleResult();
        var query = builder.createQuery(UserAccount.class);
        Root<UserAccount> root = query.from(UserAccount.class);
        query.where(userPredicates(builder, root, search, status)).orderBy(builder.desc(root.get("createdAt")), builder.desc(root.get("id")));
        List<UserAccount> items = entities.createQuery(query)
                .setFirstResult((page - 1) * perPage)
                .setMaxResults(perPage)
                .getResultList();
        PageSlice<UserAccount> slice = new PageSlice<>(items, page, PageSlice.lastPage(total, perPage), perPage, total);
        Map<String, Object> meta = meta(slice);
        meta.put("total_registered", users.count());
        return Map.of(
                "data", items.stream().map(item -> userResource(actor, item)).toList(),
                "meta", meta,
                "counts", Map.of(
                        "all", countUsers(search, "all"),
                        "active", countUsers(search, "active"),
                        "disabled", countUsers(search, "disabled")
                )
        );
    }

    @Transactional
    public Map<String, Object> register(UserAccount actor, Map<String, Object> body) {
        Access.requireManageUsers(actor);
        AccountInput input = accountInput(body, true);
        String password = Texts.text(body.get("password"));
        Map<String, List<String>> errors = Texts.errors();
        if (password == null) {
            Texts.add(errors, "password", "Enter a password.");
        } else if (password.length() > 255) {
            Texts.add(errors, "password", "Password must be 255 characters or fewer.");
        }
        if (Texts.text(body.get("password_confirmation")) == null) {
            Texts.add(errors, "password_confirmation", "Re-enter the password to confirm it.");
        } else if (password != null && !password.equals(String.valueOf(body.get("password_confirmation")))) {
            Texts.add(errors, "password_confirmation", "Passwords don't match.");
        }
        Texts.finish(errors);
        UserAccount user = new UserAccount();
        applyAccount(user, input, true);
        user.setPassword(passwords.encode(password));
        saveUser(user);
        log.info("User account registered. user_id={} username={} registered_by={}", user.getId(), user.getUsername(), actor.getUsername());
        audit.record(actor, CmsAuditAction.USER_REGISTERED, "Registered the account " + user.auditLabel() + ".", user.auditLabel(), "user", user.getId(), null);
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("name", user.getName());
        account.put("first_name", user.getFirstName());
        account.put("middle_name", user.getMiddleName());
        account.put("last_name", user.getLastName());
        account.put("suffix", user.getSuffix());
        account.put("username", user.getUsername());
        account.put("email", user.getEmail());
        account.put("role", user.getRole().value());
        return Map.of("message", "Account created.", "user", account);
    }

    @Transactional
    public Map<String, Object> updateUser(UserAccount actor, long id, Map<String, Object> body) {
        Access.requireManageUsers(actor);
        UserAccount user = requireUser(id);
        AccountInput input = accountInput(body, true);
        List<String> changed = changedFields(user, input);
        applyAccount(user, input, true);
        saveUser(user);
        if (!changed.isEmpty()) {
            log.info("User account updated. user_id={} username={} performed_by={}", user.getId(), user.getUsername(), actor.getUsername());
            audit.record(actor, CmsAuditAction.USER_UPDATED, "Updated the account " + user.auditLabel() + ".", user.auditLabel(), "user", user.getId(), Map.of("changed", changed));
        }
        String name = user.getName().replaceAll("\\.+$", "");
        String message = changed.isEmpty() ? "No changes were needed for " + name + "." : "Changes saved for " + name + ".";
        return Map.of("message", message, "user", userResource(actor, user));
    }

    @Transactional
    public Map<String, Object> disable(UserAccount actor, long id) {
        Access.requireManageUsers(actor);
        UserAccount user = requireUser(id);
        if (actor.getId().equals(user.getId())) {
            throw ApiException.errors(Map.of("account", List.of("You can't disable your own account.")));
        }
        if (!user.disabled()) {
            user.setDisabledAt(Instant.now());
            users.save(user);
            tokens.deleteByUserId(user.getId());
            log.info("User account disabled. user_id={} performed_by={}", user.getId(), actor.getUsername());
            audit.record(actor, CmsAuditAction.USER_DISABLED, "Disabled the account " + user.auditLabel() + ".", user.auditLabel(), "user", user.getId(), null);
        }
        return Map.of("message", user.getName() + "'s account is disabled. They can no longer sign in.", "user", userResource(actor, user));
    }

    @Transactional
    public Map<String, Object> enable(UserAccount actor, long id) {
        Access.requireManageUsers(actor);
        UserAccount user = requireUser(id);
        if (user.disabled()) {
            user.setDisabledAt(null);
            users.save(user);
            log.info("User account enabled. user_id={} performed_by={}", user.getId(), actor.getUsername());
        }
        return Map.of("message", user.getName() + "'s account is active again. They can sign in with their existing password.", "user", userResource(actor, user));
    }

    @Transactional
    public Map<String, Object> resetPassword(UserAccount actor, long id, Map<String, Object> body) {
        Access.requireManageUsers(actor);
        UserAccount user = requireUser(id);
        String denial = Access.resetPasswordDenial(actor, user);
        if (denial != null) {
            throw ApiException.message(403, denial);
        }
        String password = Texts.text(body.get("password"));
        Map<String, List<String>> errors = Texts.errors();
        if (password == null) {
            Texts.add(errors, "password", "Enter a password.");
        } else if (password.length() > 255) {
            Texts.add(errors, "password", "Password must be 255 characters or fewer.");
        }
        if (Texts.text(body.get("password_confirmation")) == null) {
            Texts.add(errors, "password_confirmation", "Re-enter the password to confirm it.");
        } else if (password != null && !password.equals(String.valueOf(body.get("password_confirmation")))) {
            Texts.add(errors, "password_confirmation", "Passwords don't match.");
        }
        Texts.finish(errors);
        user.setPassword(passwords.encode(password));
        users.save(user);
        tokens.deleteByUserId(user.getId());
        jdbc.update("delete from password_reset_tokens where email = ?", user.getEmail());
        log.info("User password reset by administrator. user_id={} performed_by={}", user.getId(), actor.getUsername());
        return Map.of("message", "New password set for " + user.getName() + ". They have been signed out and must sign in with the new password.", "user", userResource(actor, user));
    }

    @Transactional
    public Map<String, Object> destroy(UserAccount actor, long id, Map<String, Object> body) {
        Access.requireManageUsers(actor);
        UserAccount user = requireUser(id);
        if (actor.getId().equals(user.getId())) {
            throw ApiException.errors(Map.of("account", List.of("You can't remove your own account.")));
        }
        String confirm = Texts.text(body.get("confirm_username"));
        if (confirm == null) {
            throw ApiException.errors(Map.of("confirm_username", List.of("Type the username to confirm removal.")));
        }
        if (!confirm.toLowerCase(Locale.ROOT).equals(user.getUsername())) {
            throw ApiException.errors(Map.of("confirm_username", List.of("The username you typed does not match this account.")));
        }
        log.info("User account removed. user_id={} performed_by={}", user.getId(), actor.getUsername());
        audit.record(actor, CmsAuditAction.USER_REMOVED, "Removed the account " + user.auditLabel() + ".", user.auditLabel(), "user", user.getId(), null);
        tokens.deleteByUserId(user.getId());
        jdbc.update("delete from password_reset_tokens where email = ?", user.getEmail());
        notifications.deleteByNotifiableTypeAndNotifiableId(StoredNotification.NOTIFIABLE, user.getId());
        users.delete(user);
        return Map.of("message", "Account removed: " + user.getName() + " (" + user.getUsername() + ").");
    }

    public List<Map<String, Object>> recentSignIns() {
        int limit = Math.max(1, properties.getSignIns().getDashboardLimit());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SignInEvent event : signIns.findAllByOrderBySignedInAtDescIdDesc(PageRequest.of(0, limit))) {
            UserAccount user = users.findById(event.getUserId()).orElse(null);
            if (user == null) {
                continue;
            }
            rows.add(Map.of(
                    "id", event.getId(),
                    "name", user.getName(),
                    "signed_in_at", TimeFormats.iso(event.getSignedInAt())
            ));
        }
        return rows;
    }

    public int signInLimit() {
        return Math.max(1, properties.getSignIns().getDashboardLimit());
    }

    public void pruneSignIns() {
        signIns.deleteBySignedInAtBefore(Instant.now().minus(Duration.ofDays(properties.getSignIns().getRetentionDays())));
    }

    public static UserAccount current(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UserAccount user)) {
            throw ApiException.message(401, "Unauthenticated.");
        }
        return user;
    }

    public static ApiToken currentToken(Authentication authentication) {
        if (authentication != null && authentication.getCredentials() instanceof ApiToken token) {
            return token;
        }
        return null;
    }

    private String issueToken(UserAccount user) {
        StringBuilder plain = new StringBuilder(80);
        for (int index = 0; index < 80; index++) {
            plain.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        ApiToken token = new ApiToken();
        token.setUserId(user.getId());
        token.setName("cms-ppa");
        token.setToken(TokenAuthenticationFilter.sha256(plain.toString()));
        tokens.save(token);
        return plain.toString();
    }

    private void saveUser(UserAccount user) {
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            Map<String, List<String>> errors = Texts.errors();
            if (users.existsByUsernameAndIdNot(user.getUsername(), user.getId() == null ? -1 : user.getId()) || users.existsByUsername(user.getUsername()) && user.getId() == null) {
                Texts.add(errors, "username", Texts.USERNAME_TAKEN);
            }
            if (users.existsByEmailAndIdNot(user.getEmail(), user.getId() == null ? -1 : user.getId()) || users.existsByEmail(user.getEmail()) && user.getId() == null) {
                Texts.add(errors, "email", Texts.EMAIL_TAKEN);
            }
            if (errors.isEmpty()) {
                Texts.add(errors, "username", Texts.USERNAME_TAKEN);
            }
            throw ApiException.errors(errors);
        }
    }

    private UserAccount requireUser(long id) {
        return users.findById(id).orElseThrow(() -> ApiException.message(404, "Not found."));
    }

    private Predicate[] userPredicates(CriteriaBuilder builder, Root<UserAccount> root, String search, String status) {
        List<Predicate> predicates = new ArrayList<>();
        if (search != null) {
            String like = Texts.likeContains(search.toLowerCase(Locale.ROOT));
            predicates.add(builder.or(
                    builder.like(builder.lower(root.get("name")), like, '\\'),
                    builder.like(builder.lower(root.get("username")), like, '\\'),
                    builder.like(builder.lower(root.get("email")), like, '\\')
            ));
        }
        if ("active".equals(status)) {
            predicates.add(builder.isNull(root.get("disabledAt")));
        } else if ("disabled".equals(status)) {
            predicates.add(builder.isNotNull(root.get("disabledAt")));
        }
        return predicates.toArray(Predicate[]::new);
    }

    private long countUsers(String search, String status) {
        CriteriaBuilder builder = entities.getCriteriaBuilder();
        var query = builder.createQuery(Long.class);
        Root<UserAccount> root = query.from(UserAccount.class);
        query.select(builder.count(root)).where(userPredicates(builder, root, search, status));
        return entities.createQuery(query).getSingleResult();
    }

    private AccountInput accountInput(Map<String, Object> body, boolean roleRequired) {
        String first = Texts.namePart(body.get("first_name"));
        String middle = Texts.namePart(body.get("middle_name"));
        String last = Texts.namePart(body.get("last_name"));
        String suffix = Texts.namePart(body.get("suffix"));
        String username = lower(body.get("username"));
        String email = lower(body.get("email"));
        String name = Texts.composeName(first, middle, last, suffix);
        Map<String, List<String>> errors = Texts.errors();
        if (first == null) {
            Texts.add(errors, "first_name", roleRequired ? "Enter the user's first name." : "Enter your first name.");
        } else if (first.length() > 50) {
            Texts.add(errors, "first_name", "First name must be 50 characters or fewer.");
        }
        if (middle != null && middle.length() > 50) {
            Texts.add(errors, "middle_name", "Middle name must be 50 characters or fewer.");
        }
        if (last == null) {
            Texts.add(errors, "last_name", roleRequired ? "Enter the user's last name." : "Enter your last name.");
        } else if (last.length() > 50) {
            Texts.add(errors, "last_name", "Last name must be 50 characters or fewer.");
        }
        if (suffix != null && suffix.length() > 10) {
            Texts.add(errors, "suffix", "Suffix must be 10 characters or fewer.");
        }
        if (name.length() > 100) {
            Texts.add(errors, "name", "The full name (first, middle, and last name plus suffix) must be 100 characters or fewer in total.");
        }
        if (email == null) {
            Texts.add(errors, "email", roleRequired ? "Enter the user's email address." : "Enter your email address.");
        } else if (email.length() > 255) {
            Texts.add(errors, "email", "Email address must be 255 characters or fewer.");
        } else if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            Texts.add(errors, "email", "Enter a valid email address, like name@example.com.");
        }
        if (username == null) {
            Texts.add(errors, "username", "Choose a username.");
        } else if (username.length() < 3) {
            Texts.add(errors, "username", "Username must be at least 3 characters.");
        } else if (username.length() > 50) {
            Texts.add(errors, "username", "Username must be 50 characters or fewer.");
        } else if (!username.matches("[a-z0-9._-]+")) {
            Texts.add(errors, "username", "Use only letters, numbers, dots, underscores, and hyphens. No spaces.");
        }
        Role role = null;
        if (roleRequired) {
            role = Role.from(Texts.text(body.get("role")));
            if (Texts.text(body.get("role")) == null) {
                Texts.add(errors, "role", "Choose a role.");
            } else if (role == null) {
                Texts.add(errors, "role", "Choose Administrator or User.");
            }
        }
        Texts.finish(errors);
        return new AccountInput(name, first, middle, last, suffix, username, email, role);
    }

    private boolean applyAccount(UserAccount user, AccountInput input, boolean includeRole) {
        boolean changed = differs(user.getFirstName(), input.first())
                || differs(user.getMiddleName(), input.middle())
                || differs(user.getLastName(), input.last())
                || differs(user.getSuffix(), input.suffix())
                || differs(user.getName(), input.name())
                || differs(user.getUsername(), input.username())
                || differs(user.getEmail(), input.email())
                || (includeRole && user.getRole() != input.role());
        user.setName(input.name());
        user.setFirstName(input.first());
        user.setMiddleName(input.middle());
        user.setLastName(input.last());
        user.setSuffix(input.suffix());
        user.setUsername(input.username());
        user.setEmail(input.email());
        if (includeRole) {
            user.setRole(input.role());
        }
        return changed;
    }

    private List<String> changedFields(UserAccount user, AccountInput input) {
        List<String> changed = new ArrayList<>();
        if (differs(user.getFirstName(), input.first())) {
            changed.add("first_name");
        }
        if (differs(user.getMiddleName(), input.middle())) {
            changed.add("middle_name");
        }
        if (differs(user.getLastName(), input.last())) {
            changed.add("last_name");
        }
        if (differs(user.getSuffix(), input.suffix())) {
            changed.add("suffix");
        }
        if (differs(user.getName(), input.name())) {
            changed.add("name");
        }
        if (differs(user.getUsername(), input.username())) {
            changed.add("username");
        }
        if (differs(user.getEmail(), input.email())) {
            changed.add("email");
        }
        if (user.getRole() != input.role()) {
            changed.add("role");
        }
        return changed;
    }

    private Map<String, Object> signInAccount(UserAccount user) {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", user.getId());
        account.put("name", user.getName());
        account.put("username", user.getUsername());
        account.put("email", user.getEmail());
        account.put("role", user.getRole().value());
        account.put("can", Map.of("manage_users", Access.manageUsers(user)));
        return account;
    }

    private Map<String, Object> accountDetails(UserAccount viewer, UserAccount user) {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", user.getId());
        account.put("name", user.getName());
        account.put("first_name", user.getFirstName());
        account.put("middle_name", user.getMiddleName());
        account.put("last_name", user.getLastName());
        account.put("suffix", user.getSuffix());
        account.put("username", user.getUsername());
        account.put("email", user.getEmail());
        account.put("role", user.getRole().value());
        account.put("can", Map.of(
                "manage_users", Access.manageUsers(user),
                "manage_systems", Access.manageSystems(user),
                "view_audit_trail", Access.viewAudit(user)
        ));
        return account;
    }

    private Map<String, Object> userResource(UserAccount actor, UserAccount user) {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", user.getId());
        account.put("name", user.getName());
        account.put("first_name", user.getFirstName());
        account.put("middle_name", user.getMiddleName());
        account.put("last_name", user.getLastName());
        account.put("suffix", user.getSuffix());
        account.put("username", user.getUsername());
        account.put("email", user.getEmail());
        account.put("role", user.getRole().value());
        account.put("status", user.disabled() ? "disabled" : "active");
        account.put("disabled_at", TimeFormats.iso(user.getDisabledAt()));
        account.put("created_at", TimeFormats.iso(user.getCreatedAt()));
        account.put("updated_at", TimeFormats.iso(user.getUpdatedAt()));
        account.put("is_current_user", actor.getId().equals(user.getId()));
        account.put("can", Map.of("reset_password", Access.canResetPassword(actor, user)));
        return account;
    }

    static Map<String, Object> meta(PageSlice<?> slice) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("current_page", slice.currentPage());
        meta.put("last_page", slice.lastPage());
        meta.put("per_page", slice.perPage());
        meta.put("total", slice.total());
        meta.put("from", slice.from());
        meta.put("to", slice.to());
        return meta;
    }

    private static int page(HttpServletRequest request) {
        String value = request.getParameter("page");
        if (value == null || value.isBlank()) {
            return 1;
        }
        try {
            int page = Integer.parseInt(value);
            if (page < 1) {
                throw ApiException.errors(Map.of("page", List.of("The page field must be at least 1.")));
            }
            return page;
        } catch (NumberFormatException exception) {
            throw ApiException.errors(Map.of("page", List.of("The page field must be an integer.")));
        }
    }

    private static String bounded(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw ApiException.errors(Map.of(field, List.of("The " + field + " field must not be greater than " + max + " characters.")));
        }
        return trimmed;
    }

    private static String lower(Object value) {
        String text = Texts.text(value);
        return text == null ? null : text.toLowerCase(Locale.ROOT);
    }

    private static boolean differs(Object left, Object right) {
        return left == null ? right != null : !left.equals(right);
    }

    private record AccountInput(String name, String first, String middle, String last, String suffix, String username, String email, Role role) {
    }
}
