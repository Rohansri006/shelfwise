package com.shelfwise.core.exception;

/**
 * Thrown when adding a book whose ISBN is already in the collection.
 * <p>
 * This is deliberately not treated as a plain error. Owning two copies of a
 * book is normal, so the correct response is a question rather than a
 * refusal: add this as another copy of the existing book, add it as a
 * genuinely separate entry, or cancel. The exception therefore carries the id
 * of the book already held, so the screen can show the user what it clashed
 * with and act on their answer.
 */
public class DuplicateItemException extends ShelfWiseException {

    /** The ISBN that already exists in the collection. */
    private final String isbn;

    /** Internal id of the item already holding that ISBN. */
    private final String existingItemId;

    /** Title of the existing item, for display. */
    private final String existingTitle;

    /**
     * @param isbn           the clashing ISBN
     * @param existingItemId id of the item already using it
     * @param existingTitle  title of that item, for the prompt
     */
    public DuplicateItemException(String isbn, String existingItemId, String existingTitle) {
        super("\"" + existingTitle + "\" already has ISBN " + isbn + ".");
        this.isbn = isbn;
        this.existingItemId = existingItemId;
        this.existingTitle = existingTitle;
    }

    /** @return the clashing ISBN */
    public String getIsbn() {
        return isbn;
    }

    /** @return id of the item already using that ISBN */
    public String getExistingItemId() {
        return existingItemId;
    }

    /** @return title of the item already using that ISBN */
    public String getExistingTitle() {
        return existingTitle;
    }
}
