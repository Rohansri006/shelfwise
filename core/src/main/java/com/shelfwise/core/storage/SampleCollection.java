package com.shelfwise.core.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * The sample collection bundled inside the application: 25 well-known
 * books with categories, shelves, a series, wishlists and reading history,
 * so every screen has something to show on a first run or in a demo
 * (D22, D23).
 * <p>
 * It is a JSON export shipped as a classpath resource - a file inside the
 * built jar - rather than a file in the data folder, because the data,
 * cache and backup folders are never committed to the repository
 * (CLAUDE.md §14). Every ISBN in it was checked against Open Library.
 */
public final class SampleCollection {

    /** Where the file sits on the classpath. */
    public static final String RESOURCE = "/sample-collection.json";

    private SampleCollection() {
    }

    /**
     * Reads the sample collection. Merging it into a collection is then the
     * same {@link CollectionMerger} step as any other import.
     *
     * @return the parsed sample
     * @throws IOException if the resource is missing from the build or
     *                     cannot be read
     */
    public static ParsedImport read() throws IOException {
        // getResourceAsStream looks inside the jar (or target/classes when
        // run from an IDE); a leading "/" means "from the classpath root".
        InputStream stream = SampleCollection.class.getResourceAsStream(RESOURCE);
        if (stream == null) {
            throw new IOException("The sample collection is missing from this build.");
        }
        try (Reader in = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return new JsonImporter().read(in);
        }
    }
}
