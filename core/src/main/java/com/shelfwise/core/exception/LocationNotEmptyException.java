package com.shelfwise.core.exception;

/**
 * Thrown when deleting a location that still holds copies of books.
 * <p>
 * Like {@link CategoryNotEmptyException} this covers the whole subtree:
 * deleting a shelf must fail while any of its rows still holds a book,
 * otherwise those copies would be left pointing at a location that no longer
 * exists.
 * <p>
 * Enforced in the database by {@code ON DELETE RESTRICT} on the copy's
 * location foreign key.
 */
public class LocationNotEmptyException extends ShelfWiseException {

    /** Name of the location that could not be deleted. */
    private final String locationName;

    /** How many copies are blocking the deletion, across the whole subtree. */
    private final int copyCount;

    /**
     * @param locationName name of the location, for display
     * @param copyCount    number of copies stored in its subtree
     */
    public LocationNotEmptyException(String locationName, int copyCount) {
        super("Cannot delete the location \"" + locationName + "\": "
                + copyCount + " " + (copyCount == 1 ? "copy is" : "copies are")
                + " still stored there or in a sub-location.");
        this.locationName = locationName;
        this.copyCount = copyCount;
    }

    /** @return name of the location that could not be deleted */
    public String getLocationName() {
        return locationName;
    }

    /** @return number of copies blocking the deletion */
    public int getCopyCount() {
        return copyCount;
    }
}
