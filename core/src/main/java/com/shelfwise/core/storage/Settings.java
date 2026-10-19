package com.shelfwise.core.storage;

import com.shelfwise.core.model.User;

import java.nio.file.Path;

/**
 * The application's own preferences, as opposed to the collection: where
 * the collection lives, who uses this install, the theme, and the optional
 * Google Books key (D21, L7). Saved to {@code settings.json} by
 * {@link SettingsFile}.
 * <p>
 * Kept apart from the database because it says <em>where</em> the database
 * is. The currency is deliberately not here: it belongs to the collection
 * and travels with it (ADR 0005).
 */
public class Settings {

    /** The two looks the desktop app ships, matching its two style sheets. */
    public enum Theme {
        DARK("Dark"),
        LIGHT("Light");

        private final String label;

        Theme(String label) {
            this.label = label;
        }

        /** @return the name shown in the settings screen */
        public String getLabel() {
            return label;
        }

        /** @return the label, so a raw name never reaches the screen */
        @Override
        public String toString() {
            return label;
        }
    }

    /** The folder holding the collection; null until first run chooses one. */
    private Path dataFolder;

    /** Who uses this install; null until first run creates one. */
    private User user;

    private Theme theme;

    /**
     * The Google Books API key, or null to use Open Library only. A secret:
     * never logged and left out of {@link #toString()} (L7).
     */
    private String googleApiKey;

    /** Creates the settings of a fresh install: nothing chosen, dark theme. */
    public Settings() {
        this.theme = Theme.DARK;
    }

    /**
     * Whether first-run setup still has to happen: no folder chosen, or no
     * User created yet (D22).
     *
     * @return true until both exist
     */
    public boolean isFirstRun() {
        return dataFolder == null || user == null;
    }

    /** @return the collection's folder, or null if not chosen yet */
    public Path getDataFolder() {
        return dataFolder;
    }

    /** @param dataFolder the collection's folder; null clears the choice */
    public void setDataFolder(Path dataFolder) {
        this.dataFolder = dataFolder;
    }

    /** @return the User of this install, or null if not created yet */
    public User getUser() {
        return user;
    }

    /** @param user the User of this install */
    public void setUser(User user) {
        this.user = user;
    }

    /** @return the theme */
    public Theme getTheme() {
        return theme;
    }

    /**
     * @param theme the theme; must not be null
     * @throws IllegalArgumentException if null
     */
    public void setTheme(Theme theme) {
        if (theme == null) {
            throw new IllegalArgumentException("Theme must not be null.");
        }
        this.theme = theme;
    }

    /** @return the Google Books key, or null if none is set */
    public String getGoogleApiKey() {
        return googleApiKey;
    }

    /** @param googleApiKey the key; null or blank removes it */
    public void setGoogleApiKey(String googleApiKey) {
        if (googleApiKey == null || googleApiKey.trim().isEmpty()) {
            this.googleApiKey = null;
        } else {
            this.googleApiKey = googleApiKey.trim();
        }
    }

    /**
     * Says only whether a key is set, never the key itself, so printing
     * the settings into a log cannot leak it (L7).
     *
     * @return a readable summary
     */
    @Override
    public String toString() {
        return "Settings[folder=" + dataFolder + ", user=" + user + ", theme=" + theme
                + ", Google key " + (googleApiKey == null ? "not set" : "set") + "]";
    }
}
