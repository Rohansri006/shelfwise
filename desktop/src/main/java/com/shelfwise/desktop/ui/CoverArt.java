package com.shelfwise.desktop.ui;

import com.shelfwise.core.model.Book;
import com.shelfwise.core.model.Contributor;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.stage.Screen;
import javafx.scene.text.TextAlignment;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Book covers for Browse, the details panel and the Add page.
 * <p>
 * Priority (Q8, Q9): a cover the user uploaded, then one fetched by a
 * lookup, then a generated one. Generated covers imitate Calibre's: one of
 * four styles and a palette, both chosen by a {@link Random} seeded with
 * the book's id - so the "random" cover is the same every launch.
 */
final class CoverArt {

    /** Base colours the palettes are built from; deep enough for white text. */
    private static final Color[] BASES = {
        Color.web("#264653"), Color.web("#2a9d8f"), Color.web("#8a5a44"), Color.web("#6d597a"),
        Color.web("#355070"), Color.web("#9c2c3f"), Color.web("#3d5a40"), Color.web("#7a4419"),
        Color.web("#1d3557"), Color.web("#5f0f40"), Color.web("#22577a"), Color.web("#4a4e69")
    };

    /** Longest side of a stored upload (E26). */
    private static final int MASTER_MAX = 1600;

    /**
     * Decoded cover images, keyed by file, last-modified time and decode
     * width. The grid is rebuilt on every search keystroke; without this,
     * every rebuild would decode every cover file again.
     */
    private static final Map<String, Image> DECODED = new HashMap<>();

    /** Text stays inside this share of the cover's width and height (#6). */
    private static final double SAFE_SIDE = 0.18;

    private CoverArt() {
    }

    // ------------------------------------------------------------------
    // Choosing a cover
    // ------------------------------------------------------------------

    /**
     * @param book           the book
     * @param dataFolder     the collection's folder, for uploaded and cached covers
     * @param width          cover width on screen, in points
     * @param height         cover height on screen, in points
     * @param fullResolution true to decode the whole stored image (details
     *                       panel); false to decode only as many pixels as
     *                       the tile needs (grid)
     * @return an image view of a real cover if there is one, else a generated cover
     */
    static Node coverFor(Book book, Path dataFolder, double width, double height, boolean fullResolution) {
        Path image = imageFor(book, dataFolder);
        if (image != null) {
            return imageView(decoded(image, fullResolution ? 0 : width), width, height);
        }
        return generated(book, width, height);
    }

    /**
     * An image scaled to a cover slot with rounded corners.
     *
     * @return the view
     */
    static ImageView imageView(Image image, double width, double height) {
        ImageView view = new ImageView(image);
        // The view scales whatever was decoded to the slot; smooth = good filtering.
        view.setFitWidth(width);
        view.setFitHeight(height);
        view.setSmooth(true);
        Rectangle clip = new Rectangle(width, height);
        clip.setArcWidth(14);
        clip.setArcHeight(14);
        view.setClip(clip);
        return view;
    }

    /**
     * #8 (E26): decodes a cover at the size it will be shown. A Retina screen
     * has 2 physical pixels per point, so a 134pt tile needs 268 pixels;
     * asking {@code Image} for exactly that means a 1600px master is never
     * held in memory just to draw a thumbnail. Width 0 means "full size".
     *
     * @param file  the image file
     * @param width display width in points, or 0 for the whole image
     * @return the decoded image, from the cache if already decoded
     */
    static Image decoded(Path file, double width) {
        int pixels = width <= 0 ? 0 : (int) Math.ceil(width * Screen.getPrimary().getOutputScaleX());
        String key = file + "|" + file.toFile().lastModified() + "|" + pixels;
        Image image = DECODED.get(key);
        if (image == null) {
            String url = file.toUri().toString();
            // (url, width, height, preserveRatio, smooth): height 0 with
            // preserveRatio true keeps the original shape.
            image = pixels == 0 ? new Image(url) : new Image(url, pixels, 0, true, true);
            DECODED.put(key, image);
        }
        return image;
    }

    /**
     * @return the uploaded cover, else the cached lookup cover, else null
     */
    static Path imageFor(Book book, Path dataFolder) {
        Path uploaded = uploadedPath(book, dataFolder);
        if (Files.exists(uploaded)) {
            return uploaded;
        }
        if (book.getIsbn() != null) {
            Path fetched = dataFolder.resolve("cache").resolve("covers").resolve(book.getIsbn() + ".jpg");
            if (Files.exists(fetched)) {
                return fetched;
            }
        }
        return null;
    }

    /** @return where an uploaded cover for this book lives (Q9: keyed by book id) */
    static Path uploadedPath(Book book, Path dataFolder) {
        return dataFolder.resolve("covers").resolve(book.getId() + ".jpg");
    }

