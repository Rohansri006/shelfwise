package com.shelfwise.desktop.ui;

import com.shelfwise.core.exception.ShelfWiseException;
import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.CollectionItem;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.util.Callback;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One tab of the Organise page (E41, E89-E102): toolbar, banner, tree and
 * empty state, for either kind of tree. Add a top-level node or a
 * sub-node, rename it in place, move it under another node, delete it (with
 * Undo), or show its books in Browse.
 * <p>
 * Generic ({@code <T>}) so one class serves both tabs: everything that
 * differs between categories and locations comes from the
 * {@link Organisable} it is given - the same idea as {@link TreePicker}.
 * The page ({@link OrganiseController}) makes two of these.
 * <p>
 * Every change is saved straight after it (ADR 0001) through
 * {@link Shell#saveChanges()}.
 *
 * @param <T> the node type, {@code Category} or {@code Location}
 */
final class TreeTab<T> {

    /** E97: Shortcut is Cmd on the Mac, Ctrl elsewhere (as in E82). */
    private static final KeyCombination ADD_CHILD =
            new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination ADD_ROOT =
            new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);


    private final Organisable<T> tree;
    /** "category", "categories", "sub-category", "sub-categories". */
    private final String noun;
    private final String nouns;
    private final String childNoun;
    private final String childNouns;
    /** E100/E102: "Remove from books" / "Unshelve copies", "unfiled" / "unshelved", "books" / "copies". */
    private final String removeLabel;
    private final String removedWord;
    private final String moveNoun;
    private final TreePicker.Chosen<T> onShowInBrowse;
    private final Session session;
    private final Shell shell;

    /** The tab's whole view: toolbar, banner, and the tree or the empty state. */
    final VBox pane;
    private final TreeView<T> view = new TreeView<>();
    private final HBox banner = new HBox();
    private final Label bannerText = new Label();
    private final HBox bannerActions = new HBox(6);
    private final VBox emptyState;

    private final Button addChildButton;
    private final Button renameButton;
    private final Button moveButton;
    private final Button deleteButton;
    private final Button browseButton;
    private final MenuItem addChildItem = new MenuItem();
    private final MenuItem renameItem = new MenuItem("Rename");
    private final MenuItem moveItem = new MenuItem("Move to…");
    private final MenuItem deleteItem = new MenuItem("Delete");
    private final MenuItem browseItem = new MenuItem("Show in Browse");

    /**
     * Ids of the expanded nodes, kept across rebuilds and visits (E98).
     * Ids, not tree items, because every refresh makes new tree items.
     */
    private final Set<String> expanded = new HashSet<>();
    private boolean firstFill = true;

    /** Per node id: {books in its subtree, books filed on it directly} (E89). */
    private final Map<String, int[]> counts = new HashMap<>();

    /**
     * E94: the row being typed into for a new node. Its value is null
     * because the node does not exist until the name is accepted.
     */
    private TreeItem<T> pending;

    /** True while a finished edit is being closed, so closing it is not treated as Esc. */
    private boolean committing;

    /**
     * @param tree           the tree's data and its changes
     * @param session        the open collection, for counting books
     * @param shell          the window, for saving, toasts and dialogs
     * @param noun           "category" / "location"; the next three are its
     *                       plural and the sub-node words, singular and plural
     * @param removeLabel    E100: the red delete choice, "Remove from books" /
     *                       "Unshelve copies"
     * @param removedWord    E102: the toast's word for it, "unfiled" / "unshelved"
     * @param moveNoun       what moves up to the parent: "books" / "copies"
     * @param emptyLine      E98: the empty state's explanation
     * @param onShowInBrowse E96: opens Browse filtered to a node
     */
    TreeTab(Organisable<T> tree, Session session, Shell shell,
            String noun, String nouns, String childNoun, String childNouns,
            String removeLabel, String removedWord, String moveNoun, String emptyLine,
            TreePicker.Chosen<T> onShowInBrowse) {
        this.tree = tree;
        this.session = session;
        this.shell = shell;
        this.noun = noun;
        this.nouns = nouns;
        this.childNoun = childNoun;
        this.childNouns = childNouns;
        this.removeLabel = removeLabel;
        this.removedWord = removedWord;
        this.moveNoun = moveNoun;
        this.onShowInBrowse = onShowInBrowse;

        // Each action is one Runnable, shared by its button and its menu item.
        Runnable addRoot = new Runnable() {
            @Override public void run() { addNode(false); }
        };
        Runnable addChild = new Runnable() {
            @Override public void run() { addNode(true); }
        };
        Runnable rename = new Runnable() {
            @Override public void run() { startRename(); }
        };
        Runnable move = new Runnable() {
            @Override public void run() { openMovePicker(); }
        };
        Runnable delete = new Runnable() {
            @Override public void run() { deleteSelected(); }
        };
        Runnable browse = new Runnable() {
            @Override public void run() { showSelectedInBrowse(); }
        };

        Button addRootButton = button("Add " + noun, "mdi2f-folder-plus-outline", "accent-button", addRoot);
        addChildButton = button("Add " + childNoun, "mdi2s-subdirectory-arrow-right", "plain-button", addChild);
        renameButton = button("Rename", "mdi2p-pencil-outline", "plain-button", rename);
        moveButton = button("Move to…", "mdi2f-folder-move-outline", "plain-button", move);
        deleteButton = button("Delete", "mdi2d-delete-outline", "danger-button", delete);
        browseButton = button("Show in Browse", "mdi2b-book-search-outline", "plain-button", browse);
        HBox toolbar = new HBox(8, addRootButton, addChildButton, renameButton, moveButton,
                deleteButton, browseButton);
        toolbar.getStyleClass().add("toolbar-row");

        banner.getStyleClass().add("banner");
        bannerText.setWrapText(true);
        bannerText.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(bannerText, Priority.ALWAYS);
        banner.getChildren().addAll(bannerText, bannerActions);
        hideBanner();

        setUpTree();
        setUpContextMenu(addRoot, addChild, rename, move, delete, browse);
        emptyState = emptyState(emptyLine, addRoot);

        StackPane body = new StackPane(view, emptyState);
        VBox.setVgrow(body, Priority.ALWAYS);
        pane = new VBox(12, toolbar, banner, body);
        updateButtons();
    }

    private Button button(String text, String icon, String style, final Runnable action) {
        Button button = new Button(text);
        button.setGraphic(new FontIcon(icon));
        button.getStyleClass().add(style);
        button.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                action.run();
            }
        });
        return button;
    }

    private void setUpTree() {
        view.setShowRoot(false);
        view.setRoot(new TreeItem<T>());
        // Editable so a cell can switch into its text field (E93, E94).
        view.setEditable(true);
        view.setCellFactory(new Callback<TreeView<T>, TreeCell<T>>() {
            @Override
            public TreeCell<T> call(TreeView<T> v) {
                return new EditCell();
            }
        });
        view.getSelectionModel().selectedItemProperty().addListener(new ChangeListener<TreeItem<T>>() {
            @Override
            public void changed(ObservableValue<? extends TreeItem<T>> obs, TreeItem<T> old, TreeItem<T> now) {
                // A banner is about the node it was shown for.
                hideBanner();
                updateButtons();
            }
        });
        // E97. A filter, not a handler: it runs before the tree's own key
        // handling, which would otherwise use Enter and the arrows first.
        view.addEventFilter(KeyEvent.KEY_PRESSED, new EventHandler<KeyEvent>() {
            @Override
            public void handle(KeyEvent event) {
                // Keys typed into a name being edited belong to the text field.
                if (event.getTarget() instanceof TextInputControl || view.getEditingItem() != null) {
                    return;
                }
                if (ADD_ROOT.match(event)) {
                    addNode(false);
                } else if (ADD_CHILD.match(event)) {
                    addNode(true);
                } else if (event.getCode() == KeyCode.F2 || event.getCode() == KeyCode.ENTER) {
                    startRename();
                } else if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) {
                    deleteSelected();
                } else {
                    return;
                }
                event.consume();
            }
        });
    }

    /** E97: right-click offers the toolbar's actions; a right-click also selects the row. */
    private void setUpContextMenu(Runnable addRoot, Runnable addChild, Runnable rename, Runnable move,
                                  Runnable delete, Runnable browse) {
        MenuItem addRootItem = new MenuItem("Add " + noun);
        addChildItem.setText("Add " + childNoun);
        onAction(addRootItem, addRoot);
        onAction(addChildItem, addChild);
        onAction(renameItem, rename);
        onAction(moveItem, move);
        onAction(deleteItem, delete);
        onAction(browseItem, browse);
        ContextMenu menu = new ContextMenu(addChildItem, renameItem, moveItem, deleteItem,
                new SeparatorMenuItem(), browseItem, new SeparatorMenuItem(), addRootItem);
        view.setContextMenu(menu);
    }

    private void onAction(MenuItem item, final Runnable action) {
        item.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                action.run();
            }
        });
    }

    /** E98: shown instead of an empty tree. */
    private VBox emptyState(String line, final Runnable addRoot) {
        FontIcon icon = new FontIcon("mdi2f-file-tree-outline");
        icon.setIconSize(48);
        icon.getStyleClass().add("empty-icon");
        Label heading = new Label("No " + nouns + " yet");
        heading.getStyleClass().add("card-title");
        Label text = new Label(line);
        text.getStyleClass().add("muted");
        text.setWrapText(true);
        text.setTextAlignment(TextAlignment.CENTER);
        text.setMaxWidth(340);
        Button add = button("Add " + noun, "mdi2f-folder-plus-outline", "accent-button", addRoot);
        VBox box = new VBox(10, icon, heading, text, add);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    // ------------------------------------------------------------------
    // Filling the tree
    // ------------------------------------------------------------------

    /**
     * Rebuilds the tree from the collection, after any change. The
     * expansion and the selection are remembered by id first and put back
     * after, so a rebuild looks like nothing moved (E98).
     */
    void refresh() {
        cancelPending();
        if (firstFill) {
            // E98: the first visit opens the top level only.
            List<T> roots = tree.roots();
            for (int i = 0; i < roots.size(); i++) {
                expanded.add(tree.id(roots.get(i)));
            }
            firstFill = false;
        } else {
            expanded.clear();
            rememberExpanded(view.getRoot());
        }
        T selected = selectedNode();

        computeCounts();
        view.getRoot().getChildren().clear();
        fill(view.getRoot(), tree.roots());
        if (selected != null) {
            select(selected);
        }
        updateEmpty();
        updateButtons();
    }

    private void rememberExpanded(TreeItem<T> parent) {
        for (int i = 0; i < parent.getChildren().size(); i++) {
            TreeItem<T> child = parent.getChildren().get(i);
            if (child.isExpanded() && child.getValue() != null) {
                expanded.add(tree.id(child.getValue()));
            }
            rememberExpanded(child);
        }
    }

    /** Adds nodes sorted by name, then their children: recursion, one level per call. */
    private void fill(TreeItem<T> parent, List<T> nodes) {
        List<T> sorted = new ArrayList<>(nodes);
        Collections.sort(sorted, new Comparator<T>() {
            @Override
            public int compare(T a, T b) {
                return tree.name(a).compareToIgnoreCase(tree.name(b));
            }
        });
        for (int i = 0; i < sorted.size(); i++) {
            TreeItem<T> item = new TreeItem<>(sorted.get(i));
            item.setExpanded(expanded.contains(tree.id(sorted.get(i))));
            parent.getChildren().add(item);
            fill(item, tree.children(sorted.get(i)));
        }
    }

    /**
     * E89/E90: for every node, the live books in its whole subtree and
     * those filed on the node itself. The subtree count is counted, not
     * summed from the children: a book in two sibling categories would
     * otherwise count twice in their parent.
     */
    private void computeCounts() {
        counts.clear();
        List<CollectionItem> live = session.getManager().getItems();
        List<T> roots = tree.roots();
        for (int r = 0; r < roots.size(); r++) {
            List<T> nodes = tree.subtree(roots.get(r));
            for (int i = 0; i < nodes.size(); i++) {
                T node = nodes.get(i);
                Set<String> own = new HashSet<>();
                own.add(tree.id(node));
                counts.put(tree.id(node), new int[] {
                    countBooks(live, ids(tree.subtree(node)), tree),
                    countBooks(live, own, tree)
                });
            }
        }
    }

    private Set<String> ids(List<T> nodes) {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < nodes.size(); i++) {
            ids.add(tree.id(nodes.get(i)));
        }
        return ids;
    }

    /**
     * A row's text: "Fiction  (12)", or "Fiction  (12 · 3 here)" when the
     * node has sub-nodes and books of its own (E89). A leaf's two numbers
     * are always equal, so it never shows "here".
     */
    String label(T node) {
        if (node == null) {
            return "New " + noun;
        }
        int[] c = counts.get(tree.id(node));
        if (c == null) {
            return tree.name(node);
        }
        String text = tree.name(node) + "  (" + c[0];
        if (!tree.children(node).isEmpty() && c[1] > 0) {
            text += " · " + c[1] + " here";
        }
        return text + ")";
    }

    /** Selects a node, opening its ancestors and scrolling it into view. */
    private void select(T node) {
        TreeItem<T> item = find(view.getRoot(), tree.id(node));
        if (item == null) {
            return;
        }
        for (TreeItem<T> up = item.getParent(); up != null; up = up.getParent()) {
            up.setExpanded(true);
        }
        view.getSelectionModel().select(item);
        view.scrollTo(view.getRow(item));
    }

    /** Depth-first search for the tree item holding a node id: recursion again. */
    private TreeItem<T> find(TreeItem<T> parent, String id) {
        for (int i = 0; i < parent.getChildren().size(); i++) {
            TreeItem<T> child = parent.getChildren().get(i);
            if (child.getValue() != null && tree.id(child.getValue()).equals(id)) {
                return child;
            }
            TreeItem<T> deeper = find(child, id);
            if (deeper != null) {
                return deeper;
            }
        }
        return null;
    }

    /** @return the selected node, or null for none or the new-node row */
    T selectedNode() {
        TreeItem<T> item = view.getSelectionModel().getSelectedItem();
        return item == null ? null : item.getValue();
    }

    private void updateEmpty() {
        boolean none = view.getRoot().getChildren().isEmpty();
        emptyState.setVisible(none);
        view.setVisible(!none);
    }

    /** E99: only "Add <noun>" works with nothing selected. */
    private void updateButtons() {
        boolean none = selectedNode() == null;
        addChildButton.setDisable(none);
        renameButton.setDisable(none);
        moveButton.setDisable(none);
        deleteButton.setDisable(none);
        browseButton.setDisable(none);
        addChildItem.setDisable(none);
        renameItem.setDisable(none);
        moveItem.setDisable(none);
        deleteItem.setDisable(none);
        browseItem.setDisable(none);
    }

    // ------------------------------------------------------------------
    // Banner
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // Add and rename (E93, E94)
    // ------------------------------------------------------------------

    /**
     * E94: a "New category" row, already being typed into, under the
     * selected node (or at the top level). Nothing is created until the
     * name is accepted, so Esc leaves no trace.
     */
    private void addNode(boolean asChild) {
        TreeItem<T> parent = asChild ? view.getSelectionModel().getSelectedItem() : view.getRoot();
        if (parent == null || parent.getValue() == null && parent != view.getRoot()) {
            return;
        }
        cancelPending();
        hideBanner();
        pending = new TreeItem<>();
        parent.getChildren().add(pending);
        parent.setExpanded(true);
        updateEmpty();
        view.getSelectionModel().select(pending);
        view.scrollTo(view.getRow(pending));
        // The row's cell is only made at the next layout pass; editing needs it.
        view.layout();
        view.edit(pending);
    }

    /** E93: the selected row turns into a text field. */
    private void startRename() {
        TreeItem<T> item = view.getSelectionModel().getSelectedItem();
        if (item != null && item.getValue() != null) {
            hideBanner();
            // As in addNode: straight after a rebuild the row has no cell yet.
            view.layout();
            view.edit(item);
        }
    }

    /** Drops an unfinished new row, if there is one. */
    void cancelPending() {
        if (view.getEditingItem() != null) {
            // Ends the edit; EditCell.cancelEdit removes a pending row.
            view.edit(null);
        }
        removePending();
    }

    private void removePending() {
        if (pending != null && pending.getParent() != null) {
            pending.getParent().getChildren().remove(pending);
        }
        pending = null;
        updateEmpty();
    }

    /**
     * Enter in a row's text field: creates the new node or renames the
     * existing one through the manager. A refused name keeps the field
     * open with a red border and the reason in the banner (E93).
     *
     * @return true if the name was accepted
     */
    private boolean commitName(TreeItem<T> item, TextField field) {
        T done;
        boolean created = item == pending;
        try {
            if (created) {
                TreeItem<T> parent = item.getParent();
                done = tree.create(field.getText(), parent == view.getRoot() ? null : parent.getValue());
            } else {
                done = item.getValue();
                tree.rename(done, field.getText());
            }
        } catch (IllegalArgumentException e) {
            // Blank, or a sibling already has the name.
            if (!field.getStyleClass().contains("field-error")) {
                field.getStyleClass().add("field-error");
            }
            showBanner("error", e.getMessage());
            return false;
        }
        committing = true;
        view.edit(null);
        committing = false;
        removePending();
        hideBanner();
        refresh();
        select(done);
        // A new node was saved by tree.create, as in the Add page's picker;
        // a rename is saved here (ADR 0001).
        if (!created) {
            shell.saveChanges();
        }
        return true;
    }

    /** An edit ended without Enter (Esc, or a click elsewhere). */
    private void editCancelled(TreeItem<T> item) {
        if (committing) {
            return;
        }
        hideBanner();
        if (item != null && item == pending) {
            removePending();
        }
    }

    // ------------------------------------------------------------------
    // Move, delete, Show in Browse
    // ------------------------------------------------------------------

    /** E95: the picker, with the node and everything under it greyed out. */
    private void openMovePicker() {
        final T node = selectedNode();
        if (node == null) {
            return;
        }
        hideBanner();
        TreePicker<T> movePicker = new TreePicker<>(tree, noun, "Top level", new TreePicker.Chosen<T>() {
            @Override
            public void chosen(T newParent) {
                moveTo(node, newParent);
            }
        });
        movePicker.disable(tree.subtree(node));
        movePicker.hideNewRow();
        movePicker.showBelow(moveButton);
    }

    void moveTo(T node, T newParent) {
        T oldParent = tree.parent(node);
        if (oldParent == null ? newParent == null : oldParent.equals(newParent)) {
            return;
        }
        try {
            tree.move(node, newParent);
        } catch (IllegalArgumentException e) {
            // A node of that name is already there.
            showBanner("error", e.getMessage());
            return;
        }
        refresh();
        select(node);
        shell.saveChanges();
    }

    /**
     * E92/E100. An empty leaf goes at once. An empty node with sub-nodes
     * asks first, since they all go. A node with books asks where the
     * books go: up to the parent, or out of the deleted nodes. Every
     * delete can then be undone from the toast (E102).
     */
    private void deleteSelected() {
        T node = selectedNode();
        if (node == null) {
            return;
        }
        List<T> subtree = tree.subtree(node);
        Set<String> ids = ids(subtree);
        int live = countBooks(session.getManager().getItems(), ids, tree);
        int trashed = countBooks(session.getManager().getTrash(), ids, tree);
        int books = live + trashed;
        int below = subtree.size() - 1;
        T parent = tree.parent(node);

        if (books > 0) {
            String message = describe(node, below) + (below > 0 ? " hold " : " holds ")
                    + count(books, "book", "books")
                    + (trashed > 0 ? " (" + trashed + " in the Trash)" : "")
                    + ". Delete " + (below > 0 ? "them" : "it") + " anyway? The books stay in your collection.";
            ButtonType remove = new ButtonType(removeLabel);
            ButtonType answer;
            if (parent == null) {
                // A top-level node has nowhere to move its books up to.
                answer = choose(message, remove);
            } else {
                ButtonType move = new ButtonType("Move " + moveNoun + " to \u2018" + tree.name(parent) + "\u2019");
                answer = choose(message, remove, move);
                if (answer == move) {
                    deleteNode(node, true);
                    return;
                }
            }
            if (answer == remove) {
                deleteNode(node, false);
            }
            return;
        }
        if (below > 0 && !confirm("Delete " + describe(node, below) + "?", "Delete")) {
            return;
        }
        deleteNode(node, false);
    }

    /** "‘Fiction’" or "‘Fiction’ and its 4 sub-categories". */
    private String describe(T node, int below) {
        String name = "\u2018" + tree.name(node) + "\u2019";
        return below > 0 ? name + " and its " + count(below, childNoun, childNouns) : name;
    }

    /**
     * Frees the subtree of books (E101), deletes it, and offers Undo
     * (E102). Asks nothing: the caller already has.
     *
     * @param moveUp true to file the books under the parent, false to remove them
     */
    private void deleteNode(final T node, boolean moveUp) {
        final T parent = tree.parent(node);
        List<T> subtree = tree.subtree(node);
        Set<String> ids = ids(subtree);
        int books = countBooks(session.getManager().getItems(), ids, tree)
                + countBooks(session.getManager().getTrash(), ids, tree);
        String what = describe(node, subtree.size() - 1);

        final List<Runnable> undo = tree.release(node, moveUp ? parent : null);
        try {
            tree.delete(node);
        } catch (ShelfWiseException e) {
            // Every book was just freed, so this should not happen; if it
            // does, put the books back and show the manager's reason.
            runAll(undo);
            showBanner("error", e.getMessage());
            return;
        }
        refresh();
        if (parent != null) {
            select(parent);
        }
        // The release and the delete are one logical operation: one save.
        shell.saveChanges();

        String effect = "";
        if (books > 0) {
            effect = " \u00b7 " + count(books, "book", "books")
                    + (moveUp ? " moved to \u2018" + tree.name(parent) + "\u2019" : " " + removedWord);
        }
        shell.toast("Deleted " + what + effect, "Undo", new Runnable() {
            @Override
            public void run() {
                undoDelete(node, parent, undo);
            }
        });
    }

    /**
     * E102: the node comes back with its whole subtree (the sub-nodes
     * stayed linked to it), then every book is filed or shelved exactly
     * as before. The node must come first: filing under a category the
     * manager does not know is refused.
     */
    private void undoDelete(T node, T parent, List<Runnable> undo) {
        try {
            tree.restore(node, parent);
        } catch (IllegalArgumentException e) {
            // e.g. its parent was deleted since, or the name is taken now.
            shell.toast("Couldn't undo: " + e.getMessage());
            return;
        }
        runAll(undo);
        refresh();
        select(node);
        // E153: Undo changes memory, then saves again.
        shell.saveChanges();
    }

    private void runAll(List<Runnable> steps) {
        for (int i = 0; i < steps.size(); i++) {
            steps.get(i).run();
        }
    }

    private void showSelectedInBrowse() {
        T node = selectedNode();
        if (node != null) {
            onShowInBrowse.chosen(node);
        }
    }

    // ------------------------------------------------------------------
    // Counting and dialogs
    // ------------------------------------------------------------------

    /**
     * Counts the books with at least one id in a set. Used for the tree's
     * counts (E89, E90) and for the delete confirm (E100), on live books or
     * on the Trash.
     *
     * @param items the books to look through
     * @param ids   the node ids to look for, as a HashSet so each test is quick
     * @param tree  how to read a book's ids for this kind of tree
     */
    private static <N> int countBooks(List<CollectionItem> items, Set<String> ids, Organisable<N> tree) {
        int count = 0;
        for (int i = 0; i < items.size(); i++) {
            List<String> own = tree.idsOf((Book) items.get(i));
            for (int j = 0; j < own.size(); j++) {
                if (ids.contains(own.get(j))) {
                    // Counted once even when two of its ids match: a book on
                    // two shelves of one bookcase is still one book (E90).
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    /** "1 sub-category" / "4 sub-categories": the count with the right word. */
    private static String count(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    /**
     * Asks before deleting a node with sub-nodes (E92). The action button is
     * red, the same way as the Trash's confirms: Modena derives a button's
     * whole look from {@code -fx-base}.
     *
     * @return true if the user pressed the action button
     */
    private boolean confirm(String message, String action) {
        ButtonType go = new ButtonType(action);
        return choose(message, go) == go;
    }

    /**
     * A warning with Cancel, any ordinary choices, and one red choice
     * (E100: "Move books to …" beside "Remove from books").
     *
     * @param danger the red, destructive choice
     * @param others ordinary choices shown before it; may be none
     *               ({@code ...} is varargs: any number of arguments)
     * @return the button pressed, or Cancel if the dialog was closed
     */
    private ButtonType choose(String message, ButtonType danger, ButtonType... others) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message, ButtonType.CANCEL);
        alert.getButtonTypes().addAll(others);
        alert.getButtonTypes().add(danger);
        alert.setHeaderText(null);
        alert.initOwner(shell.getWindow());
        alert.getDialogPane().lookupButton(danger).setStyle("-fx-base: #dc2626;");
        Optional<ButtonType> answer = alert.showAndWait();
        return answer.isPresent() ? answer.get() : ButtonType.CANCEL;
    }

    // ------------------------------------------------------------------
    // The editable row
    // ------------------------------------------------------------------

    /**
     * A tree row that can turn into a text field. The TreeView calls
     * {@code startEdit} and {@code cancelEdit} (from {@code view.edit});
     * Enter goes to {@link #commitName} rather than the cell's own
     * {@code commitEdit}, because the name is checked by the manager and
     * a new node does not exist until it is accepted.
     */
    private final class EditCell extends TreeCell<T> {

        private TextField field;

        @Override
        public void startEdit() {
            super.startEdit();
            if (!isEditing()) {
                return;
            }
            final TreeItem<T> item = getTreeItem();
            field = new TextField(item.getValue() == null ? "New " + noun : tree.name(item.getValue()));
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
            TreeItem<T> item = getTreeItem();
            super.cancelEdit();
            setGraphic(null);
            setText(label(getItem()));
            editCancelled(item);
            view.requestFocus();
        }

        @Override
        protected void updateItem(T item, boolean empty) {
            super.updateItem(item, empty);
            // A pending row holds null but is not empty: its tree item exists.
            if (empty || getTreeItem() == null) {
                setText(null);
                setGraphic(null);
            } else if (isEditing()) {
                setText(null);
                setGraphic(field);
            } else {
                setText(label(item));
                setGraphic(null);
            }
        }
    }
}
