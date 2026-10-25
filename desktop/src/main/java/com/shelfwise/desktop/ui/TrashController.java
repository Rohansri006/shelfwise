package com.shelfwise.desktop.ui;

import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.CollectionItem;
import com.shelfwise.core.model.Contributor;
import com.shelfwise.core.model.IsbnValidator;
import com.shelfwise.core.service.CollectionManager;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.util.Callback;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The Trash page (E42, E73-E81, E165).
 * <p>
 * One table of trashed books with multi-select. <b>Restore</b> brings the
 * selected books back; <b>Delete permanently</b> and <b>Empty Trash</b>
 * remove books for good, so only those two ask first (E78) - moving a book
 * <em>into</em> the Trash is undone from a toast instead (E74).
 * <p>
 * Every action is saved straight after it (ADR 0001); the save also
 * refreshes the sidebar's Trash badge.
 */
public class TrashController extends PageController {

    @FXML private TextField searchField;
    @FXML private Button restoreButton;
    @FXML private Button deleteButton;
    @FXML private Button emptyButton;
    @FXML private Label summary;
    @FXML private TableView<Book> table;

    /** The rows on screen: the trashed books that match the search. */
    private final ObservableList<Book> rows = FXCollections.observableArrayList();

    /** How many books are in the Trash in total, search or not. */
    private int trashSize = 0;

    @FXML
    private void initialize() {
        setUpTable();
        // E77: the list filters as the user types.
        searchField.textProperty().addListener(new ChangeListener<String>() {
            @Override
            public void changed(ObservableValue<? extends String> obs, String old, String now) {
                reload();
            }
        });
        // Restore and Delete act on the selection, so they wait for one.
        table.getSelectionModel().getSelectedItems().addListener(new ListChangeListener<Book>() {
            @Override
            public void onChanged(Change<? extends Book> change) {
                updateButtons();
            }
        });
    }

    /**
     * The Trash is read afresh: Browse may have added to it, and after
     * "Keep theirs" every row would be an object of the old collection, so
     * the selection is dropped too.
     */
    @Override
    public void shown() {
        table.getSelectionModel().clearSelection();
        searchField.clear();
        reload();
    }

    /** E83/E84: every row shown, so a search narrows what "all" means. */
    @Override
    public boolean selectAll() {
        table.getSelectionModel().selectAll();
        return true;
    }

    /** E88: Esc clears the selection. */
    @Override
    public boolean onEscape() {
        table.getSelectionModel().clearSelection();
        return true;
    }

    /** @return the open collection; asked each time, because "Keep theirs" replaces it */
    private CollectionManager manager() {
        return session.getManager();
    }

    // ------------------------------------------------------------------
    // The table
    // ------------------------------------------------------------------

