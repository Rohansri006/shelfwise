package com.shelfwise.core.model;

/**
 * The physical or digital form of one copy of a book.
 * <p>
 * This belongs to a {@link Copy} rather than to a {@link Book}, because one
 * title can be owned in several forms at once - a paperback for reading and
 * a hardback on the shelf are two copies of the same book.
 */
public enum Format {

    HARDCOVER("Hardcover"),
    PAPERBACK("Paperback"),
    EBOOK("E-book"),
    AUDIOBOOK("Audiobook"),
    OTHER("Other");

    /** How this format is shown to the user. */
    private final String label;

    /**
     * @param label the display name for this format
     */
    Format(String label) {
        this.label = label;
    }

    /** @return the display name, e.g. "E-book" */
    public String getLabel() {
        return label;
    }

    /** @return the display name */
    @Override
    public String toString() {
        return label;
    }
}
