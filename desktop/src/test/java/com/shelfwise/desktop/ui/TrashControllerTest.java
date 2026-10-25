package com.shelfwise.desktop.ui;

import com.shelfwise.core.exception.InvalidISBNException;
import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Contributor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the Trash page's search (E77) and its confirm wording (E78).
 * Both are static methods on plain values, so no JavaFX is started.
 */
class TrashControllerTest {

    /** Glimpses of World History by Jawaharlal Nehru: a real, checksum-valid ISBN. */
    private static Book glimpses() throws InvalidISBNException {
        Book book = new Book("9780143031055", "Glimpses of World History");
        book.addContributor(new Contributor("Jawaharlal Nehru"));
        return book;
    }

    @Test
    @DisplayName("search matches the title, ignoring case")
    void matchesTitle() throws InvalidISBNException {
        assertTrue(TrashController.matches(glimpses(), "world HISTORY"));
    }

    @Test
    @DisplayName("search matches a contributor's name")
    void matchesAuthor() throws InvalidISBNException {
        assertTrue(TrashController.matches(glimpses(), "nehru"));
    }

    @Test
    @DisplayName("search matches the ISBN even when typed with hyphens")
    void matchesHyphenatedIsbn() throws InvalidISBNException {
        // ISBNs are stored without hyphens, so the query must lose them too.
        assertTrue(TrashController.matches(glimpses(), "978-0-14-303105"));
    }

    @Test
    @DisplayName("search leaves out a book that matches nothing")
    void noMatch() throws InvalidISBNException {
        assertFalse(TrashController.matches(glimpses(), "Solaris"));
    }

    @Test
    @DisplayName("an empty search matches every book")
    void emptyMatchesAll() throws InvalidISBNException {
        assertTrue(TrashController.matches(glimpses(), ""));
    }

    @Test
    @DisplayName("deleting one book names it; deleting several counts them")
    void deleteMessageSingularAndPlural() throws InvalidISBNException {
        List<Book> one = new ArrayList<>();
        one.add(glimpses());
        assertEquals("Delete “Glimpses of World History” for good? This can't be undone.",
                TrashController.deleteMessage(one));
        one.add(new Book("Solaris"));
        assertEquals("Delete 2 books for good? This can't be undone.", TrashController.deleteMessage(one));
    }

    @Test
    @DisplayName("Empty Trash says \"the 1 book\" for one and \"all N books\" for more")
    void emptyMessageSingularAndPlural() {
        assertEquals("Delete the 1 book in the Trash for good? This can't be undone.",
                TrashController.emptyMessage(1));
        assertEquals("Delete all 5 books in the Trash for good? This can't be undone.",
                TrashController.emptyMessage(5));
    }
}
