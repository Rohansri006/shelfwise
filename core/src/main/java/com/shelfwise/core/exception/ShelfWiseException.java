package com.shelfwise.core.exception;

/**
 * Base class for every error ShelfWise raises on purpose.
 * <p>
 * Three design decisions are worth being able to explain:
 * <ol>
 *   <li><strong>It is abstract.</strong> There is no such thing as a generic
 *       "ShelfWise problem" worth throwing; every real failure has a specific
 *       type. Making the base abstract means the compiler enforces that.</li>
 *   <li><strong>It extends {@link Exception}, not {@code RuntimeException},</strong>
 *       so these are <em>checked</em> exceptions. The compiler then forces the
 *       user interface to decide what to do about each one, which is the
 *       point: every exception below corresponds to a question the user must
 *       answer (add a copy or a separate book? keep my version or theirs?).</li>
 *   <li><strong>Subclasses carry structured data, not just a sentence.</strong>
 *       A screen needs the offending ISBN or the blocking book count to build
 *       its own wording; parsing that back out of a message string would be
 *       fragile.</li>
 * </ol>
 * This class also gives the project a second inheritance hierarchy alongside
 * {@code CollectionItem}, which is useful because exceptions are a case where
 * inheritance is genuinely the right tool rather than a demonstration of it.
 */
public abstract class ShelfWiseException extends Exception {

    /**
     * Creates an exception with a human-readable message.
     *
     * @param message what went wrong, as a complete sentence
     */
    protected ShelfWiseException(String message) {
        super(message);
    }

    /**
     * Creates an exception caused by another, lower-level failure.
     * <p>
     * Passing the cause through preserves the original stack trace, so a
     * network failure behind a metadata lookup is not lost.
     *
     * @param message what went wrong, as a complete sentence
     * @param cause   the underlying exception
     */
    protected ShelfWiseException(String message, Throwable cause) {
        super(message, cause);
    }
}
