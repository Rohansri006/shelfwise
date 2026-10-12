package com.shelfwise.core.exception;

/**
 * Thrown when a string is offered as an ISBN but is not one.
 * <p>
 * An ISBN is not merely digits: the final character is a check digit computed
 * from the others, so a single mistyped digit is detectable. This exception
 * reports both the rejected value and why it failed, because "9780140303105
 * is not a valid ISBN" is far less useful than knowing the check digit was
 * wrong rather than the length.
 */
public class InvalidISBNException extends ShelfWiseException {

    /** The value that was rejected. Kept so the UI can re-display it. */
    private final String isbn;

    /** Why it was rejected, e.g. "wrong length" or "check digit mismatch". */
    private final String reason;

    /**
     * @param isbn   the value that failed validation
     * @param reason short explanation of the failure
     */
    public InvalidISBNException(String isbn, String reason) {
        super("\"" + isbn + "\" is not a valid ISBN: " + reason + ".");
        this.isbn = isbn;
        this.reason = reason;
    }

    /** @return the value that failed validation */
    public String getIsbn() {
        return isbn;
    }

    /** @return short explanation of the failure */
    public String getReason() {
        return reason;
    }
}
