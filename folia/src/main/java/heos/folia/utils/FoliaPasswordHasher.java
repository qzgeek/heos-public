package heos.folia.utils;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Multi-format password hasher.
 * Supports both native PBKDF2 and AuthMe-compatible hash formats.
 *
 * Hash formats:
 *   LuoOS (native):   iterations:salt:hash            (PBKDF2WithHmacSHA256)
 *   AuthMe SHA256:    $SHA$salt$hash                   (SHA-256, hex-encoded)
 *   AuthMe BCRYPT:    $2y$cost$...                     (BCrypt)
 *   AuthMe ARGON2:    $argon2id$...                    (Argon2id)
 *   AuthMe PBKDF2:    $pbkdf2-sha256$iterations$salt$hash
 */
public final class FoliaPasswordHasher {
    private static final int LEGACY_ITERATIONS = 10000;
    private static final int CURRENT_ITERATIONS = 310000;
    private static final int KEY_LENGTH = 256;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();

    private FoliaPasswordHasher() {}

    // ========== Native LuoOS PBKDF2 ==========

    public static String hashPassword(String password) {
        try {
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            byte[] hash = pbkdf2(password.toCharArray(), salt, CURRENT_ITERATIONS);
            return CURRENT_ITERATIONS + ":" + Base64.getEncoder().encodeToString(salt)
                    + ":" + Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash password", e);
        }
    }

    // ========== Password Verification (multi-format) ==========

    public static boolean verifyPassword(String password, String storedHash) {
        if (storedHash == null || storedHash.isEmpty()) return false;
        try {
            // AuthMe formats start with $
            if (storedHash.startsWith("$")) {
                return verifyAuthMeHash(password, storedHash);
            }
            // Native LuoOS PBKDF2 format
            ParsedHash parsed = parseLuoos(storedHash);
            if (parsed == null) return false;
            return slowEquals(parsed.hash, pbkdf2(password.toCharArray(), parsed.salt, parsed.iterations));
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean needsRehash(String storedHash) {
        if (storedHash == null || storedHash.isEmpty()) return true;
        if (storedHash.startsWith("$")) {
            // AuthMe hashes should be rehashed to native format for security consistency
            return true;
        }
        ParsedHash parsed = parseLuoos(storedHash);
        return parsed == null || parsed.iterations < CURRENT_ITERATIONS;
    }

    // ========== AuthMe-compatible hash verification ==========

    private static boolean verifyAuthMeHash(String password, String storedHash) {
        try {
            String[] parts = storedHash.split("\\$", 4);
            if (parts.length < 3) return false;

            String algorithm = parts[1];

            return switch (algorithm.toUpperCase()) {
                case "SHA", "SHA256" -> {
                    if (parts.length < 4) yield false;
                    String salt = parts[2];
                    String hash = parts[3];
                    yield verifySha256(password, salt, hash);
                }
                case "2Y", "2A", "2B" -> {
                    // BCrypt: $2y$cost$salt+hash
                    yield verifyBCrypt(password, storedHash);
                }
                case "ARGON2ID", "ARGON2I", "ARGON2D" -> {
                    yield verifyArgon2(password, storedHash);
                }
                case "PBKDF2-SHA256" -> {
                    if (parts.length < 5) yield false;
                    int iterations = Integer.parseInt(parts[2]);
                    byte[] salt = HexFormat.of().parseHex(parts[3]);
                    byte[] hash = pbkdf2(password.toCharArray(), salt, iterations);
                    yield slowEquals(hash, HexFormat.of().parseHex(parts[4]));
                }
                default -> false;
            };
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean verifySha256(String password, String saltHex, String hashHex) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            // AuthMe SHA256: SHA256(SHA256(password) + saltHex)
            String innerHash = HexFormat.of().formatHex(md.digest(password.getBytes("UTF-8")));
            byte[] computed = md.digest((innerHash + saltHex).getBytes("UTF-8"));
            return slowEquals(computed, HexFormat.of().parseHex(hashHex));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean verifyBCrypt(String password, String storedHash) {
        try {
            var result = at.favre.lib.crypto.bcrypt.BCrypt.verifyer().verify(
                    password.toCharArray(), storedHash);
            return result.verified;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean verifyArgon2(String password, String storedHash) {
        // Argon2 support requires argon2-jvm library; fallback to false for now
        // Users with Argon2 hashes should reset their password
        return false;
    }

    // ========== PBKDF2 core ==========

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_LENGTH);
        return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
    }

    private static ParsedHash parseLuoos(String storedHash) {
        if (storedHash == null || storedHash.isEmpty()) return null;
        String[] parts = storedHash.split(":");
        try {
            if (parts.length == 2) {
                return new ParsedHash(LEGACY_ITERATIONS,
                        Base64.getDecoder().decode(parts[0]),
                        Base64.getDecoder().decode(parts[1]));
            }
            if (parts.length == 3) {
                return new ParsedHash(Integer.parseInt(parts[0]),
                        Base64.getDecoder().decode(parts[1]),
                        Base64.getDecoder().decode(parts[2]));
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean slowEquals(byte[] a, byte[] b) {
        int diff = a.length ^ b.length;
        for (int i = 0; i < a.length && i < b.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }

    private record ParsedHash(int iterations, byte[] salt, byte[] hash) {}
}
