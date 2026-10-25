package com.shelfwise.desktop.ui;

import com.shelfwise.core.exception.InvalidISBNException;
import com.shelfwise.core.metadata.LookupResult;
import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Contributor;
import com.shelfwise.core.model.IsbnValidator;
import com.shelfwise.core.model.Priority;
import com.shelfwise.core.model.Wishlist;
import com.shelfwise.core.model.WishlistEntry;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.List;

/**
 * The fields of the Wishlist page's Add/Edit entry dialog (E112, E113,
 * E121, E122), with their checks. The page builds the dialog around
 * {@link #grid}; this class only knows the fields.
 * <p>
 * Every change is checked at once ({@link #validate()}), so Save is only
 * enabled when the entry can be saved. Look up runs on a background thread
 * and touches the form only through {@code Platform.runLater}.
 */
final class WishlistEntryForm {

    final GridPane grid = new GridPane();
    final TextField titleField = new TextField();
    final TextField isbnField = new TextField();
    final Button lookUpButton = new Button("Look up");
    final Label lookupStatus = new Label();
    final TextField authorField = new TextField();
    final ComboBox<Priority> priorityChoice = new ComboBox<>();
    final TextField priceField = new TextField();
    final ComboBox<String> storeChoice = new ComboBox<>();
    final CheckBox rareBox = new CheckBox("Rare (hard to find; buy when you see it)");
    final Label error = new Label();

    private final Session session;
    private final Wishlist list;
    /** The entry being edited, or null for a new one. */
    final WishlistEntry editing;

    /** Set by the page once the dialog's Save button exists. */
    Button saveButton;

    /** True once the dialog has closed, so a late lookup does not fill it. */
    boolean closed = false;

