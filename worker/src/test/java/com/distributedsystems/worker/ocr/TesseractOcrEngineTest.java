package com.distributedsystems.worker.ocr;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

class TesseractOcrEngineTest {

    @Test
    void readsTextFromRenderedImage() throws Exception {
        BufferedImage image = renderText("HELLO WORLD");

        String text = new TesseractOcrEngine().extractText(image);

        assertTrue(text.toUpperCase().contains("HELLO WORLD"), "OCR output was: '" + text + "'");
    }

    @Test
    void rejectsNullImage() {
        assertThrows(IllegalArgumentException.class, () -> new TesseractOcrEngine().extractText(null));
    }

    /** Draws black text on a white background, entirely in memory (no image download needed). */
    private static BufferedImage renderText(String text) {
        BufferedImage image = new BufferedImage(600, 150, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setColor(Color.BLACK);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 48));
            g.drawString(text, 30, 95);
        } finally {
            g.dispose();
        }
        return image;
    }
}
