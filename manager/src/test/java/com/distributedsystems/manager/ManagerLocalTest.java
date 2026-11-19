package com.distributedsystems.manager;

import com.distributedsystems.aws.MessageProtocol;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import com.distributedsystems.aws.S3Service;
import com.distributedsystems.aws.SQSService;
import com.distributedsystems.manager.CloudInterfaces.ComputeClient;
import com.distributedsystems.manager.CloudInterfaces.StorageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ManagerLocalTest {

    static class InMemoryStorage implements StorageClient {
        private final Map<String, String> files = new ConcurrentHashMap<>();

        void putLines(String key, List<String> lines) {
            files.put(key, String.join("\n", lines));
        }

        String getRawContent(String key) {
            return files.get(key);
        }

        @Override
        public List<String> downloadLines(String s3Key) {
            String content = files.get(s3Key);
            if (content == null) {
                throw new IllegalArgumentException("Missing key in mock S3: " + s3Key);
            }
            return Arrays.asList(content.split("\n"));
        }

        @Override
        public void uploadText(String s3Key, String content) {
            files.put(s3Key, content);
        }
    }

    static class InMemoryCompute implements ComputeClient {
        private final AtomicInteger activeWorkers = new AtomicInteger(0);
        private final AtomicInteger totalLaunched = new AtomicInteger(0);
        private final AtomicBoolean workersTerminated = new AtomicBoolean(false);
        private final AtomicBoolean selfTerminated = new AtomicBoolean(false);
        private final List<Integer> activeClientsHistory = Collections.synchronizedList(new ArrayList<>());

        @Override
        public int getActiveWorkerCount() {
            return activeWorkers.get();
        }

        @Override
        public void launchWorkers(int count) {
            activeWorkers.addAndGet(count);
            totalLaunched.addAndGet(count);
        }

        @Override
        public void terminateAllWorkers() {
            activeWorkers.set(0);
            workersTerminated.set(true);
        }

        @Override
        public void terminateSelf() {
            selfTerminated.set(true);
        }

        @Override
        public void updateActiveClients(int activeClientCount) {
            activeClientsHistory.add(activeClientCount);
        }
    }

    static class InMemorySQSService extends SQSService {
        private final Map<String, BlockingQueue<Message>> queues = new ConcurrentHashMap<>();

        private BlockingQueue<Message> queue(String url) {
            return queues.computeIfAbsent(url, k -> new LinkedBlockingQueue<>());
        }

        @Override
        public void sendMessage(String queueUrl, String messageBody) {
            queue(queueUrl).offer(Message.builder()
                    .messageId(UUID.randomUUID().toString())
                    .receiptHandle(UUID.randomUUID().toString())
                    .body(messageBody)
                    .build());
        }

        @Override
        public void sendMessagesBatch(String queueUrl, List<String> messages) {
            for (String msg : messages) {
                sendMessage(queueUrl, msg);
            }
        }

        @Override
        public List<Message> receiveMessages(String queueUrl, int maxMessages) {
            BlockingQueue<Message> q = queue(queueUrl);
            List<Message> batch = new ArrayList<>();
            try {
                Message first = q.poll(30, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    q.drainTo(batch, maxMessages - 1);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return batch;
        }

        @Override
        public void deleteMessage(String queueUrl, Message message) {
        }
    }

    @Test
    void testWorkerScalingFormulaAndCap() {
        InMemoryCompute compute = new InMemoryCompute();
        WorkerScaler scaler = new WorkerScaler(compute, 8);

        assertEquals(3, scaler.scaleForNewJob(12, 5));
        assertEquals(3, compute.getActiveWorkerCount());

        assertEquals(0, scaler.scaleForNewJob(10, 5));
        assertEquals(3, compute.getActiveWorkerCount());

        assertEquals(5, scaler.scaleForNewJob(50, 2));
        assertEquals(8, compute.getActiveWorkerCount());
    }

    @Test
    void testConcurrentScalingThreadSafety() throws InterruptedException {
        InMemoryCompute compute = new InMemoryCompute();
        int maxWorkers = 5;
        WorkerScaler scaler = new WorkerScaler(compute, maxWorkers);

        int threads = 10;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    scaler.scaleForNewJob(20, 4);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(doneGate.await(5, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(maxWorkers, compute.getActiveWorkerCount());
        assertEquals(maxWorkers, compute.totalLaunched.get());
    }

    @Test
    void testClientJobSingleLineBase64OrderingDuplicatesAndErrors() {
        List<String> urls = List.of("http://img/a.png", "http://img/b.png", "http://img/a.png");
        ClientJob job = new ClientJob("client-1", "resp-q", 2, urls);

        assertFalse(job.recordResult(2, "http://img/a.png", "text-2", false));
        assertFalse(job.recordResult(2, "http://img/a.png", "text-2", false));
        assertEquals(2, job.getRemainingTasks());

        assertFalse(job.recordResult(0, "http://img/a.png", "404 Not Found", true));
        assertEquals(1, job.getRemainingTasks());

        assertTrue(job.recordResult(1, "http://img/b.png", "line1\nline2", false));
        assertEquals(0, job.getRemainingTasks());

        String summary = job.buildSummaryContent();
        String[] lines = summary.split("\n", -1);
        assertEquals(3, lines.length);

        String[] line0 = lines[0].split(" \\| ERROR: ", 2);
        assertEquals("http://img/a.png", line0[0]);
        assertEquals("404 Not Found", MessageProtocol.decodeOcrText(line0[1]));

        String[] line1 = lines[1].split(" \\| OCR: ", 2);
        assertEquals("http://img/b.png", line1[0]);
        assertEquals("line1\nline2", MessageProtocol.decodeOcrText(line1[1]));

        String[] line2 = lines[2].split(" \\| OCR: ", 2);
        assertEquals("http://img/a.png", line2[0]);
        assertEquals("text-2", MessageProtocol.decodeOcrText(line2[1]));
    }

    @Test
    @Timeout(10)
    void testFullEndToEndManagerFlowWithInMemoryQueues() throws Exception {
        InMemorySQSService sqs = new InMemorySQSService();
        InMemoryStorage storage = new InMemoryStorage();
        InMemoryCompute compute = new InMemoryCompute();

        String appToManager = "app-to-manager";
        String managerToWorkers = "manager-to-workers";
        String workersToManager = "workers-to-manager";

        String inputKeyA = S3Service.inputKey("client-a");
        String inputKeyB = S3Service.inputKey("client-b");
        String expectedSummaryKeyA = S3Service.summaryKey("client-a");
        String expectedSummaryKeyB = S3Service.summaryKey("client-b");

        List<String> clientAUrls = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            clientAUrls.add("http://example.com/a-" + i + ".png");
        }
        storage.putLines(inputKeyA, clientAUrls);

        List<String> clientBUrls = List.of(
                "http://example.com/dup.png",
                "http://example.com/unique.png",
                "http://example.com/dup.png"
        );
        storage.putLines(inputKeyB, clientBUrls);

        ManagerEngine engine = new ManagerEngine(
                sqs, storage, compute, 4,
                appToManager, managerToWorkers, workersToManager
        );

        Thread managerThread = new Thread(engine::start, "manager-main");
        managerThread.start();

        sqs.sendMessage(appToManager, MessageProtocol.createNewTaskMsg("client-a", "resp-a", inputKeyA, 4));
        sqs.sendMessage(appToManager, MessageProtocol.createNewTaskMsg("client-b", "resp-b", inputKeyB, 2));

        int processedTasks = 0;
        while (processedTasks < 15) {
            List<Message> tasks = sqs.receiveMessages(managerToWorkers, 10);
            for (Message taskMsg : tasks) {
                String[] parts = MessageProtocol.parse(taskMsg.body());
                assertEquals(MessageProtocol.MessageType.IMAGE_TASK.name(), parts[0]);

                String clientId = parts[1];
                int taskIndex = MessageProtocol.parseTaskIndex(parts);
                String url = parts[3];

                if ("client-a".equals(clientId) && taskIndex == 5) {
                    sqs.sendMessage(workersToManager,
                            MessageProtocol.createOcrErrorMsg(clientId, taskIndex, url, "Corrupted image header"));
                } else {
                    String doneMsg = MessageProtocol.createOcrDoneMsg(
                            clientId, taskIndex, url, "Line1-" + clientId + "-" + taskIndex + "\nLine2");
                    sqs.sendMessage(workersToManager, doneMsg);

                    if (taskIndex == 0) {
                        sqs.sendMessage(workersToManager, doneMsg);
                    }
                }
                processedTasks++;
            }
        }

        Message respA = waitForSingleMessage(sqs, "resp-a");
        Message respB = waitForSingleMessage(sqs, "resp-b");

        assertEquals(MessageProtocol.createJobDoneMsg(expectedSummaryKeyA), respA.body());
        assertEquals(MessageProtocol.createJobDoneMsg(expectedSummaryKeyB), respB.body());

        String summaryA = storage.getRawContent(expectedSummaryKeyA);
        assertNotNull(summaryA);
        String[] entriesA = summaryA.split("\n", -1);
        assertEquals(12, entriesA.length);
        assertEquals("http://example.com/a-5.png | ERROR: " + Base64.getEncoder().encodeToString(("Corrupted image header").getBytes(StandardCharsets.UTF_8)), entriesA[5]);
        assertEquals("http://example.com/a-11.png | OCR: " + Base64.getEncoder().encodeToString(("Line1-client-a-11\nLine2").getBytes(StandardCharsets.UTF_8)), entriesA[11]);

        String summaryB = storage.getRawContent(expectedSummaryKeyB);
        assertEquals(String.join("\n",
                "http://example.com/dup.png | OCR: " + Base64.getEncoder().encodeToString(("Line1-client-b-0\nLine2").getBytes(StandardCharsets.UTF_8)),
                "http://example.com/unique.png | OCR: " + Base64.getEncoder().encodeToString(("Line1-client-b-1\nLine2").getBytes(StandardCharsets.UTF_8)),
                "http://example.com/dup.png | OCR: " + Base64.getEncoder().encodeToString(("Line1-client-b-2\nLine2").getBytes(StandardCharsets.UTF_8))
        ), summaryB);

        assertTrue(compute.totalLaunched.get() >= 3 && compute.totalLaunched.get() <= 4);
        assertTrue(compute.activeClientsHistory.contains(0));
        assertEquals(0, compute.activeClientsHistory.get(compute.activeClientsHistory.size() - 1));

        sqs.sendMessage(appToManager, MessageProtocol.createTerminateMsg());
        managerThread.join(5000);
        assertFalse(managerThread.isAlive());
        assertTrue(compute.workersTerminated.get());
        assertTrue(compute.selfTerminated.get());
        assertEquals(0, compute.getActiveWorkerCount());
    }

    private Message waitForSingleMessage(InMemorySQSService sqs, String queueUrl) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            List<Message> msgs = sqs.receiveMessages(queueUrl, 1);
            if (!msgs.isEmpty()) {
                return msgs.get(0);
            }
        }
        fail("Timed out waiting for message on queue: " + queueUrl);
        return null;
    }
}