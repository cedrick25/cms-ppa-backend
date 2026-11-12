package ppa.cms.security;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import ppa.cms.web.ApiException;

/** Checks a platform-authenticator registration or assertion for one remote-desk challenge. */
public final class WebAuthnCeremony {

    public record VerifiedCredential(byte[] credentialId, byte[] publicKey, String algorithm, long signCount) {
    }

    private WebAuthnCeremony() {
    }

    public static VerifiedCredential registration(byte[] clientDataJson, byte[] attestationObject, byte[] challenge, String origin) {
        ClientData clientData = clientData(clientDataJson, "webauthn.create", challenge, origin);
        Map<Object, Object> attestation = map(read(attestationObject));
        byte[] authData = bytes(attestation.get("authData"));
        AuthenticatorData parsed = authenticatorData(authData, true);
        if (!Arrays.equals(parsed.rpIdHash(), sha256(clientData.rpId().getBytes(StandardCharsets.UTF_8)))) {
            throw invalid("The fingerprint credential was not made for this site.");
        }
        CoseKey cose = coseKey(parsed.publicKey());
        return new VerifiedCredential(parsed.credentialId(), cose.encoded(), cose.algorithm(), parsed.signCount());
    }

    public static long assertion(byte[] clientDataJson, byte[] authenticatorData, byte[] signature, byte[] challenge, String origin, byte[] publicKey, String algorithm, long storedSignCount) {
        ClientData clientData = clientData(clientDataJson, "webauthn.get", challenge, origin);
        AuthenticatorData parsed = authenticatorData(authenticatorData, false);
        if (!Arrays.equals(parsed.rpIdHash(), sha256(clientData.rpId().getBytes(StandardCharsets.UTF_8)))) {
            throw invalid("The fingerprint check was not made for this site.");
        }
        byte[] signed = new byte[authenticatorData.length + 32];
        System.arraycopy(authenticatorData, 0, signed, 0, authenticatorData.length);
        System.arraycopy(sha256(clientDataJson), 0, signed, authenticatorData.length, 32);
        if (!verify(publicKey, algorithm, signed, signature)) {
            throw invalid("The fingerprint check didn't match this account.");
        }
        if (storedSignCount > 0 && parsed.signCount() > 0 && parsed.signCount() <= storedSignCount) {
            throw invalid("This fingerprint credential has already been used.");
        }
        return parsed.signCount();
    }

    private static boolean verify(byte[] publicKey, String algorithm, byte[] signed, byte[] signature) {
        try {
            PublicKey key = KeyFactory.getInstance(algorithm.startsWith("RS") ? "RSA" : "EC").generatePublic(new X509EncodedKeySpec(publicKey));
            Signature verifier = Signature.getInstance("RS256".equals(algorithm) ? "SHA256withRSA" : "SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update(signed);
            return verifier.verify(signature);
        } catch (Exception exception) {
            return false;
        }
    }

    private static ClientData clientData(byte[] json, String type, byte[] challenge, String origin) {
        String text = new String(json, StandardCharsets.UTF_8);
        String foundType = jsonString(text, "type");
        String foundOrigin = jsonString(text, "origin");
        String foundChallenge = jsonString(text, "challenge");
        if (!type.equals(foundType) || !origin.equals(foundOrigin) || !Arrays.equals(challenge, base64Url(foundChallenge))) {
            throw invalid("The fingerprint check doesn't match this request.");
        }
        String rpId = rpId(origin);
        return new ClientData(rpId);
    }

    public static String rpId(String origin) {
        String host = host(origin);
        if (host == null || host.isEmpty() || host.indexOf(':') >= 0 || host.chars().allMatch(ch -> Character.isDigit(ch) || ch == '.')) {
            throw invalid("Fingerprint authentication isn't available from this address. Continue with your PIN and password.");
        }
        return host;
    }

    private static String host(String origin) {
        if (origin == null || !(origin.startsWith("https://") || origin.startsWith("http://"))) {
            return null;
        }
        String rest = origin.substring(origin.indexOf("://") + 3);
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            rest = rest.substring(0, slash);
        }
        int port = rest.lastIndexOf(':');
        if (port > 0 && rest.indexOf(':') == port) {
            return rest.substring(0, port);
        }
        return rest;
    }

