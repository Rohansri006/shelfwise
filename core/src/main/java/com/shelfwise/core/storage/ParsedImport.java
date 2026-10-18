package com.shelfwise.core.storage;

import com.shelfwise.core.service.CollectionData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What an {@link Importer} found in a file, before anything is merged into
 * the collection.
 * <p>
 * Holds the data in the same {@link CollectionData} shape storage uses, so
 * one {@link CollectionMerger} serves every format; the currency the file
 * named, if any; and every problem met while reading. A bad row is
 * reported here instead of stopping the import, so one typo in a
 * thousand-row spreadsheet does not throw away the other 999 (SPEC §6.6).
 */
public class ParsedImport {

    private final CollectionData data;

    /** The currency the file named, or null if it did not name one. */
    private final String currencyCode;

    private final List<String> problems;

    /**
     * @param data         what was read
     * @param currencyCode the currency the file named, or null if it names
     *                     none (a plain {@code price_paid} header means the
     *                     collection's own currency, D18)
     * @param problems     a description of each row that could not be read
     * @throws IllegalArgumentException if data is null
     */
    public ParsedImport(CollectionData data, String currencyCode, List<String> problems) {
        if (data == null) {
            throw new IllegalArgumentException("Imported data must not be null.");
        }
        this.data = data;
        this.currencyCode = currencyCode;
        this.problems = problems == null ? new ArrayList<String>() : new ArrayList<>(problems);
    }

    /** @return what was read */
    public CollectionData getData() {
        return data;
    }

    /** @return the currency the file named, or null if it named none */
    public String getCurrencyCode() {
        return currencyCode;
    }

    /**
     * Whether the screen must ask before merging: the file names a currency
     * and it is not the collection's. Prices are never converted, so
     * merging would relabel them (ADR 0005).
     *
     * @param collectionCurrency the collection's ISO 4217 code
     * @return true if the two differ
     */
    public boolean currencyDiffersFrom(String collectionCurrency) {
        return currencyCode != null && !currencyCode.equalsIgnoreCase(collectionCurrency);
    }

    /** @return a read-only list of the rows that could not be read */
    public List<String> getProblems() {
        return Collections.unmodifiableList(problems);
    }

    /** @return how many books were read */
    public int getBookCount() {
        return data.getBooks().size();
    }
}
