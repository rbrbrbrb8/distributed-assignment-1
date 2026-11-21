package com.distributedsystems.localapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.distributedsystems.aws.MessageProtocol;
import com.distributedsystems.aws.S3Service;
import com.distributedsystems.aws.SQSService;
import com.distributedsystems.localapp.manager.ManagerHandle;
import com.distributedsystems.localapp.manager.ManagerLocator;
import com.distributedsystems.localapp.summary.Base64SummaryParser;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.model.Message;

/** The run flow of {@link LocalApp} with in-memory SQS, S3 and locator. No AWS calls. */
@Timeout(10)
class LocalAppTest {

    private static final String INBOUND = "url:" + SQSService.APP_TO_MANAGER_QUEUE;

    /** In-memory SQS. A "manager" can be plugged in to answer NEW_TASK messages. */
    private static class FakeSqs extends SQSService {
        final Map<String, BlockingQueue<String>> queues = new ConcurrentHashMap<>();
        final List<String> sentToInbound = new CopyOnWriteArrayList<>();
        final List<String> deletedQueues = new CopyOnWriteArrayList<>();
        /** Number of NEW_TASKs the fake manager ignores before it answers (simulates a manager that died). */
        int newTasksToIgnore = 0;
        /** Whether the fake manager answers at all. */
        boolean managerAnswers = true;
        Runnable onTerminate = () -> { };

        @Override
        public String getOrCreateQueue(String queueName, int visibilityTimeoutSeconds) {
            String url = "url:" + queueName;
            queues.computeIfAbsent(url, k -> new LinkedBlockingQueue<>());
            return url;
        }

        @Override
        public void sendMessage(String queueUrl, String body) {
            if (!queueUrl.equals(INBOUND)) {
                queues.get(queueUrl).add(body);
                return;
            }
            sentToInbound.add(body);
            String[] parts = MessageProtocol.parse(body);
            if (parts[0].equals("TERMINATE")) {
                onTerminate.run();
            } else if (parts[0].equals("NEW_TASK") && managerAnswers) {
                if (newTasksToIgnore > 0) {
                    newTasksToIgnore--;
                    return;
                }
                String clientId = parts[1];
                // the manager writes the summary and answers on the client's own queue
                sendMessage(parts[2], MessageProtocol.createJobDoneMsg(S3Service.summaryKey(clientId)));
            }
        }

        @Override
        public List<Message> receiveMessages(String queueUrl, int maxMessages) {
            try {
                String body = queues.get(queueUrl).poll(10, TimeUnit.MILLISECONDS);
                return body == null ? List.of()
                        : List.of(Message.builder().body(body).receiptHandle(UUID.randomUUID().toString()).build());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        }

        @Override
        public void deleteQueue(String queueUrl) {
            deletedQueues.add(queueUrl);
            queues.remove(queueUrl);
        }
    }

    /** In-memory S3. Every summary download returns {@link #summary}. */
    private static class FakeS3 extends S3Service {
        final Map<String, String> objects = new ConcurrentHashMap<>();
        final List<String> deletedPrefixes = new CopyOnWriteArrayList<>();
        String summary;
        boolean failDownload = false;

        FakeS3() {
            super(Region.US_EAST_1, "test-bucket"); // builds a client object but never uses it
        }

