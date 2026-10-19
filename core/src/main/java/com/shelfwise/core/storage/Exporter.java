package com.shelfwise.core.storage;

import com.shelfwise.core.service.CollectionData;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes a collection out to a file in some format.
 * <p>
 * <strong>Template method.</strong> {@link #export(CollectionData, Path)}
 * fixes the steps every export shares - open the file as UTF-8 text, hand
 * it to the format, close it even if writing fails - and leaves one step,
 * {@link #write}, abstract. {@link CsvExporter} and {@link JsonExporter}
 * each override only that step. A caller holding an {@code Exporter}
 * variable does not know which format it has; the object's real class
 * decides at runtime (runtime polymorphism).
 * <p>
 * <strong>Why a {@link BufferedWriter}.</strong> Each {@code write} on a
 * plain file writer is a call into the operating system. The buffer
 * collects text in memory and writes it in large blocks, which matters
 * when a collection has thousands of rows.
 */
public abstract class Exporter {

    /**
     * Writes the collection to a file, replacing it if it exists.
     * <p>
     * try-with-resources closes the writer whether {@link #write} finishes
     * or throws, so a failed export never leaves the file open.
     *
     * @param data the collection to write; must not be null
     * @param file where to write it
     * @throws IOException if the file cannot be written
     */
    public void export(CollectionData data, Path file) throws IOException {
        requireData(data);
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            write(data, out);
        }
    }

    /**
     * Writes the collection to any character stream - a string in a test,
     * for instance. An overload of the {@code Path} version.
     * <p>
     * The stream is flushed but deliberately not closed: whoever opened it
     * still owns it.
     *
     * @param data the collection to write; must not be null
     * @param out  where to write it
     * @throws IOException if writing fails
     */
    public void export(CollectionData data, Writer out) throws IOException {
        requireData(data);
        BufferedWriter buffered = new BufferedWriter(out);
        write(data, buffered);
        buffered.flush();
    }

    /**
     * The extension this format's files use, without the dot, so a save
     * dialog can suggest a file name without knowing the format.
     *
     * @return e.g. "csv"
     */
    public abstract String getFileExtension();

    /**
     * The one step that differs between formats.
     *
     * @param data the collection to write
     * @param out  an open, buffered writer; closed by the caller
     * @throws IOException if writing fails
     */
    protected abstract void write(CollectionData data, BufferedWriter out) throws IOException;

    /**
     * @param data the collection to check
     * @throws IllegalArgumentException if null
     */
    private static void requireData(CollectionData data) {
        if (data == null) {
            throw new IllegalArgumentException("Collection data must not be null.");
        }
    }
}