    /**
     * @param session the open collection, for the currency, owned books and the lookup
     * @param list    the list the entry is on, or will go on
     * @param editing the entry to edit, or null for a new one
     * @param stores  E121: the Store box's suggestions; the first is the default
     */
    WishlistEntryForm(Session session, Wishlist list, WishlistEntry editing, List<String> stores) {
        this.session = session;
        this.list = list;
        this.editing = editing;
        priorityChoice.getItems().addAll(Priority.HIGH, Priority.MEDIUM, Priority.LOW);
        priorityChoice.getStyleClass().add("combo");
        storeChoice.setEditable(true);
        storeChoice.getStyleClass().add("combo");
        storeChoice.getItems().addAll(stores);
        titleField.setPromptText("Required");
        isbnField.setPromptText("Optional: 10 or 13 digits");
        priceField.setPromptText("0 = not known");
        lookupStatus.getStyleClass().add("muted");
        error.getStyleClass().add("error-text");
        error.setWrapText(true);
        lookUpButton.getStyleClass().add("plain-button");
        lookUpButton.setGraphic(new FontIcon("mdi2m-magnify"));
        lookUpButton.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                lookUp();
            }
        });

        if (editing == null) {
            priorityChoice.setValue(Priority.MEDIUM);
            storeChoice.setValue(stores.get(0));
        } else {
            titleField.setText(editing.getTitle());
            isbnField.setText(editing.getIsbn() == null ? "" : editing.getIsbn());
            authorField.setText(editing.getAuthor() == null ? "" : editing.getAuthor());
            priorityChoice.setValue(editing.getPriority());
            priceField.setText(editing.getExpectedPrice() == 0 ? ""
                    : plainNumber(editing.getExpectedPrice()));
            storeChoice.setValue(editing.getStore());
            rareBox.setSelected(editing.isRare());
        }

        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPrefWidth(460);
        HBox isbnRow = new HBox(8, isbnField, lookUpButton);
        HBox.setHgrow(isbnField, javafx.scene.layout.Priority.ALWAYS);
        int r = 0;
        grid.addRow(r++, fieldLabel("ISBN"), isbnRow);
        grid.add(lookupStatus, 1, r++);
        grid.addRow(r++, fieldLabel("Title"), titleField);
        grid.addRow(r++, fieldLabel("Author"), authorField);
        grid.addRow(r++, fieldLabel("Priority"), priorityChoice);
        grid.addRow(r++, fieldLabel("Expected price (" + session.getManager().getCurrencyCode() + ")"), priceField);
        grid.addRow(r++, fieldLabel("Store"), storeChoice);
        grid.add(rareBox, 1, r++);
        grid.add(error, 0, r, 2, 1);
        storeChoice.setMaxWidth(Double.MAX_VALUE);
        priorityChoice.setMaxWidth(Double.MAX_VALUE);

        // E113: every change is checked at once, so Save is only enabled
        // when the form can be saved.
        ChangeListener<String> recheck = new ChangeListener<String>() {
            @Override
            public void changed(ObservableValue<? extends String> obs, String old, String now) {
                validate();
            }
        };
        titleField.textProperty().addListener(recheck);
        isbnField.textProperty().addListener(recheck);
        priceField.textProperty().addListener(recheck);
    }

    private Label fieldLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("field-label");
        label.setMinWidth(Label.USE_PREF_SIZE);
        return label;
    }

    /**
     * Checks every field, marks the wrong ones red, shows the first
     * problem and enables Save only when there is none.
     *
     * @return the first problem, or null if the form can be saved
     */
    String validate() {
        unmark(titleField);
        unmark(isbnField);
        unmark(priceField);
        String problem = null;
        if (!isbnField.getText().trim().isEmpty()) {
            try {
                String isbn13 = IsbnValidator.toIsbn13(isbnField.getText());
                // E127: one ISBN once per list. The core refuses it too
                // (Wishlist.addEntry); checking here lets the form say so first.
                WishlistEntry same = list.findEntryByIsbn(isbn13);
                if (same != null && same != editing) {
                    problem = "‘" + same.getTitle() + "’ is already on ‘" + list.getName() + "’.";
                    mark(isbnField);
                }
            } catch (InvalidISBNException e) {
                problem = e.getMessage();
                mark(isbnField);
            }
        }
        if (priceNumber() < 0) {
            if (problem == null) {
                problem = "Expected price must be a number, 0 or more: “" + priceField.getText().trim() + "”.";
            }
            mark(priceField);
        }
        if (titleField.getText().trim().isEmpty()) {
            // A blank title on a fresh form is not an error yet, only not ready.
            if (problem == null && editing != null) {
                problem = "The title can't be blank.";
            }
            if (editing != null) {
                mark(titleField);
            }
            if (saveButton != null) {
                saveButton.setDisable(true);
            }
            error.setText(problem == null ? "" : problem);
            return problem == null ? "A title is required." : problem;
        }
        error.setText(problem == null ? "" : problem);
        if (saveButton != null) {
            saveButton.setDisable(problem != null);
        }
        return problem;
    }

    /**
     * E127: warns before putting a book already owned on the list. Asked
     * only when the ISBN is new to this entry, so re-saving an entry
     * already tagged "Owned" does not ask every time.
     *
     * @param owner the page, to own the question's window
     * @return true to go ahead
     */
    boolean confirmOwned(Node owner) {
        String isbn13 = WishlistPicker.isbn13OrNull(isbnField.getText());
        if (isbn13 == null || (editing != null && isbn13.equals(WishlistPicker.isbn13OrNull(editing.getIsbn())))) {
            return true;
        }
        Book owned = session.getManager().findBookByIsbn(isbn13);
        if (owned == null) {
            return true;
        }
        String where = owned.isDeleted() ? " (it is in the Trash)" : "";
        return WishlistPicker.ask(owner, "You already own ‘" + owned.getTitle() + "’" + where
                + ". Add it to ‘" + list.getName() + "’ anyway?", "Add anyway", false);
    }

    /** Copies the checked fields into an entry. Call only after {@link #validate} passed. */
    void apply(WishlistEntry entry) {
        entry.setTitle(titleField.getText().trim());
        entry.setIsbn(WishlistPicker.isbn13OrNull(isbnField.getText()));
        String author = authorField.getText().trim();
        entry.setAuthor(author.isEmpty() ? null : author);
        entry.setPriority(priorityChoice.getValue());
        entry.setExpectedPrice(Math.max(0, priceNumber()));
        // E121: the core turns a blank store back into Amazon.
        entry.setStore(storeChoice.getEditor().getText() == null || storeChoice.getEditor().getText().isEmpty()
                ? storeChoice.getValue() : storeChoice.getEditor().getText());
        entry.setRare(rareBox.isSelected());
    }

    /** @return the price typed, 0 if blank, or -1 if it is not a usable number */
    private double priceNumber() {
        String text = priceField.getText().trim().replace(",", "");
        if (text.isEmpty()) {
            return 0;
        }
        try {
            double value = Double.parseDouble(text);
            return value < 0 ? -1 : value;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * E112: fills the title and author from the catalogues. Network work
     * runs off the JavaFX thread; only {@code runLater} touches the form.
     * Only empty fields are filled when editing, so nothing typed is lost.
     */
    private void lookUp() {
        final String isbn13 = WishlistPicker.isbn13OrNull(isbnField.getText());
        if (isbn13 == null) {
            lookupStatus.setText("Type a valid ISBN first.");
            return;
        }
        lookUpButton.setDisable(true);
        lookupStatus.setText("Looking up…");
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // A new lookup each time, so a Google key saved in Settings is used.
                    LookupResult result = session.newLookup().lookUp(isbn13);
                    final Book found = result.toBook(null);
                    final String from = result.describe();
                    Platform.runLater(new Runnable() {
                        @Override
                        public void run() {
                            if (!closed) {
                                filled(found, from);
                            }
                        }
                    });
                } catch (final Exception e) {
                    Platform.runLater(new Runnable() {
                        @Override
                        public void run() {
                            lookUpButton.setDisable(false);
                            lookupStatus.setText("Not found online (" + e.getMessage() + ").");
                        }
                    });
                }
            }
        }, "wishlist-lookup");
        worker.setDaemon(true);
        worker.start();
    }

    private void filled(Book found, String from) {
        lookUpButton.setDisable(false);
        lookupStatus.setText(from);
        boolean all = editing == null;
        if (all || titleField.getText().trim().isEmpty()) {
            titleField.setText(found.getTitle());
        }
        Contributor first = found.getPrimaryAuthor();
        if (first != null && (all || authorField.getText().trim().isEmpty())) {
            authorField.setText(first.getName());
        }
    }

    private static void mark(Node field) {
        if (!field.getStyleClass().contains("field-error")) {
            field.getStyleClass().add("field-error");
        }
    }

    private static void unmark(Node field) {
        field.getStyleClass().remove("field-error");
    }

    /** "399" rather than "399.0" in the price field. */
    private static String plainNumber(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