        @Override
        public void uploadFile(String key, Path file) {
            try {
                objects.put(key, Files.readString(file));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public String downloadString(String key) {
            if (failDownload) {
                throw new RuntimeException("S3 is down");
            }
            return summary;
        }

        @Override
        public int deletePrefix(String prefix) {
            deletedPrefixes.add(prefix);
            return 1;
        }
    }

    /** Hands out managers i-m1, i-m2, ...; the ones listed in {@link #dead} are not running. */
    private static class FakeLocator implements ManagerLocator {
        final AtomicInteger ensureCalls = new AtomicInteger();
        final Set<String> dead = ConcurrentHashMap.newKeySet();

        @Override
        public ManagerHandle ensureAvailableManager(int managerClientsN) {
            return new ManagerHandle("i-m" + ensureCalls.incrementAndGet());
        }

        @Override
        public boolean isAlive(ManagerHandle manager) {
            return !dead.contains(manager.instanceId());
        }
    }

    @TempDir
    Path dir;

    private final FakeSqs sqs = new FakeSqs();
    private final FakeS3 s3 = new FakeS3();
    private final FakeLocator locator = new FakeLocator();

    private LocalApp app(Duration timeout, Duration liveness) {
        return new LocalApp(sqs, s3, locator, new Base64SummaryParser(), timeout, liveness);
    }

    private LocalAppArgs args(boolean terminate) throws IOException {
        Path input = Files.writeString(dir.resolve("input.txt"), "http://a.com/1.png\nhttp://a.com/2.png\n");
        return new LocalAppArgs(input, dir.resolve("out.html"), 3, 5, terminate);
    }

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void happyPathWritesHtmlAndCleansUp() throws IOException {
        s3.summary = "http://a.com/1.png | OCR: " + b64("HELLO") + "\nhttp://a.com/2.png | ERROR: " + b64("HTTP 404");

        app(Duration.ofSeconds(5), Duration.ofSeconds(60)).run(args(false));

        String html = Files.readString(dir.resolve("out.html"));
        assertTrue(html.contains("HELLO"));
        assertTrue(html.contains("HTTP 404"));

        // one NEW_TASK: own response queue named after the clientId, the uploaded input key, and n
        assertEquals(1, sqs.sentToInbound.size());
        String[] newTask = MessageProtocol.parse(sqs.sentToInbound.get(0));
        String clientId = newTask[1];
        assertEquals("url:ocr-client-response-" + clientId, newTask[2]);
        assertEquals(S3Service.inputKey(clientId), newTask[3]);
        assertEquals("5", newTask[4]);
        assertEquals("http://a.com/1.png\nhttp://a.com/2.png\n", s3.objects.get(S3Service.inputKey(clientId)));

        // cleanup: response queue and job folder gone, no TERMINATE
        assertEquals(List.of("url:ocr-client-response-" + clientId), sqs.deletedQueues);
        assertEquals(List.of(S3Service.jobPrefix(clientId)), s3.deletedPrefixes);
        assertEquals(1, locator.ensureCalls.get());
    }

    @Test
    void terminateIsSentLastAfterTheHtmlIsWritten() throws IOException {
        s3.summary = "http://a.com/1.png | OCR: " + b64("x");
        Path output = dir.resolve("out.html");
        boolean[] htmlExistedAtTerminate = {false};
        sqs.onTerminate = () -> htmlExistedAtTerminate[0] = Files.exists(output);

        app(Duration.ofSeconds(5), Duration.ofSeconds(60)).run(args(true));

        assertEquals(2, sqs.sentToInbound.size());
        assertTrue(sqs.sentToInbound.get(1).startsWith("TERMINATE"));
        assertTrue(htmlExistedAtTerminate[0]);
    }

    @Test
    void goneManagerIsReplacedAndTheSameTaskIsResent() throws IOException {
        s3.summary = "http://a.com/1.png | OCR: " + b64("x");
        sqs.newTasksToIgnore = 1; // the first manager dies without answering
        locator.dead.add("i-m1"); // ...and the liveness check sees it gone

        app(Duration.ofSeconds(5), Duration.ZERO).run(args(false));

        assertEquals(2, locator.ensureCalls.get());
        assertEquals(2, sqs.sentToInbound.size());
        assertEquals(sqs.sentToInbound.get(0), sqs.sentToInbound.get(1)); // identical NEW_TASK
        assertTrue(Files.exists(dir.resolve("out.html")));
    }

    @Test
    void aLiveManagerIsNotReplaced() throws IOException {
        s3.summary = "http://a.com/1.png | OCR: " + b64("x");

        app(Duration.ofSeconds(5), Duration.ZERO).run(args(false)); // liveness checked on every poll

        assertEquals(1, locator.ensureCalls.get());
        assertEquals(1, sqs.sentToInbound.size());
    }

    @Test
    void timeoutFailsClearlyAndStillCleansUpAndTerminates() throws IOException {
        sqs.managerAnswers = false;

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> app(Duration.ofMillis(200), Duration.ofSeconds(60)).run(args(true)));

        assertTrue(e.getMessage().contains("No result"));
        assertEquals(1, sqs.deletedQueues.size());
        assertEquals(1, s3.deletedPrefixes.size());
        assertTrue(sqs.sentToInbound.get(sqs.sentToInbound.size() - 1).startsWith("TERMINATE"));
        assertFalse(Files.exists(dir.resolve("out.html")));
    }

    @Test
    void failureAfterJobDoneStillCleansUp() {
        s3.failDownload = true;

        assertThrows(RuntimeException.class, () -> app(Duration.ofSeconds(5), Duration.ofSeconds(60)).run(args(false)));

        assertEquals(1, sqs.deletedQueues.size());
        assertEquals(1, s3.deletedPrefixes.size());
    }
}
