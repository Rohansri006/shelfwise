package com.shelfwise.desktop.ui;

import com.shelfwise.core.exception.DuplicateItemException;
import com.shelfwise.core.exception.InvalidISBNException;
import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Copy;
import com.shelfwise.core.model.Priority;
import com.shelfwise.core.model.Wishlist;
import com.shelfwise.core.model.WishlistEntry;
import com.shelfwise.core.service.CollectionManager;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.text.TextAlignment;
import javafx.util.Callback;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The Wishlist page (E43, E111-E127).
 * <p>
 * Wishlists on the left, the chosen list's entries on the right. An entry
 * is added and edited in a dialog (E112); <b>Mark as bought</b> turns
 * entries into books through {@code CollectionManager.markAsBought}, which
 * refuses a book already owned <em>before</em> it removes the entry, so the
 * entry is never lost (E114, E116).
 * <p>
 * Every delete asks first and still offers Undo afterwards (E118). Undo
 * works because each action remembers exactly what it changed - which
 * entry, at which position, on which list - and puts it back.
 * <p>
 * Every change is saved straight after it, and every Undo saves again
 * (ADR 0001, E153). The Add/Edit entry dialog is {@link WishlistEntryForm};
 * helpers shared with the Add page are in {@link WishlistPicker}.
 */
public class WishlistController extends PageController {