    /**
     * Builds the four columns. Each column's value is extracted once per
     * row by a cell value factory; the table sorts by that value when its
     * header is clicked (E77), so sorting needs no code of its own.
     */
    private void setUpTable() {
        table.setItems(rows);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<Book, Book> cover = new TableColumn<>("");
        cover.setPrefWidth(52);
        cover.setMinWidth(52);
        cover.setMaxWidth(52);
        cover.setSortable(false);
        cover.setCellValueFactory(new Callback<TableColumn.CellDataFeatures<Book, Book>, ObservableValue<Book>>() {
            @Override
            public ObservableValue<Book> call(TableColumn.CellDataFeatures<Book, Book> cell) {
                return new ReadOnlyObjectWrapper<>(cell.getValue());
            }
        });
        cover.setCellFactory(new Callback<TableColumn<Book, Book>, TableCell<Book, Book>>() {
            @Override
            public TableCell<Book, Book> call(TableColumn<Book, Book> column) {
                return new TableCell<Book, Book>() {
                    @Override
                    protected void updateItem(Book book, boolean empty) {
                        super.updateItem(book, empty);
                        setGraphic(empty || book == null ? null
                                : CoverArt.coverFor(book, session.getDataFolder(), 38, 54, false));
                    }
                };
            }
        });

        TableColumn<Book, String> title = new TableColumn<>("Title");
        title.setPrefWidth(320);
        title.setCellValueFactory(new Callback<TableColumn.CellDataFeatures<Book, String>, ObservableValue<String>>() {
            @Override
            public ObservableValue<String> call(TableColumn.CellDataFeatures<Book, String> cell) {
                return new ReadOnlyObjectWrapper<>(cell.getValue().getTitle());
            }
        });

        TableColumn<Book, String> author = new TableColumn<>("Author");
        author.setPrefWidth(220);
        author.setCellValueFactory(new Callback<TableColumn.CellDataFeatures<Book, String>, ObservableValue<String>>() {
            @Override
            public ObservableValue<String> call(TableColumn.CellDataFeatures<Book, String> cell) {
                Contributor first = cell.getValue().getPrimaryAuthor();
                return new ReadOnlyObjectWrapper<>(first == null ? "" : first.getName());
            }
        });

        // Integer, not String, so 10 sorts after 9 rather than after 1.
        TableColumn<Book, Integer> copies = new TableColumn<>("Copies");
        copies.setPrefWidth(90);
        copies.setCellValueFactory(new Callback<TableColumn.CellDataFeatures<Book, Integer>, ObservableValue<Integer>>() {
            @Override
            public ObservableValue<Integer> call(TableColumn.CellDataFeatures<Book, Integer> cell) {
                return new ReadOnlyObjectWrapper<>(cell.getValue().getCopyCount());
            }
        });

        // A column's style applies to its cells: text centred vertically, level
        // with the middle of the cover rather than its top edge.
        title.setStyle("-fx-alignment: CENTER-LEFT;");
        author.setStyle("-fx-alignment: CENTER-LEFT;");
        copies.setStyle("-fx-alignment: CENTER-LEFT;");
        table.getColumns().add(cover);
        table.getColumns().add(title);
        table.getColumns().add(author);
        table.getColumns().add(copies);
    }

    /**
     * Rebuilds the rows from the Trash and the search text. Called after
     * every change, so the table never shows a book that has left the Trash.
     */
    private void reload() {
        List<CollectionItem> trash = manager().getTrash();
        trashSize = trash.size();
        String query = searchField.getText().trim();
        List<Book> matching = new ArrayList<>();
        for (int i = 0; i < trash.size(); i++) {
            Book book = (Book) trash.get(i);
            if (matches(book, query)) {
                matching.add(book);
            }
        }
        rows.setAll(matching);
        // setAll replaces the list's contents, which drops the table's own
        // ordering; sort() applies the column the user last clicked again.
        table.sort();
        table.setPlaceholder(query.isEmpty() ? emptyState()
                : new Label("No book in the Trash matches “" + query + "”."));
        summary.setText(trashSize == 0 ? ""
                : trashSize == 1 ? "1 book in the Trash" : trashSize + " books in the Trash");
        updateButtons();
    }

