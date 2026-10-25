package com.shelfwise.desktop.ui;

import com.shelfwise.core.exception.InvalidISBNException;
import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Contributor;
import com.shelfwise.core.model.ContributorRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Refresh details' comparison, apply and Undo (E143-E147),
 * without JavaFX: no cover image is involved, and cover files are written
 * by hand where Undo's file handling is checked.
 */
class RefreshDetailsTest {

    @TempDir
    Path folder;

    /** Glimpses of World History, as the user has it: no pages, one author, a translator. */
    private static Book yours() throws InvalidISBNException {
        Book book = new Book("9780143031055", "Glimpses of World History");
        book.addContributor(new Contributor("Jawaharlal Nehru"));
        book.addContributor(new Contributor("A. Translator", ContributorRole.TRANSLATOR));
        book.setPublisher("Penguin");
        return book;
    }

    /** What a lookup might find for the same ISBN. */
    private static RefreshDetails.Found found() throws InvalidISBNException {
        Book book = new Book("9780143031055", "Glimpses of World History");
        book.addContributor(new Contributor("Jawaharlal Nehru"));
        book.setPublisher("Penguin India");
        book.setPages(1192);
        return new RefreshDetails.Found(book, null, "Found on Open Library");
    }

    private static RefreshDetails.Change change(List<RefreshDetails.Change> changes, RefreshDetails.Field field) {
        for (int i = 0; i < changes.size(); i++) {
            if (changes.get(i).field == field) {
                return changes.get(i);
            }
        }
        return null;
    }

    @Test
    @DisplayName("the single review lists only differing fields, ticked only where yours is empty")
    void singleReviewTicksEmptyOnly() throws InvalidISBNException {
        Book book = yours();
        List<RefreshDetails.Change> changes = RefreshDetails.diff(book, RefreshDetails.values(book), false,
                found(), null);
        // Title and authors are the same, so they are not offered at all.
        assertEquals(null, change(changes, RefreshDetails.Field.TITLE));
        assertEquals(null, change(changes, RefreshDetails.Field.AUTHORS));
        // Pages: yours is empty, so taking the found value is ticked.
        assertTrue(change(changes, RefreshDetails.Field.PAGES).use);
        // Publisher: both have one and they differ, so yours is kept unless ticked.
        assertFalse(change(changes, RefreshDetails.Field.PUBLISHER).use);
    }

    @Test
    @DisplayName("bulk rules: Fill if empty skips a filled field, Replace takes it, Keep never changes it")
    void bulkRules() throws InvalidISBNException {
        Book book = yours();
        Map<RefreshDetails.Field, RefreshDetails.Rule> rules = BulkRefreshDialog.fillEmptyEverywhere();
        List<RefreshDetails.Change> fill = RefreshDetails.diff(book, RefreshDetails.values(book), false, found(), rules);
        assertEquals(null, change(fill, RefreshDetails.Field.PUBLISHER));
        assertTrue(change(fill, RefreshDetails.Field.PAGES).use);

        rules.put(RefreshDetails.Field.PUBLISHER, RefreshDetails.Rule.REPLACE);
        rules.put(RefreshDetails.Field.PAGES, RefreshDetails.Rule.KEEP);
        List<RefreshDetails.Change> replace = RefreshDetails.diff(book, RefreshDetails.values(book), false, found(),
                rules);
        assertTrue(change(replace, RefreshDetails.Field.PUBLISHER).use);
        assertEquals(null, change(replace, RefreshDetails.Field.PAGES));
    }

    @Test
    @DisplayName("new authors replace only the Author rows; a translator is kept (E144)")
    void replaceAuthorsKeepsTranslator() throws InvalidISBNException {
        Book book = yours();
        List<Contributor> newAuthors = new ArrayList<>();
        newAuthors.add(new Contributor("J. Nehru"));
        RefreshDetails.replaceAuthors(book, newAuthors);
        assertEquals("J. Nehru", RefreshDetails.authors(book.getContributors()));
        assertEquals(2, book.getContributors().size());
        assertEquals(ContributorRole.TRANSLATOR, book.getContributors().get(1).getRole());
    }

    @Test
    @DisplayName("apply then Undo puts every field back exactly")
    void applyThenUndoRestoresFields() throws IOException, InvalidISBNException {
        Book book = yours();
        Map<RefreshDetails.Field, RefreshDetails.Rule> rules = new EnumMap<>(RefreshDetails.Field.class);
        RefreshDetails.Field[] fields = RefreshDetails.Field.values();
        for (int i = 0; i < fields.length; i++) {
            rules.put(fields[i], RefreshDetails.Rule.REPLACE);
        }
        rules.put(RefreshDetails.Field.COVER, RefreshDetails.Rule.KEEP);
        List<RefreshDetails.Change> changes = RefreshDetails.diff(book, RefreshDetails.values(book), false,
                found(), rules);
        Map<RefreshDetails.Field, String> before = RefreshDetails.values(book);
        String contributorsBefore = book.getContributors().toString();

        List<String> problems = new ArrayList<>();
        List<RefreshDetails.Snapshot> snapshots = RefreshDetails.apply(changes, folder, problems);
        assertEquals(1192, book.getPages());
        assertEquals("Penguin India", book.getPublisher());

        RefreshDetails.undo(snapshots);
        assertEquals(before, RefreshDetails.values(book));
        assertEquals(contributorsBefore, book.getContributors().toString());
        assertTrue(problems.isEmpty());
    }

    @Test
    @DisplayName("Undo puts an old cover file back byte for byte")
    void undoRestoresCoverBytes() throws IOException, InvalidISBNException {
        Book book = yours();
        Path cover = CoverArt.uploadedPath(book, folder);
        Files.createDirectories(cover.getParent());
        byte[] old = {1, 2, 3, 4, 5};
        Files.write(cover, old);

        RefreshDetails.Snapshot snapshot = new RefreshDetails.Snapshot(book, folder);
        // What a refresh would do: write a new cover over the old one.
        Files.write(cover, new byte[] {9, 9, 9});
        snapshot.restore();
        assertArrayEquals(old, Files.readAllBytes(cover));
    }

    @Test
    @DisplayName("Undo removes a cover file the refresh created where there was none")
    void undoRemovesNewCover() throws IOException, InvalidISBNException {
        Book book = yours();
        Path cover = CoverArt.uploadedPath(book, folder);
        RefreshDetails.Snapshot snapshot = new RefreshDetails.Snapshot(book, folder);
        Files.createDirectories(cover.getParent());
        Files.write(cover, new byte[] {9, 9, 9});
        snapshot.restore();
        assertFalse(Files.exists(cover));
    }
}
