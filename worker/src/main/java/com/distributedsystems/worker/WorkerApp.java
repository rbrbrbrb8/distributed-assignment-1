package com.distributedsystems.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.distributedsystems.aws.SQSService;
import com.distributedsystems.worker.download.ImageDownloader;
import com.distributedsystems.worker.ocr.TesseractOcrEngine;
import com.distributedsystems.ocr.result.OcrFailure;
import com.distributedsystems.ocr.result.OcrResult;

import software.amazon.awssdk.regions.Region;

/**
 * Entry point of the worker jar. Two modes:
 * <ul>
 *   <li>{@code java -jar worker.jar} — SQS mode (what runs on EC2): consume IMAGE_TASK messages forever.</li>
 *   <li>{@code java -jar worker.jar local <url-file>} — debug mode: OCR the URLs in a file and print the results.</li>
 * </ul>
 * Credentials are never configured here: the AWS SDK finds them itself (the IAM role on EC2,
 * {@code ~/.aws/credentials} on a developer machine). The region defaults to us-east-1 and can be
 * overridden with the {@code AWS_REGION} environment variable.
 */
public final class WorkerApp {

    /** Visibility timeout used if this worker is the one that creates the task queue. */
    private static final int TASK_QUEUE_VISIBILITY_SECONDS = 60;
    /** How long shutdown waits for the current task to finish (covers one 20 s long poll). */
    private static final long SHUTDOWN_GRACE_MS = 25_000;

    private WorkerApp() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            runSqs();
        } else if (args.length == 2 && args[0].equals("local")) {
            runLocal(Path.of(args[1]));
        } else {
            System.err.println("Usage: java -jar worker.jar               (SQS mode)");
            System.err.println("       java -jar worker.jar local <file>  (local debug mode)");
            System.exit(1);
        }
    }

    private static void runSqs() {
        Region region = Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1"));
        Worker worker = new Worker(new ImageDownloader(), new TesseractOcrEngine());

        try (SQSService sqs = new SQSService(region)) {
            String tasksQueueUrl = sqs.getOrCreateQueue(SQSService.MANAGER_TO_WORKERS_QUEUE, TASK_QUEUE_VISIBILITY_SECONDS);
            String resultsQueueUrl = sqs.getOrCreateQueue(SQSService.WORKERS_TO_MANAGER_QUEUE, TASK_QUEUE_VISIBILITY_SECONDS);

            try (WorkerLoop loop = new WorkerLoop(sqs, tasksQueueUrl, resultsQueueUrl, worker)) {
                // On SIGTERM (e.g. the instance is being terminated) let the current task finish cleanly.
                // If we are killed anyway, the undeleted task simply reappears for another worker.
                Thread mainThread = Thread.currentThread();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    loop.stop();
                    try {
                        mainThread.join(SHUTDOWN_GRACE_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, "shutdown"));

                loop.run();
            }
        }
    }

    private static void runLocal(Path urlFile) throws IOException {
        List<String> urls = Files.readAllLines(urlFile).stream()
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
}
