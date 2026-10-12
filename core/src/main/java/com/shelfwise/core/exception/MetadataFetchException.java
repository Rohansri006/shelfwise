package com.shelfwise.core.exception;

/**
 * Thrown when a metadata provider cannot supply details for an ISBN.
 * <p>
 * This is expected rather than exceptional in the ordinary sense: ShelfWise
 * tries Open Library, then Google Books, then gives up and lets the user type
 * the details in. Each failure in that chain throws this exception, and the
 * caller treats it as "try the next provider" rather than "stop".
 * <p>
 * The provider name is carried so the chain can be reported honestly -
 * "Open Library had no record, Google Books could not be reached" is a far
 * better message than "lookup failed".
 * <p>
 * This is also the one exception that commonly wraps a cause, usually an
 * {@code IOException} from the network. Passing the cause through keeps the
 * original stack trace for debugging while still presenting a typed,
 * meaningful failure to the caller.
 */
public class MetadataFetchException extends ShelfWiseException {

    /** Which provider failed, e.g. "Open Library". */
    private final String providerName;

    /** The ISBN that was being looked up. */
    private final String isbn;

    /**
     * Creates an exception for a provider that answered, but unhelpfully -
     * typically "no record found".
     *
     * @param providerName which provider failed
     * @param isbn         the ISBN being looked up
     * @param reason       why it failed
     */
    public MetadataFetchException(String providerName, String isbn, String reason) {
        super(providerName + " could not supply details for ISBN " + isbn + ": " + reason + ".");
        this.providerName = providerName;
        this.isbn = isbn;
    }

    /**
     * Creates an exception for a provider that could not be reached at all.
     *
     * @param providerName which provider failed
     * @param isbn         the ISBN being looked up
     * @param cause        the underlying failure, usually an IOException
     */
    public MetadataFetchException(String providerName, String isbn, Throwable cause) {
        super(providerName + " could not be reached while looking up ISBN "
                + isbn + ": " + cause.getMessage(), cause);
        this.providerName = providerName;
        this.isbn = isbn;
    }

    /** @return which provider failed */
    public String getProviderName() {
        return providerName;
    }

    /** @return the ISBN that was being looked up */
    public String getIsbn() {
        return isbn;
    }
}
