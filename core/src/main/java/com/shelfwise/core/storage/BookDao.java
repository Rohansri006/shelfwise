package com.shelfwise.core.storage;

import com.shelfwise.core.exception.InvalidISBNException;
import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Condition;
import com.shelfwise.core.model.Contributor;
import com.shelfwise.core.model.ContributorRole;
import com.shelfwise.core.model.Copy;
import com.shelfwise.core.model.Format;
import com.shelfwise.core.model.ReadingSession;
import com.shelfwise.core.model.ReadingStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads and writes books and everything they own, across eight tables:
 * {@code item}, {@code book}, {@code copy}, {@code contributor},
 * {@code book_contributor}, {@code item_category}, {@code custom_field} and
 * {@code reading_session}.
 * <p>
 * <strong>Inheritance in tables.</strong> {@link Book} extends
 * {@code CollectionItem}; in the database, {@code item} holds the fields
 * every item has and {@code book} holds the book-only ones, sharing one
 * primary key ("class-table inheritance"). Saving a book writes a row to
 * each; loading joins them back.
 * <p>
 * <strong>Contributors are shared in the database but not in Java.</strong>
 * Each {@code Book} keeps its own {@link Contributor} objects, while the
 * {@code contributor} table holds each (name, role) pair once and books link
 * to it. Saving looks each pair up in a {@code HashMap} so it is inserted
 * only the first time it appears.
 * <p>
 * <strong>Loading is one query per table, not one per book.</strong> Every
 * copy for every book comes back in a single query and is handed to its
 * book through a {@code HashMap} keyed by book id. A thousand books then
 * take seven queries rather than seven thousand.
 */
public class BookDao {

