package com.shelfwise.core.service;

import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Category;
import com.shelfwise.core.model.Location;
import com.shelfwise.core.model.Series;
import com.shelfwise.core.model.Wishlist;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

/**
 * Everything in one collection, handed between the service layer and a
 * {@link StorageProvider} in a single object.
 * <p>
 * An immutable snapshot. The constructor copies each list, so a caller that
 * keeps adding to its own list after building this cannot change what is
 * about to be saved, and the getters return read-only views so a storage
 * class cannot alter what the service holds. Nothing in a snapshot changes
 * after it is made.
 * <p>
 * Categories and locations are passed as <strong>every node</strong>, not
 * only the roots, with their parent and child links already wired. The user
 * is not here: it lives in the settings file, not the database.
 * <p>
 * The currency is here because prices are plain numbers: whatever stores the
 * prices must store what they mean alongside them, or a copied database
 * would lose track of it.
 */
public class CollectionData {

    /** Every book, trashed ones included - the trash is saved too. */
    private final List<Book> books;

    /** Every category node, at any depth. */
    private final List<Category> categories;

    /** Every location node, at any depth. */
    private final List<Location> locations;

    /** Every series. */
    private final List<Series> series;

    /** Every wishlist, each holding its own entries. */
    private final List<Wishlist> wishlists;

    /** When this was last saved, or null if it never has been. */
    private final Instant savedAt;

    /** The currency every price is recorded in, as an ISO 4217 code such as "INR". */
    private final String currencyCode;

    /** The currency a new collection starts with. */
    public static final String DEFAULT_CURRENCY = "INR";

    /**
     * Creates a snapshot priced in the default currency. A null list is
     * treated as empty, so a caller with nothing of one kind need not build
     * an empty list to pass in.
     *
     * @param books      every book
     * @param categories every category node
     * @param locations  every location node
     * @param series     every series
     * @param wishlists  every wishlist
     * @param savedAt    when it was saved, or null if never
     */
    public CollectionData(List<Book> books, List<Category> categories,
                          List<Location> locations, List<Series> series,
                          List<Wishlist> wishlists, Instant savedAt) {
        this(books, categories, locations, series, wishlists, savedAt, DEFAULT_CURRENCY);
    }

    /**
     * Creates a snapshot with an explicit currency.
     *
     * @param books        every book
     * @param categories   every category node
     * @param locations    every location node
     * @param series       every series
     * @param wishlists    every wishlist
     * @param savedAt      when it was saved, or null if never
     * @param currencyCode an ISO 4217 code such as "INR"; case is ignored
     * @throws IllegalArgumentException if the code is null or not a real
     *                                  currency
     */
    public CollectionData(List<Book> books, List<Category> categories,
                          List<Location> locations, List<Series> series,
                          List<Wishlist> wishlists, Instant savedAt,
                          String currencyCode) {
        this.books = copyOf(books);
        this.categories = copyOf(categories);
        this.locations = copyOf(locations);
        this.series = copyOf(series);
        this.wishlists = copyOf(wishlists);
        this.savedAt = savedAt;
        this.currencyCode = validCurrency(currencyCode);
    }

    /**
     * Checks a currency code against Java's own list of ISO 4217 currencies.
     * {@link Currency#getInstance(String)} already throws
     * {@code IllegalArgumentException} for an unknown code; it is caught and
     * rethrown only to put the offending value in the message.
     *
     * <p>
     * Package-private rather than private so {@link CollectionManager}, in
     * the same package, validates a changed currency with the same rule.
     *
     * @param code the code to check
     * @return the code in upper case
     * @throws IllegalArgumentException if null or not a real currency
     */
    static String validCurrency(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Currency code must not be null.");
        }
        String upper = code.trim().toUpperCase(Locale.ROOT);
        try {
            return Currency.getInstance(upper).getCurrencyCode();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "\"" + code + "\" is not a recognised currency code.", e);
        }
    }

    /**
     * A private copy of a list, so later changes to the caller's list do not
     * reach this snapshot.
     * <p>
     * Generic so one helper serves all five list types: {@code <T>} stands
     * for whatever element type the argument has.
     *
     * @param source the list to copy; may be null
     * @param <T>    the element type
     * @return a new list with the same elements, empty if source was null
     */
    private static <T> List<T> copyOf(List<T> source) {
        if (source == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(source);
    }

    /** @return every book, read-only */
    public List<Book> getBooks() {
        return Collections.unmodifiableList(books);
    }

    /** @return every category node, read-only */
    public List<Category> getCategories() {
        return Collections.unmodifiableList(categories);
    }

    /** @return every location node, read-only */
    public List<Location> getLocations() {
        return Collections.unmodifiableList(locations);
    }

    /** @return every series, read-only */
    public List<Series> getSeries() {
        return Collections.unmodifiableList(series);
    }

    /** @return every wishlist, read-only */
    public List<Wishlist> getWishlists() {
        return Collections.unmodifiableList(wishlists);
    }

    /** @return when it was last saved, or null if never */
    public Instant getSavedAt() {
        return savedAt;
    }

    /** @return the ISO 4217 code every price is recorded in, e.g. "INR" */
    public String getCurrencyCode() {
        return currencyCode;
    }
}