    private static AuthenticatorData authenticatorData(byte[] data, boolean registration) {
        if (data.length < 37) {
            throw invalid("The fingerprint reader returned an incomplete response.");
        }
        byte[] rpIdHash = Arrays.copyOfRange(data, 0, 32);
        int flags = data[32] & 0xff;
        if ((flags & 0x01) == 0 || (flags & 0x04) == 0) {
            throw invalid("The fingerprint check was not completed.");
        }
        long signCount = ((long) (data[33] & 0xff) << 24) | ((long) (data[34] & 0xff) << 16) | ((long) (data[35] & 0xff) << 8) | (data[36] & 0xff);
        if (!registration) {
            return new AuthenticatorData(rpIdHash, signCount, null, null);
        }
        if ((flags & 0x40) == 0 || data.length < 55) {
            throw invalid("The fingerprint reader didn't return a credential.");
        }
        int credentialLength = ((data[53] & 0xff) << 8) | (data[54] & 0xff);
        int credentialStart = 55;
        if (credentialLength < 1 || credentialStart + credentialLength > data.length) {
            throw invalid("The fingerprint credential is incomplete.");
        }
        byte[] credentialId = Arrays.copyOfRange(data, credentialStart, credentialStart + credentialLength);
        byte[] cose = Arrays.copyOfRange(data, credentialStart + credentialLength, data.length);
        return new AuthenticatorData(rpIdHash, signCount, credentialId, cose);
    }

    private static CoseKey coseKey(byte[] cose) {
        Map<Object, Object> key = map(read(cose));
        long kty = number(key.get(1L));
        long alg = number(key.get(3L));
        try {
            if (kty == 2 && alg == -7) {
                byte[] x = bytes(key.get(-2L));
                byte[] y = bytes(key.get(-3L));
                if (number(key.get(-1L)) != 1 || x.length != 32 || y.length != 32) {
                    throw invalid("This fingerprint credential uses an unsupported key.");
                }
                AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
                parameters.init(new ECGenParameterSpec("secp256r1"));
                ECPublicKeySpec spec = new ECPublicKeySpec(new ECPoint(new BigInteger(1, x), new BigInteger(1, y)), parameters.getParameterSpec(ECParameterSpec.class));
                PublicKey publicKey = KeyFactory.getInstance("EC").generatePublic(spec);
                return new CoseKey(publicKey.getEncoded(), "ES256");
            }
            if (kty == 3 && alg == -257) {
                PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(new BigInteger(1, bytes(key.get(-1L))), new BigInteger(1, bytes(key.get(-2L)))));
                return new CoseKey(publicKey.getEncoded(), "RS256");
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("This fingerprint credential uses an unsupported key.");
        }
        throw invalid("This fingerprint credential uses an unsupported key.");
    }

    private static Object read(byte[] data) {
        try {
            return CborReader.readOne(data);
        } catch (IllegalArgumentException exception) {
            throw invalid("The fingerprint reader returned a response this site couldn't read.");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> map(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<Object, Object>) map;
        }
        throw invalid("The fingerprint reader returned a response this site couldn't read.");
    }

    private static byte[] bytes(Object value) {
        if (value instanceof byte[] data) {
            return data;
        }
        throw invalid("The fingerprint reader returned a response this site couldn't read.");
    }

    private static long number(Object value) {
        if (value instanceof Long number) {
            return number;
        }
        throw invalid("The fingerprint reader returned a response this site couldn't read.");
    }

    private static String jsonString(String json, String field) {
        int key = json.indexOf("\"" + field + "\"");
        if (key < 0) {
            return "";
        }
        int colon = json.indexOf(':', key + field.length() + 2);
        if (colon < 0) {
            return "";
        }
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        if (start >= json.length() || json.charAt(start) != '"') {
            return "";
        }
        StringBuilder value = new StringBuilder();
        for (int i = start + 1; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '\\') {
                return "";
            }
            if (ch == '"') {
                return value.toString();
            }
            value.append(ch);
        }
        return "";
    }

    public static byte[] base64Url(String value) {
        if (value == null || value.isBlank()) {
            return new byte[0];
        }
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            return new byte[0];
        }
    }

    public static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception exception) {
            throw invalid("The fingerprint check couldn't be verified.");
        }
    }

    private static ApiException invalid(String message) {
        return ApiException.message(422, message);
    }

    private record ClientData(String rpId) {
    }

    private record AuthenticatorData(byte[] rpIdHash, long signCount, byte[] credentialId, byte[] publicKey) {
    }

    private record CoseKey(byte[] encoded, String algorithm) {
    }
}
