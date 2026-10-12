package com.shelfwise.core.model;

import java.util.UUID;

/**
 * A book the user wants but does not own yet.
 * <p>
 * Kept separate from {@link Book} rather than being a book with a flag,
 * because the two hold genuinely different information. A wanted book has an
 * expected price and a shop; an owned book has a price actually paid and a
 * shelf. Merging them would leave every owned book carrying empty
 * wishlist fields and vice versa.
 * <p>
 * When the user buys it, {@link Book#Book(WishlistEntry)} converts this into
 * a real book, carrying across what still applies.
 */
public class WishlistEntry {

    /** Permanent internal identity. */
    private final String id;

    /** What the user is looking for. Required. */
    private String title;

    /** Optional: often unknown until the book is found. */
    private String isbn;

    /** Optional author name, as free text rather than a {@link Contributor},
     *  because at this stage the user may only half-remember it. */
    private String author;

    /** How much the user wants it. Defaults to {@link Priority#MEDIUM}. */
    private Priority priority;

    /** What the user expects to pay. 0 means unknown. */
    private double expectedPrice;

    /** Where they intend to buy it. Defaults to Amazon. */
    private String store;

    /** Set by hand when a book is hard to find, so it can be prioritised
     *  if it ever turns up. Not determined automatically - there is no
     *  price or availability feed behind this. */
    private boolean rare;

    /**
     * Creates an entry with default priority and store.
     *
     * @param title what the user is looking for; must not be blank
     * @throws IllegalArgumentException if the title is blank
     */
    public WishlistEntry(String title) {
        this(UUID.randomUUID().toString(), title);
    }

    /**
     * Recreates an entry that already exists, keeping its identity. Used by
     * the storage layer when loading.
     *
     * @param id    the existing identity
     * @param title what the user is looking for; must not be blank
     * @throws IllegalArgumentException if the title is blank
     */
    public WishlistEntry(String id, String title) {
        this.id = id;
        this.priority = Priority.MEDIUM;
        this.store = "Amazon";
        this.expectedPrice = 0;
        this.rare = false;
        setTitle(title);
    }

    /** @return the permanent internal identity */
    public String getId() {
        return id;
    }

    /** @return what the user is looking for */
    public String getTitle() {
        return title;
    }

    /**
     * @param title the title; must not be null or blank
     * @throws IllegalArgumentException if null or blank
     */
    public void setTitle(String title) {
        if (title == null || title.trim().isEmpty()) {
            throw new IllegalArgumentException("Wishlist entry title must not be blank.");
        }
        this.title = title.trim();
    }

    /** @return the ISBN, or null if unknown */
    public String getIsbn() {
        return isbn;
    }

    /**
     * Stores the ISBN without validating it.
     * <p>
     * Deliberately lax, unlike {@link Book#setIsbn}: a wishlist is a notepad,
     * and refusing a half-remembered number would make it less useful. The
     * ISBN is validated when the entry becomes a real book.
     *
     * @param isbn the ISBN as entered, or null
     */
    public void setIsbn(String isbn) {
        this.isbn = IsbnValidator.normalise(isbn);
    }

    /** @return the author as free text, or null */
    public String getAuthor() {
        return author;
    }

    /** @param author the author as free text; null is acceptable */
    public void setAuthor(String author) {
        this.author = author;
    }

    /** @return how much the user wants it */
    public Priority getPriority() {
        return priority;
    }

    /**
     * @param priority the priority; must not be null
     * @throws IllegalArgumentException if null
     */
    public void setPriority(Priority priority) {
        if (priority == null) {
            throw new IllegalArgumentException("Priority must not be null.");
        }
        this.priority = priority;
    }

    /** @return the expected price, or 0 if unknown */
    public double getExpectedPrice() {
        return expectedPrice;
    }

    /**
     * @param expectedPrice what the user expects to pay; 0 means unknown
     * @throws IllegalArgumentException if negative
     */
    public void setExpectedPrice(double expectedPrice) {
        if (expectedPrice < 0) {
            throw new IllegalArgumentException(
                    "Expected price cannot be negative, but was " + expectedPrice + ".");
        }
        this.expectedPrice = expectedPrice;
    }

    /** @return where they intend to buy it */
    public String getStore() {
        return store;
    }

    /** @param store the shop; blank or null resets it to Amazon */
    public void setStore(String store) {
        if (store == null || store.trim().isEmpty()) {
            this.store = "Amazon";
        } else {
            this.store = store.trim();
        }
    }

    /** @return true if the user marked this as hard to find */
    public boolean isRare() {
        return rare;
    }

    /** @param rare true if hard to find */
    public void setRare(boolean rare) {
        this.rare = rare;
    }

    /**
     * @param other the object to compare with
     * @return true if the ids match
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WishlistEntry)) {
            return false;
        }
        return id.equals(((WishlistEntry) other).id);
    }

    /** @return hash of the id, matching {@link #equals} */
    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** @return title, author if known, and priority */
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder(title);
        if (author != null && !author.trim().isEmpty()) {
            text.append(" by ").append(author);
        }
        text.append(" [").append(priority.getLabel()).append(']');
        return text.toString();
    }
}
