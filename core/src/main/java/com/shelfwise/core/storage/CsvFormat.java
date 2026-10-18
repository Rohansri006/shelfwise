package com.shelfwise.core.storage;

/**
 * The CSV layout shared by {@link CsvExporter} and {@link CsvImporter}, so
 * the two cannot disagree about a column name or separator.
 * <p>
 * Only constants and static helpers, so the constructor is private: there
 * is never a reason to create one.
 */
public final class CsvFormat {

    /** Separates cells in a row. */
    public static final char COMMA = ',';

    /** Wraps a cell that contains a comma, quote or line break. */
    public static final char QUOTE = '"';

    /**
     * Separates several values inside one cell: several authors, or several
     * categories. A semicolon rather than a comma, so the cell needs no
     * quoting just for that.
     */
    public static final String LIST_SEPARATOR = ";";

    /** Separates the levels of a category or location path. */
    public static final String PATH_SEPARATOR = " > ";

    /**
     * The price column's header starts with this. The currency follows in
     * brackets - {@code price_paid (INR)} - because a bare number means
     * nothing without it (ADR 0005).
     */
    public static final String PRICE_HEADER = "price_paid";

    /**
     * Every column, in order. {@code id} comes first so a re-import can
     * recognise books it has seen before (ADR 0004). The price column's
     * real header adds the currency; see {@link #priceHeader}.
     */
    public static final String[] COLUMNS = {
            "id", "title", "subtitle", "isbn", "authors", "publisher", "year", "pages",
            "language", "categories", "location", "format", "condition", "purchase_date",
            PRICE_HEADER, "rating", "reading_status", "notes"
    };

    private CsvFormat() {
    }

    /**
     * @param currencyCode the collection's ISO 4217 code
     * @return the price column's header, e.g. "price_paid (INR)"
     */
    public static String priceHeader(String currencyCode) {
        return PRICE_HEADER + " (" + currencyCode + ")";
    }

    /**
     * Prepares one cell for writing.
     * <p>
     * A cell holding a comma, a quote or a line break is wrapped in quotes,
     * and each quote inside is doubled - the standard CSV rule (RFC 4180)
     * that spreadsheets also follow. So {@code He said "hi", twice} becomes
     * {@code "He said ""hi"", twice"}. Anything else is written as it is.
     *
     * @param value the cell's text; null is written as an empty cell
     * @return the text ready to write
     */
    public static String quote(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuotes = value.indexOf(COMMA) >= 0 || value.indexOf(QUOTE) >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuotes) {
            return value;
        }
        StringBuilder quoted = new StringBuilder();
        quoted.append(QUOTE);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == QUOTE) {
                quoted.append(QUOTE);
            }
            quoted.append(c);
        }
        quoted.append(QUOTE);
        return quoted.toString();
    }
}
