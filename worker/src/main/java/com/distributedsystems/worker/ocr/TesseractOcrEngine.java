package com.distributedsystems.worker.ocr;

import java.awt.image.BufferedImage;
import java.io.File;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import net.sourceforge.tess4j.util.LoadLibs;

/**
 * OCR engine backed by Tesseract (via tess4j).
 *
 * <p>Language data: the tess4j jar bundles {@code tessdata/eng.traineddata}. By default it is extracted
 * from the jar to a temp folder and used from there, so no manual download is needed. Setting the
 * {@code TESSDATA_PREFIX} environment variable overrides that folder.
 *
 * <p>Not thread-safe: use one instance per thread.
 */
public class TesseractOcrEngine implements OcrEngine {

    private static final String DEFAULT_LANGUAGE = "eng";

    private final Tesseract tesseract;

    /** Uses {@code TESSDATA_PREFIX} if set, otherwise the language data bundled in the tess4j jar. */
    public TesseractOcrEngine() {
        this(resolveTessdataPath(), DEFAULT_LANGUAGE);
    }

    public TesseractOcrEngine(String tessdataPath, String language) {
        this.tesseract = new Tesseract();
        this.tesseract.setDatapath(tessdataPath);
        this.tesseract.setLanguage(language);
    }

    @Override
    public String extractText(BufferedImage image) throws TesseractException {
        if (image == null) {
            throw new IllegalArgumentException("image is null");
        }
        return tesseract.doOCR(image).trim();
    }

    private static String resolveTessdataPath() {
        String fromEnv = System.getenv("TESSDATA_PREFIX");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        File extracted = LoadLibs.extractTessResources("tessdata");
        if (extracted == null) {
            throw new IllegalStateException("Could not extract bundled tessdata from the tess4j jar");
        }
        return extracted.getAbsolutePath();
    }
}