    // ------------------------------------------------------------------
    // Uploads (Q10: downscale to 600px, save as JPEG)
    // ------------------------------------------------------------------

    /**
     * Reads an image file, shrinks it so its longer side is at most 600px,
     * and saves it as this book's cover.
     *
     * @throws IOException if the file is not an image or cannot be written
     */
    static void saveUpload(Book book, Path dataFolder, File source) throws IOException {
        BufferedImage original = ImageIO.read(source);
        if (original == null) {
            throw new IOException("\"" + source.getName() + "\" is not an image ShelfWise can read.");
        }
        saveImage(book, dataFolder, original);
    }

    /** The downscale-and-write half of {@link #saveUpload}. */
    private static void saveImage(Book book, Path dataFolder, BufferedImage original) throws IOException {
        // #8 (E26): keep up to 1600px, so the details panel stays sharp.
        double scale = Math.min(1.0, (double) MASTER_MAX / Math.max(original.getWidth(), original.getHeight()));
        int w = (int) Math.round(original.getWidth() * scale);
        int h = (int) Math.round(original.getHeight() * scale);
        // JPEG has no transparency, so draw onto a plain RGB image first;
        // ImageIO refuses to write an image with an alpha channel as JPEG.
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(original, 0, 0, w, h, null);
        g.dispose();
        Path target = uploadedPath(book, dataFolder);
        Files.createDirectories(target.getParent());
        writeJpeg(scaled, target.toFile(), 0.92f);
    }

    /**
     * {@code ImageIO.write} uses a default JPEG quality of about 0.75, which
     * blurs fine print on a cover. An {@link ImageWriter} with explicit
     * parameters lets the quality be chosen.
     */
    private static void writeJpeg(BufferedImage image, File target, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        try (ImageOutputStream out = ImageIO.createImageOutputStream(target)) {
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    /** @return true if a cover was removed */
    static boolean removeUpload(Book book, Path dataFolder) throws IOException {
        return Files.deleteIfExists(uploadedPath(book, dataFolder));
    }

    /**
     * Saves a cover already on screen - one a lookup downloaded, or one
     * picked in the Add form - as this book's cover file (E153: covers are
     * real files in {@code covers/}, never kept only in memory).
     *
     * @throws IOException if the file cannot be written
     */
    static void saveCover(Book book, Path dataFolder, Image image) throws IOException {
        // SwingFXUtils converts JavaFX's Image into the BufferedImage that
        // Java's own image writer (ImageIO) works with.
        saveImage(book, dataFolder, SwingFXUtils.fromFXImage(image, null));
    }

    // ------------------------------------------------------------------
    // Downloads
    // ------------------------------------------------------------------

    /** Same limit as the metadata providers: a stalled server gives up after 5 s. */
    private static final int DOWNLOAD_TIMEOUT_MS = 5000;

    /**
     * Downloads a lookup's cover. Blocks, so it is only ever called on a
     * background thread.
     * <p>
     * Why not {@code new Image(url, false)}: in testing it came back empty
     * for cover URLs that plain Java fetched fine, and it has no timeout, so
     * one stalled server could hold up every lookup queued behind it. Here
     * the connection has 5 s limits, Java's own {@link ImageIO} decodes the
     * bytes, and {@link SwingFXUtils} converts the result for JavaFX.
     *
     * @param url the cover's address, or null
     * @return the image, or null if there is no URL, it fails, or it is
     *         Open Library's 1x1 pixel "no cover" answer
     */
    static Image download(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(DOWNLOAD_TIMEOUT_MS);
            connection.setReadTimeout(DOWNLOAD_TIMEOUT_MS);
            // Same identification as the metadata providers; Open Library asks for one.
            connection.setRequestProperty("User-Agent", "ShelfWise/1.0 (student project; personal book collection)");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            BufferedImage picture;
            try (InputStream in = connection.getInputStream()) {
                picture = ImageIO.read(in);
            }
            // ImageIO.read answers null, not an exception, for bytes that are not an image.
            // Anything under 10 px is a "no cover" placeholder, not a cover.
            if (picture == null || picture.getWidth() < 10 || picture.getHeight() < 10) {
                return null;
            }
            return SwingFXUtils.toFXImage(picture, null);
        } catch (IOException | ClassCastException e) {
            // Offline, timed out, or not an http(s) URL: no cover, never a crash.
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ------------------------------------------------------------------
    // Generated covers
    // ------------------------------------------------------------------

    /**
     * A Calibre-like cover: a patterned background in one of four styles,
     * the title large, the author small at the bottom.
     */
    static Node generated(Book book, double width, double height) {
        Random random = new Random(book.getId().hashCode());
        Color base = BASES[random.nextInt(BASES.length)];
        Color light = base.deriveColor(0, 0.8, 1.45, 1);
        Color dark = base.deriveColor(0, 1.1, 0.6, 1);
        int style = random.nextInt(4);

        Pane art = new Pane();
        art.setPrefSize(width, height);
        art.setMinSize(width, height);
        art.setMaxSize(width, height);
        Rectangle background = new Rectangle(width, height, base);
        art.getChildren().add(background);

        if (style == 0) {
            blocks(art, random, width, height, light, dark);
        } else if (style == 1) {
            stripes(art, width, height, light);
        } else if (style == 2) {
            ornament(art, width, height, light);
        } else {
            background.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                    new Stop(0, light), new Stop(1, dark)));
            Rectangle band = new Rectangle(0, height * 0.30, width, height * 0.34);
            band.setFill(Color.rgb(0, 0, 0, 0.28));
            art.getChildren().add(band);
        }

        Rectangle clip = new Rectangle(width, height);
        clip.setArcWidth(14);
        clip.setArcHeight(14);
        art.setClip(clip);

        double scale = width / 134.0;
        Label title = new Label(book.getTitle());
        title.setWrapText(true);
        title.setTextAlignment(TextAlignment.CENTER);
        title.setAlignment(Pos.CENTER);
        // #6: text stays in a safe area clear of every style's artwork: 18% in
        // from each side (inside the ornament style's inner frame), and the
        // title no taller than 40% of the cover, between the emblem and rule.
        double safeWidth = width * (1 - 2 * SAFE_SIDE);
        title.setMaxWidth(safeWidth);
        title.setMaxHeight(height * 0.40);
        title.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: "
                + Math.round(15 * scale) + "px; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 4, 0, 0, 1);");

        Label author = new Label(authorOf(book));
        author.setWrapText(true);
        author.setTextAlignment(TextAlignment.CENTER);
        // A wrapping label stretches to its maximum width, so its text must be
        // centred inside it too, not only the label inside the box.
        author.setAlignment(Pos.CENTER);
        author.setMaxWidth(safeWidth);
        author.setStyle("-fx-text-fill: rgba(255,255,255,0.85); -fx-font-size: "
                + Math.round(10 * scale) + "px;");

        VBox titleBox = new VBox(title);
        titleBox.setAlignment(Pos.CENTER);
        titleBox.setMaxSize(width, height);
        VBox authorBox = new VBox(author);
        authorBox.setAlignment(Pos.BOTTOM_CENTER);
        authorBox.setMaxSize(width, height);
        // Lift the author above the inner frame's bottom edge (11.9% of the
        // width from the bottom in the ornament style) with a little air.
        authorBox.setStyle("-fx-padding: 0 0 " + Math.round(width * 0.119 + 6 * scale) + " 0;");

        StackPane cover = new StackPane(art, titleBox, authorBox);
        cover.setMaxSize(width, height);
        cover.setPrefSize(width, height);
        return cover;
    }

