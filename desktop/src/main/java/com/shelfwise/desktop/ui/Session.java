package com.shelfwise.desktop.ui;

import com.shelfwise.core.exception.SyncConflictException;
import com.shelfwise.core.service.CollectionManager;
import com.shelfwise.core.storage.CollectionMerger;
import com.shelfwise.core.storage.SampleCollection;
import com.shelfwise.core.storage.Settings;
import com.shelfwise.core.storage.SettingsFile;
import com.shelfwise.core.storage.SqliteStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Everything one run of the app works on: the settings, the open
 * collection and where both are stored.
 * <p>
 * <b>Autosave (ADR 0001, E153).</b> There is no Save button. After each
 * logical operation a screen asks the window to save, and the window calls
 * {@link #save()}, which rewrites the whole collection in one transaction.
 * A save takes ~30 ms for 1,000 books, so it runs on the JavaFX thread.
 * <p>
 * <b>Why screens must not keep the manager.</b> "Keep theirs" after a sync
 * conflict replaces the {@link CollectionManager} with a freshly loaded
 * one. A screen that had stored the old one would go on showing stale
 * books, so screens always ask {@link #getManager()} when they need it.
 */
public class Session {

    /**
     * E158: a test hook. When this environment variable names a file, the
     * settings are read from and written to it instead of
     * {@code ~/.shelfwise/settings.json}, so a test run can use a throwaway
     * folder and never touch the real collection.
     */
    public static final String SETTINGS_OVERRIDE = "SHELFWISE_SETTINGS";

    private final SettingsFile settingsFile;
    private final Settings settings;
    private SqliteStorage storage;
    private CollectionManager manager;

    /**
     * Reads the settings. The collection is opened separately, because
     * first run has to choose its folder before there is one to open.
     *
     * @param settingsFile where the settings live
     * @throws IOException if the settings file exists but cannot be read
     */
    public Session(SettingsFile settingsFile) throws IOException {
        this.settingsFile = settingsFile;
        this.settings = settingsFile.load();
    }

    /** @return the settings file to use: the override if set, else the one in the home folder */
    public static Path settingsPath() {
        String override = System.getenv(SETTINGS_OVERRIDE);
        if (override != null && !override.trim().isEmpty()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".shelfwise", "settings.json");
    }

    /**
     * Opens the collection in the settings' data folder. A folder with no
     * database yet starts an empty collection, or the sample books (D22),
     * and saves it at once so the file exists from the first launch.
     *
     * @param withSample true to start a new collection with the sample books
     * @throws IOException if the folder or database cannot be read or written
     */
    public void openCollection(boolean withSample) throws IOException {
        Path folder = settings.getDataFolder();
        Files.createDirectories(folder);
        storage = new SqliteStorage(folder);
        if (storage.dataExists()) {
            manager = new CollectionManager(storage.load());
            return;
        }
        manager = new CollectionManager();
        if (withSample) {
            new CollectionMerger().merge(SampleCollection.read(), manager);
        }
        try {
            storage.save(manager.toCollectionData());
        } catch (SyncConflictException e) {
            // Impossible here: there was no file for anyone else to change.
            throw new IOException("The new collection could not be created: " + e.getMessage(), e);
        }
    }

    /**
     * Writes the whole collection (autosave).
     *
     * @throws SyncConflictException if the database file changed on disk
     *                               since it was loaded; nothing is written
     * @throws IOException           if the write fails; nothing is changed
     */
    public void save() throws IOException, SyncConflictException {
        storage.save(manager.toCollectionData());
    }

    /**
     * "Keep mine" after a conflict: accept the file's new state as the
     * baseline, then write ours over it.
     */
    public void saveOverConflict() throws IOException, SyncConflictException {
        storage.rebaseline();
        save();
    }

    /** "Keep theirs" after a conflict, and after a restore: load the file again, dropping what is in memory. */
    public void reload() throws IOException {
        manager = new CollectionManager(storage.load());
    }

    /** Writes the settings file (theme, user, folder). */
    public void saveSettings() throws IOException {
        settingsFile.save(settings);
    }

    public Settings getSettings() {
        return settings;
    }

    public CollectionManager getManager() {
        return manager;
    }

    public SqliteStorage getStorage() {
        return storage;
    }

    /** @return the collection's folder, where the database, covers and backups live */
    public Path getDataFolder() {
        return settings.getDataFolder();
    }
}
