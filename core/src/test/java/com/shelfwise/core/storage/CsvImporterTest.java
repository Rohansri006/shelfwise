package com.shelfwise.core.storage;

import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Category;
import com.shelfwise.core.model.Condition;
import com.shelfwise.core.model.Contributor;
import com.shelfwise.core.model.Copy;
import com.shelfwise.core.model.Format;
import com.shelfwise.core.model.Location;
import com.shelfwise.core.model.ReadingStatus;
import com.shelfwise.core.service.CollectionData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link CsvImporter}, including round trips through {@link CsvExporter}. */
class CsvImporterTest {

    /**
     * @param csv the file's text
     * @return what the importer made of it
     */
    private static ParsedImport read(String csv) throws IOException {
        return new CsvImporter().read(new StringReader(csv));
    }

    @Test
    @DisplayName("an exported file reads back with copies regrouped, trees rebuilt and awkward text intact")
    void roundTrip() throws Exception {
        Category fiction = new Category("Fiction");
        Category scifi = new Category("Science fiction");
        fiction.addChild(scifi);
        Location study = new Location("Study");
        Location shelf = new Location("Shelf A");
        study.addChild(shelf);

        // A title with a comma and quotes, notes across two lines.
        Book book = new Book("9780143031055", "Guards, \"Guards\"");
        book.addContributor(new Contributor("Terry Pratchett"));
        book.addCategoryId(scifi.getId());
        book.setNotes("First line\nsecond line");
        book.setReadingStatus(ReadingStatus.READING);
        book.getCopies().get(0).setLocationId(shelf.getId());
        Copy second = new Copy(Format.HARDCOVER, Condition.LIKE_NEW);
        second.setPricePaid(350.0);
        second.setPurchaseDate(LocalDate.of(2023, 7, 1));
        book.addCopy(second);

        List<Book> books = new ArrayList<>();
        books.add(book);
        List<Category> categories = new ArrayList<>();
        categories.add(fiction);
        categories.add(scifi);
        List<Location> locations = new ArrayList<>();
        locations.add(study);
        locations.add(shelf);
        StringWriter out = new StringWriter();
        new CsvExporter().export(new CollectionData(books, categories, locations,
                null, null, null, "EUR"), out);

        ParsedImport parsed = read(out.toString());

        assertTrue(parsed.getProblems().isEmpty());
        assertEquals("EUR", parsed.getCurrencyCode());
        assertEquals(1, parsed.getBookCount());
        Book back = parsed.getData().getBooks().get(0);
        assertEquals(book.getId(), back.getId());
        assertEquals("Guards, \"Guards\"", back.getTitle());
        assertEquals("First line\nsecond line", back.getNotes());
        assertEquals("Terry Pratchett", back.getPrimaryAuthor().getName());
        assertEquals(ReadingStatus.READING, back.getReadingStatus());
        assertEquals(2, back.getCopyCount());
        assertEquals(Format.HARDCOVER, back.getCopies().get(1).getFormat());
        assertEquals(350.0, back.getCopies().get(1).getPricePaid());
        assertEquals(LocalDate.of(2023, 7, 1), back.getCopies().get(1).getPurchaseDate());

        // The trees come back as fresh nodes with the same paths.
        assertEquals(2, parsed.getData().getCategories().size());
        Category leaf = parsed.getData().getCategories().get(1);
        assertEquals("Fiction > Science fiction", leaf.getPath());
        assertEquals(leaf.getId(), back.getCategoryIds().get(0));
        Location place = parsed.getData().getLocations().get(1);
        assertEquals("Study > Shelf A", place.getPath());
        assertEquals(place.getId(), back.getCopies().get(0).getLocationId());
    }

    @Test
    @DisplayName("bad rows are reported with their row number, and the good rows are still read")
    void badRowsReported() throws Exception {
        String csv = "id,title,isbn,year,purchase_date\n"
                + "a,Good one,,,\n"
                + "b,Bad year,,nineteen,\n"
                + "c,Bad ISBN,9780143031056,,\n"
                + "d,Bad date,,,yesterday\n"
                + "e,,,,\n"
                + "f,Good two,,1965,2024-01-31\n";

        ParsedImport parsed = read(csv);

        assertEquals(2, parsed.getBookCount());
        assertEquals("Good one", parsed.getData().getBooks().get(0).getTitle());
        assertEquals(1965, parsed.getData().getBooks().get(1).getPublicationYear());
        List<String> problems = parsed.getProblems();
        assertEquals(4, problems.size());
        assertTrue(problems.get(0).startsWith("Row 3 (\"Bad year\")"));
        assertTrue(problems.get(1).startsWith("Row 4 (\"Bad ISBN\")"));
        assertTrue(problems.get(2).startsWith("Row 5 (\"Bad date\")"));
        assertTrue(problems.get(3).startsWith("Row 6:"));
        // Readable sentences, not Java's own parser messages.
        assertEquals("Row 3 (\"Bad year\"): \"nineteen\" is not a whole number.", problems.get(0));
        assertEquals("Row 5 (\"Bad date\"): \"yesterday\" is not a date in the form 2024-03-09.",
                problems.get(2));
    }

    @Test
    @DisplayName("rows without an id group by ISBN in either form; rows with neither stay separate")
    void groupingWithoutIds() throws Exception {
        // E1: nothing else in a row identifies a book reliably, so two
        // id-less, ISBN-less rows titled the same are two books.
        String csv = "title,isbn,format\n"
                + "Glimpses of World History,9780143031055,Paperback\n"
                + "Glimpses of World History,0-14-303105-8,HARDCOVER\n"
                + "Untitled notebook,,\n"
                + "Untitled notebook,,\n";

        ParsedImport parsed = read(csv);

        assertEquals(3, parsed.getBookCount());
        Book nehru = parsed.getData().getBooks().get(0);
        assertEquals(2, nehru.getCopyCount());
        assertEquals(Format.HARDCOVER, nehru.getCopies().get(1).getFormat());
    }

    @Test
    @DisplayName("a plain price header names no currency, so no mismatch is reported")
    void plainPriceHeader() throws Exception {
        ParsedImport plain = read("title,price_paid\nDune,499\n");
        assertNull(plain.getCurrencyCode());
        assertFalse(plain.currencyDiffersFrom("INR"));

        ParsedImport euros = read("title,price_paid (eur)\nDune,9.99\n");
        assertEquals("EUR", euros.getCurrencyCode());
        assertTrue(euros.currencyDiffersFrom("INR"));
        assertFalse(euros.currencyDiffersFrom("eur"));
    }

    @Test
    @DisplayName("a hand-made sheet may reorder or omit columns and use Windows line endings")
    void handMadeSheet() throws Exception {
        String csv = "Condition,Title,Authors\r\n"
                + "like new,Godaan,Premchand;Translator Unknown\r\n"
                + "\r\n"
                + "Poor,Anathem,\r\n";

        ParsedImport parsed = read(csv);

        assertTrue(parsed.getProblems().isEmpty());
        assertEquals(2, parsed.getBookCount());
        Book godaan = parsed.getData().getBooks().get(0);
        assertEquals(Condition.LIKE_NEW, godaan.getCopies().get(0).getCondition());
        assertEquals(2, godaan.getContributors().size());
        assertEquals("Anathem", parsed.getData().getBooks().get(1).getTitle());
    }

    @Test
    @DisplayName("a file with no title column, or no content at all, is refused")
    void unrecognisableFile() {
        assertThrows(IOException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                read("name,author\nDune,Herbert\n");
            }
        });
        assertThrows(IOException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                read("");
            }
        });
    }
}
