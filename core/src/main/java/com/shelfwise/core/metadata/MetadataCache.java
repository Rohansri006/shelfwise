package com.shelfwise.core.metadata;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.shelfwise.core.service.BookMetadata;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Remembers lookup results on disk, one JSON file per ISBN, so a book is
 * looked up online once and answered from disk ever after - including with
 * no internet during a demo.
 * <p>
 * Kept forever: a book's title and author do not change. Only successes are
 * stored; a failure may just mean the network was down, so it is retried
 * next time.
 * <p>
 * <strong>Why a separate {@link Entry} class.</strong> Gson converts an
 * object to JSON by copying its fields. Writing {@link LookupResult}
 * directly would also save things that make no sense on disk, such as how
 * long the lookup took. {@code Entry} holds exactly what is stored, as plain
 * fields Gson can copy in both directions.
 * <p>
 * Files are written with a {@link BufferedWriter} and read with a
 * {@link BufferedReader}, each in try-with-resources so they are closed
 * even if something fails.
 */
public class MetadataCache {

    /** What one cache file holds. Fields only; Gson fills them by reflection. */
    private static class Entry {
        String isbn;
        String title;
        String subtitle;
        List<String> authors;
        String publisher;
        int year;
        int pages;
        String language;
        String coverUrl;
        List<String> sources;
    }

    private final Path folder;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * @param folder where cache files live; created on first write
     * @throws IllegalArgumentException if folder is null
     */
    public MetadataCache(Path folder) {
        if (folder == null) {
            throw new IllegalArgumentException("Cache folder must not be null.");
        }
        this.folder = folder;
    }

    /**
     * Reads a cached result.
     *
     * @param isbn13 the ISBN-13 to look for
     * @return the cached result marked as from the cache, or null if there
     *         is none or the file cannot be read
     */
    public LookupResult get(String isbn13) {
        Path file = fileFor(isbn13);
        if (!Files.exists(file)) {
            return null;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Entry entry = gson.fromJson(reader, Entry.class);
            if (entry == null || entry.title == null || entry.sources == null || entry.sources.isEmpty()) {
                return null;
            }
            BookMetadata metadata = new BookMetadata(entry.title);
            metadata.setSubtitle(entry.subtitle);
            if (entry.authors != null) {
                for (int i = 0; i < entry.authors.size(); i++) {
                    metadata.addAuthor(entry.authors.get(i));
                }
            }
            metadata.setPublisher(entry.publisher);
            metadata.setYear(Math.max(0, entry.year));
            metadata.setPages(Math.max(0, entry.pages));
            metadata.setLanguage(entry.language);
            metadata.setCoverUrl(entry.coverUrl);
            return new LookupResult(isbn13, metadata, entry.sources, new ArrayList<String>(), true, 0);
        } catch (IOException | JsonParseException | IllegalArgumentException e) {
            // A damaged or hand-edited file is treated as a miss: the book is
            // simply looked up again and the file rewritten.
            return null;
        }
    }

    /**
     * Stores a result, replacing any earlier one for the same ISBN.
     *
     * @param result the result to remember
     * @throws IOException if the file cannot be written
     */
    public void put(LookupResult result) throws IOException {
        Entry entry = new Entry();
        BookMetadata m = result.getMetadata();
        entry.isbn = result.getIsbn();
        entry.title = m.getTitle();
        entry.subtitle = m.getSubtitle();
        entry.authors = new ArrayList<>(m.getAuthors());
        entry.publisher = m.getPublisher();
        entry.year = m.getYear();
        entry.pages = m.getPages();
        entry.language = m.getLanguage();
        entry.coverUrl = m.getCoverUrl();
        entry.sources = new ArrayList<>(result.getSources());

        Files.createDirectories(folder);
        try (BufferedWriter writer = Files.newBufferedWriter(fileFor(result.getIsbn()), StandardCharsets.UTF_8)) {
            gson.toJson(entry, writer);
        }
    }

    /**
     * @param isbn13 an ISBN-13
     * @return the file that holds its cached result
     * @throws IllegalArgumentException if it is not 13 digits, which also
     *                                  stops a crafted value naming a file
     *                                  outside the cache folder
     */
    private Path fileFor(String isbn13) {
        if (isbn13 == null || !isbn13.matches("\\d{13}")) {
            throw new IllegalArgumentException("Cache keys are 13-digit ISBNs, but was " + isbn13 + ".");
        }
        return folder.resolve(isbn13 + ".json");
    }
}
