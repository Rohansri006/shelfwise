package com.shelfwise.desktop.ui;

import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Category;
import com.shelfwise.core.model.CollectionItem;
import com.shelfwise.core.model.Copy;
import com.shelfwise.core.model.Format;
import com.shelfwise.core.model.ReadingSession;
import com.shelfwise.core.model.ReadingStatus;
import com.shelfwise.core.model.Wishlist;
import com.shelfwise.core.model.WishlistEntry;
import com.shelfwise.core.service.CollectionManager;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.value.ObservableValue;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Callback;
import org.kordamp.ikonli.javafx.FontIcon;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The Stats page (E48, E128-E141).
 * <p>
 * <b>Where the numbers come from (E128).</b> Every figure is counted here,
 * in Java, from the {@link CollectionManager} - not read from the SQL views
 * {@code v_collection_value} / {@code v_books_per_year}. The manager holds
 * the collection as it is right now, edits included, and the counting works
 * on any platform the core runs on. The views stay in {@code schema.sql}
 * and this code follows the same rules (live books only, distinct books per
 * year), so the two can be checked against each other.
 * <p>
 * The counting methods return plain maps and numbers and touch no controls,
 * so they can be tested on their own and compared with SQLite directly.
 * The page itself is rebuilt from them on every visit (E135).
 */
public class StatsController extends PageController {

    /** E28: the status colours, the same values as the {@code .status-dot-*} CSS. */
    private static final Map<ReadingStatus, String> STATUS_COLOURS = new EnumMap<>(ReadingStatus.class);

    static {
        STATUS_COLOURS.put(ReadingStatus.READING, "#22c55e");
        STATUS_COLOURS.put(ReadingStatus.FINISHED, "#38bdf8");
        STATUS_COLOURS.put(ReadingStatus.UNREAD, "#94a3b8");
        STATUS_COLOURS.put(ReadingStatus.ABANDONED, "#f59e0b");
    }

    @FXML private VBox page;
    @FXML private ScrollPane scroll;
    @FXML private VBox content;

    /** Which series the per-year chart shows; kept while the app runs. */
    private boolean yearShowsPages = false;

    /** @return the open collection; asked each time, because "Keep theirs" replaces it */
    private CollectionManager manager() {
        return session.getManager();
    }

    // ------------------------------------------------------------------
    // Counting (no controls touched)
    // ------------------------------------------------------------------

    /** Live books only: the Trash is left out, as in the SQL views (E128). */
    List<Book> liveBooks() {
        List<Book> books = new ArrayList<>();
        List<CollectionItem> items = manager().getItems();
        for (int i = 0; i < items.size(); i++) {
            books.add((Book) items.get(i));
        }
        return books;
    }

    /**
     * E132: copies and money per format, in {@link Format} declaration order.
     *
     * @return format maps to {copies, total paid}
     */
    Map<Format, double[]> spendByFormat() {
        Map<Format, double[]> map = new LinkedHashMap<>();
        Format[] formats = Format.values();
        List<Book> books = liveBooks();
        for (int f = 0; f < formats.length; f++) {
            double copies = 0;
            double paid = 0;
            for (int i = 0; i < books.size(); i++) {
                List<Copy> list = books.get(i).getCopies();
                for (int j = 0; j < list.size(); j++) {
                    if (list.get(j).getFormat() == formats[f]) {
                        copies++;
                        paid += list.get(j).getPricePaid();
                    }
                }
            }
            if (copies > 0) {
                map.put(formats[f], new double[] {copies, paid});
            }
        }
        return map;
    }

    /** @return how many live copies have no price recorded (0 = not recorded) */
    int copiesWithoutPrice() {
        int n = 0;
        List<Book> books = liveBooks();
        for (int i = 0; i < books.size(); i++) {
            for (int j = 0; j < books.get(i).getCopies().size(); j++) {
                if (books.get(i).getCopies().get(j).getPricePaid() == 0) {
                    n++;
                }
            }
        }
        return n;
    }

    /** E134: books per reading status, every status present (0 if none). */
    Map<ReadingStatus, Integer> statusCounts() {
        Map<ReadingStatus, Integer> map = new EnumMap<>(ReadingStatus.class);
        ReadingStatus[] all = ReadingStatus.values();
        for (int i = 0; i < all.length; i++) {
            map.put(all[i], 0);
        }
        List<Book> books = liveBooks();
        for (int i = 0; i < books.size(); i++) {
            ReadingStatus status = books.get(i).getReadingStatus();
            map.put(status, map.get(status) + 1);
        }
        return map;
    }

