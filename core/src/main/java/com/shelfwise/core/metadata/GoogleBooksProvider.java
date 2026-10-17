package com.shelfwise.core.metadata;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shelfwise.core.exception.MetadataFetchException;
import com.shelfwise.core.service.BookMetadata;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Looks books up on Google Books, the second lookup source.
 * <p>
 * Asks {@code /books/v1/volumes?q=isbn:<isbn>}. The reply lists matching
 * volumes; the first one's {@code volumeInfo} holds title, subtitle,
 * authors (plain strings), publisher, publishedDate, pageCount, a language
 * code and image links. No match means {@code totalItems} is 0 and there is
 * no {@code items} list.
 * <p>
 * <strong>The API key is optional but matters.</strong> Without one,
 * requests share a global anonymous quota, and on 15 and 17 Oct every
 * keyless request was refused with HTTP 429. With a key each user has their
 * own quota. The key comes from the user's settings, never from the
 * repository, and never appears in an error message: {@link HttpJsonProvider}
 * leaves the address out of every message.
 */
public class GoogleBooksProvider extends HttpJsonProvider {

    /** Where requests go. */
    static final String BASE_URL = "https://www.googleapis.com/books/v1/volumes?q=isbn:";

    /** The user's own API key, or null to ask anonymously. */
    private final String apiKey;

    /** Asks anonymously, sharing Google's global quota. */
    public GoogleBooksProvider() {
        this(null);
    }

    /**
     * @param apiKey the user's Google Books API key; null or blank asks
     *               anonymously
     */
    public GoogleBooksProvider(String apiKey) {
        this.apiKey = (apiKey == null || apiKey.trim().isEmpty()) ? null : apiKey.trim();
    }

    /** @return true if requests carry the user's own key */
    public boolean hasKey() {
        return apiKey != null;
    }

    /** @return "Google Books" */
    @Override
    public String getProviderName() {
        return "Google Books";
    }

    /** {@inheritDoc} */
    @Override
    protected String buildUrl(String isbn) {
        String url = BASE_URL + isbn;
        if (apiKey != null) {
            // Encoded, so a key containing unusual characters cannot break the address.
            url += "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        }
        return url;
    }

    /**
     * {@inheritDoc}
     *
     * @throws MetadataFetchException if no volume matches, or the first one
     *                                has no title
     */
    @Override
    protected BookMetadata parse(String isbn, String json) throws MetadataFetchException {
        JsonObject root = parseObject(isbn, json);
        JsonElement items = root.get("items");
        if (items == null || !items.isJsonArray() || items.getAsJsonArray().size() == 0
                || !items.getAsJsonArray().get(0).isJsonObject()) {
            throw new MetadataFetchException(getProviderName(), isbn, "no record found");
        }
        JsonElement infoElement = items.getAsJsonArray().get(0).getAsJsonObject().get("volumeInfo");
        if (infoElement == null || !infoElement.isJsonObject()) {
            throw new MetadataFetchException(getProviderName(), isbn, "the record has no details");
        }
        JsonObject info = infoElement.getAsJsonObject();

        String title = text(info, "title");
        if (title == null) {
            throw new MetadataFetchException(getProviderName(), isbn, "the record has no title");
        }
        BookMetadata found = new BookMetadata(title);
        found.setSubtitle(text(info, "subtitle"));

        // Here authors are plain strings, unlike Open Library's objects.
        JsonElement authors = info.get("authors");
        if (authors != null && authors.isJsonArray()) {
            JsonArray list = authors.getAsJsonArray();
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).isJsonPrimitive()) {
                    found.addAuthor(list.get(i).getAsString());
                }
            }
        }
        found.setPublisher(text(info, "publisher"));
        found.setYear(yearFrom(text(info, "publishedDate")));
        found.setPages(Math.max(0, number(info, "pageCount")));
        found.setLanguage(languageName(text(info, "language")));

        JsonElement images = info.get("imageLinks");
        if (images != null && images.isJsonObject()) {
            String thumbnail = text(images.getAsJsonObject(), "thumbnail");
            // Google still hands out plain http links; covers are fetched over https.
            if (thumbnail != null && thumbnail.startsWith("http://")) {
                thumbnail = "https://" + thumbnail.substring("http://".length());
            }
            found.setCoverUrl(thumbnail);
        }
        return found;
    }

    /**
     * Turns a language code into its English name ("en" gives "English"),
     * because the language is shown to the user and searched as text.
     *
     * @param code an ISO language code, or null
     * @return the language's name, the code itself if Java does not know
     *         it, or null
     */
    static String languageName(String code) {
        if (code == null) {
            return null;
        }
        String name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH);
        return name.isEmpty() ? code : name;
    }
}
