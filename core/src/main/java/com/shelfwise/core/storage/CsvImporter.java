package com.shelfwise.core.storage;

import com.shelfwise.core.exception.InvalidISBNException;
import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Category;
import com.shelfwise.core.model.Condition;
import com.shelfwise.core.model.Contributor;
import com.shelfwise.core.model.Copy;
import com.shelfwise.core.model.Format;
import com.shelfwise.core.model.IsbnValidator;
import com.shelfwise.core.model.Location;
import com.shelfwise.core.model.ReadingStatus;
import com.shelfwise.core.service.CollectionData;

import java.io.BufferedReader;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Reads a CSV file in the layout {@link CsvExporter} writes - or a
 * spreadsheet a person made by hand with the same headers.
 * <p>
 * <strong>Rows become books.</strong> One row is one copy (SPEC §6.6), so
 * rows are grouped back into books: by the {@code id} column, else by ISBN.
 * A row with neither becomes a book of its own, which means re-importing
 * such a row adds it again - an accepted limitation (E1), since nothing
 * else in a row reliably identifies a book. The book-level cells (title,
 * authors and so on) are taken from the first row of each group.
 * <p>
 * <strong>Bad rows are reported, not fatal.</strong> A row whose year is
 * not a number, whose ISBN fails its checksum or whose date is not a date
 * is skipped and described in {@link ParsedImport#getProblems()}, and
 * reading carries on. Only a file without a recognisable header is
 * refused outright.
 */
public class CsvImporter extends Importer {

    /**
     * @param in an open, buffered reader
     * @return the books, categories and locations found
     * @throws IOException if reading fails or the header is unrecognisable
     */
    @Override
    protected ParsedImport parse(BufferedReader in) throws IOException {
        List<String> header = readRecord(in);
        if (header == null) {
            throw new IOException("The CSV file is empty.");
        }
        Map<String, Integer> columns = indexColumns(header);
        if (!columns.containsKey("title")) {
            throw new IOException("This is not a ShelfWise CSV file: it has no \"title\" column.");
        }
        String currency = currencyFrom(header);

        Rows rows = new Rows(columns);
        List<String> record = readRecord(in);
        int rowNumber = 2;
        while (record != null) {
            if (!isBlank(record)) {
                rows.add(record, rowNumber);
            }
            record = readRecord(in);
            rowNumber++;
        }

        // CollectionData needs some currency; the file's, or the default.
        // ParsedImport keeps the file's own answer (possibly "none") apart.
        String dataCurrency = currency == null ? CollectionData.DEFAULT_CURRENCY : currency;
        CollectionData data = new CollectionData(rows.books, rows.categoryList(),
                rows.locationList(), null, null, null, dataCurrency);
        return new ParsedImport(data, currency, rows.problems);
    }

    // ------------------------------------------------------------------
    // Reading records
    // ------------------------------------------------------------------

    /**
     * Reads one record - normally one line, but a quoted cell may contain
     * line breaks, so the text is read a character at a time rather than
     * with {@code readLine()}.
     * <p>
     * Inside quotes, a doubled quote stands for one quote character and a
     * single quote ends the quoted part. Outside quotes, a comma ends a
     * cell and a line break ends the record. A Windows line end ("\r\n")
     * counts as one break.
     *
     * @param in the reader
     * @return the record's cells, or null at the end of the file
     * @throws IOException if reading fails
     */
    private static List<String> readRecord(BufferedReader in) throws IOException {
        int c = in.read();
        if (c == -1) {
            return null;
        }
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;

        while (c != -1) {
            char ch = (char) c;
            if (inQuotes) {
                if (ch == CsvFormat.QUOTE) {
                    int next = in.read();
                    if (next == CsvFormat.QUOTE) {
                        cell.append(CsvFormat.QUOTE);
                    } else {
                        // The closing quote. Whatever follows it is handled
                        // as ordinary text on the next pass of the loop.
                        inQuotes = false;
                        c = next;
                        continue;
                    }
                } else {
                    cell.append(ch);
                }
            } else if (ch == CsvFormat.QUOTE && cell.length() == 0) {
                inQuotes = true;
            } else if (ch == CsvFormat.COMMA) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\n') {
                break;
            } else if (ch == '\r') {
                // Swallow the '\n' of a "\r\n" pair. mark/reset lets the
                // reader step back one character if it was anything else.
                in.mark(1);
                if (in.read() != '\n') {
                    in.reset();
                }
                break;
            } else {
                cell.append(ch);
            }
            c = in.read();
        }
        cells.add(cell.toString());
        return cells;
    }

    /**
     * Maps each header to its position, so columns are found by name and a
     * hand-made sheet may put them in any order or leave some out. The
     * price header is stored under its bare name whatever currency it
     * names.
     *
     * @param header the header cells
     * @return column name, lower-cased, to index
     */
    private static Map<String, Integer> indexColumns(List<String> header) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i).trim().toLowerCase(Locale.ROOT);
            if (name.startsWith(CsvFormat.PRICE_HEADER)) {
                name = CsvFormat.PRICE_HEADER;
            }
            columns.put(name, i);
        }
        return columns;
    }

    /**
     * Reads the currency out of a header such as {@code price_paid (EUR)}.
     *
     * @param header the header cells
     * @return the code, upper-cased, or null if the price header names none
     */
    private static String currencyFrom(List<String> header) {
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i).trim();
            int open = name.indexOf('(');
            int close = name.indexOf(')');
            if (name.toLowerCase(Locale.ROOT).startsWith(CsvFormat.PRICE_HEADER)
                    && open >= 0 && close > open) {
                return name.substring(open + 1, close).trim().toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    /**
     * @param record a record's cells
     * @return true if every cell is empty - a stray blank line
     */
    private static boolean isBlank(List<String> record) {
        for (int i = 0; i < record.size(); i++) {
            if (!record.get(i).trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Turning rows into books
    // ------------------------------------------------------------------

    /**
     * Everything collected so far while reading rows: the books, the tree
     * nodes their paths created, and the problems. A small private class
     * rather than a crowd of local variables passed from method to method.
     */
    private static class Rows {

        private final Map<String, Integer> columns;
        private final List<Book> books = new ArrayList<>();
        private final List<String> problems = new ArrayList<>();

        /** Books by grouping key, so later rows find their book's first row. */
        private final Map<String, Book> booksByKey = new HashMap<>();

        /** Tree nodes by full path, so "Fiction" is created only once. */
        private final Map<String, Category> categoriesByPath = new HashMap<>();
        private final Map<String, Location> locationsByPath = new HashMap<>();

        /** The same nodes in creation order, for the result. */
        private final List<Category> categories = new ArrayList<>();
        private final List<Location> locations = new ArrayList<>();

        Rows(Map<String, Integer> columns) {
            this.columns = columns;
        }

        /**
         * Turns one row into a copy, and into a book too if it is the first
         * row of its group. Everything that can fail is parsed before
         * anything is stored, so a bad row leaves no trace except its
         * problem message.
         *
         * @param row       the row's cells
         * @param rowNumber its line in the file, for messages
         */
        void add(List<String> row, int rowNumber) {
            String title = cell(row, "title");
            try {
                String key = groupKey(row, rowNumber);
                Book book = booksByKey.get(key);
                boolean firstRow = book == null;
                if (firstRow) {
                    book = newBook(row, key);
                }
                Copy copy = newCopy(row);
                List<String> categoryPaths = split(cell(row, "categories"));
                for (int i = 0; i < categoryPaths.size(); i++) {
                    requireValidPath(categoryPaths.get(i));
                }
                String locationPath = cell(row, "location");
                if (locationPath != null) {
                    requireValidPath(locationPath);
                }

                // Nothing above changed any shared state; from here on the
                // row is known to be good.
                if (locationPath != null) {
                    copy.setLocationId(location(locationPath).getId());
                }
                book.addCopy(copy);
                if (firstRow) {
                    for (int i = 0; i < categoryPaths.size(); i++) {
                        book.addCategoryId(category(categoryPaths.get(i)).getId());
                    }
                    booksByKey.put(key, book);
                    books.add(book);
                }
            } catch (InvalidISBNException | IllegalArgumentException e) {
                // One catch for two unrelated exception types (a
                // multi-catch): a checked one from the ISBN check and the
                // unchecked one every validating setter throws.
                String which = title == null ? "" : " (\"" + title + "\")";
                problems.add("Row " + rowNumber + which + ": " + e.getMessage());
            }
        }

        /**
         * Which book a row belongs to: its id, else its ISBN in ISBN-13
         * form, else nothing shared with any other row (E1).
         *
         * @param row       the row's cells
         * @param rowNumber its line, so an id-less, ISBN-less row gets a
         *                  key no other row has
         * @return the grouping key
         * @throws InvalidISBNException if the ISBN cell is not a valid ISBN
         */
        private String groupKey(List<String> row, int rowNumber) throws InvalidISBNException {
            String id = cell(row, "id");
            if (id != null) {
                return "id:" + id;
            }
            String isbn = cell(row, "isbn");
            if (isbn != null) {
                return "isbn:" + IsbnValidator.toIsbn13(isbn);
            }
            return "row:" + rowNumber;
        }

        /**
         * Builds the book from a row's book-level cells. The storage
         * constructor is used because it adds no copy of its own: each row
         * supplies its copy.
         *
         * @param row the row's cells
         * @param key its grouping key; an "id:" key supplies the book's id
         * @return the new book, not yet stored anywhere
         * @throws InvalidISBNException     if the ISBN is invalid
         * @throws IllegalArgumentException if any other cell is invalid
         */
        private Book newBook(List<String> row, String key) throws InvalidISBNException {
            String id = key.startsWith("id:") ? key.substring(3) : UUID.randomUUID().toString();
            String title = cell(row, "title");
            if (title == null) {
                throw new IllegalArgumentException("the title is blank.");
            }
            Book book = new Book(id, title, LocalDate.now());
            book.setSubtitle(cell(row, "subtitle"));
            book.setIsbn(cell(row, "isbn"));
            List<String> authors = split(cell(row, "authors"));
            for (int i = 0; i < authors.size(); i++) {
                book.addContributor(new Contributor(authors.get(i)));
            }
            book.setPublisher(cell(row, "publisher"));
            book.setPublicationYear(number(cell(row, "year")));
            book.setPages(number(cell(row, "pages")));
            book.setLanguage(cell(row, "language"));
            book.setRating(number(cell(row, "rating")));
            book.setReadingStatus(parseEnum(ReadingStatus.values(), cell(row, "reading_status"),
                    ReadingStatus.UNREAD));
            book.setNotes(cell(row, "notes"));
            return book;
        }

        /**
         * @param row the row's cells
         * @return the copy the row describes, location not yet set
         * @throws IllegalArgumentException if a value is invalid
         */
        private Copy newCopy(List<String> row) {
            Copy copy = new Copy(
                    parseEnum(Format.values(), cell(row, "format"), Format.PAPERBACK),
                    parseEnum(Condition.values(), cell(row, "condition"), Condition.GOOD));
            String date = cell(row, "purchase_date");
            if (date != null) {
                copy.setPurchaseDate(date(date));
            }
            String price = cell(row, CsvFormat.PRICE_HEADER);
            if (price != null) {
                copy.setPricePaid(decimal(price));
            }
            return copy;
        }

        /**
         * Finds or creates the category at a path such as "Fiction >
         * Fantasy", creating any missing level on the way down.
         *
         * @param path the full path
         * @return the node at the end of it
         */
        private Category category(String path) {
            String[] names = path.split(CsvFormat.PATH_SEPARATOR);
            Category parent = null;
            String soFar = "";
            for (int i = 0; i < names.length; i++) {
                String name = names[i].trim();
                soFar = i == 0 ? name : soFar + CsvFormat.PATH_SEPARATOR + name;
                Category node = categoriesByPath.get(soFar.toLowerCase(Locale.ROOT));
                if (node == null) {
                    node = new Category(name);
                    if (parent != null) {
                        parent.addChild(node);
                    }
                    categoriesByPath.put(soFar.toLowerCase(Locale.ROOT), node);
                    categories.add(node);
                }
                parent = node;
            }
            return parent;
        }

        /**
         * The location twin of {@link #category}; the two tree classes share
         * no base class (CLAUDE.md §10), so the walk is written twice.
         *
         * @param path the full path
         * @return the node at the end of it
         */
        private Location location(String path) {
            String[] names = path.split(CsvFormat.PATH_SEPARATOR);
            Location parent = null;
            String soFar = "";
            for (int i = 0; i < names.length; i++) {
                String name = names[i].trim();
                soFar = i == 0 ? name : soFar + CsvFormat.PATH_SEPARATOR + name;
                Location node = locationsByPath.get(soFar.toLowerCase(Locale.ROOT));
                if (node == null) {
                    node = new Location(name);
                    if (parent != null) {
                        parent.addChild(node);
                    }
                    locationsByPath.put(soFar.toLowerCase(Locale.ROOT), node);
                    locations.add(node);
                }
                parent = node;
            }
            return parent;
        }

        /**
         * Checked before any node is created, so a path such as
         * "Fiction >  > Fantasy" fails the row instead of leaving half a
         * branch behind.
         *
         * @param path a category or location path
         * @throws IllegalArgumentException if any level's name is blank
         */
        private void requireValidPath(String path) {
            String[] names = path.split(CsvFormat.PATH_SEPARATOR);
            for (int i = 0; i < names.length; i++) {
                if (names[i].trim().isEmpty()) {
                    throw new IllegalArgumentException(
                            "\"" + path + "\" has a level with no name.");
                }
            }
        }

        /** @return every category node created, parents before children */
        List<Category> categoryList() {
            return categories;
        }

        /** @return every location node created, parents before children */
        List<Location> locationList() {
            return locations;
        }

        /**
         * @param row    the row's cells
         * @param column a column name from {@link CsvFormat#COLUMNS}
         * @return the cell trimmed, or null if the column is missing or the
         *         cell empty
         */
        private String cell(List<String> row, String column) {
            Integer index = columns.get(column);
            if (index == null || index >= row.size()) {
                return null;
            }
            String value = row.get(index).trim();
            return value.isEmpty() ? null : value;
        }
    }

    // ------------------------------------------------------------------
    // Cell conversions
    // ------------------------------------------------------------------

    /**
     * The three converters below catch Java's own parsing exceptions and
     * rethrow them with a sentence a user can act on: "For input string:
     * nineteen" becomes "\"nineteen\" is not a whole number." The original
     * stays attached as the cause.
     *
     * @param text a cell; null or blank means "not recorded"
     * @return the number, or 0 for a blank cell
     * @throws IllegalArgumentException if the cell is not a whole number
     */
    private static int number(String text) {
        if (text == null) {
            return 0;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + text + "\" is not a whole number.", e);
        }
    }

    /**
     * @param text a price cell
     * @return the amount
     * @throws IllegalArgumentException if the cell is not a number
     */
    private static double decimal(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + text + "\" is not an amount.", e);
        }
    }

    /**
     * @param text a date cell
     * @return the date
     * @throws IllegalArgumentException if the cell is not an ISO date
     */
    private static LocalDate date(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "\"" + text + "\" is not a date in the form 2024-03-09.", e);
        }
    }

    /**
     * @param text a cell of values separated by semicolons; may be null
     * @return the non-empty values, trimmed
     */
    private static List<String> split(String text) {
        List<String> values = new ArrayList<>();
        if (text == null) {
            return values;
        }
        String[] parts = text.split(CsvFormat.LIST_SEPARATOR);
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].trim().isEmpty()) {
                values.add(parts[i].trim());
            }
        }
        return values;
    }

    /**
     * Finds an enum constant by its label ("Like new") or its name
     * ("LIKE_NEW"), ignoring case, so a file from the exporter and a file
     * typed by hand both work.
     * <p>
     * Generic, so one method serves all three enums: {@code <E>} stands for
     * whichever enum's constants are passed in.
     *
     * @param constants every constant of the enum, from {@code values()}
     * @param text      the cell; null means "use the fallback"
     * @param fallback  the value for an empty cell
     * @param <E>       the enum type
     * @return the matching constant
     * @throws IllegalArgumentException if nothing matches
     */
    private static <E extends Enum<E>> E parseEnum(E[] constants, String text, E fallback) {
        if (text == null) {
            return fallback;
        }
        for (int i = 0; i < constants.length; i++) {
            if (constants[i].name().equalsIgnoreCase(text)
                    || constants[i].toString().equalsIgnoreCase(text)) {
                return constants[i];
            }
        }
        throw new IllegalArgumentException("\"" + text + "\" is not a recognised value.");
    }
}
