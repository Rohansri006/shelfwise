package com.shelfwise.core.storage;

import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Category;
import com.shelfwise.core.model.Contributor;
import com.shelfwise.core.model.ContributorRole;
import com.shelfwise.core.model.Copy;
import com.shelfwise.core.model.Location;
import com.shelfwise.core.service.CollectionData;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the live books as a CSV file a spreadsheet can open.
 * <p>
 * <strong>One row per copy</strong> (SPEC §6.6): a book with two copies on
 * different shelves becomes two rows that share the book's columns and
 * differ in location, format and price. Re-importing groups them back into
 * one book by the {@code id} column.
 * <p>
 * CSV is the simple, readable format, so it is deliberately partial (D16):
 * books in the trash, series, wishlists, reading sessions, custom fields
 * and contributors other than authors are left out. {@link JsonExporter}
 * writes everything.
 * <p>
 * Empty cells mean "not recorded": the model's 0 for an unknown year, page
 * count, price or rating is written as nothing rather than as a misleading
 * "0".
 */
public class CsvExporter extends Exporter {

    /** @return "csv" */
    @Override
    public String getFileExtension() {
        return "csv";
    }

    /**
     * Writes the header row, then one row per copy of each live book.
     *
     * @param data the collection to write
     * @param out  an open, buffered writer
     * @throws IOException if writing fails
     */
    @Override
    protected void write(CollectionData data, BufferedWriter out) throws IOException {
        writeHeader(data.getCurrencyCode(), out);

        // Lookup tables, so each row finds its category and location names
        // in one HashMap get instead of a search through every node.
        Map<String, Category> categories = new HashMap<>();
        for (int i = 0; i < data.getCategories().size(); i++) {
            Category category = data.getCategories().get(i);
            categories.put(category.getId(), category);
        }
        Map<String, Location> locations = new HashMap<>();
        for (int i = 0; i < data.getLocations().size(); i++) {
            Location location = data.getLocations().get(i);
            locations.put(location.getId(), location);
        }

        List<Book> books = data.getBooks();
        for (int i = 0; i < books.size(); i++) {
            Book book = books.get(i);
            if (book.isDeleted()) {
                continue;
            }
            List<Copy> copies = book.getCopies();
            for (int j = 0; j < copies.size(); j++) {
                writeRow(book, copies.get(j), categories, locations, out);
            }
        }
    }

    /**
     * @param currencyCode the collection's currency, named in the price header
     * @param out          where to write
     * @throws IOException if writing fails
     */
    private void writeHeader(String currencyCode, BufferedWriter out) throws IOException {
        List<String> cells = new ArrayList<>();
        for (int i = 0; i < CsvFormat.COLUMNS.length; i++) {
            if (CsvFormat.COLUMNS[i].equals(CsvFormat.PRICE_HEADER)) {
                cells.add(CsvFormat.priceHeader(currencyCode));
            } else {
                cells.add(CsvFormat.COLUMNS[i]);
            }
        }
        writeCells(cells, out);
    }

    /**
     * Writes one copy of one book. The cells are added in exactly the order
     * of {@link CsvFormat#COLUMNS}.
     *
     * @param book       the book
     * @param copy       one of its copies
     * @param categories every category, by id
     * @param locations  every location, by id
     * @param out        where to write
     * @throws IOException if writing fails
     */
    private void writeRow(Book book, Copy copy, Map<String, Category> categories,
                          Map<String, Location> locations, BufferedWriter out)
            throws IOException {
        List<String> cells = new ArrayList<>();
        cells.add(book.getId());
        cells.add(book.getTitle());
        cells.add(book.getSubtitle());
        cells.add(book.getIsbn());
        cells.add(authorsOf(book));
        cells.add(book.getPublisher());
        cells.add(numberOrBlank(book.getPublicationYear()));
        cells.add(numberOrBlank(book.getPages()));
        cells.add(book.getLanguage());
        cells.add(categoryPathsOf(book, categories));

        Location location = copy.getLocationId() == null ? null : locations.get(copy.getLocationId());
        cells.add(location == null ? null : location.getPath());
        cells.add(copy.getFormat().getLabel());
        cells.add(copy.getCondition().getLabel());
        cells.add(copy.getPurchaseDate() == null ? null : copy.getPurchaseDate().toString());
        cells.add(copy.hasRecordedPrice() ? String.valueOf(copy.getPricePaid()) : null);

        cells.add(numberOrBlank(book.getRating()));
        cells.add(book.getReadingStatus().getLabel());
        cells.add(book.getNotes());
        writeCells(cells, out);
    }

    /**
     * Writes cells as one line: each one quoted if it needs it, separated
     * by commas, ended with a line break.
     *
     * @param cells the cells; null ones are written empty
     * @param out   where to write
     * @throws IOException if writing fails
     */
    private void writeCells(List<String> cells, BufferedWriter out) throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.write(CsvFormat.COMMA);
            }
            out.write(CsvFormat.quote(cells.get(i)));
        }
        out.newLine();
    }

    /**
     * @param book the book
     * @return its authors' names joined by semicolons, or null if none
     */
    private static String authorsOf(Book book) {
        List<String> names = new ArrayList<>();
        List<Contributor> contributors = book.getContributors();
        for (int i = 0; i < contributors.size(); i++) {
            if (contributors.get(i).getRole() == ContributorRole.AUTHOR) {
                names.add(contributors.get(i).getName());
            }
        }
        return joinOrNull(names);
    }

    /**
     * Full paths rather than bare names, because the same name may sit
     * under two parents: "Reference > Finance" and "Non-fiction > Finance"
     * are different categories.
     *
     * @param book       the book
     * @param categories every category, by id
     * @return its category paths joined by semicolons, or null if none
     */
    private static String categoryPathsOf(Book book, Map<String, Category> categories) {
        List<String> paths = new ArrayList<>();
        List<String> ids = book.getCategoryIds();
        for (int i = 0; i < ids.size(); i++) {
            Category category = categories.get(ids.get(i));
            if (category != null) {
                paths.add(category.getPath());
            }
        }
        return joinOrNull(paths);
    }

    /**
     * @param values the values to join
     * @return them joined by {@link CsvFormat#LIST_SEPARATOR}, or null if empty
     */
    private static String joinOrNull(List<String> values) {
        if (values.isEmpty()) {
            return null;
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                joined.append(CsvFormat.LIST_SEPARATOR);
            }
            joined.append(values.get(i));
        }
        return joined.toString();
    }

    /**
     * @param value a number where 0 means "not recorded"
     * @return the number as text, or null for 0
     */
    private static String numberOrBlank(int value) {
        return value == 0 ? null : String.valueOf(value);
    }
}
