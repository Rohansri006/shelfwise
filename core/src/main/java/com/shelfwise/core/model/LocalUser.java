package com.shelfwise.core.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * A password-protected profile.
 * <p>
 * <strong>The password itself is never stored</strong> - not in a field,
 * not on disk. What is kept is a SHA-256 hash of a random salt followed by
 * the password. At launch the typed password is hashed the same way and the
 * two hashes are compared.
 * <p>
 * <strong>Why the salt.</strong> Without it, two people who chose the same
 * password would have the same hash, and an attacker could look hashes up in
 * a table precomputed for common passwords. A random salt per install makes
 * every hash unique, so no precomputed table applies.
 * <p>
 * <strong>Known limitation, chosen deliberately.</strong> SHA-256 is
 * designed to be fast, which also makes guessing fast: someone holding the
 * settings file could try billions of passwords a second. Production systems
 * use a deliberately slow algorithm such as PBKDF2, bcrypt or Argon2. Plain
 * SHA-256 is used here because it needs no dependency, fits in a few lines
 * and can be explained in full; this protects a book list on a personal
 * machine, not a bank account.
 */
public class LocalUser extends User {

    /** How many random bytes make a salt. 16 bytes = 128 bits. */
    private static final int SALT_BYTES = 16;

    /** SHA-256 of salt + password, as 64 lowercase hex characters. */
    private String passwordHash;

    /** The random salt, as lowercase hex. */
    private String salt;

    /**
     * Creates a new profile, hashing the chosen password with a fresh salt.
     *
     * @param displayName the name to show; must not be blank
     * @param password    the chosen password; must not be blank
     * @throws IllegalArgumentException if either is blank
     */
    public LocalUser(String displayName, String password) {
        super(displayName);
        requireNonBlankPassword(password);
        this.salt = newSalt();
        this.passwordHash = hash(salt, password);
    }

    /**
     * Recreates a profile from the settings file, keeping its hash and salt.
     * Used by the storage layer when loading.
     *
     * @param displayName  the name to show; must not be blank
     * @param passwordHash the stored hash; must not be blank
     * @param salt         the stored salt; must not be blank
     * @throws IllegalArgumentException if any argument is blank
     */
    public LocalUser(String displayName, String passwordHash, String salt) {
        super(displayName);
        if (passwordHash == null || passwordHash.trim().isEmpty()) {
            throw new IllegalArgumentException("Stored password hash must not be blank.");
        }
        if (salt == null || salt.trim().isEmpty()) {
            throw new IllegalArgumentException("Stored salt must not be blank.");
        }
        this.passwordHash = passwordHash;
        this.salt = salt;
    }

    /** @return true: a local profile always asks for its password */
    @Override
    public boolean requiresPassword() {
        return true;
    }

    /** @return "Local" */
    @Override
    public String getProfileType() {
        return "Local";
    }

    /**
     * Checks a typed password by hashing it with the stored salt.
     * <p>
     * The comparison uses {@link MessageDigest#isEqual} rather than
     * {@code String.equals}. {@code equals} stops at the first different
     * character, so in principle the time it takes leaks how much of a guess
     * was right; {@code isEqual} always compares every byte.
     *
     * @param password what the user typed; may be null
     * @return true if it matches
     */
    @Override
    public boolean authenticate(String password) {
        if (password == null) {
            return false;
        }
        String attempt = hash(salt, password);
        return MessageDigest.isEqual(
                attempt.getBytes(StandardCharsets.UTF_8),
                passwordHash.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Replaces the password, after checking the current one.
     * <p>
     * A fresh salt is generated as well, so the new hash has no connection
     * to the old one.
     *
     * @param currentPassword the existing password
     * @param newPassword     the replacement; must not be blank
     * @throws IllegalArgumentException if the current password is wrong or
     *                                  the new one is blank
     */
    public void changePassword(String currentPassword, String newPassword) {
        if (!authenticate(currentPassword)) {
            throw new IllegalArgumentException("Current password is incorrect.");
        }
        requireNonBlankPassword(newPassword);
        this.salt = newSalt();
        this.passwordHash = hash(salt, newPassword);
    }

    /** @return the stored hash, for the settings file only */
    public String getPasswordHash() {
        return passwordHash;
    }

    /** @return the stored salt, for the settings file only */
    public String getSalt() {
        return salt;
    }

    /**
     * @param password the password to check
     * @throws IllegalArgumentException if null or blank
     */
    private static void requireNonBlankPassword(String password) {
        if (password == null || password.trim().isEmpty()) {
            throw new IllegalArgumentException("Password must not be blank.");
        }
    }

    /**
     * Generates a random salt.
     * <p>
     * {@link SecureRandom}, not {@link java.util.Random}: {@code Random} is
     * predictable from its seed, and a predictable salt defeats its purpose.
     *
     * @return 16 random bytes as 32 hex characters
     */
    private static String newSalt() {
        byte[] bytes = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(bytes);
        return toHex(bytes);
    }

    /**
     * SHA-256 of the salt followed by the password.
     *
     * @param salt     the salt, as stored
     * @param password the password
     * @return the digest as 64 hex characters
     */
    private static String hash(String salt, String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt.getBytes(StandardCharsets.UTF_8));
            digest.update(password.getBytes(StandardCharsets.UTF_8));
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to provide SHA-256, so this
            // cannot happen. It is a checked exception, so it must still be
            // handled; rethrowing unchecked avoids forcing every caller to
            // declare an exception that never occurs.
            throw new IllegalStateException("SHA-256 is not available.", e);
        }
    }

    /**
     * Converts bytes to lowercase hex, two characters per byte.
     * <p>
     * Written out rather than using {@code java.util.HexFormat}, which
     * Android only provides from API level 34 - and {@code core} is reused
     * by the future Android app.
     *
     * @param bytes the bytes to convert
     * @return the hex string
     */
    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            hex.append(String.format("%02x", bytes[i]));
        }
        return hex.toString();
    }
}
