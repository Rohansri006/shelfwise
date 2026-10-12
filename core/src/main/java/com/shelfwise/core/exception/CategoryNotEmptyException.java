package com.shelfwise.core.exception;

/**
 * Thrown when deleting a category that still has books filed under it.
 * <p>
 * The check covers the whole subtree, not just the category itself: deleting
 * "Finance" must fail if a book sits under "Finance &gt; Financial Markets",
 * because that book would otherwise lose its filing silently.
 * <p>
 * In the database this rule is enforced by {@code ON DELETE RESTRICT} on the
 * item/category join table, so it holds even if application code forgets to
 * check. The storage layer catches the resulting SQL error and rethrows this
 * exception, so callers always see a typed failure rather than a raw
 * {@code SQLException}.
 */
public class CategoryNotEmptyException extends ShelfWiseException {

    /** Name of the category that could not be deleted. */
    private final String categoryName;

    /** How many books are blocking the deletion, across the whole subtree. */
    private final int bookCount;

    /**
     * @param categoryName name of the category, for display
     * @param bookCount    number of books in its subtree
     */
    public CategoryNotEmptyException(String categoryName, int bookCount) {
        super("Cannot delete the category \"" + categoryName + "\": "
                + bookCount + " " + (bookCount == 1 ? "book is" : "books are")
                + " still filed under it or a sub-category.");
        this.categoryName = categoryName;
        this.bookCount = bookCount;
    }

    /** @return name of the category that could not be deleted */
    public String getCategoryName() {
        return categoryName;
    }

    /** @return number of books blocking the deletion */
    public int getBookCount() {
        return bookCount;
    }
}
