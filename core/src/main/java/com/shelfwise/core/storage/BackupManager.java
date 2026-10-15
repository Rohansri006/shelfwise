package com.shelfwise.core.storage;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Keeps rolling copies of the database file in a {@code backups} folder.
 * <p>
 * Plain file handling: each backup is a byte-for-byte copy named after the
 * moment it was taken, {@code shelfwise-20261015-142530.db}. Only the newest
 * few are kept; older ones are deleted. The timestamp is written
 * year-first, so sorting the names alphabetically also sorts them oldest to
 * newest, and no date parsing is needed to decide which to delete.
 * <p>
 * When a backup is taken is decided by {@code SqliteStorage}: once per
 * launch, before the first write (ADR 0002).
 */
public class BackupManager {

    /** How many backups are kept unless told otherwise. */
    public static final int DEFAULT_KEEP = 5;

    /** Name prefix and suffix; also the pattern for finding backups again. */
    private static final String PREFIX = "shelfwise-";
    private static final String SUFFIX = ".db";

    /** Year first, so alphabetical order is chronological order. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path backupFolder;
    private final int keep;

    /**
     * Keeps the default five backups.
     *
     * @param backupFolder where backups are written; created when needed
     */
    public BackupManager(Path backupFolder) {
        this(backupFolder, DEFAULT_KEEP);
    }

    /**
     * @param backupFolder where backups are written; created when needed
     * @param keep         how many to keep; at least 1
     * @throws IllegalArgumentException if the folder is null or keep is
     *                                  below 1
     */
    public BackupManager(Path backupFolder, int keep) {
        if (backupFolder == null) {
            throw new IllegalArgumentException("Backup folder must not be null.");
        }
        if (keep < 1) {
            throw new IllegalArgumentException("Must keep at least one backup, but was " + keep + ".");
        }
        this.backupFolder = backupFolder;
        this.keep = keep;
    }

    /**
     * Backs up a database file now.
     *
     * @param databaseFile the file to copy
     * @return the backup just written
     * @throws IOException if the copy or the clean-up fails
     */
    public Path backUp(Path databaseFile) throws IOException {
        return backUp(databaseFile, LocalDateTime.now());
    }

    /**
     * Backs up a database file, naming the copy after a given moment, then
     * deletes the oldest backups beyond the limit. Taking the time as a
     * parameter is what lets tests create backups "from" different times.
     *
     * @param databaseFile the file to copy
     * @param takenAt      the moment to name the backup after
     * @return the backup just written
     * @throws IOException if the copy or the clean-up fails
     */
    public Path backUp(Path databaseFile, LocalDateTime takenAt) throws IOException {
        Files.createDirectories(backupFolder);
        Path target = backupFolder.resolve(PREFIX + STAMP.format(takenAt) + SUFFIX);
        // Two backups within one second share a name; the later one wins.
        Files.copy(databaseFile, target, StandardCopyOption.REPLACE_EXISTING);
        deleteOldest();
        return target;
    }

    /**
     * Every backup in the folder, oldest first. Other files there are
     * ignored.
     *
     * @return the backups, oldest first; empty if there are none
     * @throws IOException if the folder cannot be read
     */
    public List<Path> listBackups() throws IOException {
        List<Path> backups = new ArrayList<>();
        if (!Files.isDirectory(backupFolder)) {
            return backups;
        }
        // A DirectoryStream reads the folder lazily and must be closed,
        // hence try-with-resources. The glob keeps only our own files. It is
        // Iterable but has no index, so this is the one place the codebase
        // uses an enhanced for loop instead of an indexed one.
        try (DirectoryStream<Path> files =
                     Files.newDirectoryStream(backupFolder, PREFIX + "*" + SUFFIX)) {
            for (Path file : files) {
                backups.add(file);
            }
        }
        Collections.sort(backups, new Comparator<Path>() {
            @Override
            public int compare(Path a, Path b) {
                return a.getFileName().toString().compareTo(b.getFileName().toString());
            }
        });
        return backups;
    }

    /**
     * Deletes the oldest backups until only {@code keep} remain.
     *
     * @throws IOException if a file cannot be deleted
     */
    private void deleteOldest() throws IOException {
        List<Path> backups = listBackups();
        int excess = backups.size() - keep;
        for (int i = 0; i < excess; i++) {
            Files.delete(backups.get(i));
        }
    }
}
