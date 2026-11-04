package com.beyondzy.demo.auth.cookie;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Cookie 载荷 AES-GCM 加解密；密钥由配置口令派生。 */
@Component
public class CookieTokenCipher {
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private final byte[] keyBytes;
    private final SecureRandom random = new SecureRandom();

    public CookieTokenCipher(@Value("${chat.auth.cookie-secret}") String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 16)
            throw new IllegalArgumentException("chat.auth.cookie-secret 至少 16 字节");
        try {
            keyBytes = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("无法派生 Cookie 加密密钥", error);
        }
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + cipherText.length);
            buffer.put(iv);
            buffer.put(cipherText);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Cookie 加密失败", error);
        }
    }

    public String decrypt(String encoded) {
        try {
            byte[] all = Base64.getUrlDecoder().decode(encoded);
            if (all.length <= GCM_IV_LENGTH) throw new IllegalArgumentException("密文过短");
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] cipherText = new byte[all.length - GCM_IV_LENGTH];
            System.arraycopy(all, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(all, GCM_IV_LENGTH, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new IllegalArgumentException("Cookie 解密失败", error);
        }
    }
}
