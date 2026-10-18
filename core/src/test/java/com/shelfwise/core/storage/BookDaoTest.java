package com.shelfwise.core.storage;

import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Category;
import com.shelfwise.core.model.Condition;
import com.shelfwise.core.model.Contributor;
import com.shelfwise.core.model.ContributorRole;
import com.shelfwise.core.model.Copy;
import com.shelfwise.core.model.Format;
import com.shelfwise.core.model.Location;
import com.shelfwise.core.model.ReadingSession;
import com.shelfwise.core.model.ReadingStatus;
import com.shelfwise.core.model.Series;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round-trip tests for {@link BookDao} against a real SQLite file. */
class BookDaoTest {

    @TempDir
    Path folder;

    private Database db;
    private final BookDao dao = new BookDao();

    /** Rows the books' foreign keys point at. */
    private Category scifi;
    private Location shelf;
    private Series dunes;

    @BeforeEach
    void createDatabase() throws Exception {
        db = new Database(folder.resolve("shelfwise.db"));
        db.initialiseSchema();

        scifi = new Category("Science fiction");
        shelf = new Location("Study shelf");
        dunes = new Series("Dune Chronicles", 6);
        List<Category> categories = new ArrayList<>();
        categories.add(scifi);
        List<Location> locations = new ArrayList<>();
        locations.add(shelf);
        List<Series> series = new ArrayList<>();
        series.add(dunes);
        try (Connection c = db.openConnection()) {
            new CategoryDao().insertAll(c, categories);
            new LocationDao().insertAll(c, locations);
            new SeriesDao().insertAll(c, series);
        }
    }

    /**
     * @param books the books to save
     * @return the books read back from a fresh query
     */
    private List<Book> roundTrip(List<Book> books) throws Exception {
        try (Connection c = db.openConnection()) {
            dao.insertAll(c, books);
            return dao.loadAll(c);
        }
    }

