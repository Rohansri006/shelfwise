package com.shelfwise.core.metadata;

import com.shelfwise.core.model.Book;
import com.shelfwise.core.service.BookMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link LookupResult}. */
class LookupResultTest {

    /** @return details as Open Library gives them for 9780143031055, plus a language */
    private static BookMetadata nehru() {
        BookMetadata m = new BookMetadata("Glimpses of World History");
        m.addAuthor("Jawaharlal Nehru");
        m.addAuthor("Indira Gandhi");
        m.setPublisher("Penguin Books India");
        m.setYear(2005);
        m.setPages(1192);
        m.setLanguage("English");
        return m;
    }

    @Test
    @DisplayName("accepting a result carries every form field into a new book, authors in order")
    void toBookCarriesFields() throws Exception {
        LookupResult result = new LookupResult("9780143031055", nehru(),
                Collections.singletonList("Open Library"), new ArrayList<String>(), false, 120);
        List<String> notes = new ArrayList<>();

        Book book = result.toBook(notes);

        assertEquals("Glimpses of World History", book.getTitle());
        assertEquals("9780143031055", book.getIsbn());
        assertEquals("Jawaharlal Nehru", book.getPrimaryAuthor().getName());
        assertEquals("Indira Gandhi", book.getContributors().get(1).getName());
        assertEquals("Penguin Books India", book.getPublisher());
        assertEquals(2005, book.getPublicationYear());
        assertEquals(1192, book.getPages());
        assertEquals("English", book.getLanguage());
        assertEquals(1, book.getCopyCount());
        assertTrue(notes.isEmpty());
    }

    @Test
    @DisplayName("an implausible year from a source is left empty with a note, and the book is still made")
    void badYearBecomesNote() throws Exception {
        // Sources do get things wrong; one bad field must not block the add.
        BookMetadata m = nehru();
        m.setYear(201);
        LookupResult result = new LookupResult("9780143031055", m,
                Collections.singletonList("Open Library"), new ArrayList<String>(), false, 0);
        List<String> notes = new ArrayList<>();

        Book book = result.toBook(notes);

        assertEquals(0, book.getPublicationYear());
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).contains("201"), notes.get(0));
    }

    @Test
    @DisplayName("the description names the base source, the gap-filler and any unavailable source")
    void describe() {
        LookupResult single = new LookupResult("9780143031055", nehru(),
                Collections.singletonList("Open Library"), new ArrayList<String>(), false, 0);
        assertEquals("Found on Open Library", single.describe());

        LookupResult filled = new LookupResult("9780143031055", nehru(),
                Arrays.asList("Open Library", "Google Books"), new ArrayList<String>(), true, 0);
        assertEquals("Found on Open Library, gaps filled from Google Books [from cache]", filled.describe());

        LookupResult partial = new LookupResult("9780143031055", nehru(),
                Collections.singletonList("Open Library"),
                Collections.singletonList("Google Books: rate limit reached"), false, 0);
        assertEquals("Found on Open Library (Google Books: rate limit reached)", partial.describe());
    }
}