    /**
     * E77: a book matches when the query appears in its title, in any
     * contributor's name, or in its ISBN. Matched here, not by the core's
     * {@code search}, because that only looks at live books.
     */
    static boolean matches(Book book, String query) {
        if (query.isEmpty()) {
            return true;
        }
        String q = query.toLowerCase(Locale.ROOT);
        if (book.getTitle().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        for (int i = 0; i < book.getContributors().size(); i++) {
            if (book.getContributors().get(i).getName().toLowerCase(Locale.ROOT).contains(q)) {
                return true;
            }
        }
        // ISBNs are stored without hyphens, so the query loses its hyphens too.
        String digits = IsbnValidator.normalise(query);
        return book.getIsbn() != null && !digits.isEmpty() && book.getIsbn().contains(digits);
    }

    /** E79: what an empty Trash shows instead of an empty table. */
    private Node emptyState() {
        FontIcon icon = new FontIcon("mdi2d-delete-empty-outline");
        icon.setIconSize(48);
        icon.getStyleClass().add("empty-icon");
        Label heading = new Label("The Trash is empty");
        heading.getStyleClass().add("card-title");
        Label line = new Label("Books you remove from the collection wait here until you restore them"
                + " or delete them for good.");
        line.getStyleClass().add("muted");
        line.setWrapText(true);
        line.setTextAlignment(TextAlignment.CENTER);
        line.setMaxWidth(340);
        VBox box = new VBox(10, icon, heading, line);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private void updateButtons() {
        boolean none = table.getSelectionModel().getSelectedItems().isEmpty();
        restoreButton.setDisable(none);
        deleteButton.setDisable(none);
        emptyButton.setDisable(trashSize == 0);
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /** E76: restores stay on the Trash; the toast offers the way to Browse. */
    @FXML
    private void onRestore() {
        final List<Book> books = selectedBooks();
        for (int i = 0; i < books.size(); i++) {
            manager().restoreFromTrash(books.get(i).getId());
        }
        shell.saveChanges();
        reload();
        String message = books.size() == 1 ? "Restored “" + books.get(0).getTitle() + "”"
                : books.size() + " books restored";
        shell.toast(message, "Show in Browse", new Runnable() {
            @Override
            public void run() {
                BrowseController browse = (BrowseController) shell.show(PageId.BROWSE);
                // One book opens in the details panel; several just show Browse.
                if (books.size() == 1) {
                    browse.showBook(books.get(0));
                }
            }
        });
    }

    @FXML
    private void onDeletePermanently() {
        List<Book> books = selectedBooks();
        if (!confirm(deleteMessage(books), "Delete for good")) {
            return;
        }
        for (int i = 0; i < books.size(); i++) {
            manager().deletePermanently(books.get(i).getId());
        }
        deleted(books, books.size() == 1 ? "“" + books.get(0).getTitle() + "” deleted for good"
                : books.size() + " books deleted for good");
    }

    @FXML
    private void onEmptyTrash() {
        if (!confirm(emptyMessage(trashSize), "Empty Trash")) {
            return;
        }
        // Taken before emptying: the covers to delete belong to these books.
        List<Book> books = new ArrayList<>();
        List<CollectionItem> trash = manager().getTrash();
        for (int i = 0; i < trash.size(); i++) {
            books.add((Book) trash.get(i));
        }
        int gone = manager().emptyTrash();
        deleted(books, gone == 1 ? "1 book deleted for good" : gone + " books deleted for good");
    }

    /**
     * After books are deleted for good: save, then delete their uploaded
     * covers (E165). The covers go only once the save has worked, so a
     * failed save never leaves a book whose cover is already gone.
     */
    void deleted(List<Book> books, String message) {
        boolean saved = shell.saveChanges();
        reload();
        if (!saved) {
            // The window has already said why; nothing else to report.
            return;
        }
        int stuck = 0;
        for (int i = 0; i < books.size(); i++) {
            try {
                CoverArt.removeUpload(books.get(i), session.getDataFolder());
            } catch (IOException e) {
                // The delete still stands; the leftover file is only untidy.
                stuck++;
            }
        }
        shell.toast(stuck == 0 ? message
                : message + " · " + stuck + (stuck == 1 ? " cover file" : " cover files") + " could not be removed");
    }

    /**
     * A copy of the selection. The live selection list changes while the
     * books are restored or deleted (each one leaves the table), so looping
     * over it directly would skip books.
     */
    private List<Book> selectedBooks() {
        return new ArrayList<>(table.getSelectionModel().getSelectedItems());
    }

    /** E78: names the book, or the count, and says it cannot be undone. */
    static String deleteMessage(List<Book> books) {
        String what = books.size() == 1 ? "“" + books.get(0).getTitle() + "”" : books.size() + " books";
        return "Delete " + what + " for good? This can't be undone.";
    }

    /** E78: the count of everything in the Trash, with singular wording for one. */
    static String emptyMessage(int count) {
        String what = count == 1 ? "the 1 book" : "all " + count + " books";
        return "Delete " + what + " in the Trash for good? This can't be undone.";
    }

    /**
     * Asks before an irreversible action. The action button is red (E78):
     * {@code -fx-base} is the colour Modena, JavaFX's built-in theme,
     * derives a button's fill, border and text colour from, so one setting
     * gives a correctly shaded red button with readable text.
     *
     * @return true if the user pressed the action button
     */
    private boolean confirm(String message, String action) {
        ButtonType go = new ButtonType(action);
        Alert alert = new Alert(Alert.AlertType.WARNING, message, ButtonType.CANCEL, go);
        alert.setHeaderText(null);
        alert.initOwner(shell.getWindow());
        alert.getDialogPane().lookupButton(go).setStyle("-fx-base: #dc2626;");
        Optional<ButtonType> answer = alert.showAndWait();
        return answer.isPresent() && answer.get() == go;
    }
}