    /**
     * Writes every book with all its parts.
     *
     * @param connection an open connection
     * @param books      every book, trashed ones included
     * @throws SQLException if an insert fails
     */
    public void insertAll(Connection connection, List<Book> books) throws SQLException {
        String itemSql = "INSERT INTO item (id, item_type, title, notes, rating, date_added, deleted) "
                + "VALUES (?, 'BOOK', ?, ?, ?, ?, ?)";
        String bookSql = "INSERT INTO book (item_id, subtitle, isbn, publisher, publication_year, "
                + "language, pages, series_id, series_position, reading_status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        String copySql = "INSERT INTO copy (id, book_item_id, location_id, format, condition, "
                + "purchase_date, price_paid) VALUES (?, ?, ?, ?, ?, ?, ?)";
        String contributorSql = "INSERT INTO contributor (id, name, role) VALUES (?, ?, ?)";
        String creditSql = "INSERT INTO book_contributor (book_item_id, contributor_id, position) "
                + "VALUES (?, ?, ?)";
        String categorySql = "INSERT INTO item_category (item_id, category_id) VALUES (?, ?)";
        String fieldSql = "INSERT INTO custom_field (item_id, field_key, field_value, position) "
                + "VALUES (?, ?, ?, ?)";
        String sessionSql = "INSERT INTO reading_session (id, book_item_id, start_date, end_date, "
                + "pages_read, percent_complete) VALUES (?, ?, ?, ?, ?, ?)";

        // (name, role) -> contributor row id, so each pair is inserted once.
        Map<String, String> contributorIds = new HashMap<>();

        try (PreparedStatement item = connection.prepareStatement(itemSql);
             PreparedStatement book = connection.prepareStatement(bookSql);
             PreparedStatement copy = connection.prepareStatement(copySql);
             PreparedStatement contributor = connection.prepareStatement(contributorSql);
             PreparedStatement credit = connection.prepareStatement(creditSql);
             PreparedStatement category = connection.prepareStatement(categorySql);
             PreparedStatement field = connection.prepareStatement(fieldSql);
             PreparedStatement session = connection.prepareStatement(sessionSql)) {

            for (int i = 0; i < books.size(); i++) {
                Book b = books.get(i);

                item.setString(1, b.getId());
                item.setString(2, b.getTitle());
                item.setString(3, b.getNotes());
                item.setInt(4, b.getRating());
                item.setString(5, toText(b.getDateAdded()));
                item.setInt(6, b.isDeleted() ? 1 : 0);
                item.executeUpdate();

                book.setString(1, b.getId());
                book.setString(2, b.getSubtitle());
                book.setString(3, b.getIsbn());
                book.setString(4, b.getPublisher());
                book.setInt(5, b.getPublicationYear());
                book.setString(6, b.getLanguage());
                book.setInt(7, b.getPages());
                book.setString(8, b.getSeriesId());
                book.setInt(9, b.getSeriesPosition());
                book.setString(10, b.getReadingStatus().name());
                book.executeUpdate();

                List<Copy> copies = b.getCopies();
                for (int j = 0; j < copies.size(); j++) {
                    Copy c = copies.get(j);
                    copy.setString(1, c.getId());
                    copy.setString(2, b.getId());
                    copy.setString(3, c.getLocationId());
                    copy.setString(4, c.getFormat().name());
                    copy.setString(5, c.getCondition().name());
                    copy.setString(6, toText(c.getPurchaseDate()));
                    copy.setDouble(7, c.getPricePaid());
                    copy.executeUpdate();
                }

                List<Contributor> credits = b.getContributors();
                for (int j = 0; j < credits.size(); j++) {
                    Contributor person = credits.get(j);
                    String key = person.getName() + "|" + person.getRole().name();
                    String contributorId = contributorIds.get(key);
                    if (contributorId == null) {
                        contributorId = UUID.randomUUID().toString();
                        contributor.setString(1, contributorId);
                        contributor.setString(2, person.getName());
                        contributor.setString(3, person.getRole().name());
                        contributor.executeUpdate();
                        contributorIds.put(key, contributorId);
                    }
                    credit.setString(1, b.getId());
                    credit.setString(2, contributorId);
                    credit.setInt(3, j);
                    credit.executeUpdate();
                }

                List<String> categoryIds = b.getCategoryIds();
                for (int j = 0; j < categoryIds.size(); j++) {
                    category.setString(1, b.getId());
                    category.setString(2, categoryIds.get(j));
                    category.executeUpdate();
                }

                // A LinkedHashMap's key list is in insertion order, which
                // becomes the position column.
                List<String> keys = new ArrayList<>(b.getCustomFields().keySet());
                for (int j = 0; j < keys.size(); j++) {
                    field.setString(1, b.getId());
                    field.setString(2, keys.get(j));
                    field.setString(3, b.getCustomField(keys.get(j)));
                    field.setInt(4, j);
                    field.executeUpdate();
                }

                List<ReadingSession> sessions = b.getReadingSessions();
                for (int j = 0; j < sessions.size(); j++) {
                    ReadingSession s = sessions.get(j);
                    session.setString(1, s.getId());
                    session.setString(2, b.getId());
                    session.setString(3, toText(s.getStartDate()));
                    session.setString(4, toText(s.getEndDate()));
                    session.setInt(5, s.getPagesRead());
                    session.setInt(6, s.getPercentComplete());
                    session.executeUpdate();
                }
            }
        }
    }

    /**
     * Deletes every book. Deleting from {@code item} cascades to
     * {@code book} and every table hanging off it; contributors are then
     * removed separately, as nothing cascades to them.
     *
     * @param connection an open connection
     * @throws SQLException if a delete fails
     */
    public void deleteAll(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM item");
            statement.executeUpdate("DELETE FROM contributor");
        }
    }

    /**
     * Reads every book with all its parts.
     * <p>
     * <strong>Order matters for reading status.</strong> Adding a reading
     * session updates a book's status automatically, so sessions are added
     * first and the stored status is set last. In the other order a book the
     * user marked Abandoned, with one finished read in its history, would
     * come back as Finished.
     *
     * @param connection an open connection
     * @return every book, trashed ones included
     * @throws SQLException if a query fails or stored data breaks a model
     *                      rule (an invalid ISBN, a book with no copies)
     */
    public List<Book> loadAll(Connection connection) throws SQLException {
        List<Book> books = new ArrayList<>();
        Map<String, Book> byId = new HashMap<>();
        Map<String, ReadingStatus> statusOf = new HashMap<>();

        String bookSql = "SELECT i.id, i.title, i.notes, i.rating, i.date_added, i.deleted, "
                + "b.subtitle, b.isbn, b.publisher, b.publication_year, b.language, b.pages, "
                + "b.series_id, b.series_position, b.reading_status "
                + "FROM item i JOIN book b ON b.item_id = i.id ORDER BY i.rowid";
        try (PreparedStatement query = connection.prepareStatement(bookSql);
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                Book book = new Book(rows.getString("id"), rows.getString("title"),
                        toDate(rows.getString("date_added")));
                book.setNotes(rows.getString("notes"));
                book.setRating(rows.getInt("rating"));
                book.setDeleted(rows.getInt("deleted") == 1);
                book.setSubtitle(rows.getString("subtitle"));
                try {
                    book.setIsbn(rows.getString("isbn"));
                } catch (InvalidISBNException e) {
                    throw new SQLException("Stored ISBN for \"" + book.getTitle()
                            + "\" is invalid: " + e.getMessage(), e);
                }
                book.setPublisher(rows.getString("publisher"));
                book.setPublicationYear(rows.getInt("publication_year"));
                book.setLanguage(rows.getString("language"));
                book.setPages(rows.getInt("pages"));
                // Series id before position: a position without a series is
                // refused by validate().
                book.setSeriesId(rows.getString("series_id"));
                book.setSeriesPosition(rows.getInt("series_position"));
                statusOf.put(book.getId(), ReadingStatus.valueOf(rows.getString("reading_status")));
                books.add(book);
                byId.put(book.getId(), book);
            }
        }