    /** E123 keys. Shortcut is Cmd on the Mac, Ctrl elsewhere (as in E82). */
    private static final KeyCombination NEW_ENTRY =
            new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination NEW_LIST =
            new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);

    /** E126: the list made when there would otherwise be none. */
    static final String DEFAULT_LIST = "To buy";

    /** E121: offered in the Store box, before the stores already used. */
    private static final String[] STORES = {"Amazon", "Flipkart", "Crossword"};

    @FXML private VBox page;
    @FXML private Button addEntryButton;
    @FXML private Button editButton;
    @FXML private Button boughtButton;
    @FXML private Button removeButton;
    @FXML private HBox banner;
    @FXML private Label bannerText;
    @FXML private HBox bannerActions;
    @FXML private ListView<Wishlist> lists;
    @FXML private Button newListButton;
    @FXML private Button renameListButton;
    @FXML private Button deleteListButton;
    @FXML private TableView<WishlistEntry> table;
    @FXML private Label footer;

    /** The rows of the left list: the collection's wishlists, plus a pending new one. */
    private final ObservableList<Wishlist> listRows = FXCollections.observableArrayList();

    /** The rows of the table: the current list's entries. */
    private final ObservableList<WishlistEntry> rows = FXCollections.observableArrayList();

    /** The list whose entries are showing. Kept while a new list's name is typed. */
    private Wishlist current;

    /**
     * E124: the row being typed into for a new list. It is a real
     * {@code Wishlist} object (a cell needs an item) but is not in the
     * collection until its name is accepted.
     */
    private Wishlist pending;

    /** True while a finished edit is being closed, so closing it is not treated as Esc. */
    private boolean committing = false;

    @FXML
    private void initialize() {
        setUpLists();
        setUpTable();
        setUpKeys();
        newListButton.setTooltip(new Tooltip("New list (Shift+Shortcut+N)"));
        renameListButton.setTooltip(new Tooltip("Rename list (F2)"));
        deleteListButton.setTooltip(new Tooltip("Delete list"));
    }

    /**
     * The page is shown: read the lists afresh, since the Add page may have
     * added entries (E127). The chosen list stays as the user left it (E126),
     * unless "Keep theirs" or a restore replaced the whole collection.
     */
    @Override
    public void shown() {
        if (ensureAList() != null) {
            // Making "To buy" is a change of its own, so it is saved (ADR 0001).
            shell.saveChanges();
        }
        hideBanner();
        refreshLists();
    }

    /** @return the open collection; asked each time, because "Keep theirs" replaces it */
    private CollectionManager manager() {
        return session.getManager();
    }

    /** E126: there is always a list. Called on every visit, e.g. after an import emptied them. */
    private Wishlist ensureAList() {
        if (!manager().getWishlists().isEmpty()) {
            return null;
        }
        Wishlist fresh = new Wishlist(DEFAULT_LIST);
        manager().addWishlist(fresh);
        return fresh;
    }

    // ------------------------------------------------------------------
    // The lists (left)
    // ------------------------------------------------------------------

    private void setUpLists() {
        lists.setItems(listRows);
        // Editable so a cell can switch into its text field (E124).
        lists.setEditable(true);
        lists.setCellFactory(new Callback<ListView<Wishlist>, ListCell<Wishlist>>() {
            @Override
            public ListCell<Wishlist> call(ListView<Wishlist> view) {
                return new ListNameCell();
            }
        });
        lists.getSelectionModel().selectedItemProperty().addListener(new ChangeListener<Wishlist>() {
            @Override
            public void changed(ObservableValue<? extends Wishlist> obs, Wishlist old, Wishlist now) {
                // The pending row is not a list yet: keep showing the last real one.
                if (now != null && now != pending) {
                    current = now;
                    hideBanner();
                    refreshEntries();
                }
                updateButtons();
            }
        });

        MenuItem rename = menuItem("Rename", new Runnable() {
            @Override public void run() { onRenameList(); }
        });
        MenuItem delete = menuItem("Delete", new Runnable() {
            @Override public void run() { onDeleteList(); }
        });
        MenuItem create = menuItem("New list", new Runnable() {
            @Override public void run() { onNewList(); }
        });
        lists.setContextMenu(new ContextMenu(rename, delete, new SeparatorMenuItem(), create));
    }

    /**
     * Rebuilds the left list from the collection, keeping the chosen list
     * selected - or, if it was deleted, the first one.
     */
    private void refreshLists() {
        List<Wishlist> all = manager().getWishlists();
        listRows.setAll(all);
        if (pending != null) {
            listRows.add(pending);
        }
        // indexOf compares ids (Wishlist.equals), so after "Keep theirs" or a
        // restore it finds the new object for the same list; taking that one
        // means the table never shows the old collection's entries.
        int at = current == null ? -1 : all.indexOf(current);
        current = at >= 0 ? all.get(at) : all.isEmpty() ? null : all.get(0);
        lists.getSelectionModel().select(current);
        // A list's count changes without the list object changing, so the
        // cells must be told to redraw.
        lists.refresh();
        refreshEntries();
    }

    /** "To buy (2)": the name and how many entries it holds. */
    private static String listLabel(Wishlist list) {
        return list.getName() + " (" + list.getEntryCount() + ")";
    }

    @FXML
    private void onNewList() {
        if (pending != null) {
            return;
        }
        hideBanner();
        pending = new Wishlist("New wishlist");
        listRows.add(pending);
        lists.getSelectionModel().select(pending);
        // The row's cell is only made at the next layout pass; editing needs it.
        lists.layout();
        lists.edit(listRows.indexOf(pending));
    }

    @FXML
    private void onRenameList() {
        if (current == null) {
            return;
        }
        hideBanner();
        lists.getSelectionModel().select(current);
        lists.layout();
        lists.edit(listRows.indexOf(current));
    }

    /**
     * E124: Enter in a list name. The manager decides whether the name is
     * acceptable (not blank, not taken ignoring case); a refusal leaves the
     * field open with a red border and the reason in the banner.
     *
     * @return true if the name was accepted
     */
    private boolean commitName(Wishlist item, TextField field) {
        Wishlist done;
        try {
            if (item == pending) {
                done = new Wishlist(field.getText());
                manager().addWishlist(done);
            } else {
                manager().renameWishlist(item, field.getText());
                done = item;
            }
        } catch (IllegalArgumentException e) {
            if (!field.getStyleClass().contains("field-error")) {
                field.getStyleClass().add("field-error");
            }
            showBanner("error", e.getMessage());
            return false;
        }
        committing = true;
        lists.edit(-1);
        committing = false;
        pending = null;
        current = done;
        hideBanner();
        refreshLists();
        lists.requestFocus();
        shell.saveChanges();
        return true;
    }

    /** An edit ended without Enter (Esc, or a click elsewhere): drop a pending new list. */
    private void editCancelled() {
        if (committing) {
            return;
        }
        hideBanner();
        if (pending != null) {
            pending = null;
            refreshLists();
        }
    }

    /**
     * E118/E126: asks, deletes the list and its entries (composition: they
     * have nowhere else to go), and makes an empty "To buy" if that was the
     * last list. Undo puts the list back where it was.
     */
    @FXML
    private void onDeleteList() {
        final Wishlist list = current;
        if (list == null) {
            return;
        }
        hideBanner();
        String message = list.isEmpty() ? "Delete the wishlist ‘" + list.getName() + "’?"
                : "Delete ‘" + list.getName() + "’ and its "
                + WishlistPicker.count(list.getEntryCount(), "entry", "entries") + "?";
        if (!WishlistPicker.ask(page, message, "Delete", true)) {
            return;
        }
        final int index = manager().getWishlists().indexOf(list);
        manager().removeWishlist(list);
        final Wishlist fresh = ensureAList();
        List<Wishlist> left = manager().getWishlists();
        current = left.get(Math.min(index, left.size() - 1));
        refreshLists();
        shell.saveChanges();
        String toast = "Deleted ‘" + list.getName() + "’"
                + (fresh == null ? "" : "  ·  new empty ‘" + DEFAULT_LIST + "’ made");
        shell.toast(toast, "Undo", new Runnable() {
            @Override
            public void run() {
                // The stand-in goes again, unless it has been used meanwhile.
                if (fresh != null && fresh.isEmpty() && manager().getWishlists().contains(fresh)) {
                    manager().removeWishlist(fresh);
                }
                try {
                    WishlistPicker.insertList(manager(), list,
                            Math.min(index, manager().getWishlists().size()));
                } catch (IllegalArgumentException e) {
                    // E.g. another list has taken its name since.
                    shell.toast("Couldn't undo: " + e.getMessage());
                    return;
                }
                current = list;
                refreshLists();
                // E153: Undo changes memory, then saves again.
                shell.saveChanges();
                shell.toast("‘" + list.getName() + "’ is back");
            }
        });
    }

    /**
     * A list row that can turn into a text field. The ListView calls
     * {@code startEdit} and {@code cancelEdit} (from {@code lists.edit});
     * Enter goes to {@link #commitName} rather than the cell's own
     * {@code commitEdit}, because the manager checks the name, and a new
     * list does not exist until its name is accepted. The same design as
     * the Organise page's tree cells (E93, E94).
     */
    private final class ListNameCell extends ListCell<Wishlist> {

        private TextField field;

        @Override
        public void startEdit() {
            super.startEdit();
            if (!isEditing()) {
                return;
            }
            final Wishlist item = getItem();
            field = new TextField(item.getName());
            field.setOnAction(new EventHandler<ActionEvent>() {
                @Override
                public void handle(ActionEvent event) {
                    commitName(item, field);
                }
            });
            field.addEventHandler(KeyEvent.KEY_PRESSED, new EventHandler<KeyEvent>() {
                @Override
                public void handle(KeyEvent event) {
                    if (event.getCode() == KeyCode.ESCAPE) {
                        // Consumed so the window's own Esc handling does not also run.
                        event.consume();
                        cancelEdit();
                    }
                }
            });
            setText(null);
            setGraphic(field);
            field.selectAll();
            field.requestFocus();
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            setGraphic(null);
            setText(getItem() == null ? null : listLabel(getItem()));
            editCancelled();
            lists.requestFocus();
        }

        @Override
        protected void updateItem(Wishlist item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
            } else if (isEditing()) {
                setText(null);
                setGraphic(field);
            } else {
                setText(listLabel(item));
                setGraphic(null);
            }
        }
    }

    // ------------------------------------------------------------------
    // The entries (right)
    // ------------------------------------------------------------------

    /**
     * Builds the five columns. Priority and Title hold values the table can
     * sort by; their cells draw a dot and a title with badges. The default
     * order is High priority first, then title A to Z (E119); clicking a
     * header sorts by that column instead.
     */
    private void setUpTable() {
        table.setItems(rows);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        // E119: the enum's own order is LOW, MEDIUM, HIGH, so descending puts High first.
        TableColumn<WishlistEntry, Priority> priority = new TableColumn<>("Priority");
        priority.setPrefWidth(80);
        priority.setMaxWidth(90);
        priority.setSortType(TableColumn.SortType.DESCENDING);
        priority.setCellValueFactory(new Callback<TableColumn.CellDataFeatures<WishlistEntry, Priority>,
                ObservableValue<Priority>>() {
            @Override
            public ObservableValue<Priority> call(TableColumn.CellDataFeatures<WishlistEntry, Priority> cell) {
                return new ReadOnlyObjectWrapper<>(cell.getValue().getPriority());
            }
        });
        priority.setCellFactory(new Callback<TableColumn<WishlistEntry, Priority>, TableCell<WishlistEntry, Priority>>() {
            @Override
            public TableCell<WishlistEntry, Priority> call(TableColumn<WishlistEntry, Priority> column) {
                return new TableCell<WishlistEntry, Priority>() {
                    @Override
                    protected void updateItem(Priority value, boolean empty) {
                        super.updateItem(value, empty);
                        if (empty || value == null) {
                            setGraphic(null);
                            setTooltip(null);
                        } else {
                            setGraphic(priorityDot(value));
                            setTooltip(new Tooltip(value.getLabel() + " priority"));
                        }
                    }
                };
            }
        });

        // The whole entry is the value, so the cell can draw its badges; the
        // comparator sorts by title, ignoring case.
        TableColumn<WishlistEntry, WishlistEntry> title = new TableColumn<>("Title");
        title.setPrefWidth(320);
        title.setCellValueFactory(new Callback<TableColumn.CellDataFeatures<WishlistEntry, WishlistEntry>,
                ObservableValue<WishlistEntry>>() {
            @Override
            public ObservableValue<WishlistEntry> call(TableColumn.CellDataFeatures<WishlistEntry, WishlistEntry> cell) {
                return new ReadOnlyObjectWrapper<>(cell.getValue());
            }
        });
        title.setComparator(new Comparator<WishlistEntry>() {
            @Override
            public int compare(WishlistEntry a, WishlistEntry b) {
                return a.getTitle().compareToIgnoreCase(b.getTitle());
            }
        });
        title.setCellFactory(new Callback<TableColumn<WishlistEntry, WishlistEntry>,
                TableCell<WishlistEntry, WishlistEntry>>() {
            @Override
            public TableCell<WishlistEntry, WishlistEntry> call(TableColumn<WishlistEntry, WishlistEntry> column) {
                return new TableCell<WishlistEntry, WishlistEntry>() {
                    @Override
                    protected void updateItem(WishlistEntry entry, boolean empty) {
                        super.updateItem(entry, empty);
                        setText(null);
                        setGraphic(empty || entry == null ? null : titleCell(entry));
                    }
                };
            }
        });

        TableColumn<WishlistEntry, String> author = new TableColumn<>("Author");
        author.setPrefWidth(200);
        author.setCellValueFactory(new Callback<TableColumn.CellDataFeatures<WishlistEntry, String>,
                ObservableValue<String>>() {
            @Override
            public ObservableValue<String> call(TableColumn.CellDataFeatures<WishlistEntry, String> cell) {
                String name = cell.getValue().getAuthor();
                return new ReadOnlyObjectWrapper<>(name == null ? "" : name);
            }
        });

        // Double, not String, so ₹1,000 sorts after ₹999. Unknown (0) shows "—" (E120).
        TableColumn<WishlistEntry, Double> price = new TableColumn<>("Expected price");
        price.setPrefWidth(130);
        price.setCellValueFactory(new Callback<TableColumn.CellDataFeatures<WishlistEntry, Double>,
                ObservableValue<Double>>() {
            @Override
            public ObservableValue<Double> call(TableColumn.CellDataFeatures<WishlistEntry, Double> cell) {
                return new ReadOnlyObjectWrapper<>(cell.getValue().getExpectedPrice());
            }
        });
        price.setCellFactory(new Callback<TableColumn<WishlistEntry, Double>, TableCell<WishlistEntry, Double>>() {
            @Override
            public TableCell<WishlistEntry, Double> call(TableColumn<WishlistEntry, Double> column) {
                return new TableCell<WishlistEntry, Double>() {
                    @Override
                    protected void updateItem(Double value, boolean empty) {
                        super.updateItem(value, empty);
                        if (empty || value == null) {
                            setText(null);
                        } else {
                            setText(value == 0 ? "—" : BrowseController.formatMoney(manager().getCurrencyCode(), value));
                        }
                    }
                };
            }
        });

        TableColumn<WishlistEntry, String> store = new TableColumn<>("Store");
        store.setPrefWidth(180);
        store.setCellValueFactory(new Callback<TableColumn.CellDataFeatures<WishlistEntry, String>,
                ObservableValue<String>>() {
            @Override
            public ObservableValue<String> call(TableColumn.CellDataFeatures<WishlistEntry, String> cell) {
                return new ReadOnlyObjectWrapper<>(cell.getValue().getStore());
            }
        });

        // A column's style applies to its cells: text centred vertically in the row.
        priority.setStyle("-fx-alignment: CENTER;");
        title.setStyle("-fx-alignment: CENTER-LEFT;");
        author.setStyle("-fx-alignment: CENTER-LEFT;");
        price.setStyle("-fx-alignment: CENTER-LEFT;");
        store.setStyle("-fx-alignment: CENTER-LEFT;");
        table.getColumns().add(priority);
        table.getColumns().add(title);
        table.getColumns().add(author);
        table.getColumns().add(price);
        table.getColumns().add(store);
        table.getSortOrder().add(priority);
        table.getSortOrder().add(title);

        // E112: a double-click on a row opens it in the entry dialog.
        table.setRowFactory(new Callback<TableView<WishlistEntry>, TableRow<WishlistEntry>>() {
            @Override
            public TableRow<WishlistEntry> call(TableView<WishlistEntry> view) {
                final TableRow<WishlistEntry> row = new TableRow<>();
                row.setOnMouseClicked(new EventHandler<MouseEvent>() {
                    @Override
                    public void handle(MouseEvent event) {
                        if (event.getClickCount() == 2 && !row.isEmpty()) {
                            openEntryDialog(row.getItem());
                        }
                    }
                });
                return row;
            }
        });
        table.getSelectionModel().getSelectedItems().addListener(new ListChangeListener<WishlistEntry>() {
            @Override
            public void onChanged(Change<? extends WishlistEntry> change) {
                updateButtons();
            }
        });

        MenuItem edit = menuItem("Edit", new Runnable() {
            @Override public void run() { onEdit(); }
        });
        MenuItem bought = menuItem("Mark as bought", new Runnable() {
            @Override public void run() { onMarkBought(); }
        });
        MenuItem remove = menuItem("Remove", new Runnable() {
            @Override public void run() { onRemoveEntries(); }
        });
        MenuItem add = menuItem("Add entry", new Runnable() {
            @Override public void run() { onAddEntry(); }
        });
        table.setContextMenu(new ContextMenu(edit, bought, remove, new SeparatorMenuItem(), add));
    }

    /** E119: High red, Medium amber, Low grey. The colours live in app.css. */
    private static Circle priorityDot(Priority priority) {
        Circle dot = new Circle(5);
        dot.getStyleClass().add("priority-dot-" + priority.name().toLowerCase(Locale.ROOT));
        return dot;
    }

    /**
     * The title cell: "★ " first for a rare entry (E122), then an
     * <b>Owned</b> badge when the collection already has the ISBN, Trash
     * included (E117), so Mark as bought's refusal never comes as a surprise.
     */
    private Node titleCell(WishlistEntry entry) {
        Label text = new Label((entry.isRare() ? "★ " : "") + entry.getTitle());
        HBox box = new HBox(8, text);
        box.setAlignment(Pos.CENTER_LEFT);
        if (entry.isRare()) {
            text.setTooltip(new Tooltip("Rare"));
        }
        Book owned = manager().findBookByIsbn(entry.getIsbn());
        if (owned != null) {
            Label badge = new Label(owned.isDeleted() ? "In Trash" : "Owned");
            badge.getStyleClass().add("owned-badge");
            box.getChildren().add(badge);
        }
        return box;
    }

    /** Rebuilds the table, footer and empty state from the current list. */
    private void refreshEntries() {
        List<WishlistEntry> keep = new ArrayList<>(table.getSelectionModel().getSelectedItems());
        rows.setAll(current == null ? new ArrayList<WishlistEntry>() : current.getEntries());
        // setAll drops the table's ordering; sort() applies the chosen columns again.
        table.sort();
        table.getSelectionModel().clearSelection();
        for (int i = 0; i < keep.size(); i++) {
            if (rows.contains(keep.get(i))) {
                table.getSelectionModel().select(keep.get(i));
            }
        }
        table.setPlaceholder(current == null ? new Label("") : emptyState(current));
        table.refresh();
        footer.setText(footerText());
        updateButtons();
    }

    /** E120: "3 entries · ₹15,898 total · 1 price unknown"; each clause only when it says something. */
    private String footerText() {
        if (rows.isEmpty()) {
            return "";
        }
        double total = 0;
        int unknown = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).getExpectedPrice() == 0) {
                unknown++;
            } else {
                total += rows.get(i).getExpectedPrice();
            }
        }
        String text = WishlistPicker.count(rows.size(), "entry", "entries");
        if (total > 0) {
            text += "  ·  " + BrowseController.formatMoney(manager().getCurrencyCode(), total) + " total";
        }
        if (unknown > 0) {
            text += "  ·  " + WishlistPicker.count(unknown, "price", "prices") + " unknown";
        }
        return text;
    }

    /** E126: what an empty list shows instead of an empty table. */
    private Node emptyState(Wishlist list) {
        FontIcon icon = new FontIcon("mdi2c-cart-outline");
        icon.setIconSize(48);
        icon.getStyleClass().add("empty-icon");
        Label heading = new Label("Nothing on ‘" + list.getName() + "’ yet");
        heading.getStyleClass().add("card-title");
        Label line = new Label("Add the books you want, with a price and where to buy them.");
        line.getStyleClass().add("muted");
        line.setWrapText(true);
        line.setTextAlignment(TextAlignment.CENTER);
        line.setMaxWidth(340);
        Button add = new Button("Add entry");
        add.getStyleClass().add("accent-button");
        add.setGraphic(new FontIcon("mdi2p-plus"));
        add.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                onAddEntry();
            }
        });
        VBox box = new VBox(10, icon, heading, line, add);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    /** E123: Edit needs exactly one entry; Mark as bought and Remove take any number. */
    private void updateButtons() {
        int picked = table.getSelectionModel().getSelectedItems().size();
        addEntryButton.setDisable(current == null);
        editButton.setDisable(picked != 1);
        boughtButton.setDisable(picked == 0);
        removeButton.setDisable(picked == 0);
        renameListButton.setDisable(current == null);
        deleteListButton.setDisable(current == null);
    }

    /** E83: Shortcut+A selects every entry on the list. */
    @Override
    public boolean selectAll() {
        table.getSelectionModel().selectAll();
        return true;
    }

    /** E123: Esc clears the selection. */
    @Override
    public boolean onEscape() {
        table.getSelectionModel().clearSelection();
        return true;
    }

    /**
     * A copy of the selection. The live selection list changes while entries
     * leave the table, so looping over it directly would skip some.
     */
    private List<WishlistEntry> selectedEntries() {
        return new ArrayList<>(table.getSelectionModel().getSelectedItems());
    }

    // ------------------------------------------------------------------
    // Keys (E123)
    // ------------------------------------------------------------------

    private void setUpKeys() {
        // A filter, not a handler: it runs before the table's and the list's
        // own key handling, which would otherwise use Enter and Delete first.
        page.addEventFilter(KeyEvent.KEY_PRESSED, new EventHandler<KeyEvent>() {
            @Override
            public void handle(KeyEvent event) {
                // Keys typed into a name being edited belong to the text field.
                if (event.getTarget() instanceof TextInputControl || lists.getEditingIndex() >= 0) {
                    return;
                }
                boolean inTable = table.isFocused();
                boolean inLists = lists.isFocused();
                if (NEW_LIST.match(event)) {
                    onNewList();
                } else if (NEW_ENTRY.match(event)) {
                    onAddEntry();
                } else if (event.getCode() == KeyCode.F2) {
                    onRenameList();
                } else if (event.getCode() == KeyCode.ENTER && inTable) {
                    onEdit();
                } else if (event.getCode() == KeyCode.ENTER && inLists) {
                    onRenameList();
                } else if ((event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) && inTable) {
                    onRemoveEntries();
                } else if ((event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) && inLists) {
                    onDeleteList();
                } else {
                    return;
                }
                event.consume();
            }
        });
    }

    private static MenuItem menuItem(String text, final Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                action.run();
            }
        });
        return item;
    }

    // ------------------------------------------------------------------
    // Add and edit (E112, E113)
    // ------------------------------------------------------------------

    @FXML
    private void onAddEntry() {
        if (current != null) {
            openEntryDialog(null);
        }
    }

    @FXML
    private void onEdit() {
        List<WishlistEntry> picked = selectedEntries();
        if (picked.size() == 1) {
            openEntryDialog(picked.get(0));
        }
    }

    /**
     * E112: the entry dialog. Save stays disabled while a field is wrong
     * (E113), and is checked once more when pressed, for the "already own
     * it" question (E127), which must be able to stop the dialog closing.
     *
     * @param editing the entry to edit, or null for a new one
     */
    private void openEntryDialog(WishlistEntry editing) {
        hideBanner();
        WishlistEntryForm form = new WishlistEntryForm(session, current, editing, storeSuggestions());
        Dialog<ButtonType> dialog = entryDialog(form, editing);
        Optional<ButtonType> answer = dialog.showAndWait();
        form.closed = true;
        if (answer.isPresent() && answer.get().getButtonData() == ButtonBar.ButtonData.OK_DONE) {
            finishEntry(form, editing);
        }
    }

    /** Builds the entry dialog around a form, without showing it. */
    private Dialog<ButtonType> entryDialog(final WishlistEntryForm form, WishlistEntry editing) {
        final Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(editing == null ? "Add entry" : "Edit entry");
        dialog.initOwner(page.getScene().getWindow());
        // The window's theme, so the dialog is dark in dark mode too.
        dialog.getDialogPane().getStylesheets().setAll(page.getScene().getStylesheets());
        dialog.getDialogPane().getStyleClass().add("add-page");
        dialog.getDialogPane().setContent(form.grid);
        final ButtonType save = new ButtonType(editing == null ? "Add" : "Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, save);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(save);
        saveButton.getStyleClass().add("accent-button");
        form.saveButton = saveButton;
        form.validate();
        // A filter on the button runs before the dialog closes; consuming the
        // event keeps it open (here: "Cancel" in the "already own it" question).
        saveButton.addEventFilter(ActionEvent.ACTION, new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                if (form.validate() != null || !form.confirmOwned(page)) {
                    event.consume();
                }
            }
        });
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                // A new entry usually starts from its ISBN (to look it up), an edit from its title.
                (form.editing == null ? form.isbnField : form.titleField).requestFocus();
            }
        });
        return dialog;
    }

    /** Writes a checked form into a new or existing entry, then confirms with a toast. */
    void finishEntry(WishlistEntryForm form, WishlistEntry editing) {
        final Wishlist list = current;
        if (editing != null) {
            form.apply(editing);
            refreshLists();
            table.getSelectionModel().clearSelection();
            table.getSelectionModel().select(editing);
            shell.saveChanges();
            shell.toast("Saved ‘" + editing.getTitle() + "’");
            return;
        }
        final WishlistEntry entry = new WishlistEntry(form.titleField.getText().trim());
        form.apply(entry);
        list.addEntry(entry);
        refreshLists();
        table.getSelectionModel().clearSelection();
        table.getSelectionModel().select(entry);
        shell.saveChanges();
        shell.toast("Added ‘" + entry.getTitle() + "’ to ‘" + list.getName() + "’", "Undo",
                new Runnable() {
                    @Override
                    public void run() {
                        list.removeEntry(entry);
                        refreshLists();
                        shell.saveChanges();
                    }
                });
    }

    /** E121: the usual shops first, then every other store already on any list. */
    private List<String> storeSuggestions() {
        List<String> stores = new ArrayList<>();
        for (int i = 0; i < STORES.length; i++) {
            stores.add(STORES[i]);
        }
        List<Wishlist> all = manager().getWishlists();
        for (int i = 0; i < all.size(); i++) {
            List<WishlistEntry> entries = all.get(i).getEntries();
            for (int j = 0; j < entries.size(); j++) {
                if (!stores.contains(entries.get(j).getStore())) {
                    stores.add(entries.get(j).getStore());
                }
            }
        }
        return stores;
    }

    // ------------------------------------------------------------------
    // Mark as bought (E114-E116, E123)
    // ------------------------------------------------------------------

    @FXML
    private void onMarkBought() {
        List<WishlistEntry> picked = selectedEntries();
        if (picked.size() == 1) {
            markOne(picked.get(0));
        } else if (picked.size() > 1) {
            markMany(picked);
        }
    }

    /**
     * E114: one entry becomes a book, which then opens in the Edit form. The
     * Undo toast waits until the user comes back from the form, so it is
     * never offered while the book is still being edited.
     */
    private void markOne(final WishlistEntry entry) {
        hideBanner();
        final Wishlist list = current;
        Book owned = manager().findBookByIsbn(entry.getIsbn());
        if (owned != null) {
            showOwnedBanner(entry, owned);
            return;
        }
        final int index = list.getEntries().indexOf(entry);
        final Book book;
        try {
            book = manager().markAsBought(list, entry);
        } catch (DuplicateItemException e) {
            // Cannot happen after the check above; kept because the compiler requires it.
            showOwnedBanner(entry, manager().findBookByIsbn(e.getIsbn()));
            return;
        } catch (InvalidISBNException e) {
            showInvalidIsbn(entry);
            return;
        }
        // E115: markAsBought has already copied the expected price into the
        // first copy's price paid, for the user to correct in the form.
        refreshLists();
        shell.saveChanges();
        editBook(book, new Runnable() {
            @Override
            public void run() {
                shell.toast("‘" + book.getTitle() + "’ moved to your collection", "Undo",
                        new Runnable() {
                            @Override
                            public void run() {
                                List<Bought> one = new ArrayList<>();
                                one.add(new Bought(list, entry, index, book));
                                undoBought(one);
                            }
                        });
            }
        });
    }

    /**
     * E123: several entries at once, with no form. Entries that cannot move
     * (already owned, or an unusable ISBN) are skipped and left selected, so
     * the user sees exactly which ones still need a decision.
     */
    private void markMany(List<WishlistEntry> picked) {
        hideBanner();
        if (!WishlistPicker.ask(page, "Mark " + picked.size() + " entries as bought?", "Mark as bought", false)) {
            return;
        }
        final List<Bought> moved = new ArrayList<>();
        List<WishlistEntry> skipped = new ArrayList<>();
        int owned = 0;
        int invalid = 0;
        for (int i = 0; i < picked.size(); i++) {
            WishlistEntry entry = picked.get(i);
            if (manager().findBookByIsbn(entry.getIsbn()) != null) {
                owned++;
                skipped.add(entry);
                continue;
            }
            int index = current.getEntries().indexOf(entry);
            try {
                Book book = manager().markAsBought(current, entry);
                moved.add(new Bought(current, entry, index, book));
            } catch (DuplicateItemException e) {
                owned++;
                skipped.add(entry);
            } catch (InvalidISBNException e) {
                invalid++;
                skipped.add(entry);
            }
        }
        refreshLists();
        for (int i = 0; i < skipped.size(); i++) {
            table.getSelectionModel().select(skipped.get(i));
        }
        String why = skipReasons(owned, invalid);
        if (moved.isEmpty()) {
            showBanner("warn", "None of these could be moved: " + why + ".");
            return;
        }
        shell.saveChanges();
        String message = "Moved " + moved.size() + (skipped.isEmpty() ? "" : "  ·  " + skipped.size()
                + " skipped (" + why + ")");
        shell.toast(message, "Show in Browse", new Runnable() {
            @Override
            public void run() {
                showInBrowse(moved.size() == 1 ? moved.get(0).book : null);
            }
        }, "Undo all", new Runnable() {
            @Override
            public void run() {
                undoBought(moved);
            }
        });
    }

    /** "1 already owned, 2 with an invalid ISBN": only the reasons that happened. */
    private static String skipReasons(int owned, int invalid) {
        List<String> parts = new ArrayList<>();
        if (owned > 0) {
            parts.add(owned + " already owned");
        }
        if (invalid > 0) {
            parts.add(invalid + " with an invalid ISBN");
        }
        return String.join(", ", parts);
    }

    /** One entry turned into a book: everything Undo needs to put it back. */
    private static final class Bought {
        final Wishlist list;
        final WishlistEntry entry;
        /** Where the entry was on its list at the moment it was removed. */
        final int index;
        final Book book;

        Bought(Wishlist list, WishlistEntry entry, int index, Book book) {
            this.list = list;
            this.entry = entry;
            this.index = index;
            this.book = book;
        }
    }

    /**
     * Undoes Mark as bought. Last first: each index was recorded against
     * the list as it was at that moment, so putting entries back in reverse
     * order restores the exact original order. The book goes through the
     * Trash because the core only deletes from there (D12), and loses any
     * cover uploaded in the Edit form meanwhile, as a book deleted for good
     * does (E165).
     */
    private void undoBought(List<Bought> done) {
        for (int i = done.size() - 1; i >= 0; i--) {
            Bought b = done.get(i);
            if (manager().findItemById(b.book.getId()) != null) {
                if (!b.book.isDeleted()) {
                    manager().moveToTrash(b.book.getId());
                }
                manager().deletePermanently(b.book.getId());
                try {
                    CoverArt.removeUpload(b.book, session.getDataFolder());
                } catch (IOException e) {
                    // Only an untidy leftover file; the book itself is gone.
                }
            }
            if (manager().getWishlists().contains(b.list)) {
                WishlistPicker.insertEntry(b.list, b.entry, Math.min(b.index, b.list.getEntryCount()));
            }
        }
        refreshLists();
        shell.saveChanges();
        shell.toast(done.size() == 1 ? "‘" + done.get(0).entry.getTitle() + "’ is back on the wishlist"
                : done.size() + " entries are back on the wishlist");
    }

    /** E113: only imported data can get here; the dialog never saves a bad ISBN. */
    private void showInvalidIsbn(final WishlistEntry entry) {
        Button edit = bannerButton("Edit", true, new Runnable() {
            @Override
            public void run() {
                hideBanner();
                openEntryDialog(entry);
            }
        });
        showBanner("error", "‘" + entry.getTitle() + "’ has an invalid ISBN (" + entry.getIsbn()
                + "). Fix it first.", edit);
    }

    /**
     * E116: the entry's book is already in the collection. The entry stays
     * until the user picks what to do. A book in the Trash cannot be shown in
     * Browse, so it is offered back instead.
     */
    private void showOwnedBanner(final WishlistEntry entry, final Book owned) {
        Button remove = bannerButton("Remove from wishlist", false, new Runnable() {
            @Override
            public void run() {
                List<WishlistEntry> one = new ArrayList<>();
                one.add(entry);
                removeEntries(one);
            }
        });
        if (owned.isDeleted()) {
            Button restore = bannerButton("Restore and add copy", true, new Runnable() {
                @Override
                public void run() {
                    addAsCopy(entry, owned);
                }
            });
            showBanner("warn", "‘" + owned.getTitle() + "’ is in your Trash.", remove, restore);
            return;
        }
        Button browse = bannerButton("Show in Browse", false, new Runnable() {
            @Override
            public void run() {
                showInBrowse(owned);
            }
        });
        Button copy = bannerButton("Add as copy", true, new Runnable() {
            @Override
            public void run() {
                addAsCopy(entry, owned);
            }
        });
        showBanner("warn", "You already own ‘" + owned.getTitle() + "’.", browse, remove, copy);
    }

    /**
     * E116: "I bought another one". A new copy (price pre-filled, E115) goes
     * on the owned book - restored first if it was in the Trash - the entry
     * leaves the list, and the book opens in Edit. Undo reverses all three.
     */
    private void addAsCopy(final WishlistEntry entry, final Book owned) {
        hideBanner();
        final Wishlist list = current;
        final int index = list.getEntries().indexOf(entry);
        final boolean wasTrashed = owned.isDeleted();
        if (wasTrashed) {
            manager().restoreFromTrash(owned.getId());
        }
        final Copy copy = new Copy();
        if (entry.getExpectedPrice() > 0) {
            copy.setPricePaid(entry.getExpectedPrice());
        }
        owned.addCopy(copy);
        list.removeEntry(entry);
        refreshLists();
        shell.saveChanges();
        editBook(owned, new Runnable() {
            @Override
            public void run() {
                String what = wasTrashed ? "Restored ‘" + owned.getTitle() + "’ with a new copy"
                        : "Copy added to ‘" + owned.getTitle() + "’";
                shell.toast(what, "Undo", new Runnable() {
                    @Override
                    public void run() {
                        // The copy may have been removed in the form meanwhile.
                        if (owned.getCopies().contains(copy) && owned.getCopyCount() > 1) {
                            owned.removeCopy(copy);
                        }
                        if (wasTrashed && !owned.isDeleted()) {
                            manager().moveToTrash(owned.getId());
                        }
                        if (manager().getWishlists().contains(list)) {
                            WishlistPicker.insertEntry(list, entry, Math.min(index, list.getEntryCount()));
                        }
                        refreshLists();
                        shell.saveChanges();
                        shell.toast("‘" + entry.getTitle() + "’ is back on the wishlist");
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------
    // Remove (E118)
    // ------------------------------------------------------------------

    @FXML
    private void onRemoveEntries() {
        removeEntries(selectedEntries());
    }

    /** E118: every removal asks, and Undo still follows. */
    private void removeEntries(final List<WishlistEntry> doomed) {
        if (doomed.isEmpty() || current == null) {
            return;
        }
        hideBanner();
        final Wishlist list = current;
        String what = doomed.size() == 1 ? "‘" + doomed.get(0).getTitle() + "’" : doomed.size() + " entries";
        if (!WishlistPicker.ask(page, "Remove " + what + " from ‘" + list.getName() + "’?", "Remove", true)) {
            return;
        }
        // Each index is recorded just before its own removal, so Undo can
        // put them back in reverse order (see undoBought).
        final List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < doomed.size(); i++) {
            indexes.add(list.getEntries().indexOf(doomed.get(i)));
            list.removeEntry(doomed.get(i));
        }
        refreshLists();
        shell.saveChanges();
        shell.toast("Removed " + what, "Undo", new Runnable() {
            @Override
            public void run() {
                if (!manager().getWishlists().contains(list)) {
                    return;
                }
                for (int i = doomed.size() - 1; i >= 0; i--) {
                    WishlistPicker.insertEntry(list, doomed.get(i), Math.min(indexes.get(i), list.getEntryCount()));
                }
                refreshLists();
                shell.saveChanges();
            }
        });
    }

    // ------------------------------------------------------------------
    // Other pages
    // ------------------------------------------------------------------

    /**
     * E114/E116: opens a book in the Add page's Edit form, with Wishlist
     * still lit in the sidebar (E33). Save or Cancel come back here and run
     * {@code afterwards}, which offers the Undo toast only once the user is
     * back, never while the book is still being edited.
     */
    private void editBook(Book book, final Runnable afterwards) {
        AddController add = (AddController) shell.show(PageId.ADD, PageId.WISHLIST);
        add.openEditReturning(book, new Runnable() {
            @Override
            public void run() {
                shell.show(PageId.WISHLIST);
                afterwards.run();
            }
        });
    }

    /** Browse with one book's details open, or plain Browse for null. */
    private void showInBrowse(Book book) {
        PageController page = shell.show(PageId.BROWSE);
        if (book != null && page instanceof BrowseController) {
            ((BrowseController) page).showBook(book);
        }
    }

    // ------------------------------------------------------------------
    // Banner
    // ------------------------------------------------------------------

    private Button bannerButton(String text, boolean accent, final Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add(accent ? "accent-button" : "plain-button");
        button.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                action.run();
            }
        });
        return button;
    }

    /**
     * @param kind    "info", "warn" or "error", for the colour
     * @param actions buttons shown at the banner's right end
     */
    private void showBanner(String kind, String message, Button... actions) {
        banner.getStyleClass().removeAll("info", "warn", "error");
        banner.getStyleClass().add(kind);
        bannerText.setText(message);
        bannerActions.getChildren().setAll(actions);
        banner.setVisible(true);
        banner.setManaged(true);
    }

    private void hideBanner() {
        banner.setVisible(false);
        banner.setManaged(false);
    }

}
