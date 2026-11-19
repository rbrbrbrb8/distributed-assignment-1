package com.distributedsystems.worker;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.distributedsystems.aws.MessageProtocol;
import com.distributedsystems.aws.MessageProtocol.MessageType;
import com.distributedsystems.aws.SQSService;
import com.distributedsystems.worker.ocr.result.OcrResult;

import software.amazon.awssdk.services.sqs.model.Message;

/**
 * The worker's SQS consumer. SQS is pull-only, so this loop keeps asking for work:
 * <ol>
 *   <li>Receive one IMAGE_TASK (long poll: waits up to 20 s, returns as soon as a message arrives).</li>
 *   <li>OCR the image via {@link Worker#processUrl(String)} (never throws).</li>
 *   <li>Send the result (OCR_DONE / OCR_ERROR) to the manager.</li>
 *   <li>Only then delete the task. If the worker dies before this, the message becomes visible again
 *       after the visibility timeout and another worker processes it.</li>
 * </ol>
 * While a task is being processed, a heartbeat keeps extending the message's visibility timeout, so a slow
 * (but alive) worker doesn't have its task handed to a second worker.
 *
 * <p>Threads: the calling thread runs the loop and OCR; one daemon thread runs the heartbeat.
 */
public class WorkerLoop implements AutoCloseable {

    /** How long (seconds) each heartbeat keeps the current message hidden from other workers. */
    static final int VISIBILITY_EXTENSION_SECONDS = 60;
    private static final long DEFAULT_HEARTBEAT_PERIOD_MS = 30_000;
    private static final long MAX_BACKOFF_MS = 30_000;
    /** IMAGE_TASK###clientId###taskIndex###url */
    private static final int IMAGE_TASK_FIELDS = 4;

    private final SQSService sqs;
    private final String tasksQueueUrl;
    private final String resultsQueueUrl;
    private final Worker worker;
    private final long heartbeatPeriodMs;
    private final ScheduledExecutorService heartbeatScheduler;

    private volatile boolean running = true;

    public WorkerLoop(SQSService sqs, String tasksQueueUrl, String resultsQueueUrl, Worker worker) {
        this(sqs, tasksQueueUrl, resultsQueueUrl, worker, DEFAULT_HEARTBEAT_PERIOD_MS);
    }

    /** Package-private: lets tests use a short heartbeat period. */
    WorkerLoop(SQSService sqs, String tasksQueueUrl, String resultsQueueUrl, Worker worker, long heartbeatPeriodMs) {
        this.sqs = sqs;
        this.tasksQueueUrl = tasksQueueUrl;
        this.resultsQueueUrl = resultsQueueUrl;
        this.worker = worker;
        this.heartbeatPeriodMs = heartbeatPeriodMs;
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "visibility-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Runs until {@link #stop()} is called. Errors talking to SQS are retried with exponential backoff. */
    public void run() {
        log("Worker loop started, polling " + tasksQueueUrl);
        long backoffMs = 1_000;
        while (running) {
            try {
                List<Message> messages = sqs.receiveMessages(tasksQueueUrl, 1);
                for (Message message : messages) {
                    handle(message);
                }
                backoffMs = 1_000; // a successful round trip resets the backoff
            } catch (Exception e) {
                // SQS / network trouble. The current message (if any) was not deleted,
                // so it will reappear after its visibility timeout - nothing is lost.
                log("Error in worker loop: " + e + " - retrying in " + backoffMs + " ms");
                sleep(backoffMs);
                backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
            }
        }
        log("Worker loop stopped");
    }

    /** Asks the loop to finish after the current task (used by the JVM shutdown hook). */
    public void stop() {
        running = false;
    }

    /**
     * Processes a single message. Package-private for tests.
     *
     * @throws RuntimeException if sending the result or deleting the task fails; the task is then
     *                          left in the queue so it can be retried.
     */
    void handle(Message message) {
        String[] parts;
        int taskIndex;
        try {
            parts = MessageProtocol.parse(message.body());
            if (parts.length != IMAGE_TASK_FIELDS || !MessageType.IMAGE_TASK.name().equals(parts[0])) {
                throw new IllegalArgumentException("not an IMAGE_TASK with " + IMAGE_TASK_FIELDS + " fields");
            }
            taskIndex = MessageProtocol.parseTaskIndex(parts);
        } catch (IllegalArgumentException e) {
            // Poison message: retrying can never succeed, so remove it instead of looping on it forever.
            log("Dropping malformed message (" + e.getMessage() + "): " + message.body());
            sqs.deleteMessage(tasksQueueUrl, message);
            return;
        }
        String clientId = parts[1];
        String url = parts[3];

        ScheduledFuture<?> heartbeat = startHeartbeat(message);
        try {
            OcrResult result = worker.processUrl(url);
            sqs.sendMessage(resultsQueueUrl, result.toMessage(clientId, taskIndex));
            sqs.deleteMessage(tasksQueueUrl, message); // only after the result is safely sent
            log("Task " + clientId + "#" + taskIndex + " -> " + result.getClass().getSimpleName());
        } finally {
            heartbeat.cancel(false);
        }
    }

    private ScheduledFuture<?> startHeartbeat(Message message) {
        return heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                sqs.changeMessageVisibility(tasksQueueUrl, message, VISIBILITY_EXTENSION_SECONDS);
            } catch (Exception e) {
                // Must not throw: an exception would silently cancel all future heartbeats.
                log("Heartbeat failed: " + e);
            }
        }, heartbeatPeriodMs, heartbeatPeriodMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        stop();
        heartbeatScheduler.shutdownNow();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void log(String message) {
        System.out.println(Instant.now() + " [worker] " + message);
    }
}