    /**
     * E131/E140: per year, {books finished, pages read}. Every year from the
     * first finish to this year is present, empty ones as 0, so a quiet year
     * shows as a gap instead of disappearing.
     * <p>
     * Books: a book counts once in each year it had a session end - the
     * same "distinct books" rule as {@code v_books_per_year}. Pages: E139/E140,
     * a book whose status is Finished counts its pages once, in the year of
     * its last ended session.
     *
     * @return year maps to {books, pages}, sorted by year (a TreeMap keeps its keys in order)
     */
    TreeMap<Integer, int[]> perYear() {
        TreeMap<Integer, int[]> map = new TreeMap<>();
        List<Book> books = liveBooks();
        for (int i = 0; i < books.size(); i++) {
            Book book = books.get(i);
            Set<Integer> years = new HashSet<>();
            int lastYear = 0;
            List<ReadingSession> sessions = book.getReadingSessions();
            for (int j = 0; j < sessions.size(); j++) {
                LocalDate end = sessions.get(j).getEndDate();
                if (end != null) {
                    years.add(end.getYear());
                    lastYear = Math.max(lastYear, end.getYear());
                }
            }
            List<Integer> list = new ArrayList<>(years);
            for (int j = 0; j < list.size(); j++) {
                slot(map, list.get(j))[0]++;
            }
            if (book.getReadingStatus() == ReadingStatus.FINISHED && lastYear > 0) {
                slot(map, lastYear)[1] += book.getPages();
            }
        }
        if (!map.isEmpty()) {
            int thisYear = LocalDate.now().getYear();
            for (int year = map.firstKey(); year <= thisYear; year++) {
                slot(map, year);
            }
        }
        return map;
    }

    private static int[] slot(Map<Integer, int[]> map, int year) {
        int[] counts = map.get(year);
        if (counts == null) {
            counts = new int[2];
            map.put(year, counts);
        }
        return counts;
    }

    /** E138/E139: page totals. Books with pages = 0 (not recorded) are left out of every figure. */
    static class Pages {
        int owned;
        int read;
        int known;
        int unknown;
        Book longest;
        Book shortest;
        List<Book> unknownBooks = new ArrayList<>();

        int left() {
            return owned - read;
        }

        int average() {
            return known == 0 ? 0 : Math.round((float) owned / known);
        }
    }

    Pages pages() {
        Pages p = new Pages();
        List<Book> books = liveBooks();
        for (int i = 0; i < books.size(); i++) {
            Book book = books.get(i);
            if (book.getPages() == 0) {
                p.unknown++;
                p.unknownBooks.add(book);
                continue;
            }
            p.known++;
            p.owned += book.getPages();
            if (book.getReadingStatus() == ReadingStatus.FINISHED) {
                p.read += book.getPages();
            }
            if (p.longest == null || book.getPages() > p.longest.getPages()) {
                p.longest = book;
            }
            if (p.shortest == null || book.getPages() < p.shortest.getPages()) {
                p.shortest = book;
            }
        }
        return p;
    }

    /** E141: one category row's figures, its subcategories included. */
    static class CategoryRow {
        final Category category;
        int books;
        int owned;
        int read;

        CategoryRow(Category category) {
            this.category = category;
        }

        String percent() {
            return owned == 0 ? "—" : Math.round(100.0 * read / owned) + "%";
        }
    }

    /** Counts one category through the manager's own search, so the numbers match Browse's filter. */
    CategoryRow countCategory(Category category) {
        CategoryRow row = new CategoryRow(category);
        List<CollectionItem> items = manager().search(category);
        row.books = items.size();
        for (int i = 0; i < items.size(); i++) {
            Book book = (Book) items.get(i);
            row.owned += book.getPages();
            if (book.getReadingStatus() == ReadingStatus.FINISHED) {
                row.read += book.getPages();
            }
        }
        return row;
    }

    // ------------------------------------------------------------------
    // Building the page (E135: from scratch on every visit)
    // ------------------------------------------------------------------

