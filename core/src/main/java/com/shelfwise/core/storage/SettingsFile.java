package com.shelfwise.core.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.shelfwise.core.model.GuestUser;
import com.shelfwise.core.model.LocalUser;
import com.shelfwise.core.model.User;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Reads and writes {@link Settings} as {@code settings.json}.
 * <p>
 * The file's location is passed in, never worked out here: core does not
 * hardcode paths (CLAUDE.md rule 6). The desktop app passes
 * {@code ~/.shelfwise/settings.json}; an Android app would pass a file in
 * its own private folder and reuse everything else.
 * <p>
 * <strong>Why a DTO again.</strong> {@link User} is abstract, so Gson
 * could not create one on reading - it would not know whether to build a
 * {@link GuestUser} or a {@link LocalUser}. The file stores the type as
 * text, and {@link #toUser} chooses the subclass.
 * <p>
 * <strong>Saving is all-or-nothing.</strong> The new text goes to a
 * temporary file first, which is then moved over the old one in a single
 * step. A crash mid-save leaves the old settings intact instead of a
 * half-written file the app could not read on the next launch.
 */
public class SettingsFile {

    /** What the file holds. Fields only; Gson fills them by reflection. */
    private static class Stored {
        String dataFolder;
        String userType;
        String displayName;
        String passwordHash;
        String salt;
        String theme;
        String googleApiKey;
    }

    private static final String GUEST = "GUEST";
    private static final String LOCAL = "LOCAL";

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * @param file where the settings live; created on the first save
     * @throws IllegalArgumentException if null
     */
    public SettingsFile(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("Settings file path must not be null.");
        }
        this.file = file;
    }

    /**
     * Reads the settings, or returns those of a fresh install if the file
     * does not exist yet - the normal state on first run, not an error.
     *
     * @return the settings
     * @throws IOException if the file exists but cannot be read or is
     *                     damaged
     */
    public Settings load() throws IOException {
        if (!Files.exists(file)) {
            return new Settings();
        }
        Stored stored;
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            stored = gson.fromJson(in, Stored.class);
        } catch (JsonParseException e) {
            throw new IOException("The settings file is damaged: " + e.getMessage(), e);
        }
        Settings settings = new Settings();
        if (stored == null) {
            return settings;
        }
        try {
            if (stored.dataFolder != null) {
                settings.setDataFolder(Paths.get(stored.dataFolder));
            }
            settings.setUser(toUser(stored));
            if (stored.theme != null) {
                settings.setTheme(Settings.Theme.valueOf(stored.theme));
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("The settings file is damaged: " + e.getMessage(), e);
        }
        settings.setGoogleApiKey(stored.googleApiKey);
        return settings;
    }

    /**
     * Writes the settings, replacing the file in one step.
     *
     * @param settings what to save; must not be null
     * @throws IOException if the file cannot be written
     */
    public void save(Settings settings) throws IOException {
        if (settings == null) {
            throw new IllegalArgumentException("Settings must not be null.");
        }
        Stored stored = new Stored();
        stored.dataFolder = settings.getDataFolder() == null
                ? null : settings.getDataFolder().toString();
        stored.theme = settings.getTheme().name();
        stored.googleApiKey = settings.getGoogleApiKey();

        // instanceof picks the fields each subclass has. A guest has no
        // password, so only a LocalUser writes a hash and salt.
        User user = settings.getUser();
        if (user instanceof LocalUser) {
            LocalUser local = (LocalUser) user;
            stored.userType = LOCAL;
            stored.passwordHash = local.getPasswordHash();
            stored.salt = local.getSalt();
        } else if (user != null) {
            stored.userType = GUEST;
        }
        if (user != null) {
            stored.displayName = user.getDisplayName();
        }

        Path folder = file.toAbsolutePath().getParent();
        Files.createDirectories(folder);
        Path temporary = folder.resolve(file.getFileName() + ".tmp");
        try (BufferedWriter out = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            gson.toJson(stored, out);
        }
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Rebuilds the right {@link User} subclass from the stored type.
     *
     * @param stored what the file held
     * @return the User, or null if none has been created yet
     * @throws IllegalArgumentException if the type is unknown or a field
     *                                  the type needs is missing
     */
    private static User toUser(Stored stored) {
        if (stored.userType == null) {
            return null;
        }
        if (stored.userType.equals(LOCAL)) {
            return new LocalUser(stored.displayName, stored.passwordHash, stored.salt);
        }
        if (stored.userType.equals(GUEST)) {
            return new GuestUser(stored.displayName);
        }
        throw new IllegalArgumentException("\"" + stored.userType + "\" is not a kind of User.");
    }
}
