package com.shelfwise.core.metadata;

import com.google.gson.JsonObject;
import com.shelfwise.core.exception.MetadataFetchException;
import com.shelfwise.core.service.BookMetadata;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link HttpJsonProvider}. The real HTTP code is exercised
 * against the JDK's own small web server, started on a free local port, so
 * nothing here touches the internet.
 */
class HttpJsonProviderTest {

    private HttpServer server;

    /** What the local server replies with, set per test. */
    private int replyStatus;
    private String replyBody;

    /** Header the provider sent, captured by the server. */
    private String userAgentSeen;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                userAgentSeen = exchange.getRequestHeaders().getFirst("User-Agent");
                byte[] bytes = replyBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(replyStatus, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** A minimal source that asks the local server and reads {"title": ...}. */
    private HttpJsonProvider localSource() {
        final String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/books/";
        return new HttpJsonProvider() {
            @Override
            public String getProviderName() {
                return "Local test source";
            }

            @Override
            protected String buildUrl(String isbn) {
                return base + isbn + "?key=SECRET-KEY";
            }

            @Override
            protected BookMetadata parse(String isbn, String json) throws MetadataFetchException {
                JsonObject root = parseObject(isbn, json);
                return new BookMetadata(text(root, "title"));
            }
        };
    }

    @Test
    @DisplayName("a 200 reply is downloaded and parsed, and the source identifies itself")
    void successfulFetch() throws Exception {
        replyStatus = 200;
        replyBody = "{\"title\": \"Glimpses of World History\"}";

        BookMetadata found = localSource().fetchByIsbn("9780143031055");

        assertEquals("Glimpses of World History", found.getTitle());
        assertTrue(userAgentSeen.startsWith("ShelfWise"), userAgentSeen);
    }

    @Test
    @DisplayName("HTTP 429 is reported as a rate limit, naming the source")
    void rateLimit() {
        replyStatus = 429;
        replyBody = "{}";
        final HttpJsonProvider source = localSource();
        MetadataFetchException e = assertThrows(MetadataFetchException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                source.fetchByIsbn("9780143031055");
            }
        });
        assertTrue(e.getMessage().contains("rate limit"), e.getMessage());
        assertEquals("Local test source", e.getProviderName());
    }

    @Test
    @DisplayName("any other non-200 reply is reported with its status code")
    void otherStatus() {
        replyStatus = 503;
        replyBody = "down";
        final HttpJsonProvider source = localSource();
        MetadataFetchException e = assertThrows(MetadataFetchException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                source.fetchByIsbn("9780143031055");
            }
        });
        assertTrue(e.getMessage().contains("503"), e.getMessage());
    }

    @Test
    @DisplayName("an unreachable source fails cleanly without revealing the address or its key")
    void unreachable() {
        // Stop the server first, so the port refuses connections.
        server.stop(0);
        final HttpJsonProvider source = localSource();
        MetadataFetchException e = assertThrows(MetadataFetchException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                source.fetchByIsbn("9780143031055");
            }
        });
        assertTrue(e.getMessage().contains("could not be reached"), e.getMessage());
        assertFalse(e.getMessage().contains("SECRET-KEY"), e.getMessage());
        // Restart so @AfterEach has something to stop.
        startServerQuietly();
    }

    @Test
    @DisplayName("a reply that is not JSON is reported, not thrown as a crash")
    void unreadableReply() {
        replyStatus = 200;
        replyBody = "<html>not json</html>";
        final HttpJsonProvider source = localSource();
        MetadataFetchException e = assertThrows(MetadataFetchException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                source.fetchByIsbn("9780143031055");
            }
        });
        assertTrue(e.getMessage().contains("not readable JSON") || e.getMessage().contains("not a JSON object"),
                e.getMessage());
    }

    @Test
    @DisplayName("a year is found in the date formats the sources use")
    void yearFormats() {
        assertEquals(2005, HttpJsonProvider.yearFrom("March 30, 2005"));
        assertEquals(2005, HttpJsonProvider.yearFrom("2005-03-30"));
        assertEquals(1997, HttpJsonProvider.yearFrom("1997"));
        assertEquals(2008, HttpJsonProvider.yearFrom("Sep 01, 2008"));
        assertEquals(0, HttpJsonProvider.yearFrom("circa the 90s"));
        assertEquals(0, HttpJsonProvider.yearFrom(""));
        assertEquals(0, HttpJsonProvider.yearFrom(null));
        // Five digits is not a year.
        assertEquals(0, HttpJsonProvider.yearFrom("12345"));
    }

    @Test
    @DisplayName("missing or empty fields read as null text and 0 numbers")
    void fieldHelpers() {
        JsonObject o = new JsonObject();
        o.addProperty("title", "  ");
        o.addProperty("pages", "many");
        assertNull(HttpJsonProvider.text(o, "title"));
        assertNull(HttpJsonProvider.text(o, "absent"));
        assertEquals(0, HttpJsonProvider.number(o, "pages"));
        assertEquals(0, HttpJsonProvider.number(o, "absent"));
    }

    /** Recreates the server after a test stopped it on purpose. */
    private void startServerQuietly() {
        try {
            startServer();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
