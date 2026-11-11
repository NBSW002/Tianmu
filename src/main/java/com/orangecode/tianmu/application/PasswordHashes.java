package com.orangecode.tianmu.application;

import com.orangecode.tianmu.constants.UserConstant;
import org.springframework.util.DigestUtils;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Versioned password storage, with read-only support for existing salted MD5. */
public final class PasswordHashes {
    private static final String PREFIX = "pbkdf2-sha256$600000$";
    private static final int ITERATIONS = 600_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private PasswordHashes() {}

    public static String encode(String password) {
        if (password == null || password.isEmpty()) throw new IllegalArgumentException("Empty password");
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return PREFIX + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derive(password, salt));
    }

    public static boolean legacy(String stored) {
        return stored != null && stored.matches("[a-f0-9]{32}");
    }

    public static boolean matches(String password, String stored) {
        if (password == null || password.isEmpty() || stored == null) return false;
        if (legacy(stored)) {
            // Preserve the charset used by the previous login implementation.
            byte[] actual = DigestUtils.md5Digest((UserConstant.PASSWORD_SALT + password).getBytes(Charset.defaultCharset()));
            return MessageDigest.isEqual(HexFormat.of().parseHex(stored), actual);
        }
        if (!stored.startsWith(PREFIX) || stored.length() > 128) return false;
        try {
            String[] parts = stored.substring(PREFIX.length()).split("\\$", -1);
            if (parts.length != 2) return false;
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] expected = Base64.getDecoder().decode(parts[1]);
            return salt.length == 16 && expected.length == 32
                    && MessageDigest.isEqual(expected, derive(password, salt));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt) {
        var spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("Password hashing unavailable", unavailable);
        } finally {
            spec.clearPassword();
        }
    }
}
