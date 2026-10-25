package com.shelfwise.desktop.ui;

import javafx.stage.Window;

/**
 * What a page may ask of the main window. Pages see only this interface,
 * not {@link MainController} itself, so a page cannot reach into the
 * sidebar or another page: everything it needs from outside goes through
 * these few methods (the demos' {@code Host} pattern, E157).
 */
public interface Shell {

    /** E47: a short success message, gone after 3 seconds. */
    void toast(String message);

    /**
     * A toast with one action (Undo, Show in Browse), shown for 6 seconds.
     * A newer toast replaces it, so Undo only ever undoes the latest action.
     */
    void toast(String message, String action, Runnable onAction);

    /** A toast with two actions (E123: "Show in Browse" and "Undo all"). */
    void toast(String message, String action, Runnable onAction, String action2, Runnable onAction2);

    /**
     * Autosave (ADR 0001): called by a page after every logical operation.
     * Saves the collection, handles a conflict or failure with a dialog,
     * and refreshes the sidebar's counts.
     *
     * @return true if the collection was saved
     */
    boolean saveChanges();

    /**
     * The whole collection was read again from its file (a restore, an
     * import's Undo, "Keep theirs"): refreshes the sidebar's counts and
     * shows the current page afresh, since it holds objects of the old
     * collection.
     */
    void collectionReplaced();

    /**
     * Switches to a page, as if its sidebar item were clicked.
     *
     * @return the page's controller, so the caller can tell it what to show
     */
    PageController show(PageId page);

    /**
     * Switches to a page but lights a different sidebar item. E33: "Edit
     * book" opens the Add page's form while Browse stays lit, because the
     * user is still working on a book from Browse.
     *
     * @param page the page to show
     * @param lit  the sidebar item to light
     * @return the page's controller
     */
    PageController show(PageId page, PageId lit);

    /** @return the window, as the owner for dialogs and file choosers */
    Window getWindow();

    /** @return true while the dark theme is showing */
    boolean isDark();

    /** Switches between the dark and light theme and remembers the choice. */
    void setDark(boolean dark);
}
