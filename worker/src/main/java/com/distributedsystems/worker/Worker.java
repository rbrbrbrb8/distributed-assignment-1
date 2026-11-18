package com.distributedsystems.worker;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.distributedsystems.worker.download.ImageDownloader;
import com.distributedsystems.worker.ocr.OcrEngine;
import com.distributedsystems.worker.ocr.TesseractOcrEngine;
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

    /**
     * Local entry point: {@code java -jar worker.jar <url-file>}, where the file has one image URL per line.
     * Prints one result per URL. (Will be replaced by the SQS loop later.)
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: java -jar worker.jar <url-file>");
            System.exit(1);
        }
        List<String> urls = Files.readAllLines(Path.of(args[0])).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();

        Worker worker = new Worker(new ImageDownloader(), new TesseractOcrEngine());
        long start = System.nanoTime();
        List<OcrResult> results = worker.processUrls(urls);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        results.forEach(System.out::println);
        long failures = results.stream().filter(r -> r instanceof OcrFailure).count();
        System.out.printf("Processed %d URLs (%d failed) in %d ms%n", results.size(), failures, elapsedMs);
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + message;
    }
}
