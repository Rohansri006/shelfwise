package com.shelfwise.core.storage;

import com.shelfwise.core.exception.SyncConflictException;
import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Category;
import com.shelfwise.core.model.Location;
import com.shelfwise.core.model.Series;
import com.shelfwise.core.model.Wishlist;
import com.shelfwise.core.service.CollectionData;
import com.shelfwise.core.service.StorageProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * Saves and loads the whole collection to one SQLite file,
 * {@code shelfwise.db}, in the chosen data folder.
 * <p>
 * <strong>Every save rewrites everything, inside one transaction</strong>
 * (ADR 0001). Auto-commit is switched off, every row is deleted and the
 * snapshot re-inserted, then {@code commit()} makes it all permanent at once.
 * If anything fails part-way, {@code rollback()} undoes the lot, so the file
 * always holds either the old collection or the new one, never half of
 * each.
 * <p>
 * <strong>Exception translation.</strong> The DAOs throw
 * {@link SQLException}; {@link StorageProvider} declares
 * {@link IOException}. This class converts one into the other, keeping the
 * original as the cause, so nothing above it ever depends on JDBC.
 * <p>
 * <strong>Conflict detection.</strong> The data folder may be a synced
 * folder. The file's last-modified time is recorded at every load and save.
 * If it has changed by the next save, something else wrote the file, and
 * the save is refused with {@link SyncConflictException} rather than
 * destroying that work. {@link #rebaseline()} is the "keep mine" answer.
 * <p>
 * <strong>Backups, once per launch.</strong> The first save made by this
 * object copies the existing file into {@code backups/} before writing
 * anything (ADR 0002). One {@code SqliteStorage} is created per app launch,
 * so with autosave the five kept backups cover the last five sessions, not
 * the last five edits.
 */
public class SqliteStorage implements StorageProvider {

    /** The database's file name inside the data folder. */
    public static final String DATABASE_FILE = "shelfwise.db";

    /** The backups folder's name inside the data folder. */
    public static final String BACKUP_FOLDER = "backups";

    private final Database database;
    private final BookDao bookDao = new BookDao();
    private final CategoryDao categoryDao = new CategoryDao();
    private final LocationDao locationDao = new LocationDao();
    private final SeriesDao seriesDao = new SeriesDao();
    private final WishlistDao wishlistDao = new WishlistDao();
    private final SettingDao settingDao = new SettingDao();
    private final BackupManager backups;

    /** Whether this object (this launch) has already taken its backup. */
    private boolean backedUpThisSession;

    /**
     * The file's last-modified time as of this object's last load or save,
     * or null if it has done neither.
     */
    private FileTime knownModified;

    /**
     * @param dataFolder the folder holding {@code shelfwise.db}; created on
     *                   first save if it does not exist
     * @throws IllegalArgumentException if dataFolder is null
     */
    public SqliteStorage(Path dataFolder) {
        if (dataFolder == null) {
            throw new IllegalArgumentException("Data folder must not be null.");
        }
        this.database = new Database(dataFolder.resolve(DATABASE_FILE));
        this.backups = new BackupManager(dataFolder.resolve(BACKUP_FOLDER));
    }

    /** @return the database file this storage reads and writes */
    public Path getDatabaseFile() {
        return database.getFile();
    }

    /** {@inheritDoc} */
    @Override
    public boolean dataExists() {
        return Files.exists(database.getFile());
    }

    /**
     * {@inheritDoc}
     * <p>
     * With no file yet, returns an empty collection and creates nothing.
     */
    @Override
    public CollectionData load() throws IOException {
        if (!dataExists()) {
            return new CollectionData(null, null, null, null, null, null);
        }
        try {
            database.initialiseSchema();
            CollectionData data;
            try (Connection connection = database.openConnection()) {
                List<Series> series = seriesDao.loadAll(connection);
                List<Category> categories = categoryDao.loadAll(connection);
                List<Location> locations = locationDao.loadAll(connection);
                List<Book> books = bookDao.loadAll(connection);
                List<Wishlist> wishlists = wishlistDao.loadAll(connection);
                String currency = settingDao.get(connection, SettingDao.CURRENCY);
                if (currency == null) {
                    currency = CollectionData.DEFAULT_CURRENCY;
                }
                data = new CollectionData(books, categories, locations, series, wishlists,
                        lastModified().toInstant(), currency);
            }
            knownModified = lastModified();
            return data;
        } catch (SQLException e) {
            throw new IOException("Could not read the collection from "
                    + database.getFile() + ": " + e.getMessage(), e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Deletes run children first and inserts parents first, so no foreign
     * key is ever broken in between: books go before the categories,
     * locations and series they point at, and come back after them.
     */
    @Override
    public void save(CollectionData data) throws IOException, SyncConflictException {
        if (data == null) {
            throw new IllegalArgumentException("Collection data must not be null.");
        }
        checkForConflict();
        backUpOncePerSession();
        try {
            database.initialiseSchema();
            try (Connection connection = database.openConnection()) {
                connection.setAutoCommit(false);
                try {
                    bookDao.deleteAll(connection);
                    categoryDao.deleteAll(connection);
                    locationDao.deleteAll(connection);
                    seriesDao.deleteAll(connection);
                    wishlistDao.deleteAll(connection);

                    seriesDao.insertAll(connection, data.getSeries());
                    categoryDao.insertAll(connection, data.getCategories());
                    locationDao.insertAll(connection, data.getLocations());
                    bookDao.insertAll(connection, data.getBooks());
                    wishlistDao.insertAll(connection, data.getWishlists());
                    settingDao.put(connection, SettingDao.CURRENCY, data.getCurrencyCode());

                    connection.commit();
                } catch (SQLException e) {
                    connection.rollback();
                    throw e;
                }
            }
        } catch (SQLException e) {
            throw new IOException("Could not save the collection; nothing was changed. "
                    + e.getMessage(), e);
        }
        knownModified = lastModified();
    }

    /**
     * On the first save of this launch, copies the file as it was before
     * this session changed anything. On a first run there is no file yet,
     * so nothing is copied. If the copy fails the save is abandoned: writing
     * without the safety net is not worth the risk.
     *
     * @throws IOException if the backup cannot be written
     */
    private void backUpOncePerSession() throws IOException {
        if (backedUpThisSession) {
            return;
        }
        if (dataExists()) {
            backups.backUp(database.getFile());
        }
        backedUpThisSession = true;
    }

    /** @return the manager of this collection's backups, e.g. to list them */
    public BackupManager getBackups() {
        return backups;
    }

    /**
     * Accepts the file as it is now, so the next save overwrites it. What
     * the "Keep mine" choice calls after a {@link SyncConflictException}.
     *
     * @throws IOException if the file's time cannot be read
     */
    public void rebaseline() throws IOException {
        knownModified = dataExists() ? lastModified() : null;
    }

    /**
     * Refuses to save over a file that changed since this object last saw
     * it - or over an existing file it never loaded at all.
     *
     * @throws IOException           if the file's time cannot be read
     * @throws SyncConflictException if the file changed underneath us
     */
    private void checkForConflict() throws IOException, SyncConflictException {
        if (!dataExists()) {
            return;
        }
        FileTime current = lastModified();
        if (knownModified == null || !current.equals(knownModified)) {
            Instant known = knownModified == null ? null : knownModified.toInstant();
            throw new SyncConflictException(known, current.toInstant());
        }
    }

    /**
     * @return the database file's last-modified time
     * @throws IOException if it cannot be read
     */
    private FileTime lastModified() throws IOException {
        return Files.getLastModifiedTime(database.getFile());
    }
}