    /** Style 0: a mosaic of tinted squares across the top third. */
    private static void blocks(Pane art, Random random, double width, double height, Color light, Color dark) {
        int columns = 5;
        double size = width / columns;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < columns; col++) {
                Rectangle block = new Rectangle(col * size, row * size, size, size);
                int pick = random.nextInt(3);
                block.setFill(pick == 0 ? light : (pick == 1 ? dark : Color.TRANSPARENT));
                block.setOpacity(0.55);
                art.getChildren().add(block);
            }
        }
    }

    /** Style 1: diagonal stripes, like a book cloth. */
    private static void stripes(Pane art, double width, double height, Color light) {
        double gap = width / 7;
        for (double x = -height; x < width; x += gap) {
            Polygon stripe = new Polygon(x, height, x + gap / 2, height, x + gap / 2 + height, 0, x + height, 0);
            stripe.setFill(light);
            stripe.setOpacity(0.18);
            art.getChildren().add(stripe);
        }
    }

    /** Style 2: an inset double frame with a small emblem, like an old hardback. */
    private static void ornament(Pane art, double width, double height, Color light) {
        double inset = width * 0.07;
        Rectangle outer = new Rectangle(inset, inset, width - 2 * inset, height - 2 * inset);
        outer.setFill(Color.TRANSPARENT);
        outer.setStroke(light);
        outer.setStrokeWidth(1.5);
        Rectangle inner = new Rectangle(inset * 1.7, inset * 1.7, width - 3.4 * inset, height - 3.4 * inset);
        inner.setFill(Color.TRANSPARENT);
        inner.setStroke(light);
        inner.setOpacity(0.6);
        Circle emblem = new Circle(width / 2, height * 0.2, width * 0.06, Color.TRANSPARENT);
        emblem.setStroke(light);
        Line rule = new Line(width * 0.3, height * 0.72, width * 0.7, height * 0.72);
        rule.setStroke(light);
        art.getChildren().addAll(outer, inner, emblem, rule);
    }

    private static String authorOf(Book book) {
        Contributor author = book.getPrimaryAuthor();
        return author == null ? "" : author.getName();
    }
}
