package com.distributedsystems.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.distributedsystems.aws.MessageProtocol;
import com.distributedsystems.aws.SQSService;
import com.distributedsystems.worker.download.ImageDownloader;
import com.distributedsystems.worker.ocr.OcrEngine;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * Tests WorkerLoop's per-message logic and idle shutdown with a stub SQSService (no network access).
 */
class WorkerLoopTest {

    private static final String TASKS = "tasks-queue";
    private static final String RESULTS = "results-queue";

    /** Stub SQS: records every call as a string, optionally fails on send, and serves scripted polls. */
    private static class RecordingSqs extends SQSService {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final Queue<List<Message>> scriptedPolls = new ArrayDeque<>();
        Runnable onPoll = () -> {};
        boolean failOnSend = false;

        RecordingSqs() {
            super(Region.US_EAST_1); // builds a client object but never uses it
        }

        @Override
        public List<Message> receiveMessages(String queueUrl, int maxMessages) {
            onPoll.run();
            List<Message> next = scriptedPolls.poll();
            return next != null ? next : List.of();
        }

        @Override
        public void sendMessage(String queueUrl, String messageBody) {
            if (failOnSend) {
                throw new RuntimeException("simulated SQS outage");
            }
            calls.add("send:" + queueUrl + ":" + messageBody);
        }

        @Override
        public void deleteMessage(String queueUrl, Message message) {
            calls.add("delete:" + queueUrl + ":" + message.receiptHandle());
        }

        @Override
        public void changeMessageVisibility(String queueUrl, Message message, int newTimeoutSeconds) {
            calls.add("visibility:" + queueUrl + ":" + newTimeoutSeconds);
        }
    }

    private static final ImageDownloader STUB_DOWNLOADER = new ImageDownloader() {
        @Override
        public BufferedImage download(String url) {
            return new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        }
    };

    private static Worker workerReturning(String text) {
        return new Worker(STUB_DOWNLOADER, image -> text);
    }

    private static Message message(String body) {
        return Message.builder().body(body).receiptHandle("rh-1").build();
    }

    @Test
    void validTaskSendsResultThenDeletesTask() {
        RecordingSqs sqs = new RecordingSqs();
        try (WorkerLoop loop = new WorkerLoop(sqs, TASKS, RESULTS, workerReturning("hi"))) {

            loop.handle(message(MessageProtocol.createImageTaskMsg("client-1", 4, "http://x/a.png")));
        }

        String expectedResult = MessageProtocol.createOcrDoneMsg("client-1", 4, "http://x/a.png", "hi");
        assertEquals(List.of(
                "send:" + RESULTS + ":" + expectedResult,
                "delete:" + TASKS + ":rh-1"), sqs.calls);
    }

    @Test
    void failedOcrIsReportedAsOcrErrorAndTaskIsDeleted() {
        RecordingSqs sqs = new RecordingSqs();
        OcrEngine failingEngine = image -> {
            throw new IllegalStateException("OCR crashed");
        };
        try (WorkerLoop loop = new WorkerLoop(sqs, TASKS, RESULTS, new Worker(STUB_DOWNLOADER, failingEngine))) {

            loop.handle(message(MessageProtocol.createImageTaskMsg("client-1", 2, "http://x/a.png")));
        }

        String expectedResult = MessageProtocol.createOcrErrorMsg(
                "client-1", 2, "http://x/a.png", "IllegalStateException: OCR crashed");
        assertEquals(List.of(
                "send:" + RESULTS + ":" + expectedResult,
                "delete:" + TASKS + ":rh-1"), sqs.calls);
    }

    @Test
    void malformedMessageIsDeletedWithoutSendingResult() {
        RecordingSqs sqs = new RecordingSqs();
        try (WorkerLoop loop = new WorkerLoop(sqs, TASKS, RESULTS, workerReturning("hi"))) {

            loop.handle(message("garbage"));
            loop.handle(message("IMAGE_TASK###client-1###not-a-number###http://x/a.png"));
            loop.handle(message(MessageProtocol.createTerminateMsg())); // wrong message type for a worker
        }

        assertEquals(List.of(
                "delete:" + TASKS + ":rh-1",
                "delete:" + TASKS + ":rh-1",
                "delete:" + TASKS + ":rh-1"), sqs.calls);
    }

