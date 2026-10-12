package com.shelfwise.core.model;

/**
 * How much the user wants a book that is on a wishlist.
 * <p>
 * Declared from lowest to highest so that, as with {@link Condition},
 * natural enum ordering sorts sensibly.
 */
public enum Priority {

    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High");

    /** How this priority is shown to the user. */
    private final String label;

    /**
     * @param label the display name for this priority
     */
    Priority(String label) {
        this.label = label;
    }

    /** @return the display name, e.g. "Medium" */
    public String getLabel() {
        return label;
    }

    /** @return the display name */
    @Override
    public String toString() {
        return label;
    }
}
