package com.shelfwise.core.metadata;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shelfwise.core.exception.MetadataFetchException;
import com.shelfwise.core.service.BookMetadata;

/**
 * Looks books up on Open Library, the first lookup source tried: free, and
 * needs no key.
 * <p>
 * Asks {@code /api/books?bibkeys=ISBN:<isbn>&format=json&jscmd=data}. The
 * reply is an object keyed by what was asked for:
 * <pre>
 * {"ISBN:9780143031055": {"title": ..., "authors": [{"name": ...}],
 *   "publishers": [{"name": ...}], "publish_date": "March 30, 2005",
 *   "number_of_pages": 1192, "cover": {"medium": ...}}}
 * </pre>
 * An unknown ISBN gets an empty object, {@code {}}, not an error status.
 * Open Library's reply has no language, which is why the lookup may ask the
 * next source to fill that gap.
 */
public class OpenLibraryProvider extends HttpJsonProvider {

    /** Where requests go. Package-private so a test could point it elsewhere. */
    static final String BASE_URL = "https://openlibrary.org/api/books?format=json&jscmd=data&bibkeys=ISBN:";

    /** @return "Open Library" */
    @Override
    public String getProviderName() {
        return "Open Library";
    }

    /** {@inheritDoc} */
    @Override
    protected String buildUrl(String isbn) {
        return BASE_URL + isbn;
    }

    /**
     * {@inheritDoc}
     *
     * @throws MetadataFetchException if the reply has no entry for this
     *                                ISBN, or the entry has no title
     */
    @Override
    protected BookMetadata parse(String isbn, String json) throws MetadataFetchException {
        JsonObject root = parseObject(isbn, json);
        JsonElement entryElement = root.get("ISBN:" + isbn);
        if (entryElement == null || !entryElement.isJsonObject()) {
            throw new MetadataFetchException(getProviderName(), isbn, "no record found");
        }
        JsonObject entry = entryElement.getAsJsonObject();

        String title = text(entry, "title");
        if (title == null) {
            throw new MetadataFetchException(getProviderName(), isbn, "the record has no title");
        }
        BookMetadata found = new BookMetadata(title);
        found.setSubtitle(text(entry, "subtitle"));

        // Authors and publishers are lists of objects, each with a "name".
        JsonArray authors = array(entry, "authors");
        for (int i = 0; i < authors.size(); i++) {
            if (authors.get(i).isJsonObject()) {
                found.addAuthor(text(authors.get(i).getAsJsonObject(), "name"));
            }
        }
        JsonArray publishers = array(entry, "publishers");
        if (publishers.size() > 0 && publishers.get(0).isJsonObject()) {
            found.setPublisher(text(publishers.get(0).getAsJsonObject(), "name"));
        }

        found.setYear(yearFrom(text(entry, "publish_date")));
        found.setPages(Math.max(0, number(entry, "number_of_pages")));

        JsonElement cover = entry.get("cover");
        if (cover != null && cover.isJsonObject()) {
            found.setCoverUrl(text(cover.getAsJsonObject(), "medium"));
        }
        return found;
    }

    /**
     * @param object a JSON object
     * @param key    a field name
     * @return the field as an array, or an empty array if it is missing or
     *         not an array, so callers can loop without checking
     */
    private static JsonArray array(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonArray()) {
            return new JsonArray();
        }
        return value.getAsJsonArray();
    }
}
