package com.shelfwise.core.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link BackupManager}. */
class BackupManagerTest {

    @TempDir
    Path folder;

    /** A stand-in database file; its contents only need to be copied. */
    private Path databaseFile;
    private Path backupFolder;

    @BeforeEach
    void createFiles() throws IOException {
        databaseFile = folder.resolve("shelfwise.db");
        Files.write(databaseFile, "pretend database".getBytes(StandardCharsets.UTF_8));
        backupFolder = folder.resolve("backups");
    }

    @Test
    @DisplayName("a backup is a byte-for-byte copy named after when it was taken")
    void backupIsCopy() throws IOException {
        Path backup = new BackupManager(backupFolder)
                .backUp(databaseFile, LocalDateTime.of(2026, 10, 15, 14, 25, 30));

        assertEquals("shelfwise-20261015-142530.db", backup.getFileName().toString());
        assertArrayEquals(Files.readAllBytes(databaseFile), Files.readAllBytes(backup));
    }

    @Test
    @DisplayName("only the five newest backups are kept")
    void rotationKeepsNewest() throws IOException {
        BackupManager backups = new BackupManager(backupFolder);
        for (int day = 1; day <= 7; day++) {
            backups.backUp(databaseFile, LocalDateTime.of(2026, 10, day, 9, 0, 0));
        }

        List<Path> kept = backups.listBackups();
        assertEquals(5, kept.size());
        // Days 1 and 2 were the oldest, so day 3 is now the oldest kept.
        assertEquals("shelfwise-20261003-090000.db", kept.get(0).getFileName().toString());
        assertEquals("shelfwise-20261007-090000.db", kept.get(4).getFileName().toString());
    }

    @Test
    @DisplayName("other files in the backups folder are never touched")
    void foreignFilesIgnored() throws IOException {
        Files.createDirectories(backupFolder);
        Path notes = backupFolder.resolve("my-notes.txt");
        Files.write(notes, "keep me".getBytes(StandardCharsets.UTF_8));

        BackupManager backups = new BackupManager(backupFolder, 1);
        backups.backUp(databaseFile, LocalDateTime.of(2026, 10, 1, 9, 0, 0));
        backups.backUp(databaseFile, LocalDateTime.of(2026, 10, 2, 9, 0, 0));

        assertTrue(Files.exists(notes));
        assertEquals(1, backups.listBackups().size());
    }

    @Test
    @DisplayName("with no backups folder yet, the list is empty")
    void noFolderYet() throws IOException {
        assertTrue(new BackupManager(backupFolder).listBackups().isEmpty());
    }

    @Test
    @DisplayName("backing up a missing file fails with an IOException")
    void missingSource() {
        final BackupManager backups = new BackupManager(backupFolder);
        assertThrows(IOException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                backups.backUp(folder.resolve("missing.db"));
            }
        });
    }

    @Test
    @DisplayName("keeping fewer than one backup is refused, naming the value")
    void keepMustBePositive() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                new Executable() {
                    @Override
                    public void execute() {
                        new BackupManager(backupFolder, 0);
                    }
                });
        assertTrue(e.getMessage().contains("0"), e.getMessage());
    }
}
