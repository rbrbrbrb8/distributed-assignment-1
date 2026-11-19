package com.distributedsystems.worker;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import com.distributedsystems.worker.download.ImageDownloader;
import com.distributedsystems.worker.ocr.OcrEngine;
import com.distributedsystems.worker.ocr.result.OcrFailure;
import com.distributedsystems.worker.ocr.result.OcrResult;
import com.distributedsystems.worker.ocr.result.OcrSuccess;

/**
 * Downloads images and runs OCR on them. A failure on one URL never stops the others:
 * it is reported as an {@link OcrFailure} and processing continues.
 */
public class Worker {

    private final ImageDownloader downloader;
    private final OcrEngine ocrEngine;

    public Worker(ImageDownloader downloader, OcrEngine ocrEngine) {
        this.downloader = downloader;
        this.ocrEngine = ocrEngine;
    }

    /** Processes a single URL. Never throws; errors become an {@link OcrFailure}. */
    public OcrResult processUrl(String url) {
        try {
            BufferedImage image = downloader.download(url);
            String text = ocrEngine.extractText(image);
            return new OcrSuccess(url, text);
        } catch (Exception e) {
            return new OcrFailure(url, describe(e));
        }
    }

    /** Processes the URLs one after another; results are returned in the same order as the input. */
    public List<OcrResult> processUrls(List<String> urls) {
        List<OcrResult> results = new ArrayList<>(urls.size());
        for (String url : urls) {
            results.add(processUrl(url));
        }
        return results;
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + message;
    }
}
