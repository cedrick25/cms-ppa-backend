package ppa.cms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import ppa.cms.crypto.LaravelEncrypter;

class LaravelEncrypterTest {

    private static final String KEY = "base64:MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void readsAValueWrittenByLaravel() {
        String payload = "eyJpdiI6IllXRmhZV0ZoWVdGaFlXRmhZV0ZoWVE9PSIsInZhbHVlIjoiejZjazl3WmU0enVOeXRmWXA3akJhUT09IiwibWFjIjoiZWUxZWNkMGZlMGY4ODM2MTNkZTM2Yzk2ZjY4NGVlMzljZGExNjY1OWE5ZjAzMjg0NTNjMzIzYjA5MjI2MGQ5MyIsInRhZyI6IiJ9";
        assertEquals("secret-note", new LaravelEncrypter(KEY).decrypt(payload));
    }

    @Test
    void roundTripsAndRejectsATamperedPayload() {
        LaravelEncrypter encrypter = new LaravelEncrypter(KEY);
        String payload = encrypter.encrypt("desk-password");
        assertEquals("desk-password", encrypter.decrypt(payload));
        assertThrows(IllegalStateException.class, () -> encrypter.decrypt(payload.substring(0, payload.length() - 2) + "aa"));
    }
}
