package com.shelfwise.desktop.ui;

import com.shelfwise.core.model.Book;
import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;

/**
 * The Add page (E32): a header with the [Single | Bulk scan] switch over
 * two panes, each with its own controller ({@link BookFormController},
 * {@link BulkScanController}). This class only routes between them and
 * back to Browse; the work is done in the panes.
 * <p>
 * The two panes arrive through {@code fx:include}: for an include with
 * {@code fx:id="form"}, the loader injects its root into the field
 * {@code form} and its controller into {@code formController}.
 * <p>
 * As a {@link PageController} it overrides the window's hooks: it asks
 * before an unsaved form is left (E40), releases the camera when hidden
 * (E59), and handles Esc and Shortcut+A for the queue (E83, E88).
 */
public class AddController extends PageController {

    @FXML private Label pageTitle;
    @FXML private HBox modeSwitch;
    @FXML private ToggleButton singleToggle;
    @FXML private ToggleButton bulkToggle;
    @FXML private Parent form;
    @FXML private BookFormController formController;
    @FXML private Parent bulk;
    @FXML private BulkScanController bulkController;

    /** True while the form is showing a Bulk scan row (E37), so leaving it returns to the queue. */
    private boolean editingQueueRow = false;

    @FXML
    private void initialize() {
        ToggleGroup modes = new ToggleGroup();
        singleToggle.setToggleGroup(modes);
        bulkToggle.setToggleGroup(modes);
        singleToggle.setSelected(true);
    }

    /**
     * Passes the window and the collection on to both panes, each with its
     * own small {@code Host} - an anonymous class that tells this page what
     * happened, so the panes never need to know about each other.
     */
    @Override
    public void setUp(Shell shell, Session session) {
        super.setUp(shell, session);
        formController.setUp(shell, session, new BookFormController.Host() {
            @Override
            public void saved(Book book, boolean addAnother) {
                if (addAnother) {
                    formController.openNew();
                } else {
                    showBrowse(book);
                }
            }

            @Override
            public void cancelled() {
                leaveForm();
            }

            @Override
            public void openExisting(Book book) {
                showBrowse(book);
            }

            @Override
            public void queueDone(Book draft, Image cover) {
                bulkController.rowEdited(draft, cover);
                showBulk();
            }

            @Override
            public boolean mirrorCamera() {
                return bulkController.isMirrored();
            }
        });
        bulkController.setUp(shell, session, new BulkScanController.Host() {
            @Override
            public void editRow(Book draft, Image cover) {
                editingQueueRow = true;
                bulkController.deactivate();
                formController.openQueueRow(draft, cover);
                showPane(false);
                pageTitle.setText("Edit scanned book");
            }
        });
    }

    /**
     * Sidebar "Add" or Browse's "Add book": a blank Single form. The queue
     * keeps its rows between visits, brought up to date with the collection.
     */
    @Override
    public void shown() {
        bulkController.recheck();
        openNew();
    }

    /** E40: an unsaved form asks first. Bulk scan rows are kept, so it never asks. */
    @Override
    public boolean canLeave() {
        return !form.isVisible() || formController.confirmLeave();
    }

    /** E59: neither camera keeps running behind another page. */
    @Override
    public void hidden() {
        formController.stopScan();
        bulkController.deactivate();
    }

    /** E88: a selection in the queue clears first; otherwise Esc is Back (E40). */
    @Override
    public boolean onEscape() {
        if (bulk.isVisible() && bulkController.clearSelection()) {
            return true;
        }
        onBack();
        return true;
    }

    /** E83: Shortcut+A selects every queue row, when Bulk scan is showing. */
    @Override
    public boolean selectAll() {
        if (!bulk.isVisible()) {
            return false;
        }
        bulkController.selectAll();
        return true;
    }

    /**
     * "Edit book" in Browse's details panel: the form, with no mode switch.
     * Browse shows this page first, then calls this.
     *
     * @param book the book to edit
     */
    public void openEdit(Book book) {
        editingQueueRow = false;
        formController.openEdit(book);
        showPane(false);
        pageTitle.setText("Edit book");
        modeSwitch.setVisible(false);
    }

    private void openNew() {
        editingQueueRow = false;
        formController.openNew();
        showPane(false);
        pageTitle.setText("Add book");
        modeSwitch.setVisible(true);
    }

    /** ← Back, or Esc: the same as Cancel on the form; from Bulk scan, back to Browse. */
    @FXML
    private void onBack() {
        if (!form.isVisible()) {
            showBrowse(null);
        } else if (formController.confirmLeave()) {
            formController.stopScan();
            leaveForm();
        }
    }

    /** The form was left without saving: back to the queue it came from, or to Browse. */
    private void leaveForm() {
        if (editingQueueRow) {
            showBulk();
        } else {
            showBrowse(null);
        }
    }

    /**
     * Goes to Browse, with one book's details open if given.
     *
     * @param select the book to show, or null
     */
    private void showBrowse(Book select) {
        PageController page = shell.show(PageId.BROWSE);
        // If the user chose to stay (E40), the window hands back this page.
        if (page instanceof BrowseController) {
            ((BrowseController) page).showBook(select);
        }
    }

    @FXML
    private void onSingle() {
        singleToggle.setSelected(true);
        if (bulk.isVisible()) {
            openNew();
        }
    }

    @FXML
    private void onBulk() {
        bulkToggle.setSelected(true);
        if (form.isVisible()) {
            if (!formController.confirmLeave()) {
                singleToggle.setSelected(true);
                return;
            }
            // Bulk scan needs the camera, and only one screen may hold it.
            formController.stopScan();
            showBulk();
        }
    }

    private void showBulk() {
        editingQueueRow = false;
        showPane(true);
        pageTitle.setText("Add book");
        modeSwitch.setVisible(true);
        bulkController.activate();
    }

    private void showPane(boolean showBulk) {
        form.setVisible(!showBulk);
        bulk.setVisible(showBulk);
        singleToggle.setSelected(!showBulk);
        bulkToggle.setSelected(showBulk);
        if (!showBulk) {
            bulkController.deactivate();
        }
    }
}
