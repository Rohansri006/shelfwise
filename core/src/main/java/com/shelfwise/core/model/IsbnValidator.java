package com.shelfwise.core.model;

import com.shelfwise.core.exception.InvalidISBNException;

/**
 * Checks and normalises ISBNs.
 * <p>
 * An ISBN is not just a string of digits. Its last character is a check digit
 * calculated from the ones before it, so most single-digit typos and
 * transpositions can be detected rather than silently stored. That matters
 * here because an ISBN is what metadata lookups and duplicate detection both
 * key on: one wrong digit means the wrong book, or no book at all.
 * <p>
 * Two lengths are in use. ISBN-10 was the original; ISBN-13 replaced it in
 * 2007 and is what barcodes carry. Both are accepted, because books printed
 * before 2007 only have the shorter form.
 * <p>
 * This class is never instantiated - it is a holder for static methods, which
 * is why the constructor is private.
 */
public final class IsbnValidator {

    /** Not instantiable: this class only holds static helpers. */
    private IsbnValidator() {
    }

    /**
     * Removes the spaces and hyphens people naturally type.
     * <p>
     * "978-0-14-303105-5" and "9780143031055" are the same ISBN, so they must
     * compare equal, and only the stripped form is ever stored.
     *
     * @param raw the ISBN as entered, possibly null
     * @return the digits only, uppercased for a trailing 'x'; null stays null
     */
    public static String normalise(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '-' && c != ' ') {
                // An ISBN-10 check digit of ten is written 'X'; accept either case.
                digits.append(Character.toUpperCase(c));
            }
        }
        return digits.toString();
    }

    /**
     * Checks an ISBN and returns it in normalised form.
     *
     * @param raw the ISBN as entered
     * @return the normalised ISBN, guaranteed valid
     * @throws InvalidISBNException if it is blank, the wrong length, contains
     *                              illegal characters, or fails its checksum
     */
    public static String validate(String raw) throws InvalidISBNException {
        String isbn = normalise(raw);

        if (isbn == null || isbn.isEmpty()) {
            throw new InvalidISBNException(String.valueOf(raw), "it is blank");
        }
        if (isbn.length() == 10) {
            validateIsbn10(isbn);
        } else if (isbn.length() == 13) {
            validateIsbn13(isbn);
        } else {
            throw new InvalidISBNException(isbn,
                    "an ISBN has 10 or 13 digits, but this has " + isbn.length());
        }
        return isbn;
    }

    /**
     * Whether a string is a valid ISBN, without throwing.
     * <p>
     * Convenient where invalid input is expected rather than exceptional -
     * deciding whether a scanned barcode is a book at all, for instance.
     *
     * @param raw the ISBN as entered
     * @return true if it would pass {@link #validate}
     */
    public static boolean isValid(String raw) {
        try {
            validate(raw);
            return true;
        } catch (InvalidISBNException e) {
            return false;
        }
    }

    /**
     * Validates a 10-character ISBN.
     * <p>
     * Each digit is multiplied by a descending weight - the first by 10, the
     * second by 9, down to the last by 1 - and the total must divide by 11.
     * Eleven is the modulus because it is prime, which is what makes the
     * scheme catch transposed digits as well as single wrong ones. Since a
     * check digit might need to represent ten, which is not a single digit,
     * the character 'X' is used for it.
     *
     * @param isbn a normalised 10-character ISBN
     * @throws InvalidISBNException if a character is illegal or the sum fails
     */
    private static void validateIsbn10(String isbn) throws InvalidISBNException {
        int total = 0;

        for (int i = 0; i < 10; i++) {
            char c = isbn.charAt(i);
            int value;

            if (c >= '0' && c <= '9') {
                value = c - '0';
            } else if (c == 'X' && i == 9) {
                // Only the final position may be X, where it means ten.
                value = 10;
            } else {
                throw new InvalidISBNException(isbn,
                        "'" + c + "' is not allowed at position " + (i + 1));
            }
            total += value * (10 - i);
        }

        if (total % 11 != 0) {
            throw new InvalidISBNException(isbn, "check digit mismatch");
        }
    }

    /**
     * Validates a 13-character ISBN.
     * <p>
     * Digits are multiplied alternately by 1 and 3 and the total must divide
     * by 10. ISBN-13 is also a valid EAN-13 barcode, which is exactly what
     * the webcam scanner reads off the back of a book.
     *
     * @param isbn a normalised 13-character ISBN
     * @throws InvalidISBNException if a character is illegal or the sum fails
     */
    private static void validateIsbn13(String isbn) throws InvalidISBNException {
        int total = 0;

        for (int i = 0; i < 13; i++) {
            char c = isbn.charAt(i);
            if (c < '0' || c > '9') {
                throw new InvalidISBNException(isbn,
                        "'" + c + "' is not a digit, and a 13-digit ISBN allows only digits");
            }
            int value = c - '0';
            // Even positions weigh 1, odd positions weigh 3.
            if (i % 2 == 0) {
                total += value;
            } else {
                total += value * 3;
            }
        }

        if (total % 10 != 0) {
            throw new InvalidISBNException(isbn, "check digit mismatch");
        }
    }
}
