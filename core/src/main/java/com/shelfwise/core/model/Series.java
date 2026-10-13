package com.shelfwise.core.model;

import java.util.UUID;

/**
 * A named run of books meant to be read together - "Discworld", "The
 * Expanse" - and how many books it contains, when that is known.
 * <p>
 * <strong>The link runs one way, from book to series.</strong> A
 * {@link Book} holds the series' id and its own position in it; a series
 * holds no list of books. This is the same choice as {@link Copy} pointing
 * at a {@link Location}: if each side referenced the other, neither could be
 * loaded without the other. The consequence is that this class cannot check
 * a book's position against {@link #getTotalBooks()} - that rule needs both
 * objects, so it belongs in the service layer.
 * <p>
 * Two rules live outside this class for the same reason. Series names are
 * unique across the whole collection, which only something that sees every
 * series can enforce - the database's {@code UNIQUE} constraint and the
 * service. And deleting a series leaves its books in the collection, simply
 * no longer in a series: the book's foreign key is {@code ON DELETE SET NULL}.
 */
public class Series {

    /** Permanent internal identity. */
    private final String id;

    /** Display name. Required. */
    private String name;

    /** How many books the series has. 0 means unknown. */
    private int totalBooks;

    /**
     * Creates a series whose length is not yet known.
     *
     * @param name the display name; must not be blank
     * @throws IllegalArgumentException if the name is blank
     */
    public Series(String name) {
        this(name, 0);
    }

    /**
     * Creates a series with a fresh identity and a known length.
     *
     * @param name       the display name; must not be blank
     * @param totalBooks how many books the series has; 0 means unknown
     * @throws IllegalArgumentException if the name is blank or the total is
     *                                  negative
     */
    public Series(String name, int totalBooks) {
        this(UUID.randomUUID().toString(), name, totalBooks);
    }

    /**
     * Recreates a series that already exists, keeping its identity. Used by
     * the storage layer when loading.
     *
     * @param id         the existing identity
     * @param name       the display name; must not be blank
     * @param totalBooks how many books the series has; 0 means unknown
     * @throws IllegalArgumentException if the name is blank or the total is
     *                                  negative
     */
    public Series(String id, String name, int totalBooks) {
        this.id = id;
        // Through the setters, so a loaded row is validated exactly like a
        // newly typed one.
        setName(name);
        setTotalBooks(totalBooks);
    }

    /** @return the permanent internal identity */
    public String getId() {
        return id;
    }

    /** @return the display name */
    public String getName() {
        return name;
    }

    /**
     * @param name the display name; must not be null or blank
     * @throws IllegalArgumentException if null or blank
     */
    public void setName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Series name must not be blank.");
        }
        this.name = name.trim();
    }

    /** @return how many books the series has, or 0 if unknown */
    public int getTotalBooks() {
        return totalBooks;
    }

    /**
     * @param totalBooks how many books the series has; 0 means unknown
     * @throws IllegalArgumentException if negative
     */
    public void setTotalBooks(int totalBooks) {
        if (totalBooks < 0) {
            throw new IllegalArgumentException(
                    "Total books cannot be negative, but was " + totalBooks + ".");
        }
        this.totalBooks = totalBooks;
    }

    /**
     * Whether the series' length has been recorded.
     * <p>
     * Exists so callers can choose between "3 of 7" and "3 of ?" without
     * knowing that 0 is the project's "not recorded" value.
     *
     * @return true if the total is known
     */
    public boolean isTotalKnown() {
        return totalBooks > 0;
    }

    /**
     * Identity is the id alone, so renaming a series keeps every book in it.
     *
     * @param other the object to compare with
     * @return true if the ids match
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Series)) {
            return false;
        }
        return id.equals(((Series) other).id);
    }

    /** @return hash of the id, matching {@link #equals} */
    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** @return the name, which is how a series appears in a list */
    @Override
    public String toString() {
        return name;
    }
}
