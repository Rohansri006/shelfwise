package com.shelfwise.desktop.ui;

import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.util.Callback;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * A popup holding a category or location tree, used wherever a screen needs
 * one picked (E62, E65), with a "+ New" row that creates a child of the
 * highlighted node (E69).
 * <p>
 * Generic ({@code <T>}) so one class serves both trees: the parts that
 * differ - where the roots come from, a node's children and name, how a
 * new node is created - are supplied through the {@link Source} interface.
 * That is the same idea as {@code Searchable} or {@code MetadataProvider}:
 * the picker codes against an interface, never a concrete tree class.
 *
 * @param <T> the node type, {@code Category} or {@code Location}
 */
final class TreePicker<T> {

    /** What the picker needs to know about one kind of tree. */
    interface Source<T> {

        /** @return the top-level nodes */
        List<T> roots();

        /** @return a node's direct children */
        List<T> children(T node);

        /** @return the name shown for a node */
        String name(T node);

        /**
         * Creates a node, adds it to the collection and saves.
         *
         * @param name   the new node's name
         * @param parent the highlighted node, or null for a root node
         * @return the new node
         * @throws IllegalArgumentException if the name is blank or taken
         *                                  among its siblings
         */
        T create(String name, T parent);
    }

    /** Told which node the user picked; null means the "none" row (e.g. Unshelved). */
    interface Chosen<T> {
        void chosen(T node);
    }

    private final Source<T> source;
    private final String noun;
    private final String noneLabel;
    private final Chosen<T> onChosen;

    private final Popup popup = new Popup();
    private final TreeView<T> tree = new TreeView<>();
    private final TextField newName = new TextField();
    private final Label error = new Label();

    /**
     * @param source    the tree's data
     * @param noun      "category" or "location", for the "+ New" prompt
     * @param noneLabel a row above the tree meaning "none" (e.g.
     *                  "Unshelved"), or null for no such row
     * @param onChosen  called with the picked node
     */
    TreePicker(Source<T> source, String noun, String noneLabel, Chosen<T> onChosen) {
        this.source = source;
        this.noun = noun;
        this.noneLabel = noneLabel;
        this.onChosen = onChosen;
        build();
    }

    /**
     * Opens the popup just below a node, usually the button that asked for it.
     *
     * @param anchor the node to open under
     */
    void showBelow(Node anchor) {
        // A popup is its own window with its own scene, so it does not see
        // the main window's stylesheets unless they are copied across.
        VBox content = (VBox) popup.getContent().get(0);
        content.getStylesheets().setAll(anchor.getScene().getStylesheets());
        Bounds b = anchor.localToScreen(anchor.getBoundsInLocal());
        popup.show(anchor.getScene().getWindow(), b.getMinX(), b.getMaxY() + 4);
        newName.clear();
        error.setText("");
    }

    private void build() {
        tree.setShowRoot(false);
        tree.setRoot(new TreeItem<T>());
        fill(tree.getRoot(), source.roots());
        tree.setPrefHeight(260);
        tree.setPrefWidth(300);
        tree.setCellFactory(new Callback<TreeView<T>, TreeCell<T>>() {
            @Override
            public TreeCell<T> call(TreeView<T> view) {
                final TreeCell<T> cell = new TreeCell<T>() {
                    @Override
                    protected void updateItem(T item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty || item == null ? null : source.name(item));
                    }
                };
                // One click only highlights (so "+ New" knows the parent);
                // a double click picks.
                cell.setOnMouseClicked(new EventHandler<MouseEvent>() {
                    @Override
                    public void handle(MouseEvent event) {
                        if (event.getClickCount() == 2 && !cell.isEmpty()) {
                            pick(cell.getItem());
                        }
                    }
                });
                return cell;
            }
        });

        Button choose = new Button("Choose");
        choose.getStyleClass().add("accent-button");
        choose.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                TreeItem<T> item = tree.getSelectionModel().getSelectedItem();
                if (item != null) {
                    pick(item.getValue());
                }
            }
        });

        newName.setPromptText("New " + noun + " under the highlighted one");
        HBox.setHgrow(newName, Priority.ALWAYS);
        Button add = new Button();
        add.getStyleClass().add("plain-button");
        add.setGraphic(new FontIcon("mdi2p-plus"));
        add.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                createNode();
            }
        });
        // Enter in the name field does the same as the + button.
        newName.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                createNode();
            }
        });
        error.getStyleClass().add("error-text");
        error.setWrapText(true);

        VBox content = new VBox(8);
        // "root" gives the popup the theme's colour variables, which are
        // defined on .root in the theme stylesheets.
        content.getStyleClass().addAll("root", "picker-popup");
        if (noneLabel != null) {
            Button none = new Button(noneLabel);
            none.getStyleClass().add("plain-button");
            none.setMaxWidth(Double.MAX_VALUE);
            none.setOnAction(new EventHandler<ActionEvent>() {
                @Override
                public void handle(ActionEvent event) {
                    pick(null);
                }
            });
            content.getChildren().add(none);
        }
        HBox newRow = new HBox(6, newName, add);
        content.getChildren().addAll(tree, newRow, error, choose);
        popup.getContent().add(content);
        popup.setAutoHide(true);
    }

    private void pick(T node) {
        popup.hide();
        onChosen.chosen(node);
    }

    /** E69: a child of the highlighted node, or a root node if nothing is highlighted. */
    private void createNode() {
        TreeItem<T> highlighted = tree.getSelectionModel().getSelectedItem();
        T parent = highlighted == null ? null : highlighted.getValue();
        T created;
        try {
            created = source.create(newName.getText(), parent);
        } catch (IllegalArgumentException e) {
            // Blank, or a sibling already has the name: shown in the popup.
            error.setText(e.getMessage());
            return;
        }
        error.setText("");
        newName.clear();
        TreeItem<T> item = new TreeItem<>(created);
        TreeItem<T> under = highlighted == null ? tree.getRoot() : highlighted;
        under.getChildren().add(item);
        under.setExpanded(true);
        tree.getSelectionModel().select(item);
        tree.scrollTo(tree.getRow(item));
    }

    /** Adds nodes, sorted by name, then their children: recursion, one level per call. */
    private void fill(TreeItem<T> parent, List<T> nodes) {
        List<T> sorted = new ArrayList<>(nodes);
        Collections.sort(sorted, new Comparator<T>() {
            @Override
            public int compare(T a, T b) {
                return source.name(a).compareToIgnoreCase(source.name(b));
            }
        });
        for (int i = 0; i < sorted.size(); i++) {
            TreeItem<T> item = new TreeItem<>(sorted.get(i));
            item.setExpanded(true);
            parent.getChildren().add(item);
            fill(item, source.children(sorted.get(i)));
        }
    }
}
