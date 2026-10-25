package com.shelfwise.desktop.ui;

import com.shelfwise.core.exception.ShelfWiseException;
import com.shelfwise.core.model.Category;
import com.shelfwise.core.storage.SettingsFile;
import com.shelfwise.core.storage.SqliteStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the Settings page's file operations in {@link Session}: Back up
 * now, Restore (E46, E160) and the import Undo (E161). Each works on a real
 * SQLite file in a temporary folder, because what is being tested is
 * exactly what ends up in that file.
 */
class SessionTest {

    @TempDir
    Path folder;

    private Session session;

    /** An empty collection holding one book, saved to disk. */
    @BeforeEach
    void setUp() throws IOException, ShelfWiseException {
        session = new Session(new SettingsFile(folder.resolve("settings.json")));
        session.getSettings().setDataFolder(folder.resolve("data"));
        session.openCollection(false);
        session.getManager().addItem("Dune");
        session.save();
    }

    /**
     * Reads the books in a backup file by copying it into a folder of its
     * own as {@code shelfwise.db}, the name the storage expects.
     */
    private int booksIn(Path backup) throws IOException {
        Path copy = Files.createTempDirectory(folder, "read");
        Files.copy(backup, copy.resolve(SqliteStorage.DATABASE_FILE));
        return new SqliteStorage(copy).load().getBooks().size();
    }

    @Test
    @DisplayName("Back up now writes a listed backup holding the collection")
    void backUpNowWritesABackup() throws IOException, ShelfWiseException {
        session.getManager().addItem("Solaris");
        session.save();
        Path backup = session.backUpNow();
        // Counting files would be unreliable: two backups in one second share a name.
        assertTrue(session.listBackups().contains(backup));
        assertEquals(2, booksIn(backup));
    }

    @Test
    @DisplayName("restoring a backup brings back its books, keeps the newer state as a backup, and saves cleanly after")
    void restoreBringsBackTheBackup() throws IOException, ShelfWiseException {
        Path oneBook = session.backUpNow();
        session.getManager().addItem("Solaris");
        session.save();

        session.restore(oneBook);
        assertEquals(1, session.getManager().getItems().size());
        // The two-book state was backed up before being replaced.
        boolean kept = false;
        List<Path> backups = session.listBackups();
        for (int i = 0; i < backups.size(); i++) {
            if (booksIn(backups.get(i)) == 2) {
                kept = true;
            }
        }
        assertTrue(kept);

        // The file changed under the storage, but the reload took that as the
        // new starting point: the next save must not report a conflict.
        session.getManager().addItem("Foundation");
        session.save();
    }

    @Test
    @DisplayName("undoing an import puts the database back as it was just before")
    void undoImportPutsTheDatabaseBack() throws IOException, ShelfWiseException {
        session.snapshotBeforeImport();
        // What a merge might do: a new book and a new category.
        session.getManager().addItem("Solaris");
        session.getManager().addCategory(new Category("Imported"));
        session.save();

        session.undoImport();
        assertEquals(1, session.getManager().getItems().size());
        assertEquals(0, session.getManager().getRootCategories().size());
        // Disk agrees with memory.
        assertEquals(1, new SqliteStorage(folder.resolve("data")).load().getBooks().size());
        // The copy is used up, so a second Undo has nothing to go back to.
        Path db = folder.resolve("data").resolve(SqliteStorage.DATABASE_FILE);
        assertFalse(Files.exists(db.resolveSibling(SqliteStorage.DATABASE_FILE + ".before-import")));
        assertThrows(IOException.class, new Executable() {
            @Override
            public void execute() throws IOException {
                session.undoImport();
            }
        });
    }

    @Test
    @DisplayName("the import snapshot does not use one of the backup slots")
    void snapshotIsNotABackup() throws IOException {
        List<Path> before = session.listBackups();
        session.snapshotBeforeImport();
        assertEquals(before, session.listBackups());
    }
}
