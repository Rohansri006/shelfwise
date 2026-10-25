package com.shelfwise.desktop.ui;

import com.shelfwise.core.exception.SyncConflictException;
import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.CollectionItem;
import com.shelfwise.core.service.CollectionManager;
import com.shelfwise.core.storage.Settings;
import javafx.animation.PauseTransition;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The main window: the sidebar, the page switching, the theme and the
 * toast (E16, E47, E49). It implements {@link Shell}, the small interface
 * pages use to reach it, and holds each page as a {@link PageController}.
 * <p>
 * It knows nothing about books on any page: it only shows a page, tells it
 * that it is shown, and passes it Esc and Shortcut+A. What each page does
 * with those is up to the page (runtime polymorphism, see
 * {@link PageController}).
 */
public class MainController implements Shell {

    /** E83/E84: Shortcut is Cmd on the Mac and Ctrl elsewhere. */
    private static final KeyCombination SELECT_ALL = new KeyCodeCombination(KeyCode.A, KeyCombination.SHORTCUT_DOWN);

    /** Stylesheet slots in the scene: 0 is the layout, 1 the theme, swapped by the toggle. */
    private static final int THEME_SLOT = 1;

    @FXML private VBox sidebar;
    @FXML private Label appName;
    @FXML private ToggleButton navBrowse;
    @FXML private ToggleButton navAdd;
    @FXML private ToggleButton navOrganise;
    @FXML private ToggleButton navWishlist;
    @FXML private ToggleButton navStats;
    @FXML private ToggleButton navTrash;
    @FXML private Label trashBadge;
    @FXML private ToggleButton navSettings;
    @FXML private VBox stats;
    @FXML private Label statsBooks;
    @FXML private Label statsCopies;
    @FXML private ToggleButton themeToggle;
    @FXML private FontIcon themeIcon;
    @FXML private StackPane centre;
    @FXML private Label toast;
    @FXML private Button toastAction;
    @FXML private Button toastAction2;
    @FXML private Node toastBox;

    private Session session;

    /** Each page's controller and its view, by page. EnumMap: a map whose keys are one enum's constants. */
    private final Map<PageId, PageController> pages = new EnumMap<>(PageId.class);
    private final Map<PageId, Node> views = new EnumMap<>(PageId.class);
    private final Map<PageId, ToggleButton> navButtons = new EnumMap<>(PageId.class);

    private PageId current;

    /** The sidebar item lit for the current page: usually the page itself (see {@link #show(PageId, PageId)}). */
    private PageId litNow;
    private boolean collapsed = false;
    private boolean dark = true;

    /**
     * Bumped by every toast. A toast's timer hides it only if no newer toast
     * has been shown since; comparing the text would fail when two toasts
     * say the same thing.
     */
    private int toastNumber = 0;

    @FXML
    private void initialize() {
        navButtons.put(PageId.BROWSE, navBrowse);
        navButtons.put(PageId.ADD, navAdd);
        navButtons.put(PageId.ORGANISE, navOrganise);
        navButtons.put(PageId.WISHLIST, navWishlist);
        navButtons.put(PageId.STATS, navStats);
        navButtons.put(PageId.TRASH, navTrash);
        navButtons.put(PageId.SETTINGS, navSettings);
        ToggleGroup nav = new ToggleGroup();
        PageId[] ids = PageId.values();
        for (int i = 0; i < ids.length; i++) {
            ToggleButton button = navButtons.get(ids[i]);
            button.setToggleGroup(nav);
            // On the icon rail the text is hidden, so the tooltip names the page.
            button.setTooltip(new Tooltip(ids[i].getLabel()));
            // The button remembers its page, so one handler serves all seven.
            button.setUserData(ids[i]);
        }

        // E137: the footer's "25 books · 26 copies" opens Stats.
        stats.setCursor(Cursor.HAND);
        Tooltip.install(stats, new Tooltip("Open Stats"));
        stats.setOnMouseClicked(new EventHandler<MouseEvent>() {
            @Override
            public void handle(MouseEvent event) {
                show(PageId.STATS);
            }
        });
    }

