package com.shelfwise.core.service;

import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Category;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link CollectionData}. */
class CollectionDataTest {

    @Test
    @DisplayName("a snapshot returns what it was given")
    void holdsContents() {
        List<Book> books = new ArrayList<>();
        Book dune = new Book("Dune");
        books.add(dune);
        List<Category> categories = new ArrayList<>();
        categories.add(new Category("Fiction"));
        Instant now = Instant.parse("2026-10-15T10:00:00Z");

        CollectionData data = new CollectionData(books, categories, null, null, null, now);

        assertSame(dune, data.getBooks().get(0));
        assertEquals(1, data.getCategories().size());
        assertEquals(now, data.getSavedAt());
    }

    @Test
    @DisplayName("null lists become empty and a null timestamp means never saved")
    void nullsBecomeEmpty() {
        CollectionData data = new CollectionData(null, null, null, null, null, null);
        assertTrue(data.getBooks().isEmpty());
        assertTrue(data.getLocations().isEmpty());
        assertTrue(data.getSeries().isEmpty());
        assertTrue(data.getWishlists().isEmpty());
        assertNull(data.getSavedAt());
    }

    @Test
    @DisplayName("changing the caller's list afterwards does not change the snapshot")
    void defensiveCopy() {
        // Without the copy, a book added while a save was in progress could
        // appear in some tables and not others.
        List<Book> books = new ArrayList<>();
        books.add(new Book("Dune"));
        CollectionData data = new CollectionData(books, null, null, null, null, null);

        books.add(new Book("Neuromancer"));

        assertEquals(1, data.getBooks().size());
    }

    @Test
    @DisplayName("lists returned by a snapshot are read-only")
    void gettersUnmodifiable() {
        final CollectionData data = new CollectionData(null, null, null, null, null, null);
        assertThrows(UnsupportedOperationException.class, new Executable() {
            @Override
            public void execute() {
                data.getBooks().add(new Book("Sneaky"));
            }
        });
    }
}
