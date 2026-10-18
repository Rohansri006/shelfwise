package com.shelfwise.core.storage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads a file of some format into a {@link ParsedImport}, without touching
 * the collection.
 * <p>
 * The mirror image of {@link Exporter}, and the same template method:
 * {@link #read(Path)} opens the file as UTF-8 through a
 * {@link BufferedReader} and closes it afterwards, and each format supplies
 * only {@link #parse}. {@link CsvImporter} and {@link JsonImporter} both
 * produce the same result type, so everything after reading - showing the
 * user what was found, then merging it in through {@link CollectionMerger}
 * - is written once for both.
 * <p>
 * Reading and merging are separate steps on purpose. Between them, the
 * screen can show how many books were found, which rows were unreadable and
 * whether the file's currency differs from the collection's (ADR 0005),
 * and the user can still cancel with nothing changed.
 */
public abstract class Importer {

    /**
     * Reads a file.
     *
     * @param file the file to read
     * @return what it contains, with any problems found along the way
     * @throws IOException if the file cannot be read or is not in this
     *                     format at all
     */
    public ParsedImport read(Path file) throws IOException {
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(in);
        }
    }

    /**
     * Reads from any character stream - a string in a test, or a file
     * bundled inside the application. An overload of the {@code Path}
     * version. The stream is not closed: whoever opened it still owns it.
     *
     * @param in where to read from
     * @return what it contains, with any problems found along the way
     * @throws IOException if reading fails or the text is not in this format
     */
    public ParsedImport read(Reader in) throws IOException {
        return parse(new BufferedReader(in));
    }

    /**
     * The one step that differs between formats.
     *
     * @param in an open, buffered reader; closed by the caller
     * @return what it contains
     * @throws IOException if reading fails or the text is not in this format
     */
    protected abstract ParsedImport parse(BufferedReader in) throws IOException;
}
