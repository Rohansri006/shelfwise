package com.shelfwise.core.storage;

import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Category;
import com.shelfwise.core.model.Condition;
import com.shelfwise.core.model.Contributor;
import com.shelfwise.core.model.ContributorRole;
import com.shelfwise.core.model.Copy;
import com.shelfwise.core.model.Format;
import com.shelfwise.core.model.Location;
import com.shelfwise.core.service.CollectionData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link CsvExporter} and the quoting rule in {@link CsvFormat}. */
class CsvExporterTest {

    @TempDir
    Path folder;

    /**
     * Exports to a string, the quickest way to look at the output.
     *
     * @param data what to export
     * @return the CSV text, split into lines
     */
    private static String[] exportLines(CollectionData data) throws Exception {
        StringWriter out = new StringWriter();
        new CsvExporter().export(data, out);
        return out.toString().split("\\R");
    }

    /**
     * @param books the books to put in a snapshot
     * @return a snapshot with no trees, priced in INR
     */
    private static CollectionData dataWith(List<Book> books) {
        return new CollectionData(books, null, null, null, null, null);
    }

    @Test
    @DisplayName("the header starts with id and names the currency in the price column")
    void header() throws Exception {
        CollectionData data = new CollectionData(null, null, null, null, null, null, "EUR");
        String header = exportLines(data)[0];

        assertTrue(header.startsWith("id,title,subtitle,isbn,authors,"));
        assertTrue(header.contains(",price_paid (EUR),"));
        assertTrue(header.endsWith(",notes"));
    }

    @Test
    @DisplayName("a book with two copies becomes two rows, and trashed books are left out")
    void oneRowPerCopy() throws Exception {
        Book dune = new Book("Dune");
        dune.addCopy(new Copy(Format.HARDCOVER, Condition.LIKE_NEW));
        Book binned = new Book("Binned");
        binned.setDeleted(true);
        List<Book> books = new ArrayList<>();
        books.add(dune);
        books.add(binned);

        String[] lines = exportLines(dataWith(books));

        assertEquals(3, lines.length);
        assertTrue(lines[1].startsWith(dune.getId() + ",Dune,"));
        assertTrue(lines[2].startsWith(dune.getId() + ",Dune,"));
        // Labels, never enum names like LIKE_NEW.
        assertTrue(lines[2].contains(",Hardcover,Like new,"));
    }

    @Test
    @DisplayName("cells with commas, quotes or line breaks are quoted, with inner quotes doubled")
    void quoting() {
        assertEquals("Plain", CsvFormat.quote("Plain"));
        assertEquals("\"Guards, Guards\"", CsvFormat.quote("Guards, Guards"));
        assertEquals("\"He said \"\"hi\"\"\"", CsvFormat.quote("He said \"hi\""));
        assertEquals("\"two\nlines\"", CsvFormat.quote("two\nlines"));
        assertEquals("", CsvFormat.quote(null));
    }

    @Test
    @DisplayName("authors, category paths, location path and recorded values fill their cells; zeros stay blank")
    void cellContents() throws Exception {
        Category nonFiction = new Category("Non-fiction");
        Category history = new Category("History");
        nonFiction.addChild(history);
        Location room = new Location("Study");
        Location shelf = new Location("Shelf A");
        room.addChild(shelf);

        Book book = new Book("9780143031055", "Glimpses of World History");
        book.addContributor(new Contributor("Jawaharlal Nehru"));
        book.addContributor(new Contributor("Someone Else", ContributorRole.EDITOR));
        book.addCategoryId(history.getId());
        book.setPages(992);
        Copy copy = book.getCopies().get(0);
        copy.setLocationId(shelf.getId());
        copy.setPricePaid(499.5);
        copy.setPurchaseDate(LocalDate.of(2024, 3, 9));

        List<Book> books = new ArrayList<>();
        books.add(book);
        List<Category> categories = new ArrayList<>();
        categories.add(nonFiction);
        categories.add(history);
        List<Location> locations = new ArrayList<>();
        locations.add(room);
        locations.add(shelf);
        CollectionData data = new CollectionData(books, categories, locations, null, null, null);

        String row = exportLines(data)[1];

        // Only authors: the editor is a contributor but not an author.
        assertTrue(row.contains(",Jawaharlal Nehru,"));
        assertTrue(row.contains(",Non-fiction > History,Study > Shelf A,"));
        assertTrue(row.contains(",2024-03-09,499.5,"));
        // Year unknown (0): an empty cell between publisher and pages.
        assertTrue(row.contains(",,,992,"));
    }

    @Test
    @DisplayName("exporting to a file writes UTF-8, so non-English titles survive")
    void fileIsUtf8() throws Exception {
        List<Book> books = new ArrayList<>();
        books.add(new Book("गोदान"));
        Path file = folder.resolve("collection.csv");

        new CsvExporter().export(dataWith(books), file);

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).contains(",गोदान,"));
    }
}
