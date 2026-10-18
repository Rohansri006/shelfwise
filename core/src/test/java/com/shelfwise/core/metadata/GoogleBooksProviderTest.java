package com.shelfwise.core.metadata;

import com.shelfwise.core.exception.MetadataFetchException;
import com.shelfwise.core.service.BookMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link GoogleBooksProvider}.
 * <p>
 * The full reply fixture is hand-written to the shape Google documents for
 * {@code volumes?q=isbn:}, because every live keyless request on 15 and 17
 * Oct was refused with HTTP 429, so no real reply could be captured.
 */
class GoogleBooksProviderTest {

    /**
     * @param key   the API key to configure, or null
     * @param reply what the fake network returns
     * @return a provider whose download is replaced by the given reply
     */
    private static GoogleBooksProvider replying(String key, final String reply) {
        return new GoogleBooksProvider(key) {
            @Override
            protected String download(String address, String isbn) {
                return reply;
            }
        };
    }

    @Test
    @DisplayName("every field of a reply is read, the language becomes a name and the cover link https")
    void fullReply() throws Exception {
        BookMetadata found = replying(null, OpenLibraryProviderTest.fixture("googlebooks-sample.json"))
                .fetchByIsbn("9780143031055");

        assertEquals("Glimpses of World History", found.getTitle());
        assertEquals("Being Further Letters to His Daughter", found.getSubtitle());
        assertEquals("Jawaharlal Nehru", found.getAuthors().get(0));
        assertEquals("Penguin Books India", found.getPublisher());
        assertEquals(2004, found.getYear());
        assertEquals(1192, found.getPages());
        assertEquals("English", found.getLanguage());
        assertTrue(found.getCoverUrl().startsWith("https://"), found.getCoverUrl());
    }

    @Test
    @DisplayName("no matching volume is 'no record found'")
    void noMatch() {
        final GoogleBooksProvider source = replying(null, "{\"kind\": \"books#volumes\", \"totalItems\": 0}");
        MetadataFetchException e = assertThrows(MetadataFetchException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                source.fetchByIsbn("9788183071000");
            }
        });
        assertTrue(e.getMessage().contains("no record found"), e.getMessage());
    }

    @Test
    @DisplayName("the key is added to the request only when there is one, and never to a message")
    void keyHandling() {
        assertFalse(new GoogleBooksProvider().buildUrl("9780143031055").contains("key="));
        assertFalse(new GoogleBooksProvider("  ").hasKey());
        GoogleBooksProvider keyed = new GoogleBooksProvider("MY-SECRET-KEY");
        assertTrue(keyed.hasKey());
        assertTrue(keyed.buildUrl("9780143031055").endsWith("&key=MY-SECRET-KEY"));

        final GoogleBooksProvider failing = replying("MY-SECRET-KEY", "{}");
        MetadataFetchException e = assertThrows(MetadataFetchException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                failing.fetchByIsbn("9780143031055");
            }
        });
        assertFalse(e.getMessage().contains("MY-SECRET-KEY"), e.getMessage());
    }

    @Test
    @DisplayName("language codes become English names, and unknown codes stay as they are")
    void languageNames() {
        assertEquals("English", GoogleBooksProvider.languageName("en"));
        assertEquals("Hindi", GoogleBooksProvider.languageName("hi"));
        assertNull(GoogleBooksProvider.languageName(null));
        assertEquals("zz", GoogleBooksProvider.languageName("zz"));
    }
}
