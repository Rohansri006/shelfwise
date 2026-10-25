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
     * Puts a backup back as the database (E46). The current database is
     * backed up first, so a restore can itself be undone by restoring that
     * new backup.
     * <p>
     * The order matters. The chosen backup is first copied next to the
     * database: taking the safety backup can rotate out the oldest backup,
     * and if that was the one chosen it would otherwise be deleted before
     * it was used. Only then is the copy moved over the database, in one
     * step, so a failure part-way never leaves a half-written database.
     * <p>
     * Nothing may hold the database open while this runs; the caller
     * reloads the collection from the file afterwards.
     *
     * @param backup       one of this folder's backups, from {@link #listBackups()}
     * @param databaseFile the database file to replace
     * @return the safety backup of the database as it was before
     * @throws IOException              if a copy or the move fails
     * @throws IllegalArgumentException if {@code backup} is not one of this
     *                                  folder's backups
     */
    public Path restore(Path backup, Path databaseFile) throws IOException {
        if (backup == null || !listBackups().contains(backup)) {
            throw new IllegalArgumentException("Not one of the backups in " + backupFolder + ": " + backup);
        }
        Path incoming = databaseFile.resolveSibling(databaseFile.getFileName() + ".restoring");
        Files.copy(backup, incoming, StandardCopyOption.REPLACE_EXISTING);
        Path safety = backUp(databaseFile);
        Files.move(incoming, databaseFile, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        return safety;
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