    /** Rebuilt from scratch on every visit: anything may have changed since (E135). */
    @Override
    public void shown() {
        content.getChildren().clear();
        scroll.setVvalue(0);
        if (manager().getItems().isEmpty()) {
            content.getChildren().add(emptyState());
            return;
        }
        content.getChildren().add(tiles());
        content.getChildren().add(yearChart());
        HBox pair = new HBox(18, formatChart(), statusChart());
        for (int i = 0; i < pair.getChildren().size(); i++) {
            HBox.setHgrow(pair.getChildren().get(i), Priority.ALWAYS);
        }
        content.getChildren().add(pair);
        content.getChildren().add(categoryTable());
    }

    private VBox emptyState() {
        FontIcon icon = new FontIcon("mdi2c-chart-bar");
        icon.setIconSize(48);
        icon.getStyleClass().add("empty-icon");
        Label title = new Label("No stats yet");
        title.getStyleClass().add("page-title");
        Label line = new Label("Add some books and their numbers appear here.");
        line.getStyleClass().add("muted");
        Button add = new Button("Add a book");
        add.getStyleClass().add("accent-button");
        add.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                shell.show(PageId.ADD);
            }
        });
        VBox box = new VBox(10, icon, title, line, add);
        box.setAlignment(Pos.CENTER);
        box.setMinHeight(400);
        return box;
    }

    // ---------------------------------------------------------------- tiles

    private FlowPane tiles() {
        List<Book> books = liveBooks();
        int copies = 0;
        double spent = 0;
        for (int i = 0; i < books.size(); i++) {
            copies += books.get(i).getCopyCount();
            spent += books.get(i).getTotalPricePaid();
        }
        String currency = manager().getCurrencyCode();
        FlowPane row = new FlowPane(12, 12);

        row.getChildren().add(tile("Books", String.valueOf(books.size())));
        row.getChildren().add(tile("Copies", String.valueOf(copies)));

        VBox money = tile("Total spent", BrowseController.formatMoney(currency, spent));
        int noPrice = copiesWithoutPrice();
        if (noPrice > 0) {
            money.getChildren().add(note(WishlistPicker.count(noPrice, "copy", "copies") + " price unknown"));
        }
        row.getChildren().add(money);

        row.getChildren().add(pagesTile());

        int thisYear = LocalDate.now().getYear();
        int[] now = perYear().get(thisYear);
        row.getChildren().add(tile("Finished in " + thisYear, String.valueOf(now == null ? 0 : now[0])));

        row.getChildren().add(wishlistTile(currency));
        return row;
    }

    private VBox pagesTile() {
        final Pages p = pages();
        VBox tile = tile("Pages", p.known == 0 ? "—" : String.format("%,d", p.owned));
        if (p.known > 0) {
            tile.getChildren().add(note(String.format("%,d read  ·  %,d left  ·  avg %,d", p.read,
                    p.left(), p.average())));
            tile.getChildren().add(bookLink("Longest: ", p.longest));
            tile.getChildren().add(bookLink("Shortest: ", p.shortest));
        }
        if (p.unknown > 0) {
            Label unknown = note("pages unknown for " + WishlistPicker.count(p.unknown, "book", "books"));
            Hyperlink fill = new Hyperlink("Fill…");
            fill.getStyleClass().add("stat-link");
            fill.setOnAction(new EventHandler<ActionEvent>() {
                @Override
                public void handle(ActionEvent event) {
                    fillPages(p.unknownBooks);
                }
            });
            HBox line = new HBox(2, unknown, fill);
            line.setAlignment(Pos.CENTER_LEFT);
            tile.getChildren().add(line);
        }
        return tile;
    }

    /** "Longest: War and Peace, 1,192" - the title opens the book. */
    private HBox bookLink(String prefix, final Book book) {
        Hyperlink link = new Hyperlink(book.getTitle());
        link.getStyleClass().add("stat-link");
        link.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                showBook(book);
            }
        });
        HBox line = new HBox(0, note(prefix), link, note(String.format(", %,d", book.getPages())));
        line.setAlignment(Pos.CENTER_LEFT);
        return line;
    }

    private VBox wishlistTile(String currency) {
        int entries = 0;
        double total = 0;
        List<Wishlist> lists = manager().getWishlists();
        for (int i = 0; i < lists.size(); i++) {
            List<WishlistEntry> list = lists.get(i).getEntries();
            entries += list.size();
            for (int j = 0; j < list.size(); j++) {
                total += list.get(j).getExpectedPrice();
            }
        }
        VBox tile = tile("Wishlist", WishlistPicker.count(entries, "entry", "entries"));
        tile.getChildren().add(note(BrowseController.formatMoney(currency, total) + " to buy"));
        tile.getStyleClass().add("clickable");
        tile.setCursor(Cursor.HAND);
        Tooltip.install(tile, new Tooltip("Open the Wishlist"));
        tile.setOnMouseClicked(new EventHandler<MouseEvent>() {
            @Override
            public void handle(MouseEvent event) {
                shell.show(PageId.WISHLIST);
            }
        });
        return tile;
    }

    private static VBox tile(String caption, String value) {
        Label big = new Label(value);
        big.getStyleClass().add("stat-value");
        Label small = new Label(caption);
        small.getStyleClass().add("stat-caption");
        VBox tile = new VBox(2, small, big);
        tile.getStyleClass().add("stat-tile");
        tile.setMinWidth(150);
        return tile;
    }

    private static Label note(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("muted");
        return label;
    }

    // ---------------------------------------------------------------- charts

    /** A titled card holding one chart (or its "nothing yet" line). */
    private static VBox card(String title, Node... body) {
        Label heading = new Label(title);
        heading.getStyleClass().add("section-label");
        VBox card = new VBox(8, heading);
        card.getChildren().addAll(body);
        card.getStyleClass().add("stat-card");
        return card;
    }

    /**
     * An axis from 0 with round steps. Auto-ranging would pick steps like
     * 0.5, which make no sense for a count of books.
     */
    private static NumberAxis axis(double max, String label) {
        double step = Math.max(1, Math.ceil(max / 5));
        // Round the step to a tidy number: 1, 2, 5, 10, 20, 50, 100...
        double magnitude = Math.pow(10, Math.floor(Math.log10(step)));
        double[] tidy = {1, 2, 5, 10};
        for (int i = 0; i < tidy.length; i++) {
            if (tidy[i] * magnitude >= step) {
                step = tidy[i] * magnitude;
                break;
            }
        }
        NumberAxis axis = new NumberAxis(0, Math.max(step, Math.ceil(max / step) * step), step);
        axis.setMinorTickVisible(false);
        axis.setLabel(label);
        return axis;
    }

    private VBox yearChart() {
        final TreeMap<Integer, int[]> years = perYear();
        if (years.isEmpty()) {
            return card("BOOKS FINISHED PER YEAR", note("Nothing finished yet."));
        }
        // E140: books and pages differ a hundredfold, so one axis cannot
        // carry both: a toggle switches the chart between the two.
        ToggleGroup group = new ToggleGroup();
        final ToggleButton booksButton = new ToggleButton("Books");
        final ToggleButton pagesButton = new ToggleButton("Pages read");
        booksButton.setToggleGroup(group);
        pagesButton.setToggleGroup(group);
        booksButton.getStyleClass().add("plain-button");
        pagesButton.getStyleClass().add("plain-button");
        (yearShowsPages ? pagesButton : booksButton).setSelected(true);

        final VBox holder = new VBox();
        holder.getChildren().add(buildYearChart(years, yearShowsPages));
        EventHandler<ActionEvent> flip = new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent event) {
                // Keep one button lit: a toggle group lets the user unselect both.
                if (!booksButton.isSelected() && !pagesButton.isSelected()) {
                    ((ToggleButton) event.getSource()).setSelected(true);
                }
                yearShowsPages = pagesButton.isSelected();
                holder.getChildren().setAll(buildYearChart(years, yearShowsPages));
            }
        };
        booksButton.setOnAction(flip);
        pagesButton.setOnAction(flip);
        HBox toggles = new HBox(6, booksButton, pagesButton);
        return card("FINISHED PER YEAR", toggles, holder);
    }

    private BarChart<String, Number> buildYearChart(TreeMap<Integer, int[]> years, boolean showPages) {
        int which = showPages ? 1 : 0;
        int max = 0;
        List<Integer> keys = new ArrayList<>(years.keySet());
        for (int i = 0; i < keys.size(); i++) {
            max = Math.max(max, years.get(keys.get(i))[which]);
        }
        CategoryAxis x = new CategoryAxis();
        BarChart<String, Number> chart = new BarChart<>(x, axis(max, showPages ? "Pages" : "Books"));
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        series.setName(showPages ? "Pages read" : "Books finished");
        for (int i = 0; i < keys.size(); i++) {
            series.getData().add(new XYChart.Data<String, Number>(String.valueOf(keys.get(i)),
                    years.get(keys.get(i))[which]));
        }
        chart.getData().add(series);
        for (int i = 0; i < series.getData().size(); i++) {
            XYChart.Data<String, Number> bar = series.getData().get(i);
            int n = bar.getYValue().intValue();
            tip(bar.getNode(), bar.getXValue() + ": " + (showPages ? String.format("%,d pages", n)
                    : WishlistPicker.count(n, "book", "books")));
        }
        styleChart(chart);
        chart.setPrefHeight(260);
        return chart;
    }

    private VBox formatChart() {
        Map<Format, double[]> byFormat = spendByFormat();
        if (byFormat.isEmpty()) {
            return card("SPEND BY FORMAT", note("No copies yet."));
        }
        String currency = manager().getCurrencyCode();
        double max = 0;
        List<Format> formats = new ArrayList<>(byFormat.keySet());
        for (int i = 0; i < formats.size(); i++) {
            max = Math.max(max, byFormat.get(formats.get(i))[1]);
        }
        BarChart<String, Number> chart = new BarChart<>(new CategoryAxis(), axis(max, "Spent (" + currency + ")"));
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        for (int i = 0; i < formats.size(); i++) {
            series.getData().add(new XYChart.Data<String, Number>(formats.get(i).getLabel(),
                    byFormat.get(formats.get(i))[1]));
        }
        chart.getData().add(series);
        for (int i = 0; i < formats.size(); i++) {
            double[] figures = byFormat.get(formats.get(i));
            tip(series.getData().get(i).getNode(), formats.get(i).getLabel() + ": "
                    + BrowseController.formatMoney(currency, figures[1]) + " on "
                    + WishlistPicker.count((int) figures[0], "copy", "copies"));
        }
        styleChart(chart);
        chart.setPrefHeight(260);
        return card("SPEND BY FORMAT", chart);
    }

    private VBox statusChart() {
        Map<ReadingStatus, Integer> counts = statusCounts();
        PieChart pie = new PieChart();
        pie.setAnimated(false);
        pie.setLegendVisible(false);
        pie.setLabelsVisible(true);
        pie.setStartAngle(90);
        pie.setPrefHeight(260);
        ReadingStatus[] all = ReadingStatus.values();
        for (int i = 0; i < all.length; i++) {
            int n = counts.get(all[i]);
            if (n == 0) {
                // A zero slice draws nothing but still prints its label.
                continue;
            }
            final ReadingStatus status = all[i];
            PieChart.Data slice = new PieChart.Data(status.getLabel() + " (" + n + ")", n);
            pie.getData().add(slice);
            // The slice's node exists once it is in the chart.
            slice.getNode().setStyle("-fx-pie-color: " + STATUS_COLOURS.get(status) + ";");
            slice.getNode().setCursor(Cursor.HAND);
            tip(slice.getNode(), WishlistPicker.count(n, "book", "books") + " " + status.getLabel().toLowerCase()
                    + "  ·  click to show them");
            slice.getNode().setOnMouseClicked(new EventHandler<MouseEvent>() {
                @Override
                public void handle(MouseEvent event) {
                    showOnly(status, null);
                }
            });
        }
        pie.getStyleClass().add("stat-chart");
        return card("READING STATUS", pie);
    }

    private static void styleChart(BarChart<String, Number> chart) {
        chart.setAnimated(false);
        chart.setLegendVisible(false);
        chart.setBarGap(2);
        chart.setCategoryGap(10);
        chart.setVerticalGridLinesVisible(false);
        chart.getStyleClass().add("stat-chart");
        chart.getYAxis().setSide(Side.LEFT);
    }

    private static void tip(Node node, String text) {
        if (node != null) {
            Tooltip.install(node, new Tooltip(text));
        }
    }

    // ---------------------------------------------------------------- other pages

    /** E134/E141: Browse showing only one status or one category. */
    private void showOnly(ReadingStatus status, Category category) {
        PageController page = shell.show(PageId.BROWSE);
        if (page instanceof BrowseController) {
            ((BrowseController) page).showOnly(status, category);
        }
    }

    /**
     * E138: Refresh details for the books with no page count, preset to
     * Pages only. After Apply or Undo the page counts again.
     */
    private void fillPages(List<Book> books) {
        new RefreshFlow(session, shell).refreshMany(books, BulkRefreshDialog.pagesOnly(), new Runnable() {
            @Override
            public void run() {
                shown();
            }
        });
    }

    /** E138: one book's details in Browse (longest / shortest). */
    private void showBook(Book book) {
        PageController page = shell.show(PageId.BROWSE);
        if (page instanceof BrowseController) {
            ((BrowseController) page).showBook(book);
        }
    }

    // ---------------------------------------------------------------- category table

    private VBox categoryTable() {
        List<Category> roots = manager().getRootCategories();
        if (roots.isEmpty()) {
            return card("BY CATEGORY", note("No categories yet. Make some on the Organise page."));
        }
        TreeItem<CategoryRow> root = new TreeItem<>(null);
        for (int i = 0; i < roots.size(); i++) {
            root.getChildren().add(categoryItem(roots.get(i)));
        }
        final TreeTableView<CategoryRow> table = new TreeTableView<>(root);
        table.setShowRoot(false);
        table.getStyleClass().add("stat-table");
        table.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TreeTableColumn<CategoryRow, String> name = column("Category", 0);
        name.setPrefWidth(260);
        table.getColumns().add(name);
        table.getColumns().add(column("Books", 1));
        table.getColumns().add(column("Pages owned", 2));
        table.getColumns().add(column("Pages read", 3));
        table.getColumns().add(column("% read", 4));

        // E141: a double-click opens Browse filtered to the row's category
        // (a single click only selects, so expanding a row never navigates away).
        table.setRowFactory(new Callback<TreeTableView<CategoryRow>, TreeTableRow<CategoryRow>>() {
            @Override
            public TreeTableRow<CategoryRow> call(TreeTableView<CategoryRow> view) {
                final TreeTableRow<CategoryRow> row = new TreeTableRow<>();
                row.setOnMouseClicked(new EventHandler<MouseEvent>() {
                    @Override
                    public void handle(MouseEvent event) {
                        if (event.getClickCount() == 2 && row.getItem() != null) {
                            showOnly(null, row.getItem().category);
                        }
                    }
                });
                return row;
            }
        });
        int rows = countRows(root);
        // Tall enough for every row, so the page scrolls instead of the table.
        table.setPrefHeight(34 + rows * 30);
        table.setMinHeight(34 + Math.min(rows, 4) * 30);

        Label note = note("A book in two categories counts in both. Double-click a row to see its books.");
        return card("BY CATEGORY", table, note);
    }

    private TreeItem<CategoryRow> categoryItem(Category category) {
        TreeItem<CategoryRow> item = new TreeItem<>(countCategory(category));
        List<Category> children = category.getChildren();
        for (int i = 0; i < children.size(); i++) {
            item.getChildren().add(categoryItem(children.get(i)));
        }
        return item;
    }

    private static int countRows(TreeItem<CategoryRow> parent) {
        int n = 0;
        for (int i = 0; i < parent.getChildren().size(); i++) {
            n += 1 + (parent.getChildren().get(i).isExpanded() ? countRows(parent.getChildren().get(i)) : 0);
        }
        return n;
    }

    /** One text column; {@code which} picks the figure (0 = name ... 4 = percent). */
    private static TreeTableColumn<CategoryRow, String> column(String title, final int which) {
        TreeTableColumn<CategoryRow, String> column = new TreeTableColumn<>(title);
        column.setCellValueFactory(new Callback<TreeTableColumn.CellDataFeatures<CategoryRow, String>,
                ObservableValue<String>>() {
            @Override
            public ObservableValue<String> call(TreeTableColumn.CellDataFeatures<CategoryRow, String> cell) {
                CategoryRow row = cell.getValue().getValue();
                String text;
                switch (which) {
                    case 0:
                        text = row.category.getName();
                        break;
                    case 1:
                        text = String.valueOf(row.books);
                        break;
                    case 2:
                        text = row.owned == 0 ? "—" : String.format("%,d", row.owned);
                        break;
                    case 3:
                        text = row.owned == 0 ? "—" : String.format("%,d", row.read);
                        break;
                    default:
                        text = row.percent();
                        break;
                }
                return new ReadOnlyStringWrapper(text);
            }
        });
        column.setSortable(false);
        return column;
    }

}
