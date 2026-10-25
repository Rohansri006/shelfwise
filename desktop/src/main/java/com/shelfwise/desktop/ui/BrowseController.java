package com.shelfwise.desktop.ui;

import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Category;
import com.shelfwise.core.model.CollectionItem;
import com.shelfwise.core.model.Contributor;
import com.shelfwise.core.model.Copy;
import com.shelfwise.core.model.Location;
import com.shelfwise.core.model.ReadingSession;
import com.shelfwise.core.model.ReadingStatus;
import com.shelfwise.core.service.CollectionManager;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.ClosePath;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.QuadCurveTo;
import javafx.stage.FileChooser;
import javafx.util.Callback;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The Browse page (E15-E30, E39, E49-E53): the collection as a cover grid or
 * a table, narrowed by search, a reading-status filter and the filter
 * drawer's category and location trees, with a details panel for one book
 * and a bulk bar for several.
 * <p>
 * Extends {@link PageController}, so the window treats it like any other
 * page; it overrides {@link #shown()}, {@link #onEscape()} and
 * {@link #selectAll()} with what those mean on Browse.
 * <p>
 * Every change it makes to the collection (bulk status, location, category,
 * Move to Trash and its Undo) is followed by {@link Shell#saveChanges()}:
 * autosave after each logical operation (ADR 0001).
 */
public class BrowseController extends PageController {

    private static final String ALL_STATUSES = "All statuses";

    private static final String SMALL = "Small covers";
    private static final String MEDIUM = "Medium covers";
    private static final String LARGE = "Large covers";

    /** Height of one tree row; trees are sized to rows times this (#2). */
    private static final double TREE_ROW = 26;

    /** The tree's own padding and border; without this allowance it shows a scrollbar. */
    private static final double TREE_INSETS = 10;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy");

    @FXML private SplitPane mainSplit;
    @FXML private VBox browsePage;
    @FXML private Label countLabel;
    @FXML private TextField searchField;
    @FXML private ComboBox<String> statusChoice;
    @FXML private Button filtersButton;
    @FXML private ToggleButton gridToggle;
    @FXML private ToggleButton tableToggle;
    @FXML private HBox chipRow;
    @FXML private SplitPane drawerSplit;
    @FXML private ScrollPane drawer;
    @FXML private TreeView<Category> categoryTree;
    @FXML private TreeView<Location> locationTree;
    @FXML private ScrollPane gridScroll;
    @FXML private TilePane grid;
    @FXML private TableView<Book> table;
    @FXML private Label emptyLabel;
    @FXML private ComboBox<String> sizeChoice;
    @FXML private ScrollPane detailScroll;
    @FXML private VBox detail;
    @FXML private HBox bulkBar;
    @FXML private Label bulkCount;
    @FXML private MenuButton bulkStatus;

    /** Kept so its cells can be given tooltips (E51). */
    private TableColumn<Book, String> categoryColumn;

    /** Divider positions, kept while the panels are closed so they reopen at the same size. */
    private static final double DRAWER_DEFAULT = 0.24;
    private static final double DETAIL_DEFAULT = 0.70;
    private double drawerDivider = DRAWER_DEFAULT;
    private double detailDivider = DETAIL_DEFAULT;

    /** The details panel released narrower than this snaps flat against its edge. */
    private static final double COLLAPSE_BELOW = 140;

    /**
     * E49: the filter drawer's narrowest readable width. Released any
     * narrower, it snaps shut instead of showing clipped tree rows.
     */
    private static final double DRAWER_MIN = 200;

    /**
     * A snapped panel still measures a few pixels, because the split pane
     * keeps half of the divider's width on each side; anything under this
     * counts as snapped.
     */
    private static final double SNAPPED_BELOW = 12;

    /** False until the page is first shown; the panels are snapped shut then. */
    private boolean panelsSnapped = false;

    private ReadingStatus statusFilter;
    private Category categoryFilter;
    private Location locationFilter;

    private Book selected;
    private VBox selectedCard;

    /**
     * E39: the books picked for a bulk action, shared by the grid and the
     * table so switching view keeps the selection. A set, because a book is
     * either picked or not; {@code Book.equals} compares ids, so lookups work.
     */
    private final Set<Book> picked = new HashSet<>();

    /** The books on screen, in display order, for shift-click ranges. */
    private List<Book> shown = new ArrayList<>();

    /** Index in {@link #shown} of the last plain or cmd click; a shift-click ranges from here. */
    private int anchor = -1;

    /**
     * True while code (not the user) is changing the table's selection, so
     * the table's selection listener does not react to our own changes.
     */
    private boolean syncingTable = false;

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    /** Called by the FXML loader once every {@code @FXML} field is filled. */
    @FXML
    private void initialize() {
        ToggleGroup view = new ToggleGroup();
        gridToggle.setToggleGroup(view);
        tableToggle.setToggleGroup(view);
        gridToggle.setTooltip(new Tooltip("Grid"));
        tableToggle.setTooltip(new Tooltip("Table"));

        statusChoice.getItems().add(ALL_STATUSES);
        ReadingStatus[] statuses = ReadingStatus.values();
        for (int i = 0; i < statuses.length; i++) {
            statusChoice.getItems().add(statuses[i].getLabel());
        }
        statusChoice.setValue(ALL_STATUSES);
        // A cell factory decides how each row of the list is drawn: here, the
        // status's coloured dot before its name. The button cell is the one
        // shown while the list is closed, so it gets the same treatment.
        Callback<ListView<String>, ListCell<String>> statusCells = new Callback<ListView<String>, ListCell<String>>() {
            @Override
            public ListCell<String> call(ListView<String> list) {
                return new ListCell<String>() {
                    @Override
                    protected void updateItem(String item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty ? null : item);
                        ReadingStatus status = empty ? null : statusFromLabel(item);
                        setGraphic(status == null ? null : statusDot(status, 4));
                        setGraphicTextGap(8);
                    }
                };
            }
        };
        statusChoice.setCellFactory(statusCells);
        statusChoice.setButtonCell(statusCells.call(null));
        statusChoice.getSelectionModel().selectedItemProperty().addListener(new ChangeListener<String>() {
            @Override
            public void changed(ObservableValue<? extends String> obs, String old, String now) {
                statusFilter = statusFromLabel(now);
                refresh();
            }
        });
        searchField.textProperty().addListener(new ChangeListener<String>() {
            @Override
            public void changed(ObservableValue<? extends String> obs, String old, String now) {
                refresh();
            }
        });

        // #1 (E25): three named cover sizes instead of a zoom slider.
        sizeChoice.getItems().addAll(SMALL, MEDIUM, LARGE);
        sizeChoice.setValue(MEDIUM);
        sizeChoice.setTooltip(new Tooltip("Cover size"));
        sizeChoice.valueProperty().addListener(new ChangeListener<String>() {
            @Override
            public void changed(ObservableValue<? extends String> obs, String old, String now) {
                refresh();
            }
        });

        setUpTrees();
        setUpTable();
        setUpBulkStatus();
        fitToRows(categoryTree);
        fitToRows(locationTree);

        // Both side panels live in their split panes permanently. "Closed"
        // means snapped to zero width, leaving the divider and its three-dot
        // grip at the edge to drag or click open again.
        drawerSplit.getItems().add(0, drawer);
        mainSplit.getItems().add(detailScroll);
        SplitPane.setResizableWithParent(detailScroll, false);
        showEmptyDetail();
        installSnap(drawerSplit, true);
        installSnap(mainSplit, false);

        // The drawer's divider starts below the toolbar, so its centre (and
        // grip) sits lower than the details divider's. Realign whenever either
        // moves: the window resizes, or the filter chip row appears.
        InvalidationListener realign = new InvalidationListener() {
            @Override
            public void invalidated(Observable observable) {
                // Later: the skin builds the grip during layout, after this fires.
                Platform.runLater(new Runnable() {
                    @Override
                    public void run() {
                        alignGrips();
                    }
                });
            }
        };
        mainSplit.heightProperty().addListener(realign);
        drawerSplit.boundsInParentProperty().addListener(realign);
    }

    /**
     * Read afresh every time: another page may have added, edited or
     * trashed books, and after "Keep theirs" (E47) the whole collection is a
     * new set of objects, so picks, the open book and the tree filters would
     * point at stale ones. All three are dropped and the trees rebuilt.
     */
    @Override
    public void shown() {
        picked.clear();
        anchor = -1;
        hideDetail();
        rebuildTrees();
        refresh();
        if (!panelsSnapped) {
            panelsSnapped = true;
            snapPanels();
        }
    }

    /**
     * Opens one book's details, for pages that send the user here to see a
     * book: the Add page after Save, Open or Restore. Call it after showing
     * Browse, because {@link #shown()} closes the details panel.
     *
     * @param book the book to show; a trashed book is not shown, since
     *             Browse lists only live books
     */
    public void showBook(Book book) {
        if (book == null || book.isDeleted()) {
            return;
        }
        showDetail(book);
        // Again after the details are open, so the book is highlighted.
        refresh();
    }

    /** E39: Esc drops a bulk selection and closes the details panel. */
    @Override
    public boolean onEscape() {
        clearPicks();
        closeDetail();
        return true;
    }

    /** E83/E84: Shortcut+A picks every book shown. */
    @Override
    public boolean selectAll() {
        pickAllShown();
        return true;
    }

    /** @return the open collection; asked each time, because "Keep theirs" replaces it */
    private CollectionManager manager() {
        return session.getManager();
    }

    /** @return the collection's folder, where covers live */
    private Path dataFolder() {
        return session.getDataFolder();
    }

    /**
     * Moves the drawer's three-dot grip to the same height as the details
     * panel's, so the two read as a pair. Only the grip moves; the divider
     * still runs the drawer's full height.
     */
    private void alignGrips() {
        for (int i = 0; i < drawerSplit.getChildrenUnmodifiable().size(); i++) {
            Node divider = drawerSplit.getChildrenUnmodifiable().get(i);
            // A divider is a Parent (it holds the grip); a plain Node has no children.
            if (!divider.getStyleClass().contains("split-pane-divider")
                    || ((Parent) divider).getChildrenUnmodifiable().isEmpty()) {
                continue;
            }
            Node grip = ((Parent) divider).getChildrenUnmodifiable().get(0);
            // Both centres in scene coordinates, the window's common frame.
            double target = mainSplit.localToScene(0, mainSplit.getHeight() / 2).getY();
            double natural = divider.localToScene(0, divider.getLayoutBounds().getHeight() / 2).getY();
            grip.setTranslateY(target - natural);
        }
    }

    /**
     * Snaps both side panels shut, the first time the page is shown.
     * Positions set before or during the page's first layout are
     * overwritten by it, so this waits for that layout: a listener notices
     * the page getting a width (and removes itself, being needed once), and
     * {@code Platform.runLater} then waits until the layout pass has finished.
     */
    private void snapPanels() {
        if (mainSplit.getWidth() > 0) {
            snapNow();
            return;
        }
        mainSplit.widthProperty().addListener(new ChangeListener<Number>() {
            @Override
            public void changed(ObservableValue<? extends Number> obs, Number old, Number now) {
                if (now.doubleValue() > 0) {
                    // "this" is the listener itself, an anonymous inner class object.
                    obs.removeListener(this);
                    Platform.runLater(new Runnable() {
                        @Override
                        public void run() {
                            snapNow();
                        }
                    });
                }
            }
        });
    }

    private void snapNow() {
        drawerSplit.setDividerPositions(0);
        mainSplit.setDividerPositions(1);
        // Only now stop the drawer growing when its split pane widens (as it
        // does whenever the detail panel closes). Set any earlier, the split
        // pane would lock in the drawer's pre-layout width instead of zero.
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                SplitPane.setResizableWithParent(drawer, false);
            }
        });
    }

    /**
     * E84: picks every book currently shown - after search and filters, never
     * a hidden one, so a bulk action cannot touch books the user cannot see.
     * Goes through the same path as cmd-click, so the ticks, the table and
     * the bulk bar all follow.
     */
    private void pickAllShown() {
        picked.clear();
        picked.addAll(shown);
        anchor = -1;
        afterPickChange();
    }

    /** Browse's "Add book" button: the same as the sidebar's Add (E32). */
    @FXML
    private void onAddBook() {
        shell.show(PageId.ADD);
    }

    // ------------------------------------------------------------------
    // Toolbar, filters and chips
    // ------------------------------------------------------------------

    @FXML
    private void onToggleFilters() {
        if (panelWidth(drawerSplit, true) >= SNAPPED_BELOW) {
            snapDrawer();
        } else {
            openDrawer();
        }
    }

    private void openDrawer() {
        // Never reopen narrower than readable (E49), e.g. after the window shrank.
        double least = drawerSplit.getWidth() > 0 ? DRAWER_MIN / drawerSplit.getWidth() : 0;
        drawerSplit.setDividerPositions(Math.max(drawerDivider, least));
        if (!filtersButton.getStyleClass().contains("active")) {
            filtersButton.getStyleClass().add("active");
        }
    }

    private void snapDrawer() {
        if (panelWidth(drawerSplit, true) >= DRAWER_MIN) {
            drawerDivider = drawerSplit.getDividerPositions()[0];
        }
        drawerSplit.setDividerPositions(0);
        filtersButton.getStyleClass().remove("active");
    }

    private void openDetail() {
        mainSplit.setDividerPositions(detailDivider);
    }

    private void snapDetail() {
        if (panelWidth(mainSplit, false) >= COLLAPSE_BELOW) {
            detailDivider = mainSplit.getDividerPositions()[0];
        }
        mainSplit.setDividerPositions(1);
    }

    /**
     * @param split      a split pane with one side panel
     * @param panelFirst true if the panel is its first item (the drawer),
     *                   false if its last (the detail panel)
     * @return the panel's current width in pixels
     */
    private static double panelWidth(SplitPane split, boolean panelFirst) {
        double position = split.getDividerPositions()[0];
        double share = panelFirst ? position : 1 - position;
        return share * split.getWidth();
    }

    /**
     * Snap behaviour for one split pane, decided when the mouse is released
     * on its divider rather than while dragging, so the divider never fights
     * the user's hand:
     * <ul>
     *   <li>released narrower than {@link #DRAWER_MIN} (drawer) or
     *       {@link #COLLAPSE_BELOW} (details): snap flat to the edge;</li>
     *   <li>a click (no drag) on a snapped divider: open at the remembered width;</li>
     *   <li>otherwise: keep the width and remember it.</li>
     * </ul>
     * Event filters see the press and release before the divider handles them.
     */
    private void installSnap(final SplitPane split, final boolean panelFirst) {
        final double[] pressedAt = {-1};
        split.addEventFilter(MouseEvent.MOUSE_PRESSED, new EventHandler<MouseEvent>() {
            @Override
            public void handle(MouseEvent event) {
                pressedAt[0] = isOwnDivider(split, event.getTarget()) ? split.getDividerPositions()[0] : -1;
            }
        });
        split.addEventFilter(MouseEvent.MOUSE_RELEASED, new EventHandler<MouseEvent>() {
            @Override
            public void handle(MouseEvent event) {
                if (pressedAt[0] < 0) {
                    return;
                }
                final boolean dragged = Math.abs(split.getDividerPositions()[0] - pressedAt[0]) > 0.002;
                pressedAt[0] = -1;
                // Let the divider finish its own release handling first.
                Platform.runLater(new Runnable() {
                    @Override
                    public void run() {
                        settle(panelFirst, dragged);
                    }
                });
            }
        });
    }

    /**
     * Applies the snap rules after a press and release on a divider.
     *
     * @param panelFirst true for the drawer, false for the detail panel
     * @param dragged    true if the divider moved, false for a plain click
     */
    private void settle(boolean panelFirst, boolean dragged) {
        SplitPane split = panelFirst ? drawerSplit : mainSplit;
        double width = panelWidth(split, panelFirst);
        if (!dragged && width < SNAPPED_BELOW) {
            if (panelFirst) {
                openDrawer();
            } else {
                openDetail();
            }
        } else if (!dragged) {
            // #3: a click on the grip of an open panel closes it again.
            if (panelFirst) {
                snapDrawer();
            } else {
                snapDetail();
            }
        } else if (width < (panelFirst ? DRAWER_MIN : COLLAPSE_BELOW)) {
            if (panelFirst) {
                snapDrawer();
            } else {
                snapDetail();
            }
        } else if (panelFirst) {
            drawerDivider = split.getDividerPositions()[0];
            if (!filtersButton.getStyleClass().contains("active")) {
                filtersButton.getStyleClass().add("active");
            }
        } else {
            detailDivider = split.getDividerPositions()[0];
        }
    }

    /**
     * Whether a mouse target is this split pane's own divider, or the grip
     * inside it. The drawer's split pane sits inside the main one, so a
     * press on the inner divider must not count for the outer pane.
     */
    private static boolean isOwnDivider(SplitPane split, Object target) {
        if (!(target instanceof Node)) {
            return false;
        }
        Node node = (Node) target;
        while (node != null && node != split) {
            if (node.getStyleClass().contains("split-pane-divider")) {
                return node.getParent() == split;
            }
            node = node.getParent();
        }
        return false;
    }

    @FXML
    private void onShowGrid() {
        gridToggle.setSelected(true);
        gridScroll.setVisible(true);
        table.setVisible(false);
        // #1: cover size means nothing in the table, so it only shows with the grid.
        sizeChoice.setVisible(true);
        sizeChoice.setManaged(true);
    }

    @FXML
    private void onShowTable() {
        tableToggle.setSelected(true);
        gridScroll.setVisible(false);
        table.setVisible(true);
        sizeChoice.setVisible(false);
        sizeChoice.setManaged(false);
    }

    private void setUpTrees() {
        categoryTree.setRoot(new TreeItem<Category>());
        categoryTree.setCellFactory(new Callback<TreeView<Category>, TreeCell<Category>>() {
            @Override
            public TreeCell<Category> call(TreeView<Category> view) {
                return new TreeCell<Category>() {
                    @Override
                    protected void updateItem(Category item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty || item == null ? null : item.getName());
                    }
                };
            }
        });
        categoryTree.getSelectionModel().selectedItemProperty().addListener(
                new ChangeListener<TreeItem<Category>>() {
                    @Override
                    public void changed(ObservableValue<? extends TreeItem<Category>> obs,
                                        TreeItem<Category> old, TreeItem<Category> now) {
                        categoryFilter = now == null ? null : now.getValue();
                        refresh();
                    }
                });

        locationTree.setRoot(new TreeItem<Location>());
        locationTree.setCellFactory(new Callback<TreeView<Location>, TreeCell<Location>>() {
            @Override
            public TreeCell<Location> call(TreeView<Location> view) {
                return new TreeCell<Location>() {
                    @Override
                    protected void updateItem(Location item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty || item == null ? null : item.getName());
                    }
                };
            }
        });
        locationTree.getSelectionModel().selectedItemProperty().addListener(
                new ChangeListener<TreeItem<Location>>() {
                    @Override
                    public void changed(ObservableValue<? extends TreeItem<Location>> obs,
                                        TreeItem<Location> old, TreeItem<Location> now) {
                        locationFilter = now == null ? null : now.getValue();
                        refresh();
                    }
                });
    }

    /**
     * #2 (E27): sizes a tree to exactly its visible rows. A TreeView normally
     * fills whatever height it is given, leaving blank space under collapsed
     * nodes.
     * <p>
     * The root listens for expand and collapse events, which bubble up from
     * any node in the tree, and the rows are counted again by
     * {@link #visibleRows}. (The tree's own {@code getExpandedItemCount()} is
     * not used: it is recalculated lazily and can still hold the old number
     * when the event arrives.) Generic ({@code <T>}) so one method serves the
     * category and location trees.
     *
     * @param tree the tree to size
     * @param <T>  what the tree holds
     */
    private static <T> void fitToRows(final TreeView<T> tree) {
        tree.setFixedCellSize(TREE_ROW);
        // Pin min and max to the preferred height: otherwise the section
        // stretches the tree to fill spare space, which is the gap being removed.
        tree.setMinHeight(Region.USE_PREF_SIZE);
        tree.setMaxHeight(Region.USE_PREF_SIZE);
        tree.setPrefHeight(visibleRows(tree.getRoot()) * TREE_ROW + TREE_INSETS);
        EventHandler<TreeItem.TreeModificationEvent<T>> resize = new EventHandler<TreeItem.TreeModificationEvent<T>>() {
            @Override
            public void handle(TreeItem.TreeModificationEvent<T> event) {
                tree.setPrefHeight(visibleRows(tree.getRoot()) * TREE_ROW + TREE_INSETS);
            }
        };
        tree.getRoot().addEventHandler(TreeItem.<T>branchExpandedEvent(), resize);
        tree.getRoot().addEventHandler(TreeItem.<T>branchCollapsedEvent(), resize);
    }

    /**
     * Counts the rows a hidden-root tree shows: each child is one row, plus
     * its own children's rows if it is expanded. Recursion - the method calls
     * itself for each level of the tree.
     *
     * @param parent a node
     * @return the rows shown beneath it
     */
    private static <T> int visibleRows(TreeItem<T> parent) {
        int rows = 0;
        for (int i = 0; i < parent.getChildren().size(); i++) {
            TreeItem<T> child = parent.getChildren().get(i);
            rows++;
            if (child.isExpanded()) {
                rows += visibleRows(child);
            }
        }
        return rows;
    }

    private void fillTrees() {
        List<Category> roots = manager().getRootCategories();
        Collections.sort(roots, new Comparator<Category>() {
            @Override
            public int compare(Category a, Category b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (int i = 0; i < roots.size(); i++) {
            categoryTree.getRoot().getChildren().add(categoryItem(roots.get(i)));
        }
        List<Location> places = manager().getRootLocations();
        Collections.sort(places, new Comparator<Location>() {
            @Override
            public int compare(Location a, Location b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (int i = 0; i < places.size(); i++) {
            locationTree.getRoot().getChildren().add(locationItem(places.get(i)));
        }
    }

    /**
     * Other pages create, rename and delete categories and locations (E69,
     * E89), so the filter drawer's trees are rebuilt whenever Browse is
     * shown. Filters are cleared because their tree items are replaced.
     */
    private void rebuildTrees() {
        categoryTree.getSelectionModel().clearSelection();
        locationTree.getSelectionModel().clearSelection();
        categoryTree.getRoot().getChildren().clear();
        locationTree.getRoot().getChildren().clear();
        fillTrees();
        categoryTree.setPrefHeight(visibleRows(categoryTree.getRoot()) * TREE_ROW + TREE_INSETS);
        locationTree.setPrefHeight(visibleRows(locationTree.getRoot()) * TREE_ROW + TREE_INSETS);
    }

    private TreeItem<Category> categoryItem(Category category) {
        TreeItem<Category> item = new TreeItem<>(category);
        item.setExpanded(true);
        List<Category> children = new ArrayList<>(category.getChildren());
        Collections.sort(children, new Comparator<Category>() {
            @Override
            public int compare(Category a, Category b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (int i = 0; i < children.size(); i++) {
            item.getChildren().add(categoryItem(children.get(i)));
        }
        return item;
    }

    private TreeItem<Location> locationItem(Location location) {
        TreeItem<Location> item = new TreeItem<>(location);
        item.setExpanded(true);
        List<Location> children = new ArrayList<>(location.getChildren());
        Collections.sort(children, new Comparator<Location>() {
            @Override
            public int compare(Location a, Location b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (int i = 0; i < children.size(); i++) {
            item.getChildren().add(locationItem(children.get(i)));
        }
        return item;
    }

    private void rebuildChips() {
        chipRow.getChildren().clear();
        if (statusFilter != null) {
            chipRow.getChildren().add(chip("Status: " + statusFilter.getLabel(), new Runnable() {
                @Override
                public void run() {
                    statusChoice.setValue(ALL_STATUSES);
                }
            }));
        }
        if (categoryFilter != null) {
            chipRow.getChildren().add(chip(categoryFilter.getPath(), new Runnable() {
                @Override
                public void run() {
                    categoryTree.getSelectionModel().clearSelection();
                }
            }));
        }
        if (locationFilter != null) {
            chipRow.getChildren().add(chip(locationFilter.getPath(), new Runnable() {
                @Override
                public void run() {
                    locationTree.getSelectionModel().clearSelection();
                }
            }));
        }
        boolean any = !chipRow.getChildren().isEmpty();
        chipRow.setVisible(any);
        chipRow.setManaged(any);
    }

    /** A removable filter chip; also used for the Add form's categories. */
    static HBox chip(String text, final Runnable onRemove) {
        Button remove = new Button();
        remove.getStyleClass().add("icon-button");
        remove.setGraphic(new FontIcon("mdi2c-close"));
        remove.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                onRemove.run();
            }
        });
        HBox chip = new HBox(new Label(text), remove);
        chip.getStyleClass().add("chip");
        return chip;
    }

    // ------------------------------------------------------------------
    // Results: grid and table
    // ------------------------------------------------------------------

    private void refresh() {
        // The FXML loader fires the filter listeners before setUp has run.
        if (session == null) {
            return;
        }
        // Each filter is one of the Searchable overloads; the results are
        // intersected by id.
        List<CollectionItem> found = manager().search(searchField.getText());
        Set<String> keep = null;
        if (statusFilter != null) {
            keep = intersect(keep, manager().search(statusFilter));
        }
        if (categoryFilter != null) {
            keep = intersect(keep, manager().search(categoryFilter));
        }
        if (locationFilter != null) {
            keep = intersect(keep, manager().search(locationFilter));
        }
        List<Book> books = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            if (keep == null || keep.contains(found.get(i).getId())) {
                books.add((Book) found.get(i));
            }
        }

        int total = manager().getItems().size();
        countLabel.setText(books.size() == total
                ? total + (total == 1 ? " book" : " books")
                : books.size() + " of " + total + " books");
        emptyLabel.setVisible(books.isEmpty());
        rebuildChips();

        // A bulk action must only touch books the user can see, so picks
        // hidden by a new filter are dropped. Book.equals compares ids.
        picked.retainAll(books);
        // A shift-click range counts positions, so the anchor only survives
        // while the same books are on screen in the same order.
        if (!books.equals(shown)) {
            anchor = -1;
        }
        shown = books;

        grid.getChildren().clear();
        selectedCard = null;
        for (int i = 0; i < books.size(); i++) {
            VBox card = card(books.get(i), i);
            if (books.get(i) == selected) {
                card.getStyleClass().add("selected");
                selectedCard = card;
            }
            grid.getChildren().add(card);
        }
        // New items clear the table's selection, which would fire the
        // selection listener and wipe the picks; guard it, then put them back.
        syncingTable = true;
        table.setItems(FXCollections.observableArrayList(books));
        syncingTable = false;
        syncTableSelection();
        updateBulkBar();
    }

    private static Set<String> intersect(Set<String> soFar, List<CollectionItem> matches) {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < matches.size(); i++) {
            if (soFar == null || soFar.contains(matches.get(i).getId())) {
                ids.add(matches.get(i).getId());
            }
        }
        return ids;
    }

    /** @return the cover width for the chosen size; the height keeps a 134:190 book shape */
    private double coverWidth() {
        if (SMALL.equals(sizeChoice.getValue())) {
            return 110;
        }
        if (LARGE.equals(sizeChoice.getValue())) {
            return 170;
        }
        return 134;
    }

    /**
     * @param book  the book to draw
     * @param index its position in {@link #shown}, for shift-click ranges
     */
    private VBox card(final Book book, final int index) {
        double width = coverWidth();
        double height = Math.round(width * 190 / 134);
        StackPane cover = new StackPane(CoverArt.coverFor(book, dataFolder(), width, height, false));
        cover.setMaxSize(width, height);
        Group mark = bookmark(book.getReadingStatus(), width, height);
        StackPane.setAlignment(mark, Pos.TOP_LEFT);
        // Hangs from the top edge, its left side 10% in from the cover's left.
        StackPane.setMargin(mark, new javafx.geometry.Insets(0, 0, 0, Math.round(width * 0.10)));
        cover.getChildren().add(mark);
        if (picked.contains(book)) {
            // E39: the tick that marks a picked cover in the grid.
            // A solid disc behind the icon, so the tick reads on any cover.
            int size = Math.max(20, (int) Math.round(width * 0.17));
            Circle disc = new Circle(size / 2.0 - 1);
            disc.getStyleClass().add("pick-tick-disc");
            FontIcon icon = new FontIcon("mdi2c-check-circle");
            icon.getStyleClass().add("pick-tick");
            icon.setIconSize(size);
            StackPane tick = new StackPane(disc, icon);
            tick.setMaxSize(size, size);
            StackPane.setAlignment(tick, Pos.TOP_RIGHT);
            StackPane.setMargin(tick, new javafx.geometry.Insets(6, 6, 0, 0));
            cover.getChildren().add(tick);
        }

        Label title = new Label(book.getTitle());
        title.getStyleClass().add("card-title");
        title.setWrapText(true);
        title.setMaxWidth(width);
        title.setMaxHeight(36);
        Label author = new Label(authorOf(book));
        author.getStyleClass().add("card-author");
        author.setMaxWidth(width);

        final VBox card = new VBox(cover, title, author);
        card.getStyleClass().add("book-card");
        if (picked.contains(book)) {
            card.getStyleClass().add("picked");
        }
        card.setPrefWidth(width + 16);
        card.setOnMouseClicked(new EventHandler<MouseEvent>() {
            @Override
            public void handle(MouseEvent event) {
                // E39: cmd-click (ctrl on Windows/Linux; "shortcut" means
                // whichever the platform uses) toggles one book, shift-click
                // picks a range, a plain click opens the details as before.
                if (event.isShortcutDown()) {
                    togglePick(book, index);
                } else if (event.isShiftDown()) {
                    pickRange(index);
                } else {
                    boolean hadPicks = !picked.isEmpty();
                    picked.clear();
                    if (selectedCard != null) {
                        selectedCard.getStyleClass().remove("selected");
                    }
                    card.getStyleClass().add("selected");
                    selectedCard = card;
                    showDetail(book);
                    if (hadPicks) {
                        // Repaint so the old ticks disappear; refresh marks
                        // the selected card again by comparing with `selected`.
                        refresh();
                    }
                    anchor = index;
                    updateBulkBar();
                }
            }
        });
        return card;
    }

    private static String authorOf(Book book) {
        Contributor author = book.getPrimaryAuthor();
        return author == null ? "Unknown author" : author.getName();
    }

    private void setUpTable() {
        table.getColumns().add(column("Title", 220, new TextOf() {
            @Override
            public String of(Book b) {
                return b.getTitle();
            }
        }));
        table.getColumns().add(column("Author", 150, new TextOf() {
            @Override
            public String of(Book b) {
                return authorOf(b);
            }
        }));
        table.getColumns().add(column("Year", 56, new TextOf() {
            @Override
            public String of(Book b) {
                return b.getPublicationYear() == 0 ? "" : String.valueOf(b.getPublicationYear());
            }
        }));
        table.getColumns().add(column("Status", 86, new TextOf() {
            @Override
            public String of(Book b) {
                return b.getReadingStatus().getLabel();
            }
        }));
        table.getColumns().add(column("Rating", 100, new TextOf() {
            @Override
            public String of(Book b) {
                return stars(b.getRating());
            }
        }));
        table.getColumns().add(column("Copies", 58, new TextOf() {
            @Override
            public String of(Book b) {
                return String.valueOf(b.getCopyCount());
            }
        }));
        categoryColumn = column("Categories", 160, new TextOf() {
            @Override
            public String of(Book b) {
                return categoriesText(b);
            }
        });
        table.getColumns().add(categoryColumn);
        table.getColumns().add(column("Location", 190, new TextOf() {
            @Override
            public String of(Book b) {
                return locationText(b.getCopies().get(0));
            }
        }));
        // E39: the table's own multi-select (cmd/shift-click rows) feeds the
        // same picked set as the grid's ticks.
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.getSelectionModel().getSelectedItems().addListener(new ListChangeListener<Book>() {
            @Override
            public void onChanged(Change<? extends Book> change) {
                if (syncingTable) {
                    return;
                }
                List<Book> rows = table.getSelectionModel().getSelectedItems();
                picked.clear();
                if (rows.size() >= 2) {
                    picked.addAll(rows);
                    hideDetail();
                } else if (rows.size() == 1) {
                    showDetail(rows.get(0));
                }
                updateBulkBar();
            }
        });
        table.setPlaceholder(new Label("No books match."));
        categoryColumn.setCellFactory(categoryCells());

        // E52: the table takes whatever width the page gives it and never asks
        // for more, so eight columns cannot push the toolbar or bulk bar off
        // the right edge. The page stretches it to fill anyway.
        table.setMinWidth(0);
        table.setPrefWidth(100);
        table.widthProperty().addListener(new ChangeListener<Number>() {
            @Override
            public void changed(ObservableValue<? extends Number> obs, Number old, Number now) {
                fitColumns(now.doubleValue());
            }
        });
    }

    /**
     * E52: fill when there is room, scroll when there is not.
     * <p>
     * Wider than the columns' natural total, a constrained policy stretches
     * the text columns to fill the table. Narrower, an unconstrained policy
     * keeps every column at its natural width and the table shows a
     * horizontal scrollbar, instead of squeezing text into "...". Each
     * column's minimum is its natural width, so the constrained policy can
     * only ever widen columns, never shrink them.
     *
     * @param width the table's current width
     */
    private void fitColumns(double width) {
        double natural = 0;
        for (int i = 0; i < table.getColumns().size(); i++) {
            natural += table.getColumns().get(i).getMinWidth();
        }
        // Room for the table's border and its vertical scrollbar.
        boolean roomy = width >= natural + 20;
        if (roomy && table.getColumnResizePolicy() != TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS) {
            table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        } else if (!roomy && table.getColumnResizePolicy() != TableView.UNCONSTRAINED_RESIZE_POLICY) {
            table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
            // Undo any stretching from the roomy layout.
            for (int i = 0; i < table.getColumns().size(); i++) {
                TableColumn<Book, ?> column = table.getColumns().get(i);
                column.setPrefWidth(column.getMinWidth());
            }
        }
    }

    /**
     * E51: the Categories cell shows names; hovering shows each category's
     * full path, one per line.
     */
    private Callback<TableColumn<Book, String>, TableCell<Book, String>> categoryCells() {
        return new Callback<TableColumn<Book, String>, TableCell<Book, String>>() {
            @Override
            public TableCell<Book, String> call(TableColumn<Book, String> column) {
                return new TableCell<Book, String>() {
                    @Override
                    protected void updateItem(String item, boolean empty) {
                        super.updateItem(item, empty);
                        Book book = empty || getTableRow() == null ? null : getTableRow().getItem();
                        setText(empty ? null : item);
                        setTooltip(book == null || book.getCategoryIds().isEmpty()
                                ? null : new Tooltip(categoryPaths(book)));
                    }
                };
            }
        };
    }

    /** @return each of the book's categories as a full path, one per line */
    private String categoryPaths(Book book) {
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < book.getCategoryIds().size(); i++) {
            Category c = manager().findCategoryById(book.getCategoryIds().get(i));
            if (c != null) {
                paths.add(c.getPath());
            }
        }
        return String.join("\n", paths);
    }

    /** One cell's text, for {@link #column}. */
    private interface TextOf {
        String of(Book book);
    }

    private static TableColumn<Book, String> column(String name, double width, final TextOf text) {
        TableColumn<Book, String> column = new TableColumn<>(name);
        column.setPrefWidth(width);
        // E52: a column never goes below its natural width (the table
        // scrolls instead). Short columns may not grow either, so all spare
        // width goes to the four text columns.
        boolean flexible = name.equals("Title") || name.equals("Author")
                || name.equals("Categories") || name.equals("Location");
        column.setMinWidth(width);
        if (!flexible) {
            column.setMaxWidth(width);
        }
        column.setCellValueFactory(new Callback<TableColumn.CellDataFeatures<Book, String>,
                ObservableValue<String>>() {
            @Override
            public ObservableValue<String> call(TableColumn.CellDataFeatures<Book, String> cell) {
                return new ReadOnlyStringWrapper(text.of(cell.getValue()));
            }
        });
        return column;
    }

    // ------------------------------------------------------------------
    // Bulk selection (E39) and bulk actions (D11)
    // ------------------------------------------------------------------

    /** Cmd-click: picks or unpicks one book. */
    private void togglePick(Book book, int index) {
        if (picked.contains(book)) {
            picked.remove(book);
        } else {
            picked.add(book);
        }
        anchor = index;
        afterPickChange();
    }

    /**
     * Shift-click: picks every book between the anchor and this one,
     * inclusive, in display order. Without an anchor it acts like cmd-click.
     */
    private void pickRange(int index) {
        if (anchor < 0) {
            togglePick(shown.get(index), index);
            return;
        }
        int from = Math.min(anchor, index);
        int to = Math.max(anchor, index);
        for (int i = from; i <= to; i++) {
            picked.add(shown.get(i));
        }
        afterPickChange();
    }

    /** E39: two or more picks close the detail panel; then repaint the ticks. */
    private void afterPickChange() {
        if (picked.size() >= 2) {
            hideDetail();
        }
        refresh();
    }

    /**
     * Copies the picks into the table's own selection, so both views agree.
     * With nothing picked, the book in the detail panel stays highlighted.
     */
    private void syncTableSelection() {
        syncingTable = true;
        table.getSelectionModel().clearSelection();
        for (int i = 0; i < shown.size(); i++) {
            Book book = shown.get(i);
            if (picked.contains(book) || (picked.isEmpty() && book.equals(selected))) {
                table.getSelectionModel().select(i);
            }
        }
        syncingTable = false;
    }

    private void updateBulkBar() {
        boolean show = picked.size() >= 2;
        bulkBar.setVisible(show);
        bulkBar.setManaged(show);
        bulkCount.setText(picked.size() + " books selected");
    }

    private void clearPicks() {
        if (picked.isEmpty()) {
            return;
        }
        picked.clear();
        anchor = -1;
        refresh();
    }

    @FXML
    private void onClearPicks() {
        clearPicks();
    }

    /** The "Set status" menu: one item per reading status, each with its dot. */
    private void setUpBulkStatus() {
        ReadingStatus[] statuses = ReadingStatus.values();
        for (int i = 0; i < statuses.length; i++) {
            final ReadingStatus status = statuses[i];
            MenuItem item = new MenuItem(status.getLabel(), statusDot(status, 4));
            item.setOnAction(new EventHandler<ActionEvent>() {
                @Override
                public void handle(ActionEvent event) {
                    List<Book> books = pickedInOrder();
                    for (int j = 0; j < books.size(); j++) {
                        books.get(j).setReadingStatus(status);
                    }
                    afterBulk(books.size() + " books marked " + status.getLabel());
                }
            });
            bulkStatus.getItems().add(item);
        }
    }

    /**
     * D11: moves every copy of every picked book. A book with several copies
     * may have them on different shelves, so that case is warned about first.
     */
    @FXML
    private void onBulkLocation() {
        List<Location> all = new ArrayList<>();
        collectLocations(manager().getRootLocations(), all);
        List<String> choices = new ArrayList<>();
        choices.add("Unshelved");
        for (int i = 0; i < all.size(); i++) {
            choices.add(all.get(i).getPath());
        }
        String chosen = choose("Set location", "Move the selected books to:", choices);
        if (chosen == null) {
            return;
        }
        // Index 0 is "Unshelved"; every other choice lines up with all, one place later.
        Location target = chosen.equals("Unshelved") ? null : all.get(choices.indexOf(chosen) - 1);

        List<Book> books = pickedInOrder();
        int multi = 0;
        for (int i = 0; i < books.size(); i++) {
            if (books.get(i).getCopyCount() > 1) {
                multi++;
            }
        }
        if (multi > 0 && !confirm((multi == 1 ? "1 of these books has" : multi + " of these books have")
                + " several copies. All copies will move to " + chosen + ".", "Move all copies")) {
            return;
        }
        for (int i = 0; i < books.size(); i++) {
            List<Copy> copies = books.get(i).getCopies();
            for (int j = 0; j < copies.size(); j++) {
                manager().setLocation(copies.get(j), target);
            }
        }
        afterBulk(books.size() + " books moved to " + chosen);
    }

    /** D11: files every picked book under one more category (existing ones are kept). */
    @FXML
    private void onBulkCategory() {
        List<Category> all = new ArrayList<>();
        collectCategories(manager().getRootCategories(), all);
        List<String> choices = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            choices.add(all.get(i).getPath());
        }
        String chosen = choose("Add category", "File the selected books under:", choices);
        if (chosen == null) {
            return;
        }
        Category target = all.get(choices.indexOf(chosen));
        List<Book> books = pickedInOrder();
        for (int i = 0; i < books.size(); i++) {
            manager().fileUnder(books.get(i), target);
        }
        afterBulk(books.size() + " books filed under " + chosen);
    }

    @FXML
    private void onBulkTrash() {
        List<Book> books = pickedInOrder();
        clearPicks();
        moveToTrash(books);
    }

    /**
     * E73-E75: the one way books go to the Trash, from the bulk bar or the
     * details panel. No confirm: the toast's Undo puts back exactly these
     * books, which is quicker than a dialog and just as safe, because the
     * Trash itself is recoverable (D12).
     */
    private void moveToTrash(final List<Book> books) {
        for (int i = 0; i < books.size(); i++) {
            manager().moveToTrash(books.get(i).getId());
        }
        closeDetail();
        // saveChanges also updates the sidebar's counts and Trash badge.
        boolean saved = shell.saveChanges();
        refresh();
        if (!saved) {
            return;
        }
        String message = books.size() == 1 ? "“" + books.get(0).getTitle() + "” moved to the Trash"
                : books.size() + " books moved to the Trash";
        shell.toast(message, "Undo", new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < books.size(); i++) {
                    // Skip a book deleted for good in the meantime: it no longer exists.
                    if (books.get(i).isDeleted() && manager().findItemById(books.get(i).getId()) != null) {
                        manager().restoreFromTrash(books.get(i).getId());
                    }
                }
                // Undo changes memory, then saves again (E153).
                if (shell.saveChanges()) {
                    shell.toast(books.size() == 1 ? "Moved back to the collection"
                            : books.size() + " books moved back to the collection");
                }
                refresh();
            }
        });
    }

    /** @return the picked books in display order, so actions run top to bottom */
    private List<Book> pickedInOrder() {
        List<Book> books = new ArrayList<>();
        for (int i = 0; i < shown.size(); i++) {
            if (picked.contains(shown.get(i))) {
                books.add(shown.get(i));
            }
        }
        return books;
    }

    /**
     * After a bulk action: save (ADR 0001), repaint and confirm with a toast.
     * The picks are kept, so several actions can be applied to the same
     * books in a row. A failed save has already shown its own dialog, so
     * there is no success toast then.
     */
    private void afterBulk(String message) {
        boolean saved = shell.saveChanges();
        refresh();
        if (saved) {
            shell.toast(message);
        }
    }

    /** Depth-first walk: each location, then everything beneath it. */
    private static void collectLocations(List<Location> level, List<Location> into) {
        for (int i = 0; i < level.size(); i++) {
            into.add(level.get(i));
            collectLocations(level.get(i).getChildren(), into);
        }
    }

    /** Depth-first walk: each category, then everything beneath it. */
    private static void collectCategories(List<Category> level, List<Category> into) {
        for (int i = 0; i < level.size(); i++) {
            into.add(level.get(i));
            collectCategories(level.get(i).getChildren(), into);
        }
    }

    /**
     * A dropdown in a small dialog.
     *
     * @return the chosen text, or null if cancelled
     */
    private String choose(String title, String prompt, List<String> choices) {
        if (choices.isEmpty()) {
            return null;
        }
        ChoiceDialog<String> dialog = new ChoiceDialog<>(choices.get(0), choices);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText(prompt);
        dialog.initOwner(shell.getWindow());
        Optional<String> answer = dialog.showAndWait();
        return answer.isPresent() ? answer.get() : null;
    }

    /**
     * E47: destructive or surprising actions are confirmed with an Alert.
     *
     * @return true if the user pressed the action button
     */
    private boolean confirm(String message, String action) {
        ButtonType go = new ButtonType(action);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.CANCEL, go);
        alert.setHeaderText(null);
        alert.initOwner(shell.getWindow());
        Optional<ButtonType> answer = alert.showAndWait();
        return answer.isPresent() && answer.get() == go;
    }

    /** E47: a short success message at the bottom, gone after 3 seconds. */
    // ------------------------------------------------------------------
    // Detail panel
    // ------------------------------------------------------------------

    private void showDetail(Book book) {
        selected = book;
        detail.getChildren().clear();

        Button close = new Button();
        close.getStyleClass().add("icon-button");
        close.setGraphic(new FontIcon("mdi2c-close"));
        close.setTooltip(new Tooltip("Close (Esc)"));
        close.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                closeDetail();
            }
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        detail.getChildren().add(new HBox(spacer, close));

        HBox coverRow = new HBox(CoverArt.coverFor(book, dataFolder(), 150, 213, true));
        coverRow.setAlignment(Pos.CENTER);
        detail.getChildren().add(coverRow);
        detail.getChildren().add(coverButtons(book));

        Label title = new Label(book.getTitle());
        title.getStyleClass().add("detail-title");
        title.setWrapText(true);
        detail.getChildren().add(title);
        if (book.getSubtitle() != null) {
            Label subtitle = new Label(book.getSubtitle());
            subtitle.getStyleClass().add("detail-subtitle");
            subtitle.setWrapText(true);
            detail.getChildren().add(subtitle);
        }
        detail.getChildren().add(new Label(authorsOf(book)));

        Label stars = new Label(stars(book.getRating()));
        stars.getStyleClass().add("stars");
        Label status = new Label(book.getReadingStatus().getLabel());
        status.getStyleClass().add("status-chip");
        status.setGraphic(statusDot(book.getReadingStatus(), 4));
        status.setGraphicTextGap(6);
        HBox ratingRow = new HBox(10, status, stars);
        ratingRow.setAlignment(Pos.CENTER_LEFT);
        detail.getChildren().add(ratingRow);

        List<String> meta = new ArrayList<>();
        if (book.getPublisher() != null) {
            meta.add(book.getPublisher());
        }
        if (book.getPublicationYear() != 0) {
            meta.add(String.valueOf(book.getPublicationYear()));
        }
        if (book.getLanguage() != null) {
            meta.add(book.getLanguage());
        }
        if (book.getIsbn() != null) {
            meta.add("ISBN " + book.getIsbn());
        }
        Label metaLabel = new Label(String.join("  ·  ", meta));
        metaLabel.getStyleClass().add("muted");
        metaLabel.setWrapText(true);
        detail.getChildren().add(metaLabel);

        if (!book.getCategoryIds().isEmpty()) {
            detail.getChildren().add(section("CATEGORIES"));
            for (int i = 0; i < book.getCategoryIds().size(); i++) {
                Category c = manager().findCategoryById(book.getCategoryIds().get(i));
                if (c != null) {
                    detail.getChildren().add(iconLine("mdi2t-tag-outline", c.getPath()));
                }
            }
        }

        detail.getChildren().add(section(book.getCopyCount() == 1 ? "COPY" : "COPIES (" + book.getCopyCount() + ")"));
        for (int i = 0; i < book.getCopies().size(); i++) {
            Copy copy = book.getCopies().get(i);
            VBox row = new VBox();
            row.getStyleClass().add("copy-row");
            row.getChildren().add(new Label(copy.getFormat().getLabel() + "  ·  " + copy.getCondition().getLabel()));
            row.getChildren().add(iconLine("mdi2m-map-marker-outline", locationText(copy)));
            List<String> bought = new ArrayList<>();
            if (copy.getPurchaseDate() != null) {
                bought.add("Bought " + copy.getPurchaseDate().format(DATE));
            }
            if (copy.hasRecordedPrice()) {
                bought.add(money(copy.getPricePaid()));
            }
            if (!bought.isEmpty()) {
                Label b = new Label(String.join("  ·  ", bought));
                b.getStyleClass().add("muted");
                row.getChildren().add(b);
            }
            detail.getChildren().add(row);
        }

        detail.getChildren().add(section("READING"));
        Label reading = new Label(readingText(book));
        reading.setWrapText(true);
        detail.getChildren().add(reading);

        if (book.getNotes() != null) {
            detail.getChildren().add(section("NOTES"));
            Label notes = new Label(book.getNotes());
            notes.setWrapText(true);
            detail.getChildren().add(notes);
        }
        Map<String, String> fields = book.getCustomFields();
        if (!fields.isEmpty()) {
            detail.getChildren().add(section("MORE"));
            List<String> keys = new ArrayList<>(fields.keySet());
            for (int i = 0; i < keys.size(); i++) {
                detail.getChildren().add(new Label(keys.get(i) + ": " + fields.get(keys.get(i))));
            }
        }

        Button edit = new Button("Edit book");
        edit.getStyleClass().add("accent-button");
        edit.setGraphic(new FontIcon("mdi2p-pencil"));
        edit.setMaxWidth(Double.MAX_VALUE);
        edit.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                // E33: the full form, with Browse still lit in the sidebar.
                AddController add = (AddController) shell.show(PageId.ADD, PageId.BROWSE);
                add.openEdit(book);
            }
        });
        // E73: one book to the Trash; undone from the toast (E74), so no confirm.
        Button trash = new Button("Move to Trash");
        trash.getStyleClass().add("danger-button");
        trash.setGraphic(new FontIcon("mdi2d-delete-outline"));
        trash.setMaxWidth(Double.MAX_VALUE);
        trash.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                List<Book> one = new ArrayList<>();
                one.add(book);
                moveToTrash(one);
            }
        });
        Region gap = new Region();
        gap.setMinHeight(4);
        detail.getChildren().addAll(gap, edit, trash);

        if (panelWidth(mainSplit, false) < SNAPPED_BELOW) {
            openDetail();
        }
        detailScroll.setVvalue(0);
    }

    private void closeDetail() {
        if (picked.isEmpty()) {
            syncingTable = true;
            table.getSelectionModel().clearSelection();
            syncingTable = false;
        }
        hideDetail();
    }

    /**
     * Snaps the detail panel shut without touching the table's selection,
     * so a bulk selection survives (E39: 2+ picked closes the panel).
     */
    private void hideDetail() {
        selected = null;
        if (selectedCard != null) {
            selectedCard.getStyleClass().remove("selected");
            selectedCard = null;
        }
        showEmptyDetail();
        snapDetail();
    }

    /** What the detail panel shows when it is dragged open with no book selected. */
    private void showEmptyDetail() {
        Label empty = new Label("Select a book to see its details.");
        empty.getStyleClass().add("empty-state");
        empty.setWrapText(true);
        detail.getChildren().setAll(empty);
    }

    /**
     * "Change cover..." and, if the book has an uploaded one, "Remove".
     * Uploads are downscaled to 600px and saved as JPEG (Q8-Q10).
     */
    private HBox coverButtons(final Book book) {
        Button change = new Button("Change cover\u2026");
        change.getStyleClass().add("plain-button");
        change.setGraphic(new FontIcon("mdi2i-image-edit-outline"));
        change.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                FileChooser chooser = new FileChooser();
                chooser.setTitle("Choose a cover for " + book.getTitle());
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                        "Images", "*.jpg", "*.jpeg", "*.png", "*.gif", "*.bmp"));
                File file = chooser.showOpenDialog(shell.getWindow());
                if (file == null) {
                    return;
                }
                try {
                    CoverArt.saveUpload(book, dataFolder(), file);
                } catch (IOException e) {
                    Alert alert = new Alert(Alert.AlertType.ERROR, e.getMessage());
                    alert.setHeaderText("Could not use that image");
                    alert.showAndWait();
                    return;
                }
                refresh();
                showDetail(book);
            }
        });
        HBox row = new HBox(8, change);
        row.setAlignment(Pos.CENTER);
        if (Files.exists(CoverArt.uploadedPath(book, dataFolder()))) {
            Button remove = new Button("Remove");
            remove.getStyleClass().add("plain-button");
            remove.setGraphic(new FontIcon("mdi2i-image-remove"));
            remove.setOnAction(new EventHandler<ActionEvent>() {
                @Override
                public void handle(ActionEvent event) {
                    try {
                        CoverArt.removeUpload(book, dataFolder());
                    } catch (IOException e) {
                        return;
                    }
                    refresh();
                    showDetail(book);
                }
            });
            row.getChildren().add(remove);
        }
        return row;
    }

    /**
     * The reading-status colour as a small dot. The colour comes from the
     * {@code status-dot-<status>} style class in app.css, so the dropdown,
     * the details chip and the cover bookmark can never disagree.
     *
     * @param status the reading status
     * @param radius the dot's radius in points
     * @return the dot
     */
    private static Circle statusDot(ReadingStatus status, double radius) {
        Circle dot = new Circle(radius);
        dot.getStyleClass().add(statusClass(status));
        return dot;
    }

    /** @return e.g. "status-dot-reading" */
    private static String statusClass(ReadingStatus status) {
        return "status-dot-" + status.name().toLowerCase(Locale.ROOT);
    }

    /**
     * A bookmark in the status colour, hanging from the cover's top edge,
     * with a thin dark edge so it stands out on any cover colour.
     * <p>
     * The edge is a second, slightly bigger copy of the shape drawn behind
     * it, not a {@code DropShadow} effect: JavaFX draws a node with an effect
     * into a separate image first, and on a Retina screen that image can land
     * half a pixel low, leaving a thin seam above the bookmark. Plain shapes
     * at whole-pixel positions are drawn exactly where they are.
     *
     * @param status the reading status, for the colour
     * @param coverW the cover's width; the bookmark is about 9% of it
     * @param coverH the cover's height; the bookmark is about 17% of it
     * @return the edge and the bookmark, grouped so they position as one
     */
    private static Group bookmark(ReadingStatus status, double coverW, double coverH) {
        double w = Math.max(10, coverW * 0.09);
        double h = coverH * 0.17;
        double r = Math.max(1.0, coverW / 90);

        // 1px wider on each side and 1px longer, but the same top: the dark
        // edge shows left, right and below, never above.
        javafx.scene.shape.Path edge = ribbon(w + 2, h + 1, r);
        edge.setLayoutX(-1);
        edge.getStyleClass().add("bookmark-edge");

        javafx.scene.shape.Path ribbon = ribbon(w, h, r);
        ribbon.getStyleClass().addAll("bookmark", statusClass(status));
        // Drawn in order, so the ribbon (added last) sits on top of its edge.
        return new Group(edge, ribbon);
    }

    /**
     * A strip with a V notch cut into its bottom. Built as a JavaFX
     * {@code Path} - a pen that moves, draws straight lines and curves - so
     * the three bottom corners can be rounded slightly with
     * {@link QuadCurveTo}: each sharp corner is replaced by a curve that
     * starts {@code r} before the corner and ends {@code r} after it,
     * bending towards the corner point.
     * <p>
     * Written as {@code javafx.scene.shape.Path} in full because this class
     * already imports {@code java.nio.file.Path} for folders; Java cannot
     * import two classes with the same simple name.
     *
     * @param w width
     * @param h height
     * @param r corner rounding
     * @return the shape, top-left corner at (0, 0)
     */
    private static javafx.scene.shape.Path ribbon(double w, double h, double r) {
        double notch = w * 0.45;

        // Corners: top-left (0,0), top-right (w,0), bottom-right tip (w,h),
        // notch point (w/2, h-notch), bottom-left tip (0,h).
        double[] toNotch = unit(w / 2 - w, (h - notch) - h);
        double[] fromNotch = unit(0 - w / 2, h - (h - notch));

        javafx.scene.shape.Path path = new javafx.scene.shape.Path();
        path.getElements().add(new MoveTo(0, 0));
        path.getElements().add(new LineTo(w, 0));
        // Bottom-right tip.
        path.getElements().add(new LineTo(w, h - r));
        path.getElements().add(new QuadCurveTo(w, h, w + toNotch[0] * r, h + toNotch[1] * r));
        // Notch point.
        path.getElements().add(new LineTo(w / 2 - toNotch[0] * r, (h - notch) - toNotch[1] * r));
        path.getElements().add(new QuadCurveTo(w / 2, h - notch,
                w / 2 + fromNotch[0] * r, (h - notch) + fromNotch[1] * r));
        // Bottom-left tip.
        path.getElements().add(new LineTo(0 - fromNotch[0] * r, h - fromNotch[1] * r));
        path.getElements().add(new QuadCurveTo(0, h, 0, h - r));
        path.getElements().add(new ClosePath());

        // No outline: a stroke would be drawn half outside the top edge.
        path.setStroke(null);
        return path;
    }

    /** @return the vector (x, y) scaled to length 1 */
    private static double[] unit(double x, double y) {
        double length = Math.sqrt(x * x + y * y);
        return new double[] {x / length, y / length};
    }

    private static Label section(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-label");
        label.setPadding(new javafx.geometry.Insets(6, 0, 0, 0));
        return label;
    }

    private static HBox iconLine(String icon, String text) {
        FontIcon glyph = new FontIcon(icon);
        glyph.setIconSize(15);
        Label label = new Label(text);
        label.setWrapText(true);
        HBox line = new HBox(6, glyph, label);
        line.setAlignment(Pos.CENTER_LEFT);
        return line;
    }

    private static String authorsOf(Book book) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < book.getContributors().size(); i++) {
            Contributor c = book.getContributors().get(i);
            names.add(c.getRole().name().equals("AUTHOR")
                    ? c.getName() : c.getName() + " (" + c.getRole().getLabel().toLowerCase(Locale.ROOT) + ")");
        }
        return names.isEmpty() ? "Unknown author" : String.join(", ", names);
    }

    private static String stars(int rating) {
        if (rating == 0) {
            return "";
        }
        StringBuilder s = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            s.append(i <= rating ? '★' : '☆');
        }
        return s.toString();
    }

    /**
     * The book's categories by name, comma-separated. Names, not full paths:
     * a book in two nested categories would otherwise fill the column with
     * repeated parents ("Fiction > Classics, Fiction > Indian").
     */
    private String categoriesText(Book book) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < book.getCategoryIds().size(); i++) {
            Category c = manager().findCategoryById(book.getCategoryIds().get(i));
            if (c != null) {
                names.add(c.getName());
            }
        }
        return String.join(", ", names);
    }

    private String locationText(Copy copy) {
        Location location = manager().findLocationById(copy.getLocationId());
        return location == null ? "Unshelved" : location.getPath();
    }

    private String money(double amount) {
        return formatMoney(manager().getCurrencyCode(), amount);
    }

    /**
     * An amount in the collection's currency (D15), e.g. "₹15,898". Static
     * and package-visible so the Wishlist page formats prices the same way.
     *
     * @param currencyCode an ISO 4217 code, e.g. "INR"
     */
    static String formatMoney(String currencyCode, double amount) {
        Currency currency = Currency.getInstance(currencyCode);
        String symbol = currency.getSymbol(Locale.forLanguageTag("en-IN"));
        return amount == Math.floor(amount)
                ? symbol + String.format(Locale.ROOT, "%,.0f", amount)
                : symbol + String.format(Locale.ROOT, "%,.2f", amount);
    }

    private static String readingText(Book book) {
        List<ReadingSession> sessions = book.getReadingSessions();
        ReadingSession open = null;
        LocalDate lastFinished = null;
        for (int i = 0; i < sessions.size(); i++) {
            ReadingSession s = sessions.get(i);
            if (s.getEndDate() == null) {
                open = s;
            } else if (lastFinished == null || s.getEndDate().isAfter(lastFinished)) {
                lastFinished = s.getEndDate();
            }
        }
        int times = book.getTimesRead();
        if (book.getReadingStatus() == ReadingStatus.READING && open != null) {
            return "Reading since " + open.getStartDate().format(DATE)
                    + (times > 0 ? "  ·  read " + times + (times == 1 ? " time" : " times") + " before" : "");
        }
        if (book.getReadingStatus() == ReadingStatus.ABANDONED) {
            return "Abandoned" + (open != null ? " after starting on " + open.getStartDate().format(DATE) : "");
        }
        if (times > 0) {
            return "Read " + (times == 1 ? "once" : times + " times")
                    + "  ·  last finished " + lastFinished.format(DATE);
        }
        return "Not started yet";
    }

    private static ReadingStatus statusFromLabel(String label) {
        ReadingStatus[] statuses = ReadingStatus.values();
        for (int i = 0; i < statuses.length; i++) {
            if (statuses[i].getLabel().equals(label)) {
                return statuses[i];
            }
        }
        return null;
    }
}
