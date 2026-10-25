package com.shelfwise.desktop.ui;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.EAN13Writer;
import com.shelfwise.core.exception.ScannerException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link WebcamScanner} that need no camera: the barcodes are
 * drawn by ZXing's own writer, the same library that reads them, so the
 * decoding half of the scanner can be checked on any machine.
 */
class WebcamScannerTest {

    /** @return a printed-style EAN-13 barcode for the given 13 digits */
    private static BufferedImage barcode(String isbn) {
        BitMatrix bars = new EAN13Writer().encode(isbn, BarcodeFormat.EAN_13, 400, 150);
        return MatrixToImageWriter.toBufferedImage(bars);
    }

    @Test
    @DisplayName("an ISBN-13 barcode is decoded to its digits")
    void decodesIsbn13() {
        WebcamScanner scanner = new WebcamScanner(0);
        // A demo dataset ISBN (Glimpses of World History), checksum-valid.
        assertEquals("9780143031055", scanner.decode(barcode("9780143031055")));
    }

    @Test
    @DisplayName("the same reader decodes one barcode after another")
    void decodesSeveralInARow() {
        // The reader is reused across frames (decodeWithState), so a second
        // barcode must not be confused by the state left by the first.
        WebcamScanner scanner = new WebcamScanner(0);
        assertEquals("9780143031055", scanner.decode(barcode("9780143031055")));
        assertEquals("9788183071000", scanner.decode(barcode("9788183071000")));
    }

    @Test
    @DisplayName("a picture with no barcode decodes to null, not an exception")
    void blankPictureGivesNull() {
        BufferedImage blank = new BufferedImage(400, 150, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = blank.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 400, 150);
        g.dispose();
        assertNull(new WebcamScanner(0).decode(blank));
    }

    @Test
    @DisplayName("stop before start is safe, twice, and reading then returns null")
    void stopBeforeStartIsSafe() throws ScannerException {
        // The interface promises stop() is safe more than once and makes
        // readNextBarcode() return null; neither may touch a camera here.
        WebcamScanner scanner = new WebcamScanner(0);
        scanner.stop();
        scanner.stop();
        assertNull(scanner.readNextBarcode());
    }
}