    /**
     * Called by the app once the collection is open: builds every page and
     * opens Browse.
     *
     * @param session the settings and the open collection
     */
    public void start(Session session) {
        this.session = session;
        PageId[] ids = PageId.values();
        for (int i = 0; i < ids.length; i++) {
            ComingSoonPage page = new ComingSoonPage(ids[i]);
            addPage(ids[i], page, page.getView());
        }
        // Each real page replaces its placeholder as it is ported (E152).
        loadPage(PageId.BROWSE, "browse-view.fxml");
        loadPage(PageId.ADD, "add-view.fxml");
        loadPage(PageId.ORGANISE, "organise-view.fxml");
        loadPage(PageId.SETTINGS, "settings-view.fxml");
        loadPage(PageId.TRASH, "trash-view.fxml");
        applyTheme(session.getSettings().getTheme() == Settings.Theme.DARK);
        updateCounts();
        show(PageId.BROWSE);
    }

    /**
     * Puts a page into the centre, underneath the toast, hidden until shown.
     *
     * @param id   which sidebar item it belongs to
     * @param page its controller
     * @param view what it shows
     */
    private void addPage(PageId id, PageController page, Node view) {
        page.setUp(this, session);
        view.setVisible(false);
        pages.put(id, page);
        views.put(id, view);
        centre.getChildren().add(centre.getChildren().indexOf(toastBox), view);
    }

    /**
     * Loads a page from its FXML file: the file names its controller, which
     * must extend {@link PageController}.
     *
     * @param id   the page
     * @param fxml the file, beside this class's FXML
     */
    void loadPage(PageId id, String fxml) {
        FXMLLoader loader = new FXMLLoader(MainController.class.getResource(fxml));
        Parent view;
        try {
            view = loader.load();
        } catch (IOException e) {
            throw new IllegalStateException("Could not load " + fxml + ": " + e.getMessage(), e);
        }
        // A page loaded again replaces the placeholder that held its place.
        if (views.containsKey(id)) {
            centre.getChildren().remove(views.get(id));
        }
        addPage(id, (PageController) loader.getController(), view);
    }

    // ------------------------------------------------------------------
    // Pages
    // ------------------------------------------------------------------

    @FXML
    private void onNavigate(ActionEvent event) {
        ToggleButton clicked = (ToggleButton) event.getSource();
        show((PageId) clicked.getUserData());
    }

    @Override
    public PageController show(PageId id) {
        return show(id, id);
    }

    /** The one real show method; {@link #show(PageId)} lights the page's own item. */
    @Override
    public PageController show(PageId id, PageId lit) {
        // E40: a page with unsaved changes may keep the user there.
        if (current != null && current != id && !pages.get(current).canLeave()) {
            navButtons.get(litNow).setSelected(true);
            return pages.get(current);
        }
        if (current == id) {
            // Already showing: shown() again would throw away what the page
            // holds (a half-filled form), so only the lit item changes.
            litNow = lit;
            navButtons.get(lit).setSelected(true);
            return pages.get(id);
        }
        if (current != null) {
            pages.get(current).hidden();
        }
        current = id;
        litNow = lit;
        // A toggle group lets the user unselect the lit item; keep it lit.
        navButtons.get(lit).setSelected(true);
        PageId[] ids = PageId.values();
        for (int i = 0; i < ids.length; i++) {
            views.get(ids[i]).setVisible(ids[i] == id);
        }
        pages.get(id).shown();
        return pages.get(id);
    }