        String copySql = "SELECT id, book_item_id, location_id, format, condition, purchase_date, "
                + "price_paid FROM copy ORDER BY rowid";
        try (PreparedStatement query = connection.prepareStatement(copySql);
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                Copy copy = new Copy(rows.getString("id"), Format.valueOf(rows.getString("format")),
                        Condition.valueOf(rows.getString("condition")));
                copy.setLocationId(rows.getString("location_id"));
                copy.setPurchaseDate(toDate(rows.getString("purchase_date")));
                copy.setPricePaid(rows.getDouble("price_paid"));
                byId.get(rows.getString("book_item_id")).addCopy(copy);
            }
        }

        String creditSql = "SELECT bc.book_item_id, c.name, c.role FROM book_contributor bc "
                + "JOIN contributor c ON c.id = bc.contributor_id "
                + "ORDER BY bc.book_item_id, bc.position";
        try (PreparedStatement query = connection.prepareStatement(creditSql);
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                byId.get(rows.getString("book_item_id")).addContributor(new Contributor(
                        rows.getString("name"), ContributorRole.valueOf(rows.getString("role"))));
            }
        }

        String categorySql = "SELECT item_id, category_id FROM item_category ORDER BY rowid";
        try (PreparedStatement query = connection.prepareStatement(categorySql);
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                byId.get(rows.getString("item_id")).addCategoryId(rows.getString("category_id"));
            }
        }

        String fieldSql = "SELECT item_id, field_key, field_value FROM custom_field "
                + "ORDER BY item_id, position";
        try (PreparedStatement query = connection.prepareStatement(fieldSql);
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                byId.get(rows.getString("item_id")).setCustomField(
                        rows.getString("field_key"), rows.getString("field_value"));
            }
        }

        String sessionSql = "SELECT id, book_item_id, start_date, end_date, pages_read, "
                + "percent_complete FROM reading_session ORDER BY rowid";
        try (PreparedStatement query = connection.prepareStatement(sessionSql);
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                ReadingSession session = new ReadingSession(rows.getString("id"),
                        toDate(rows.getString("start_date")));
                session.setEndDate(toDate(rows.getString("end_date")));
                session.setPagesRead(rows.getInt("pages_read"));
                session.setPercentComplete(rows.getInt("percent_complete"));
                byId.get(rows.getString("book_item_id")).addReadingSession(session);
            }
        }

        for (int i = 0; i < books.size(); i++) {
            Book book = books.get(i);
            book.setReadingStatus(statusOf.get(book.getId()));
            if (book.getCopyCount() == 0) {
                throw new SQLException("Stored book \"" + book.getTitle() + "\" has no copies.");
            }
        }
        return books;
    }

    /**
     * Dates are stored as ISO-8601 text ("2026-10-15"), which sorts and
     * compares correctly as text and moves to MySQL's DATE unchanged.
     *
     * @param date the date, or null
     * @return its ISO text, or null
     */
    private static String toText(LocalDate date) {
        return date == null ? null : date.toString();
    }

    /**
     * @param text ISO text, or null
     * @return the date, or null
     */
    private static LocalDate toDate(String text) {
        return text == null ? null : LocalDate.parse(text);
    }
}
