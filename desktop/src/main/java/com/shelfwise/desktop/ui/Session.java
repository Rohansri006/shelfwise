package com.shelfwise.desktop.ui;

import com.shelfwise.core.exception.SyncConflictException;
import com.shelfwise.core.metadata.GoogleBooksProvider;
import com.shelfwise.core.metadata.MetadataCache;
import com.shelfwise.core.metadata.MetadataLookup;
import com.shelfwise.core.metadata.OpenLibraryProvider;
import com.shelfwise.core.service.CollectionManager;
import com.shelfwise.core.service.MetadataProvider;
import com.shelfwise.core.storage.BackupManager;
import com.shelfwise.core.storage.CollectionMerger;
import com.shelfwise.core.storage.SampleCollection;
import com.shelfwise.core.storage.Settings;
import com.shelfwise.core.storage.SettingsFile;
import com.shelfwise.core.storage.SqliteStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

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

    // ------------------------------------------------------------------
    // Backups (E46) and import Undo (E161)
    // ------------------------------------------------------------------

    /** @return the backups of this collection, oldest first */
    public List<Path> listBackups() throws IOException {
        return backups().listBackups();
    }

    /**
     * "Back up now": copies the database into {@code backups/}. Autosave
     * means the file already holds everything on screen.
     *
     * @return the new backup
     */
    public Path backUpNow() throws IOException {
        return backups().backUp(storage.getDatabaseFile());
    }

    /**
     * E46: replaces the collection with a backup. {@link BackupManager}
     * backs up the current database first, so a restore can itself be
     * undone from the backup list. The collection is then read from the
     * file, like "Keep theirs", so screens must read the manager again.
     *
     * @param backup one of {@link #listBackups()}
     * @throws IOException if a copy or the reload fails
     */
    public void restore(Path backup) throws IOException {
        backups().restore(backup, storage.getDatabaseFile());
        reload();
    }

    /**
     * E161: keeps the database as it is just before an import, so the
     * import can be undone. A copy of the file rather than of the objects:
     * the merge changes existing categories and wishlists in place, so
     * objects kept in memory would change too, but the file cannot. Autosave
     * means the file holds exactly what is in memory at this moment. The
     * copy is a memento (a saved state to go back to); it does not use one
     * of the backup slots.
     */
    public void snapshotBeforeImport() throws IOException {
        Files.copy(storage.getDatabaseFile(), beforeImport(), StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * E161/E162: puts back the database kept by {@link #snapshotBeforeImport()}
     * and reloads it. Anything changed after the import is dropped too.
     *
     * @throws IOException if there is no snapshot, or the move or reload fails
     */
    public void undoImport() throws IOException {
        if (!Files.exists(beforeImport())) {
            throw new IOException("There is nothing to undo: the copy from before the import is gone.");
        }
        // One move, as BackupManager.restore does: a failure part-way never
        // leaves a half-written database.
        Files.move(beforeImport(), storage.getDatabaseFile(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        reload();
    }

    /** @return the backup folder's manager, keeping the default 5 */
    private BackupManager backups() {
        return new BackupManager(getDataFolder().resolve(SqliteStorage.BACKUP_FOLDER));
    }

    /** @return where {@link #snapshotBeforeImport()} keeps its copy, beside the database */
    private Path beforeImport() {
        Path db = storage.getDatabaseFile();
        return db.resolveSibling(db.getFileName() + ".before-import");
    }

    /**
     * E55: the ISBN lookup - Open Library first, Google Books to fill the
     * gaps - with answers cached in the collection's {@code cache/metadata}
     * folder, so a book looked up once is found again offline.
     * <p>
     * A new one each time it is asked for: it is only a few small objects,
     * and building it fresh means a Google key changed in Settings is used
     * at once. With no key, Google is still asked, on the shared keyless
     * quota, which can answer "too many requests"; that falls back cleanly.
     *
     * @return a lookup that reads the current settings
     */
    public MetadataLookup newLookup() {
        List<MetadataProvider> sources = new ArrayList<>();
        sources.add(new OpenLibraryProvider());
        // A null or blank key makes the provider ask anonymously.
        sources.add(new GoogleBooksProvider(settings.getGoogleApiKey()));
        return new MetadataLookup(sources, new MetadataCache(getDataFolder().resolve("cache").resolve("metadata")));
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
