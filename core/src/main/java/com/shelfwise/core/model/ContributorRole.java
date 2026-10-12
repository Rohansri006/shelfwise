package com.shelfwise.core.model;

/**
 * The part a person played in making a book.
 * <p>
 * Most books only ever need {@link #AUTHOR}, which is why it is the default.
 * The others exist because they change what a credit means: a translated
 * novel has both an author and a translator, and treating the translator as
 * a second author would be wrong.
 * <p>
 * Each constant carries a human-readable label rather than relying on its
 * own name, because {@code LIKE_NEW}-style names are not what a user should
 * ever see on screen.
 */
public enum ContributorRole {

    AUTHOR("Author"),
    TRANSLATOR("Translator"),
    EDITOR("Editor"),
    ILLUSTRATOR("Illustrator");

    /** How this role is shown to the user. */
    private final String label;

    /**
     * Enum constructors are implicitly private: the fixed set of constants
     * above is the only way instances ever come into existence.
     *
     * @param label the display name for this role
     */
    ContributorRole(String label) {
        this.label = label;
    }

    /** @return the display name, e.g. "Illustrator" */
    public String getLabel() {
        return label;
    }

    /**
     * Returns the label, so putting a role straight into a combo box or a
     * text template produces readable output.
     *
     * @return the display name
     */
    @Override
    public String toString() {
        return label;
    }
}
