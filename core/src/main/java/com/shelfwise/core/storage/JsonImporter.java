package com.shelfwise.core.storage;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.shelfwise.core.exception.InvalidISBNException;
import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Category;
import com.shelfwise.core.model.Condition;
import com.shelfwise.core.model.Contributor;
import com.shelfwise.core.model.ContributorRole;
import com.shelfwise.core.model.Copy;
import com.shelfwise.core.model.Format;
import com.shelfwise.core.model.Location;
import com.shelfwise.core.model.Priority;
import com.shelfwise.core.model.ReadingSession;
import com.shelfwise.core.model.ReadingStatus;
import com.shelfwise.core.model.Series;
import com.shelfwise.core.model.Wishlist;
import com.shelfwise.core.model.WishlistEntry;
import com.shelfwise.core.service.CollectionData;

import java.io.BufferedReader;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a file written by {@link JsonExporter} - including the sample
 * collection bundled with the application (D20).
 * <p>
 * Gson fills a {@link JsonCollection} by reflection, and every object is
 * then rebuilt through the model's own constructors and setters, so a file
 * edited by hand is held to the same rules as anything typed into the
 * application. Ids are kept, which is what lets a re-import recognise
 * books it has seen before (ADR 0004).
 * <p>
 * As with CSV, one broken book is reported and skipped rather than
 * failing the whole file. A file that is not JSON at all, or comes from a
 * newer version of ShelfWise, is refused.
 */
public class JsonImporter extends Importer {

    private final Gson gson = new Gson();

    /**
     * @param in an open, buffered reader
     * @return everything the file holds
     * @throws IOException if reading fails or the text is not a ShelfWise
     *                     JSON file this version understands
     */
    @Override
    protected ParsedImport parse(BufferedReader in) throws IOException {
        JsonCollection json;
        try {
            json = gson.fromJson(in, JsonCollection.class);
        } catch (JsonParseException e) {
            // Gson's exception is unchecked; translated so the caller deals
            // with one checked type for every way reading can fail.
            throw new IOException("This is not a ShelfWise JSON file: " + e.getMessage(), e);
        }
        if (json == null) {
            throw new IOException("The JSON file is empty.");
        }
        if (json.formatVersion > JsonCollection.CURRENT_VERSION) {
            throw new IOException("This file was written by a newer version of ShelfWise"
                    + " (format " + json.formatVersion + ").");
        }

        List<String> problems = new ArrayList<>();
        List<Category> categories = categoryTree(json.categories, problems);
        List<Location> locations = locationTree(json.locations, problems);
        List<Series> series = new ArrayList<>();
        for (int i = 0; i < json.series.size(); i++) {
            JsonCollection.SeriesJson s = json.series.get(i);
            try {
                series.add(new Series(s.id, s.name, s.totalBooks));
            } catch (IllegalArgumentException e) {
                problems.add("Series \"" + s.name + "\": " + e.getMessage());
            }
        }
        List<Wishlist> wishlists = new ArrayList<>();
        for (int i = 0; i < json.wishlists.size(); i++) {
            Wishlist wishlist = toWishlist(json.wishlists.get(i), problems);
            if (wishlist != null) {
                wishlists.add(wishlist);
            }
        }
        List<Book> books = new ArrayList<>();
        for (int i = 0; i < json.books.size(); i++) {
            JsonCollection.BookJson b = json.books.get(i);
            try {
                books.add(toBook(b));
            } catch (InvalidISBNException | IllegalArgumentException e) {
                problems.add("Book \"" + b.title + "\": " + e.getMessage());
            }
        }

        String currency = json.currency == null ? CollectionData.DEFAULT_CURRENCY : json.currency;
        CollectionData data;
        try {
            data = new CollectionData(books, categories, locations, series, wishlists, null, currency);
        } catch (IllegalArgumentException e) {
            throw new IOException("The file's currency is not valid: " + e.getMessage(), e);
        }
        return new ParsedImport(data, data.getCurrencyCode(), problems);
    }