    /**
     * Esc and Shortcut+A go to whichever page is showing.
     *
     * @param scene the window's scene
     */
    public void installShortcuts(Scene scene) {
        // A filter, not a handler: filters run on the way down to the focused
        // control, before it. A focused TableView has its own select-all and
        // would otherwise act first.
        scene.addEventFilter(KeyEvent.KEY_PRESSED, new EventHandler<KeyEvent>() {
            @Override
            public void handle(KeyEvent event) {
                // E85: in a text field, Shortcut+A selects the text, as anywhere else.
                if (!SELECT_ALL.match(event) || event.getTarget() instanceof TextInputControl) {
                    return;
                }
                if (current != null && pages.get(current).selectAll()) {
                    event.consume();
                }
            }
        });
        scene.addEventHandler(KeyEvent.KEY_PRESSED, new EventHandler<KeyEvent>() {
            @Override
            public void handle(KeyEvent event) {
                if (event.getCode() == KeyCode.ESCAPE && current != null) {
                    pages.get(current).onEscape();
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // Sidebar
    // ------------------------------------------------------------------

    /** E16: between the labelled sidebar (~210 px) and the icon rail (56 px). */
    @FXML
    private void onToggleSidebar() {
        collapsed = !collapsed;
        sidebar.setPrefWidth(collapsed ? 56 : 210);
        ContentDisplay display = collapsed ? ContentDisplay.GRAPHIC_ONLY : ContentDisplay.LEFT;
        PageId[] ids = PageId.values();
        for (int i = 0; i < ids.length; i++) {
            navButtons.get(ids[i]).setContentDisplay(display);
        }
        themeToggle.setContentDisplay(display);
        // On the icon rail there is no text to sit beside, so the badge moves
        // up to the icon's top-right corner, like an app badge.
        StackPane.setAlignment(trashBadge, collapsed ? Pos.TOP_RIGHT : Pos.CENTER_RIGHT);
        StackPane.setMargin(trashBadge, collapsed ? new Insets(6, 8, 0, 0) : new Insets(0, 10, 0, 0));
        appName.setVisible(!collapsed);
        appName.setManaged(!collapsed);
        stats.setVisible(!collapsed);
        stats.setManaged(!collapsed);
        if (collapsed) {
            sidebar.getStyleClass().add("collapsed");
        } else {
            sidebar.getStyleClass().remove("collapsed");
        }
        updateCounts();
    }

    /**
     * The footer's book and copy counts, and the Trash badge: a count pill in
     * the full sidebar, a plain dot on the icon rail (E53), where a number
     * would cover the icon. An empty Trash shows no badge, not a "0".
     */
    private void updateCounts() {
        CollectionManager manager = session.getManager();
        List<CollectionItem> items = manager.getItems();
        int copies = 0;
        for (int i = 0; i < items.size(); i++) {
            copies += ((Book) items.get(i)).getCopyCount();
        }
        statsBooks.setText(items.size() + (items.size() == 1 ? " book" : " books"));
        statsCopies.setText(copies + (copies == 1 ? " copy" : " copies"));

        int trashed = manager.getTrash().size();
        trashBadge.setText(collapsed ? "" : String.valueOf(trashed));
        trashBadge.getStyleClass().remove("dot");
        if (collapsed) {
            trashBadge.getStyleClass().add("dot");
        }
        trashBadge.setVisible(trashed > 0);
    }

    // ------------------------------------------------------------------
    // Theme
    // ------------------------------------------------------------------

    @FXML
    private void onToggleTheme() {
        setDark(!dark);
    }

    @Override
    public boolean isDark() {
        return dark;
    }

    /** Switches the theme and remembers it in the settings file (E108). */
    @Override
    public void setDark(boolean wanted) {
        applyTheme(wanted);
        session.getSettings().setTheme(wanted ? Settings.Theme.DARK : Settings.Theme.LIGHT);
        try {
            session.saveSettings();
        } catch (IOException e) {
            toast("The theme could not be remembered: " + e.getMessage());
        }
    }

    /** Swaps the theme stylesheet; every colour in app.css is looked up from it. */
    private void applyTheme(boolean wanted) {
        dark = wanted;
        Scene scene = sidebar.getScene();
        String theme = MainController.class.getResource(
                dark ? "/com/shelfwise/desktop/css/theme-dark.css" : "/com/shelfwise/desktop/css/theme-light.css")
                .toExternalForm();
        scene.getStylesheets().set(THEME_SLOT, theme);
        themeToggle.setText(dark ? "Dark" : "Light");
        // The theme item is a ToggleButton only for its look; it is never "on".
        themeToggle.setSelected(false);
        themeIcon.setIconLiteral(dark ? "mdi2w-weather-night" : "mdi2w-white-balance-sunny");
        // E108: tell every page; only Settings does anything with it (E163).
        PageId[] ids = PageId.values();
        for (int i = 0; i < ids.length; i++) {
            if (pages.containsKey(ids[i])) {
                pages.get(ids[i]).themeChanged(dark);
            }
        }
    }

    // ------------------------------------------------------------------
    // Saving (ADR 0001)
    // ------------------------------------------------------------------

    @Override
    public boolean saveChanges() {
        boolean saved = false;
        try {
            session.save();
            saved = true;
        } catch (SyncConflictException e) {
            saved = resolveConflict(e);
        } catch (IOException e) {
            error("Your change could not be saved", e.getMessage()
                    + "\n\nIt is still on screen. Try again, or check that the collection folder is reachable.");
        }
        updateCounts();
        return saved;
    }

    /**
     * E47: the database file changed on disk since it was loaded, for
     * example from another copy of ShelfWise. Nothing has been written yet;
     * the user picks which version wins. There is no merge.
     *
     * @return true if "Keep mine" saved successfully
     */
    private boolean resolveConflict(SyncConflictException conflict) {
        ButtonType mine = new ButtonType("Keep mine", ButtonBar.ButtonData.OK_DONE);
        ButtonType theirs = new ButtonType("Keep theirs", ButtonBar.ButtonData.OTHER);
        Alert alert = new Alert(Alert.AlertType.WARNING,
                "The collection file was changed by something else since ShelfWise opened it.\n\n"
                        + "Keep mine: save what is on screen over it.\n"
                        + "Keep theirs: drop the changes on screen and load the file.", theirs, mine);
        alert.setHeaderText("The collection changed on disk");
        alert.initOwner(getWindow());
        Optional<ButtonType> answer = alert.showAndWait();
        try {
            if (answer.isPresent() && answer.get() == mine) {
                session.saveOverConflict();
                return true;
            }
            session.reload();
            collectionReplaced();
            toast("Loaded the collection from the file");
        } catch (SyncConflictException | IOException e) {
            error("The collection could not be saved", e.getMessage());
        }
        return false;
    }

    /** The manager was replaced: the counts and the page must read the new one. */
    @Override
    public void collectionReplaced() {
        updateCounts();
        pages.get(current).shown();
    }

    private void error(String header, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        alert.setHeaderText(header);
        alert.initOwner(getWindow());
        alert.showAndWait();
    }

    @Override
    public Window getWindow() {
        return sidebar.getScene().getWindow();
    }

    // ------------------------------------------------------------------
    // Toast (E47, E74-E76, E123)
    // ------------------------------------------------------------------

    @Override
    public void toast(String message) {
        toast(message, null, null, null, null);
    }

    @Override
    public void toast(String message, String action, Runnable onAction) {
        toast(message, action, onAction, null, null);
    }

    /**
     * The one real toast method; the overloads above call it with fewer
     * buttons. A toast with an action stays 6 seconds rather than 3, long
     * enough to reach the button.
     */
    @Override
    public void toast(String message, String action, Runnable onAction, String action2, Runnable onAction2) {
        toastNumber++;
        final int mine = toastNumber;
        toast.setText(message);
        toastButton(toastAction, action, onAction);
        toastButton(toastAction2, action2, onAction2);
        toastBox.setVisible(true);
        PauseTransition wait = new PauseTransition(Duration.seconds(action != null ? 6 : 3));
        wait.setOnFinished(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                // Only hide if no newer toast replaced this one meanwhile.
                if (toastNumber == mine) {
                    toastBox.setVisible(false);
                }
            }
        });
        wait.play();
    }

    /** Shows one toast button with its text and action, or hides it for a null text. */
    private void toastButton(Button button, String text, final Runnable action) {
        boolean shown = text != null;
        button.setVisible(shown);
        button.setManaged(shown);
        if (shown) {
            button.setText(text);
            button.setOnAction(new EventHandler<ActionEvent>() {
                @Override
                public void handle(ActionEvent event) {
                    toastBox.setVisible(false);
                    action.run();
                }
            });
        }
    }
}
