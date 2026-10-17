package com.shelfwise.core.metadata;

import com.shelfwise.core.exception.InvalidISBNException;
import com.shelfwise.core.exception.MetadataFetchException;
import com.shelfwise.core.service.BookMetadata;
import com.shelfwise.core.service.MetadataProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MetadataLookup}. The sources are small fakes written
 * here against the {@link MetadataProvider} interface, which is the point of
 * having the interface: the lookup cannot tell them from the real ones.
 */
class MetadataLookupTest {

    @TempDir
    Path folder;

    /** A fake source that answers with fixed details, or fails, and counts its calls. */
    private static class FakeSource implements MetadataProvider {
        private final String name;
        private final BookMetadata answer;
        private final String failure;
        int calls;

        FakeSource(String name, BookMetadata answer, String failure) {
            this.name = name;
            this.answer = answer;
            this.failure = failure;
        }

        @Override
        public String getProviderName() {
            return name;
        }

        @Override
        public BookMetadata fetchByIsbn(String isbn) throws MetadataFetchException {
            calls++;
            if (failure != null) {
                throw new MetadataFetchException(name, isbn, failure);
            }
            return answer;
        }
    }

    /** @return details like Open Library's: no language, so there is a gap */
    private static BookMetadata openLibraryStyle() {
        BookMetadata m = new BookMetadata("Glimpses of World History");
        m.addAuthor("Jawaharlal Nehru");
        m.setPublisher("Penguin Books India");
        m.setYear(2005);
        m.setPages(1192);
        return m;
    }

    /** @return details like Google's: a language, and a different publisher */
    private static BookMetadata googleStyle() {
        BookMetadata m = new BookMetadata("Glimpses Of World History (different casing)");
        m.addAuthor("J. Nehru");
        m.setPublisher("Penguin");
        m.setYear(2004);
        m.setPages(1200);
        m.setLanguage("English");
        return m;
    }

    /**
     * @param sources fakes, in order
     * @param cache   a cache, or null
     * @return a lookup over them
     */
    private static MetadataLookup lookupOver(MetadataCache cache, MetadataProvider... sources) {
        List<MetadataProvider> list = new ArrayList<>();
        for (int i = 0; i < sources.length; i++) {
            list.add(sources[i]);
        }
        return new MetadataLookup(list, cache);
    }

    @Test
    @DisplayName("the next source fills only the gaps; values already found are never replaced")
    void gapFill() throws Exception {
        FakeSource openLibrary = new FakeSource("Open Library", openLibraryStyle(), null);
        FakeSource google = new FakeSource("Google Books", googleStyle(), null);

        LookupResult result = lookupOver(null, openLibrary, google).lookUp("9780143031055");
        BookMetadata m = result.getMetadata();

        assertEquals("Glimpses of World History", m.getTitle());
        assertEquals("Jawaharlal Nehru", m.getAuthors().get(0));
        assertEquals(1, m.getAuthors().size());
        assertEquals("Penguin Books India", m.getPublisher());
        assertEquals(2005, m.getYear());
        // The one gap, filled from the second source.
        assertEquals("English", m.getLanguage());
        assertEquals("Found on Open Library, gaps filled from Google Books", result.describe());
    }

    @Test
    @DisplayName("a complete first answer means the next source is never asked")
    void completeAnswerStops() throws Exception {
        BookMetadata complete = openLibraryStyle();
        complete.setLanguage("English");
        FakeSource first = new FakeSource("Open Library", complete, null);
        FakeSource second = new FakeSource("Google Books", googleStyle(), null);

        lookupOver(null, first, second).lookUp("9780143031055");

        assertEquals(0, second.calls);
    }

    @Test
    @DisplayName("a gap-filler that fails still leaves a successful lookup, with the failure named")
    void failedGapFillIsPartialSuccess() throws Exception {
        FakeSource openLibrary = new FakeSource("Open Library", openLibraryStyle(), null);
        FakeSource google = new FakeSource("Google Books", null, "rate limit reached");

        LookupResult result = lookupOver(null, openLibrary, google).lookUp("9780143031055");

        assertNull(result.getMetadata().getLanguage());
        assertEquals("Found on Open Library (Google Books: rate limit reached)", result.describe());
    }

    @Test
    @DisplayName("when the first source does not know the book, the second becomes the base")
    void secondSourceFinds() throws Exception {
        FakeSource openLibrary = new FakeSource("Open Library", null, "no record found");
        FakeSource google = new FakeSource("Google Books", googleStyle(), null);

        LookupResult result = lookupOver(null, openLibrary, google).lookUp("9780143031055");

        assertEquals("Penguin", result.getMetadata().getPublisher());
        assertEquals("Found on Google Books (Open Library: no record found)", result.describe());
    }

    @Test
    @DisplayName("when no source finds the book, one exception lists every reason")
    void allFail() {
        final MetadataLookup lookup = lookupOver(null,
                new FakeSource("Open Library", null, "no record found"),
                new FakeSource("Google Books", null, "rate limit reached"));
        MetadataFetchException e = assertThrows(MetadataFetchException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                lookup.lookUp("9788183071000");
            }
        });
        assertTrue(e.getMessage().contains("Open Library: no record found"), e.getMessage());
        assertTrue(e.getMessage().contains("Google Books: rate limit reached"), e.getMessage());
    }

    @Test
    @DisplayName("an ISBN-10 is looked up and cached under its ISBN-13")
    void isbn10Normalised() throws Exception {
        FakeSource source = new FakeSource("Open Library", openLibraryStyle(), null);
        MetadataCache cache = new MetadataCache(folder);

        LookupResult result = lookupOver(cache, source).lookUp("0143031058");

        assertEquals("9780143031055", result.getIsbn());
        assertTrue(cache.get("9780143031055") != null);
    }

    @Test
    @DisplayName("a cached answer is served without asking any source; 'look up again' asks anyway")
    void cacheUsedAndBypassed() throws Exception {
        FakeSource source = new FakeSource("Open Library", openLibraryStyle(), null);
        MetadataLookup lookup = lookupOver(new MetadataCache(folder), source);

        lookup.lookUp("9780143031055");
        LookupResult second = lookup.lookUp("9780143031055");
        assertEquals(1, source.calls);
        assertTrue(second.isFromCache());

        LookupResult again = lookup.lookUp("9780143031055", true);
        assertEquals(2, source.calls);
        assertFalse(again.isFromCache());
    }

    @Test
    @DisplayName("a failed lookup is not cached, so it is retried next time")
    void failuresNotCached() {
        final FakeSource source = new FakeSource("Open Library", null, "no record found");
        final MetadataLookup lookup = lookupOver(new MetadataCache(folder), source);
        for (int i = 0; i < 2; i++) {
            assertThrows(MetadataFetchException.class, new Executable() {
                @Override
                public void execute() throws Throwable {
                    lookup.lookUp("9788183071000");
                }
            });
        }
        assertEquals(2, source.calls);
    }

    @Test
    @DisplayName("an invalid ISBN is refused before any source is asked")
    void invalidIsbn() {
        final FakeSource source = new FakeSource("Open Library", openLibraryStyle(), null);
        final MetadataLookup lookup = lookupOver(null, source);
        assertThrows(InvalidISBNException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                lookup.lookUp("9780143031056");
            }
        });
        assertEquals(0, source.calls);
    }
}