    /**
     * @param sql a COUNT(*) query
     * @return its result
     */
    private int count(String sql) throws Exception {
        try (Connection c = db.openConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    @DisplayName("every field of a book and its copies survives a save and load")
    void fullRoundTrip() throws Exception {
        Book dune = new Book("9780143031055", "Dune");
        dune.setSubtitle("Deluxe Edition");
        dune.setPublisher("Penguin");
        dune.setPublicationYear(2005);
        dune.setLanguage("English");
        dune.setPages(896);
        dune.setNotes("Gift from Mum");
        dune.setRating(5);
        dune.setSeriesId(dunes.getId());
        dune.setSeriesPosition(1);
        dune.addCategoryId(scifi.getId());
        Copy first = dune.getCopies().get(0);
        first.setFormat(Format.HARDCOVER);
        first.setCondition(Condition.LIKE_NEW);
        first.setLocationId(shelf.getId());
        first.setPurchaseDate(LocalDate.of(2024, 3, 9));
        first.setPricePaid(799.0);
        dune.addCopy(new Copy(Format.EBOOK, Condition.NEW));

        List<Book> books = new ArrayList<>();
        books.add(dune);
        Book back = roundTrip(books).get(0);

        assertEquals(dune.getId(), back.getId());
        assertEquals(dune.getDateAdded(), back.getDateAdded());
        assertEquals("Dune", back.getTitle());
        assertEquals("Deluxe Edition", back.getSubtitle());
        assertEquals("9780143031055", back.getIsbn());
        assertEquals("Penguin", back.getPublisher());
        assertEquals(2005, back.getPublicationYear());
        assertEquals("English", back.getLanguage());
        assertEquals(896, back.getPages());
        assertEquals("Gift from Mum", back.getNotes());
        assertEquals(5, back.getRating());
        assertEquals(dunes.getId(), back.getSeriesId());
        assertEquals(1, back.getSeriesPosition());
        assertEquals(scifi.getId(), back.getCategoryIds().get(0));

        assertEquals(2, back.getCopyCount());
        Copy backFirst = back.getCopies().get(0);
        assertEquals(first.getId(), backFirst.getId());
        assertEquals(Format.HARDCOVER, backFirst.getFormat());
        assertEquals(Condition.LIKE_NEW, backFirst.getCondition());
        assertEquals(shelf.getId(), backFirst.getLocationId());
        assertEquals(LocalDate.of(2024, 3, 9), backFirst.getPurchaseDate());
        assertEquals(799.0, backFirst.getPricePaid());
        assertTrue(back.getCopies().get(1).isUnshelved());
        assertNull(back.getCopies().get(1).getPurchaseDate());
    }

    @Test
    @DisplayName("authors come back in credit order, so the primary author is stable")
    void authorOrderPreserved() throws Exception {
        // Credited second-alphabetically first: only the position column
        // can bring this order back.
        Book book = new Book("Dune: House Atreides");
        book.addContributor(new Contributor("Kevin J. Anderson"));
        book.addContributor(new Contributor("Brian Herbert"));
        book.addContributor(new Contributor("Some Translator", ContributorRole.TRANSLATOR));
        List<Book> books = new ArrayList<>();
        books.add(book);

        Book back = roundTrip(books).get(0);

        assertEquals("Kevin J. Anderson", back.getPrimaryAuthor().getName());
        assertEquals("Brian Herbert", back.getContributors().get(1).getName());
        assertEquals(ContributorRole.TRANSLATOR, back.getContributors().get(2).getRole());
    }

    @Test
    @DisplayName("a person credited on two books is stored once")
    void contributorsShared() throws Exception {
        Book dune = new Book("Dune");
        dune.addContributor(new Contributor("Frank Herbert"));
        Book messiah = new Book("Dune Messiah");
        messiah.addContributor(new Contributor("Frank Herbert"));
        List<Book> books = new ArrayList<>();
        books.add(dune);
        books.add(messiah);

        List<Book> back = roundTrip(books);

        assertEquals(1, count("SELECT COUNT(*) FROM contributor"));
        assertEquals(2, count("SELECT COUNT(*) FROM book_contributor"));
        assertEquals("Frank Herbert", back.get(1).getPrimaryAuthor().getName());
    }

    @Test
    @DisplayName("custom fields come back in the order they were added")
    void customFieldOrder() throws Exception {
        Book book = new Book("Dune");
        book.setCustomField("Signed", "yes");
        book.setCustomField("Acquired at", "Daryaganj market");
        List<Book> books = new ArrayList<>();
        books.add(book);

        List<String> keys = new ArrayList<>(roundTrip(books).get(0).getCustomFields().keySet());

        assertEquals("Signed", keys.get(0));
        assertEquals("Acquired at", keys.get(1));
    }

    @Test
    @DisplayName("one field set under two spellings saves as one field")
    void customFieldCaseSaves() throws Exception {
        // Before D30 the model kept "Signed" and "signed" apart while the
        // NOCASE primary key treated them as one, so this save failed.
        Book book = new Book("Dune");
        book.setCustomField("Signed", "yes");
        book.setCustomField("signed", "by the author");
        List<Book> books = new ArrayList<>();
        books.add(book);

        Book back = roundTrip(books).get(0);

        assertEquals(1, back.getCustomFields().size());
        assertEquals("by the author", back.getCustomField("Signed"));
    }

    @Test
    @DisplayName("a stored status survives even when the sessions would imply another")
    void storedStatusWinsOverSessions() throws Exception {
        // Read once, then abandoned on a re-read. If sessions were added
        // after the status, the finished session would flip it to FINISHED.
        Book book = new Book("Dune");
        ReadingSession firstRead = new ReadingSession(LocalDate.of(2023, 1, 1));
        firstRead.setEndDate(LocalDate.of(2023, 2, 1));
        book.addReadingSession(firstRead);
        book.setReadingStatus(ReadingStatus.ABANDONED);
        List<Book> books = new ArrayList<>();
        books.add(book);

        Book back = roundTrip(books).get(0);

        assertEquals(ReadingStatus.ABANDONED, back.getReadingStatus());
        assertEquals(1, back.getTimesRead());
        assertEquals(LocalDate.of(2023, 2, 1), back.getReadingSessions().get(0).getEndDate());
    }

    @Test
    @DisplayName("a trashed book comes back still in the trash")
    void trashFlagPreserved() throws Exception {
        Book book = new Book("Dune");
        book.setDeleted(true);
        List<Book> books = new ArrayList<>();
        books.add(book);

        assertTrue(roundTrip(books).get(0).isDeleted());
    }

    @Test
    @DisplayName("deleteAll removes books, their parts and the contributors")
    void deleteAllCascades() throws Exception {
        Book book = new Book("Dune");
        book.addContributor(new Contributor("Frank Herbert"));
        book.addCategoryId(scifi.getId());
        book.setCustomField("Signed", "yes");
        book.addReadingSession(new ReadingSession(LocalDate.of(2023, 1, 1)));
        List<Book> books = new ArrayList<>();
        books.add(book);

        try (Connection c = db.openConnection()) {
            dao.insertAll(c, books);
            dao.deleteAll(c);
        }

        String[] tables = {"item", "book", "copy", "contributor", "book_contributor",
                "item_category", "custom_field", "reading_session"};
        for (int i = 0; i < tables.length; i++) {
            assertEquals(0, count("SELECT COUNT(*) FROM " + tables[i]), tables[i]);
        }
    }
}
