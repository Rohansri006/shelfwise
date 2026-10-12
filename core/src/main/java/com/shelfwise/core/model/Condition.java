package com.shelfwise.core.model;

/**
 * What state a particular copy is in.
 * <p>
 * Deliberately a small, ordered scale rather than free text, so copies can
 * be compared and filtered. The order of the constants is meaningful: they
 * run from best to worst, which means {@link #compareTo} and
 * {@link #ordinal()} already sort correctly without extra code.
 */
public enum Condition {

    NEW("New"),
    LIKE_NEW("Like new"),
    GOOD("Good"),
    FAIR("Fair"),
    POOR("Poor");

    /** How this condition is shown to the user. */
    private final String label;

    /**
     * @param label the display name for this condition
     */
    Condition(String label) {
        this.label = label;
    }

    /** @return the display name, e.g. "Like new" */
    public String getLabel() {
        return label;
    }

    /**
     * Whether this condition is better than another.
     * <p>
     * Relies on declaration order: NEW is declared first, so it has the
     * lowest ordinal, so a lower ordinal means a better condition. This is
     * a small method, but it is the reason the constants are declared in
     * order rather than alphabetically.
     *
     * @param other the condition to compare against
     * @return true if this condition is better than {@code other}
     */
    public boolean isBetterThan(Condition other) {
        return this.ordinal() < other.ordinal();
    }

    /** @return the display name */
    @Override
    public String toString() {
        return label;
    }
}
