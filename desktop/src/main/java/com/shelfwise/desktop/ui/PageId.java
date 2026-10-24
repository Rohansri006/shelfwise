package com.shelfwise.desktop.ui;

/**
 * The window's pages, one per sidebar item (E49). Screens name the page
 * they want to go to ("Show in Browse") with one of these, so they never
 * need a reference to another screen's controller.
 */
public enum PageId {
    BROWSE("Browse"),
    ADD("Add"),
    ORGANISE("Organise"),
    WISHLIST("Wishlist"),
    STATS("Stats"),
    TRASH("Trash"),
    SETTINGS("Settings");

    private final String label;

    PageId(String label) {
        this.label = label;
    }

    /** @return the name shown in the sidebar */
    public String getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
