package com.shelfwise.core.model;

import com.shelfwise.core.exception.InvalidISBNException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for ISBN normalisation and checksum validation. */
class IsbnValidatorTest {

    @Test
    @DisplayName("accepts the two ISBNs actually scanned by the webcam prototype")
    void acceptsRealScannedIsbns() {
        // These came off real books via the barcode prototype, so they prove
        // the validator agrees with what a physical barcode carries - a
        // stronger check than any hand-written example.
        assertTrue(IsbnValidator.isValid("9788183071000"));
        assertTrue(IsbnValidator.isValid("9780143031055"));
    }

    @Test
    @DisplayName("accepts valid ISBN-13s and strips hyphens")
    void acceptsIsbn13() throws InvalidISBNException {
        assertEquals("9780143031055", IsbnValidator.validate("978-0-14-303105-5"));
        assertEquals("9780143031055", IsbnValidator.validate("978 0 14 303105 5"));
    }

    @Test
    @DisplayName("accepts valid ISBN-10s, including a trailing X")
    void acceptsIsbn10() throws InvalidISBNException {
        assertEquals("0306406152", IsbnValidator.validate("0-306-40615-2"));
        // 043942089X is a real ISBN-10 whose check digit is ten, written X.
        assertEquals("043942089X", IsbnValidator.validate("043942089X"));
        // Lower case x is what people actually type.
        assertEquals("043942089X", IsbnValidator.validate("043942089x"));
    }

    @Test
    @DisplayName("rejects a single mistyped digit, which is the whole point")
    void rejectsWrongCheckDigit() {
        // Last digit changed from 5 to 6: valid-looking, but the checksum
        // catches it. This is why ISBNs have a check digit at all.
        InvalidISBNException e = assertThrows(InvalidISBNException.class,
                new org.junit.jupiter.api.function.Executable() {
                    @Override
                    public void execute() throws Throwable {
                        IsbnValidator.validate("9780143031056");
                    }
                });
        assertEquals("check digit mismatch", e.getReason());
    }

    @Test
    @DisplayName("rejects transposed digits")
    void rejectsTransposition() {
        // 0306406152 is valid; swapping two adjacent digits must not be.
        assertFalse(IsbnValidator.isValid("0306406512"));
    }

    @Test
    @DisplayName("rejects wrong lengths with a message naming the length")
    void rejectsWrongLength() {
        InvalidISBNException e = assertThrows(InvalidISBNException.class,
                new org.junit.jupiter.api.function.Executable() {
                    @Override
                    public void execute() throws Throwable {
                        IsbnValidator.validate("12345");
                    }
                });
        assertTrue(e.getReason().contains("5"), e.getReason());
    }

    @Test
    @DisplayName("rejects blank and letter-bearing input")
    void rejectsRubbish() {
        assertFalse(IsbnValidator.isValid(null));
        assertFalse(IsbnValidator.isValid(""));
        assertFalse(IsbnValidator.isValid("   "));
        assertFalse(IsbnValidator.isValid("hello world!"));
        // X is only legal as the final character of an ISBN-10.
        assertFalse(IsbnValidator.isValid("03X6406152"));
        assertFalse(IsbnValidator.isValid("978014303105X"));
    }

    @Test
    @DisplayName("normalise leaves null alone rather than throwing")
    void normaliseHandlesNull() {
        // Books legitimately have no ISBN, so null must flow through quietly.
        assertEquals(null, IsbnValidator.normalise(null));
    }

    @Test
    @DisplayName("an ISBN-10 converts to the ISBN-13 of the same book")
    void isbn10ConvertsTo13() throws InvalidISBNException {
        // Values checked against the project's two known real ISBNs.
        assertEquals("9780143031055", IsbnValidator.toIsbn13("0143031058"));
        assertEquals("9788183071000", IsbnValidator.toIsbn13("81-8307-100-7"));
        // An X check digit on the ISBN-10 side still converts.
        assertEquals("9780141187761", IsbnValidator.toIsbn13("014118776X"));
    }

    @Test
    @DisplayName("an ISBN-13 passes through unchanged, hyphens removed")
    void isbn13PassesThrough() throws InvalidISBNException {
        assertEquals("9780143031055", IsbnValidator.toIsbn13("978-0-14-303105-5"));
    }

    @Test
    @DisplayName("an invalid ISBN cannot be converted")
    void invalidCannotConvert() {
        assertThrows(InvalidISBNException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() throws Throwable {
                IsbnValidator.toIsbn13("0143031059");
            }
        });
    }
}
