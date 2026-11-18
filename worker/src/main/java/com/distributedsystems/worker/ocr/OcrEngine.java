package com.distributedsystems.worker.ocr;

import java.awt.image.BufferedImage;

/**
 * Extracts text from an image. Implementations are not required to be thread-safe.
 */
public interface OcrEngine {

    /**
     * @param image the image to read
     * @return the recognized text (possibly empty if the image has no text)
     * @throws Exception if recognition fails
     */
    String extractText(BufferedImage image) throws Exception;
}
