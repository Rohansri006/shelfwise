package com.shelfwise.core.metadata;

import com.shelfwise.core.service.BookMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link MetadataCache}, against real files in a temporary folder. */
class MetadataCacheTest {

    @TempDir
    Path folder;

    @Test
    @DisplayName("a stored result comes back with every field and its sources, marked as from the cache")
    void roundTrip() throws Exception {
        BookMetadata m = new BookMetadata("Glimpses of World History");
        m.addAuthor("Jawaharlal Nehru");
        m.setPublisher("Penguin Books India");
        m.setYear(2005);
        m.setPages(1192);
        m.setLanguage("English");
        m.setCoverUrl("https://covers.openlibrary.org/b/id/1-M.jpg");
        MetadataCache cache = new MetadataCache(folder.resolve("cache"));

        cache.put(new LookupResult("9780143031055", m, Arrays.asList("Open Library", "Google Books"),
                new ArrayList<String>(), false, 900));
        LookupResult back = cache.get("9780143031055");

        assertTrue(back.isFromCache());
        assertEquals("Glimpses of World History", back.getMetadata().getTitle());
        assertEquals("Jawaharlal Nehru", back.getMetadata().getAuthors().get(0));
        assertEquals(2005, back.getMetadata().getYear());
        assertEquals(1192, back.getMetadata().getPages());
        assertEquals("English", back.getMetadata().getLanguage());
        assertEquals("Google Books", back.getSources().get(1));
        assertTrue(Files.exists(folder.resolve("cache").resolve("9780143031055.json")));
    }

    @Test
    @DisplayName("an ISBN never looked up is a miss")
    void miss() {
        assertNull(new MetadataCache(folder).get("9780143031055"));
    }

    @Test
    @DisplayName("a damaged cache file is treated as a miss, not a crash")
    void corruptFileIsMiss() throws Exception {
        Files.write(folder.resolve("9780143031055.json"), "{ not json".getBytes(StandardCharsets.UTF_8));
        assertNull(new MetadataCache(folder).get("9780143031055"));
    }

    @Test
    @DisplayName("only 13-digit ISBNs are accepted as keys, so no file outside the folder can be named")
    void keysAreIsbn13() {
        final MetadataCache cache = new MetadataCache(folder);
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                cache.get("../../etc/passwd");
            }
        });
    }
}
