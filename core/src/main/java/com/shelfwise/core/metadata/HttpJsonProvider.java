package com.shelfwise.core.metadata;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.shelfwise.core.exception.MetadataFetchException;
import com.shelfwise.core.service.BookMetadata;
import com.shelfwise.core.service.MetadataProvider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * A lookup source reached over HTTP that answers in JSON.
 * <p>
 * <strong>Template method.</strong> {@link #fetchByIsbn} fixes the steps
 * every such source shares - build the address, download the reply, read the
 * book out of it - and leaves two of them abstract. Each subclass says only
 * what is different about its service: where to ask ({@link #buildUrl}) and
 * how its answer is laid out ({@link #parse}). The network code, timeouts and
 * error handling are written once, here.
 * <p>
 * Uses {@link HttpURLConnection}, not {@code java.net.http.HttpClient},
 * because {@code core} is reused by the Android app and the older class
 * works there unchanged.
 * <p>
 * <strong>Blocks.</strong> A lookup waits on the network for up to ten
 * seconds, so it must never run on the JavaFX thread.
 */
public abstract class HttpJsonProvider implements MetadataProvider {

    /** How long to wait to connect, and then to wait for the reply. */
    static final int TIMEOUT_MS = 5000;

    /** Open Library asks callers to identify themselves. */
    static final String USER_AGENT = "ShelfWise/1.0 (student project; personal book collection)";

    /**
     * Looks a book up: build the address, download, parse.
     *
     * @param isbn the ISBN, already validated
     * @return what the source knows; never null
     * @throws MetadataFetchException if the source cannot be reached, does
     *                                not know the ISBN, or replies with
     *                                something unreadable
     */
    @Override
    public BookMetadata fetchByIsbn(String isbn) throws MetadataFetchException {
        String json = download(buildUrl(isbn), isbn);
        return parse(isbn, json);
    }

    /**
     * @param isbn the ISBN to look up
     * @return the full address to request
     */
    protected abstract String buildUrl(String isbn);

    /**
     * Reads a book out of the source's reply.
     *
     * @param isbn the ISBN that was looked up, for error messages
     * @param json the reply body
     * @return the details found
     * @throws MetadataFetchException if the reply holds no usable book
     */
    protected abstract BookMetadata parse(String isbn, String json) throws MetadataFetchException;

    /**
     * Fetches a reply body.
     * <p>
     * Protected and overridable so tests can hand back a saved reply instead
     * of going to the network.
     * <p>
     * Error messages never include the address, because it can contain an
     * API key.
     *
     * @param address the address to request
     * @param isbn    the ISBN being looked up, for error messages
     * @return the reply body
     * @throws MetadataFetchException on a timeout, a network failure, a rate
     *                                limit (HTTP 429) or any other non-200
     *                                reply
     */
    protected String download(String address, String isbn) throws MetadataFetchException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(address).toURL().openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");

            int status = connection.getResponseCode();
            if (status == 429) {
                throw new MetadataFetchException(getProviderName(), isbn, "rate limit reached");
            }
            if (status != 200) {
                throw new MetadataFetchException(getProviderName(), isbn, "the server answered HTTP " + status);
            }

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                while (line != null) {
                    body.append(line).append('\n');
                    line = reader.readLine();
                }
            }
            return body.toString();
        } catch (IOException e) {
            // The reason names only the kind of failure; the IOException's own
            // message can contain the address, and with it an API key.
            MetadataFetchException failure = new MetadataFetchException(getProviderName(), isbn,
                    "could not be reached (" + e.getClass().getSimpleName() + ")");
            failure.initCause(e);
            throw failure;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Parses a reply that must be a JSON object.
     *
     * @param isbn the ISBN, for error messages
     * @param json the reply body
     * @return the object
     * @throws MetadataFetchException if it is not valid JSON or not an object
     */
    protected JsonObject parseObject(String isbn, String json) throws MetadataFetchException {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                throw new MetadataFetchException(getProviderName(), isbn, "the reply was not a JSON object");
            }
            return root.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new MetadataFetchException(getProviderName(), isbn, "the reply was not readable JSON");
        }
    }

    /**
     * @param object a JSON object
     * @param key    a field name
     * @return the field as text, or null if it is missing, null or not a
     *         plain value
     */
    protected static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        String text = value.getAsString().trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * @param object a JSON object
     * @param key    a field name
     * @return the field as a whole number, or 0 (unknown) if it is missing
     *         or not a number
     */
    protected static int number(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return 0;
        }
        return value.getAsInt();
    }

    /**
     * Finds a year in a date written any way a source writes it: "2005",
     * "2005-03-30" and "March 30, 2005" all give 2005. Takes the first run
     * of exactly four digits.
     *
     * @param date the date text, or null
     * @return the year, or 0 if none is found
     */
    protected static int yearFrom(String date) {
        if (date == null) {
            return 0;
        }
        int runStart = -1;
        for (int i = 0; i <= date.length(); i++) {
            boolean digit = i < date.length() && Character.isDigit(date.charAt(i));
            if (digit && runStart < 0) {
                runStart = i;
            } else if (!digit && runStart >= 0) {
                if (i - runStart == 4) {
                    return Integer.parseInt(date.substring(runStart, i));
                }
                runStart = -1;
            }
        }
        return 0;
    }
}
