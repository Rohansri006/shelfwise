package com.shelfwise.desktop.ui;

import com.shelfwise.core.model.Category;
import com.shelfwise.core.model.Location;
import javafx.fxml.FXML;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.StackPane;

/**
 * The Organise page (E41, E89-E102): two tabs, Categories and Locations,
 * one shown at a time. Both tabs behave identically, so each is a
 * {@link TreeTab} - one generic class - given a different
 * {@link Organisable}: {@link CategoryTree} or {@link LocationTree}.
 * <p>
 * This class only switches between the tabs and passes on the page's
 * lifecycle calls; everything a tab does lives in {@code TreeTab}.
 */
public class OrganiseController extends PageController {

    @FXML private ToggleButton categoriesToggle;
    @FXML private ToggleButton locationsToggle;
    @FXML private StackPane tabHolder;

    private TreeTab<Category> categories;
    private TreeTab<Location> locations;

    @FXML
    private void initialize() {
        // A toggle group makes the two segments act as one switch.
        ToggleGroup group = new ToggleGroup();
        categoriesToggle.setToggleGroup(group);
        locationsToggle.setToggleGroup(group);
        categoriesToggle.setSelected(true);
    }

    /** Builds the two tabs, once the window and collection are known. */
    @Override
    public void setUp(final Shell shell, Session session) {
        super.setUp(shell, session);
        categories = new TreeTab<>(new CategoryTree(session, shell), session, shell,
                "category", "categories", "sub-category", "sub-categories",
                "Remove from books", "unfiled", "books",
                "Categories group your books, and a book can be in several.",
                new TreePicker.Chosen<Category>() {
                    @Override
                    public void chosen(Category node) {
                        BrowseController browse = (BrowseController) shell.show(PageId.BROWSE);
                        browse.showFiltered(node, null);
                    }
                });
        locations = new TreeTab<>(new LocationTree(session, shell), session, shell,
                "location", "locations", "sub-location", "sub-locations",
                "Unshelve copies", "unshelved", "copies",
                "Locations are where your copies live: rooms, shelves, rows.",
                new TreePicker.Chosen<Location>() {
                    @Override
                    public void chosen(Location node) {
                        BrowseController browse = (BrowseController) shell.show(PageId.BROWSE);
                        browse.showFiltered(null, node);
                    }
                });
        tabHolder.getChildren().addAll(categories.pane, locations.pane);
        locations.pane.setVisible(false);
    }

    /**
     * Both trees are read afresh, since the Add page or the Trash may have
     * changed them, or "Keep theirs" replaced the whole collection. The tab,
     * expansion and selection stay as the user left them (E98).
     */
    @Override
    public void shown() {
        categories.refresh();
        locations.refresh();
    }

    /** A half-typed new node is dropped rather than left behind on the page. */
    @Override
    public void hidden() {
        categories.cancelPending();
        locations.cancelPending();
    }

    @FXML
    private void onCategories() {
        showTab(false);
    }

    @FXML
    private void onLocations() {
        showTab(true);
    }

    private void showTab(boolean showLocations) {
        // A toggle in a group can be clicked off; keep the chosen one on.
        categoriesToggle.setSelected(!showLocations);
        locationsToggle.setSelected(showLocations);
        categories.cancelPending();
        locations.cancelPending();
        categories.pane.setVisible(!showLocations);
        locations.pane.setVisible(showLocations);
    }
}
