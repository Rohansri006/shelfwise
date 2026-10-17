package com.shelfwise.core.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the exception hierarchy.
 * <p>
 * These check three things: that the hierarchy is shaped as designed, that
 * each exception keeps the structured data a screen will need, and that the
 * generated messages actually read as English. The last one matters because
 * these strings are shown to the user.
 */
class ShelfWiseExceptionTest {

    @Test
    @DisplayName("base class is abstract, so only specific exceptions can be thrown")
    void baseClassIsAbstract() {
        assertTrue(Modifier.isAbstract(ShelfWiseException.class.getModifiers()));
    }

    @Test
    @DisplayName("all exceptions are checked, forcing the UI to handle them")
    void exceptionsAreChecked() {
        // A checked exception extends Exception but NOT RuntimeException.
        assertTrue(Exception.class.isAssignableFrom(ShelfWiseException.class));
        assertFalse(RuntimeException.class.isAssignableFrom(ShelfWiseException.class));
    }

    @Test
    @DisplayName("every custom exception shares the ShelfWiseException base")
    void allShareTheBase() {
        assertTrue(ShelfWiseException.class.isAssignableFrom(InvalidISBNException.class));
        assertTrue(ShelfWiseException.class.isAssignableFrom(DuplicateItemException.class));
        assertTrue(ShelfWiseException.class.isAssignableFrom(CategoryNotEmptyException.class));
        assertTrue(ShelfWiseException.class.isAssignableFrom(LocationNotEmptyException.class));
        assertTrue(ShelfWiseException.class.isAssignableFrom(MetadataFetchException.class));
        assertTrue(ShelfWiseException.class.isAssignableFrom(SyncConflictException.class));
    }

    @Test
    @DisplayName("catching the base type catches any subclass (runtime polymorphism)")
    void baseTypeCatchesSubclasses() {
        // This is the practical payoff of the hierarchy: a screen that cannot
        // do anything useful with a specific failure can still catch the base
        // type and show the message.
        String caughtMessage = null;
        try {
            throw new InvalidISBNException("123", "wrong length");
        } catch (ShelfWiseException e) {
            caughtMessage = e.getMessage();
        }
        assertEquals("\"123\" is not a valid ISBN: wrong length.", caughtMessage);
    }

    @Test
    @DisplayName("InvalidISBNException keeps the rejected value and the reason")
    void invalidIsbnKeepsDetails() {
        InvalidISBNException e = new InvalidISBNException("9780140303106", "check digit mismatch");
        assertEquals("9780140303106", e.getIsbn());
        assertEquals("check digit mismatch", e.getReason());
    }

    @Test
    @DisplayName("DuplicateItemException identifies the book already held")
    void duplicateIdentifiesExisting() {
        DuplicateItemException e =
                new DuplicateItemException("9780060555665", "b1", "The Intelligent Investor");
        assertEquals("9780060555665", e.getIsbn());
        assertEquals("b1", e.getExistingItemId());
        assertEquals("The Intelligent Investor", e.getExistingTitle());
        assertEquals("\"The Intelligent Investor\" already has ISBN 9780060555665.",
                e.getMessage());
    }

    @Test
    @DisplayName("CategoryNotEmptyException reports the blocking count and reads correctly")
    void categoryNotEmptyWording() {
        // Singular and plural are both checked: "1 books are" would be visible
        // to the user and looks like a bug even though nothing is broken.
        CategoryNotEmptyException one = new CategoryNotEmptyException("Finance", 1);
        assertTrue(one.getMessage().contains("1 book is"), one.getMessage());

        CategoryNotEmptyException many = new CategoryNotEmptyException("Finance", 4);
        assertTrue(many.getMessage().contains("4 books are"), many.getMessage());
        assertEquals(4, many.getBookCount());
        assertEquals("Finance", many.getCategoryName());
    }

    @Test
    @DisplayName("LocationNotEmptyException reports the blocking count and reads correctly")
    void locationNotEmptyWording() {
        LocationNotEmptyException one = new LocationNotEmptyException("Row 2", 1);
        assertTrue(one.getMessage().contains("1 copy is"), one.getMessage());

        LocationNotEmptyException many = new LocationNotEmptyException("Row 2", 3);
        assertTrue(many.getMessage().contains("3 copies are"), many.getMessage());
        assertEquals(3, many.getCopyCount());
        assertEquals("Row 2", many.getLocationName());
    }

    @Test
    @DisplayName("MetadataFetchException names the provider that failed")
    void metadataNamesProvider() {
        MetadataFetchException e =
                new MetadataFetchException("Open Library", "9780140303105", "no record found");
        assertEquals("Open Library", e.getProviderName());
        assertEquals("9780140303105", e.getIsbn());
        assertTrue(e.getMessage().startsWith("Open Library could not supply details"));
    }

    @Test
    @DisplayName("MetadataFetchException gives its reason on its own, for the caller's own wording")
    void metadataReason() {
        assertEquals("no record found",
                new MetadataFetchException("Open Library", "9780140303105", "no record found").getReason());
        assertEquals("could not be reached",
                new MetadataFetchException("Google Books", "9780140303105", new IOException("x")).getReason());
    }

    @Test
    @DisplayName("MetadataFetchException preserves the underlying cause")
    void metadataPreservesCause() {
        // Chaining matters here: the real diagnosis of a failed lookup is
        // usually in the IOException, not in our own message.
        IOException network = new IOException("connect timed out");
        MetadataFetchException e =
                new MetadataFetchException("Google Books", "9780140303105", network);

        assertSame(network, e.getCause());
        assertTrue(e.getMessage().contains("connect timed out"), e.getMessage());
    }

    @Test
    @DisplayName("SyncConflictException carries both timestamps")
    void syncConflictCarriesTimestamps() {
        Instant loaded = Instant.parse("2026-10-12T09:00:00Z");
        Instant modified = Instant.parse("2026-10-12T09:30:00Z");

        SyncConflictException e = new SyncConflictException(loaded, modified);

        assertEquals(loaded, e.getLoadedAt());
        assertEquals(modified, e.getFileModifiedAt());
        // The user must be told nothing was written, or they will assume the
        // save succeeded.
        assertTrue(e.getMessage().contains("Nothing was saved"), e.getMessage());
    }
}
