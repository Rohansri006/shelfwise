package com.shelfwise.desktop.ui;

/**
 * The base class of every page's controller (Browse, Add, Trash, ...).
 * <p>
 * The window keeps its pages as {@code PageController}s and calls these
 * methods without knowing which page it is talking to: Browse overrides
 * {@link #selectAll()} to tick every book shown, Trash to select every
 * trashed one, and a page with nothing to select simply inherits the
 * default. That is runtime polymorphism - the method that runs is chosen
 * by the object's real class, not by the variable's type.
 * <p>
 * Abstract because "a page" on its own is not a thing that can be shown:
 * every real page must at least say what happens when it is opened.
 */
public abstract class PageController {

    /** The window, for toasts, autosave and moving between pages. */
    protected Shell shell;

    /** The settings and the open collection. */
    protected Session session;

    /**
     * Called once, after the page's FXML is loaded.
     *
     * @param shell   the window
     * @param session the settings and collection
     */
    public void setUp(Shell shell, Session session) {
        this.shell = shell;
        this.session = session;
    }

    /**
     * Called every time the page is shown. The collection may have changed
     * on another page since, so each page reads it afresh here.
     */
    public abstract void shown();

    /**
     * Esc on this page. The default does nothing.
     *
     * @return true if the page used the key
     */
    public boolean onEscape() {
        return false;
    }

    /**
     * Shortcut+A on this page (E83). The default selects nothing.
     *
     * @return true if the page selected something
     */
    public boolean selectAll() {
        return false;
    }

    /**
     * Asked before the user leaves this page (E40: a form with unsaved
     * changes asks first). The default always lets them go.
     *
     * @return true if the page may be left
     */
    public boolean canLeave() {
        return true;
    }
}
