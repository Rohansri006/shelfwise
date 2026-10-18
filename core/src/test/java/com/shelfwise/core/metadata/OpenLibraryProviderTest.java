package com.shelfwise.core.metadata;

import com.shelfwise.core.exception.MetadataFetchException;
import com.shelfwise.core.service.BookMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link OpenLibraryProvider}, using saved replies instead of the network. */
class OpenLibraryProviderTest {

    /**
     * @param name a file in src/test/resources/metadata
     * @return its contents
     */
    static String fixture(String name) throws IOException {
        try (InputStream in = OpenLibraryProviderTest.class.getResourceAsStream("/metadata/" + name)) {
            if (in == null) {
                throw new IOException("Missing test fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * @param reply what the fake network returns
     * @return a provider whose download is replaced by the given reply
     */
    private static OpenLibraryProvider replying(final String reply) {
        return new OpenLibraryProvider() {
            @Override
            protected String download(String address, String isbn) {
                return reply;
            }
        };
    }

    @Test
    @DisplayName("a real Open Library reply is read into every field it has")
    void realReply() throws Exception {
        // Captured from the live service on 17 Oct 2026. Note the title: this
        // ISBN is Nehru's book, not Dune as some older tests assume.
        BookMetadata found = replying(fixture("openlibrary-9780143031055.json")).fetchByIsbn("9780143031055");

        assertEquals("Glimpses of World History", found.getTitle());
        assertEquals("Jawaharlal Nehru", found.getAuthors().get(0));
        assertEquals("Penguin Books India", found.getPublisher());
        assertEquals(2005, found.getYear());
        assertEquals(1192, found.getPages());
        // Open Library's reply has no language: the gap the lookup may fill.
        assertNull(found.getLanguage());
    }

    @Test
    @DisplayName("an unknown ISBN, which Open Library answers with {}, is 'no record found'")
    void unknownIsbn() {
        final OpenLibraryProvider source = replying("{}");
        MetadataFetchException e = assertThrows(MetadataFetchException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                source.fetchByIsbn("9788183071000");
            }
        });
        assertTrue(e.getMessage().contains("no record found"), e.getMessage());
        assertEquals("Open Library", e.getProviderName());
    }

    @Test
    @DisplayName("an entry without a title is refused rather than becoming a blank suggestion")
    void entryWithoutTitle() {
        final OpenLibraryProvider source = replying("{\"ISBN:9780143031055\": {\"number_of_pages\": 10}}");
        assertThrows(MetadataFetchException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                source.fetchByIsbn("9780143031055");
            }
        });
    }

    @Test
    @DisplayName("cover, subtitle and several authors are read when present")
    void optionalFields() throws Exception {
        String reply = "{\"ISBN:9780000000019\": {\"title\": \"T\", \"subtitle\": \"S\","
                + " \"authors\": [{\"name\": \"A\"}, {\"name\": \"B\"}],"
                + " \"cover\": {\"medium\": \"https://covers.openlibrary.org/b/id/1-M.jpg\"}}}";
        BookMetadata found = replying(reply).fetchByIsbn("9780000000019");

        assertEquals("S", found.getSubtitle());
        assertEquals(2, found.getAuthors().size());
        assertEquals("B", found.getAuthors().get(1));
        assertEquals("https://covers.openlibrary.org/b/id/1-M.jpg", found.getCoverUrl());
        assertEquals(0, found.getYear());
    }

    @Test
    @DisplayName("the request asks for exactly the ISBN given")
    void url() {
        assertTrue(new OpenLibraryProvider().buildUrl("9780143031055").endsWith("bibkeys=ISBN:9780143031055"));
    }
}
