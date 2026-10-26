package ppa.cms.crypto;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import ppa.cms.support.JsonCodec;

/**
 * Reads and writes the encrypted strings Laravel stores with the encrypted cast.
 * The payload is base64 JSON with iv, value, mac, and an empty tag. AES-256-CBC.
 */
public final class LaravelEncrypter {

    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public LaravelEncrypter(String appKey) {
        this.key = decodeKey(appKey);
    }

    public static byte[] decodeKey(String appKey) {
        if (appKey == null || appKey.isBlank()) {
            throw new IllegalStateException("APP_KEY is missing. Set it to the existing Laravel key so saved passwords stay readable.");
        }
        String encoded = appKey.startsWith("base64:") ? appKey.substring("base64:".length()) : appKey;
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("APP_KEY is not valid base64.", exception);
        }
        if (key.length != 32) {
            throw new IllegalStateException("APP_KEY must decode to 32 bytes.");
        }
        return key;
    }

    public String encrypt(String plain) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[16];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            String ivText = Base64.getEncoder().encodeToString(iv);
            String value = Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
            return packagePayload(ivText, value, mac(ivText, value));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not encrypt a stored value.", exception);
        }
    }

    public String decrypt(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> json = JsonCodec.readObject(new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8));
            String ivText = String.valueOf(json.get("iv"));
            String value = String.valueOf(json.get("value"));
            String mac = String.valueOf(json.get("mac"));
            if (!MessageDigest.isEqual(mac(ivText, value).getBytes(StandardCharsets.UTF_8), mac.getBytes(StandardCharsets.UTF_8))) {
                throw new IllegalStateException("Stored value failed its integrity check.");
            }
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(Base64.getDecoder().decode(ivText)));
            return new String(cipher.doFinal(Base64.getDecoder().decode(value)), StandardCharsets.UTF_8);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read a stored value. Check that APP_KEY matches the key used when it was saved.", exception);
        }
    }

    private String mac(String ivText, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        byte[] digest = mac.doFinal((ivText + value).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte piece : digest) {
            hex.append(String.format("%02x", piece));
        }
        return hex.toString();
    }

    private static String packagePayload(String ivText, String value, String mac) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("iv", ivText);
        json.put("value", value);
        json.put("mac", mac);
        json.put("tag", "");
        return Base64.getEncoder().encodeToString(JsonCodec.write(json).getBytes(StandardCharsets.UTF_8));
    }
}
