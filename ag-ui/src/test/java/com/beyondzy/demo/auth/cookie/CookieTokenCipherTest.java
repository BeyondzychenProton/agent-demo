package com.beyondzy.demo.auth.cookie;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CookieTokenCipherTest {
    @Test
    void roundTripPreservesToken() {
        CookieTokenCipher cipher = new CookieTokenCipher("local-dev-cookie-secret-change-me!!");
        String raw = "abcdef0123456789abcdef0123456789";
        String encoded = cipher.encrypt(raw);
        assertNotEquals(raw, encoded);
        assertEquals(raw, cipher.decrypt(encoded));
    }

    @Test
    void tamperedCipherTextFails() {
        CookieTokenCipher cipher = new CookieTokenCipher("local-dev-cookie-secret-change-me!!");
        String encoded = cipher.encrypt("token-value");
        String broken = encoded.substring(0, encoded.length() - 2) + "aa";
        assertThrows(IllegalArgumentException.class, () -> cipher.decrypt(broken));
    }
}