    @Test
    void failedSendLeavesTaskInQueue() {
        RecordingSqs sqs = new RecordingSqs();
        sqs.failOnSend = true;
        try (WorkerLoop loop = new WorkerLoop(sqs, TASKS, RESULTS, workerReturning("hi"))) {

            assertThrows(RuntimeException.class,
                    () -> loop.handle(message(MessageProtocol.createImageTaskMsg("client-1", 0, "http://x/a.png"))));
        }

        // No delete: the message reappears after its visibility timeout and another worker retries it.
        assertTrue(sqs.calls.stream().noneMatch(call -> call.startsWith("delete:")));
    }

    @Test
    void slowTaskKeepsExtendingVisibilityAndHeartbeatStopsAfterwards() throws InterruptedException {
        RecordingSqs sqs = new RecordingSqs();
        OcrEngine slowEngine = image -> {
            Thread.sleep(300); // "OCR" takes 300 ms; heartbeat fires every 50 ms
            return "slow text";
        };
        try (WorkerLoop loop = new WorkerLoop(sqs, TASKS, RESULTS, new Worker(STUB_DOWNLOADER, slowEngine), 50)) {

            loop.handle(message(MessageProtocol.createImageTaskMsg("client-1", 0, "http://x/a.png")));

            long heartbeats = sqs.calls.stream()
                    .filter(call -> call.equals("visibility:" + TASKS + ":" + WorkerLoop.VISIBILITY_EXTENSION_SECONDS))
                    .count();
            assertTrue(heartbeats >= 2, "expected several heartbeats, got " + heartbeats + ": " + sqs.calls);
            assertTrue(sqs.calls.contains("delete:" + TASKS + ":rh-1"));

            // After the task is finished, no more heartbeats arrive (loop still open, scheduler still alive).
            int callsAfterTask = sqs.calls.size();
            Thread.sleep(200);
            assertEquals(callsAfterTask, sqs.calls.size(), "heartbeat kept running after task: " + sqs.calls);
        }
    }

    @Test
    void idleLoopShutsDownAfterTimeoutAndResetsTimerWhenWorkArrives() {
        RecordingSqs sqs = new RecordingSqs();
        AtomicLong nowMs = new AtomicLong(1_000);
        AtomicBoolean shutdownTriggered = new AtomicBoolean(false);

        // Poll 1: empty at t=400s (idle 399s < 600s) -> keep running
        // Poll 2: 1 task arrives at t=500s -> processed, resets idle timer to 500s
        // Poll 3: empty at t=900s (idle 400s < 600s) -> keep running (would have timed out if timer hadn't reset!)
        // Poll 4: empty at t=1100s (idle 600s >= 600s) -> trigger self-shutdown and exit loop
        sqs.scriptedPolls.add(List.of());
        sqs.scriptedPolls.add(List.of(message(MessageProtocol.createImageTaskMsg("client-1", 0, "http://x/a.png"))));
        sqs.scriptedPolls.add(List.of());
        sqs.scriptedPolls.add(List.of());

        long[] pollTimes = { 400_000, 500_000, 900_000, 1_100_000 };
        int[] pollIdx = { 0 };
        sqs.onPoll = () -> {
            int i = pollIdx[0]++;
            if (i < pollTimes.length) {
                nowMs.set(pollTimes[i]);
            }
        };

        try (WorkerLoop loop = new WorkerLoop(sqs, TASKS, RESULTS, workerReturning("ok"),
                30_000, 600_000, () -> shutdownTriggered.set(true), nowMs::get)) {
            loop.run();
        }

        assertTrue(shutdownTriggered.get(), "expected idle shutdown callback to fire after 600s idle");
        assertEquals(4, pollIdx[0], "expected exactly 4 polls before loop exited");
        assertFalse(sqs.calls.isEmpty(), "expected the middle task to be processed");
    }

    @Test
    void parseIdleShutdownMinutesDefaultsToZeroWhenUnsetOrInvalid() {
        assertEquals(0, WorkerApp.parseIdleShutdownMinutes(null));
        assertEquals(0, WorkerApp.parseIdleShutdownMinutes(""));
        assertEquals(0, WorkerApp.parseIdleShutdownMinutes("   "));
        assertEquals(0, WorkerApp.parseIdleShutdownMinutes("-5"));
        assertEquals(0, WorkerApp.parseIdleShutdownMinutes("abc"));
        assertEquals(10, WorkerApp.parseIdleShutdownMinutes("10"));
        assertEquals(10, WorkerApp.parseIdleShutdownMinutes(" 10 "));
    }
}