    /**
     * Rebuilds the category tree: every node first, then each one attached
     * to its parent by id. Two passes, because a child may appear in the
     * file before its parent.
     *
     * @param nodes    the flat nodes
     * @param problems where to report a node that cannot be placed
     * @return every node, linked
     */
    private static List<Category> categoryTree(List<JsonCollection.NodeJson> nodes,
                                               List<String> problems) {
        Map<String, Category> byId = new HashMap<>();
        List<Category> result = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            JsonCollection.NodeJson n = nodes.get(i);
            if (n.id == null || n.name == null || n.name.trim().isEmpty()) {
                problems.add("A category with no id or no name was skipped.");
                continue;
            }
            Category category = new Category(n.id, n.name);
            byId.put(category.getId(), category);
            result.add(category);
        }
        for (int i = 0; i < nodes.size(); i++) {
            String parentId = nodes.get(i).parentId;
            if (parentId == null) {
                continue;
            }
            Category child = byId.get(nodes.get(i).id);
            Category parent = byId.get(parentId);
            if (child == null) {
                continue;
            }
            if (parent == null) {
                problems.add("Category \"" + child.getName()
                        + "\" names a parent that is not in the file; it is kept at the top level.");
            } else {
                parent.addChild(child);
            }
        }
        return result;
    }

    /**
     * The location twin of {@link #categoryTree}; the two tree classes share
     * no base class (CLAUDE.md §10).
     *
     * @param nodes    the flat nodes
     * @param problems where to report a node that cannot be placed
     * @return every node, linked
     */
    private static List<Location> locationTree(List<JsonCollection.NodeJson> nodes,
                                               List<String> problems) {
        Map<String, Location> byId = new HashMap<>();
        List<Location> result = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            JsonCollection.NodeJson n = nodes.get(i);
            if (n.id == null || n.name == null || n.name.trim().isEmpty()) {
                problems.add("A location with no id or no name was skipped.");
                continue;
            }
            Location location = new Location(n.id, n.name);
            byId.put(location.getId(), location);
            result.add(location);
        }
        for (int i = 0; i < nodes.size(); i++) {
            String parentId = nodes.get(i).parentId;
            if (parentId == null) {
                continue;
            }
            Location child = byId.get(nodes.get(i).id);
            Location parent = byId.get(parentId);
            if (child == null) {
                continue;
            }
            if (parent == null) {
                problems.add("Location \"" + child.getName()
                        + "\" names a parent that is not in the file; it is kept at the top level.");
            } else {
                parent.addChild(child);
            }
        }
        return result;
    }

    /**
     * Rebuilds one book through its storage constructor and setters.
     * <p>
     * The stored reading status is set <em>last</em>: adding a finished
     * session moves the status to FINISHED, which would overwrite a book the
     * user had since marked ABANDONED.
     *
     * @param b the book's fields
     * @return the book
     * @throws InvalidISBNException     if its ISBN is invalid
     * @throws IllegalArgumentException if any other value is invalid
     */
    private static Book toBook(JsonCollection.BookJson b) throws InvalidISBNException {
        if (b.id == null) {
            // Without its id a re-import could never recognise the book.
            throw new IllegalArgumentException("it has no id.");
        }
        LocalDate added = b.dateAdded == null ? LocalDate.now() : date(b.dateAdded);
        Book book = new Book(b.id, b.title, added);
        book.setSubtitle(b.subtitle);
        book.setIsbn(b.isbn);
        book.setPublisher(b.publisher);
        book.setPublicationYear(b.publicationYear);
        book.setLanguage(b.language);
        book.setPages(b.pages);
        book.setSeriesId(b.seriesId);
        book.setSeriesPosition(b.seriesPosition);
        book.setRating(b.rating);
        book.setNotes(b.notes);
        book.setDeleted(b.deleted);
        for (int i = 0; i < b.categoryIds.size(); i++) {
            book.addCategoryId(b.categoryIds.get(i));
        }
        for (int i = 0; i < b.contributors.size(); i++) {
            JsonCollection.ContributorJson c = b.contributors.get(i);
            book.addContributor(new Contributor(c.name,
                    enumValue(ContributorRole.class, c.role, ContributorRole.AUTHOR)));
        }
        for (int i = 0; i < b.copies.size(); i++) {
            JsonCollection.CopyJson c = b.copies.get(i);
            Format format = enumValue(Format.class, c.format, Format.PAPERBACK);
            Condition condition = enumValue(Condition.class, c.condition, Condition.GOOD);
            // A hand-written copy without an id gets a fresh one.
            Copy copy = c.id == null ? new Copy(format, condition) : new Copy(c.id, format, condition);
            copy.setLocationId(c.locationId);
            copy.setPurchaseDate(c.purchaseDate == null ? null : date(c.purchaseDate));
            copy.setPricePaid(c.pricePaid);
            book.addCopy(copy);
        }
        if (book.getCopyCount() == 0) {
            // Owning a book means having a copy (CLAUDE.md §10).
            throw new IllegalArgumentException("it has no copies.");
        }
        for (int i = 0; i < b.readingSessions.size(); i++) {
            JsonCollection.SessionJson s = b.readingSessions.get(i);
            ReadingSession session = s.id == null
                    ? new ReadingSession(date(s.startDate))
                    : new ReadingSession(s.id, date(s.startDate));
            session.setEndDate(s.endDate == null ? null : date(s.endDate));
            session.setPagesRead(s.pagesRead);
            session.setPercentComplete(s.percentComplete);
            book.addReadingSession(session);
        }
        for (int i = 0; i < b.customFields.size(); i++) {
            book.setCustomField(b.customFields.get(i).key, b.customFields.get(i).value);
        }
        book.setReadingStatus(enumValue(ReadingStatus.class, b.readingStatus, book.getReadingStatus()));
        return book;
    }

    /**
     * @param w        the wishlist's fields
     * @param problems where to report a bad wishlist or entry
     * @return the wishlist, or null if it could not be built at all
     */
    private static Wishlist toWishlist(JsonCollection.WishlistJson w, List<String> problems) {
        Wishlist wishlist;
        try {
            wishlist = new Wishlist(w.id, w.name);
        } catch (IllegalArgumentException e) {
            problems.add("Wishlist \"" + w.name + "\": " + e.getMessage());
            return null;
        }
        for (int i = 0; i < w.entries.size(); i++) {
            JsonCollection.EntryJson e = w.entries.get(i);
            try {
                WishlistEntry entry = new WishlistEntry(e.id, e.title);
                entry.setIsbn(e.isbn);
                entry.setAuthor(e.author);
                entry.setPriority(enumValue(Priority.class, e.priority, Priority.MEDIUM));
                entry.setExpectedPrice(e.expectedPrice);
                entry.setStore(e.store);
                entry.setRare(e.rare);
                wishlist.addEntry(entry);
            } catch (IllegalArgumentException ex) {
                problems.add("Wishlist \"" + w.name + "\", entry \"" + e.title + "\": "
                        + ex.getMessage());
            }
        }
        return wishlist;
    }

    /**
     * Looks up an enum constant by name, with a fallback for a missing
     * value. {@code Enum.valueOf} alone would throw a
     * {@code NullPointerException} for a missing name, which no caller
     * expects; an unknown name still throws
     * {@code IllegalArgumentException}, reported like any bad value.
     * <p>
     * {@code Class<E>} is how a generic method is told which enum to search,
     * since {@code E} itself is not available at runtime.
     *
     * @param type     the enum's class, e.g. {@code Format.class}
     * @param name     the constant's name, or null
     * @param fallback what a missing name means
     * @param <E>      the enum type
     * @return the constant
     * @throws IllegalArgumentException if the name matches no constant
     */
    private static <E extends Enum<E>> E enumValue(Class<E> type, String name, E fallback) {
        if (name == null) {
            return fallback;
        }
        return Enum.valueOf(type, name);
    }

    /**
     * @param text an ISO date such as "2024-03-09"
     * @return the date
     * @throws IllegalArgumentException if it is missing or not an ISO date
     */
    private static LocalDate date(String text) {
        if (text == null) {
            throw new IllegalArgumentException("a required date is missing.");
        }
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("\"" + text + "\" is not a date in the form 2024-03-09.", e);
        }
    }
}
