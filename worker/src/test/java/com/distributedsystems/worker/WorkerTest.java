package com.distributedsystems.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.distributedsystems.worker.download.ImageDownloader;
import com.distributedsystems.worker.ocr.OcrEngine;
import com.distributedsystems.ocr.result.OcrFailure;
import com.distributedsystems.ocr.result.OcrResult;
import com.distributedsystems.ocr.result.OcrSuccess;

/**
 * Tests the Worker's own logic using stubs (fake downloader / fake OCR engine),
 * so no network access and no real Tesseract are involved.
 */
class WorkerTest {

    /** Success stub: returns a tiny blank image for any URL. */
    private static final ImageDownloader SUCCESS_DOWNLOADER = new ImageDownloader() {
        @Override
        public BufferedImage download(String url) {
            return new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        }
    };

    /** Mixed stub: fails for URLs containing "broken", succeeds otherwise. */
    private static final ImageDownloader FAILING_FOR_BROKEN_DOWNLOADER = new ImageDownloader() {
        @Override
        public BufferedImage download(String url) throws IOException {
            if (url.contains("broken")) {
                throw new IOException("HTTP 404");
            }
            return new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        }
    };

    /** Success stub: always "recognizes" the same text. */
    private static final OcrEngine SUCCESS_ENGINE = image -> "fake text";

    /** Failure stub: OCR always fails. */
    private static final OcrEngine FAILING_ENGINE = image -> {
        throw new IllegalStateException("OCR crashed");
    };

    @Test
    void successfulUrlReturnsRecognizedText() {
        Worker worker = new Worker(SUCCESS_DOWNLOADER, SUCCESS_ENGINE);

        OcrResult result = worker.processUrl("http://example.test/ok.png");

        OcrSuccess success = assertInstanceOf(OcrSuccess.class, result);
        assertEquals("http://example.test/ok.png", success.url());
        assertEquals("fake text", success.text());
    }

    @Test
    void downloadFailureBecomesOcrFailure() {
        Worker worker = new Worker(FAILING_FOR_BROKEN_DOWNLOADER, SUCCESS_ENGINE);

        OcrResult result = worker.processUrl("http://example.test/broken.png");

        OcrFailure failure = assertInstanceOf(OcrFailure.class, result);
        assertEquals("http://example.test/broken.png", failure.url());
        assertEquals("IOException: HTTP 404", failure.error());
    }

    @Test
    void ocrFailureBecomesOcrFailure() {
        Worker worker = new Worker(SUCCESS_DOWNLOADER, FAILING_ENGINE);

        OcrResult result = worker.processUrl("http://example.test/ok.png");

        OcrFailure failure = assertInstanceOf(OcrFailure.class, result);
        assertEquals("IllegalStateException: OCR crashed", failure.error());
    }

    @Test
    void batchContinuesAfterFailureAndKeepsOrder() {
        Worker worker = new Worker(FAILING_FOR_BROKEN_DOWNLOADER, SUCCESS_ENGINE);
        List<String> urls = List.of(
                "http://example.test/ok1.png",
                "http://example.test/broken.png",
                "http://example.test/ok2.png");

        List<OcrResult> results = worker.processUrls(urls);

        assertEquals(3, results.size());
        assertEquals(urls, results.stream().map(OcrResult::url).toList());
        assertInstanceOf(OcrSuccess.class, results.get(0));
        assertInstanceOf(OcrFailure.class, results.get(1));
        assertInstanceOf(OcrSuccess.class, results.get(2));
    }

    @Test
    void emptyBatchReturnsEmptyList() {
        Worker worker = new Worker(SUCCESS_DOWNLOADER, SUCCESS_ENGINE);

        assertTrue(worker.processUrls(List.of()).isEmpty());
    }
}
