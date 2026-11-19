package com.distributedsystems.manager;

import com.distributedsystems.aws.MessageProtocol;
import com.distributedsystems.aws.S3Service;
import com.distributedsystems.aws.SQSService;
import com.distributedsystems.manager.CloudInterfaces.ComputeClient;
import com.distributedsystems.manager.CloudInterfaces.StorageClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ManagerEngine {
    private static final int SQS_BATCH_LIMIT = 10;
    private static final long POLL_ERROR_BACKOFF_MS = 1000;

    private final SQSService sqs;
    private final StorageClient storage;
    private final ComputeClient compute;
    private final WorkerScaler scaler;

    private final String appToManagerUrl;
    private final String managerToWorkersUrl;
    private final String workersToManagerUrl;

    private final ConcurrentHashMap<String, ClientJob> activeJobs = new ConcurrentHashMap<>();
    private final ExecutorService jobSetupPool = Executors.newCachedThreadPool();
    private final AtomicInteger pendingJobSetups = new AtomicInteger(0);
    private final AtomicBoolean terminating = new AtomicBoolean(false);

    public ManagerEngine(SQSService sqs,
                         StorageClient storage,
                         ComputeClient compute,
                         int maxWorkers,
                         String appToManagerUrl,
                         String managerToWorkersUrl,
                         String workersToManagerUrl) {
        this.sqs = sqs;
        this.storage = storage;
        this.compute = compute;
        this.scaler = new WorkerScaler(compute, maxWorkers);
        this.appToManagerUrl = appToManagerUrl;
        this.managerToWorkersUrl = managerToWorkersUrl;
        this.workersToManagerUrl = workersToManagerUrl;
    }

    public void start() {
        Thread clientListener = new Thread(this::pollClientRequests, "client-listener");
        Thread workerListener = new Thread(this::pollWorkerResults, "worker-listener");

        clientListener.start();
        workerListener.start();

        try {
            clientListener.join();
            workerListener.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            shutdownSequence();
        }
    }

    private void pollClientRequests() {
        while (!terminating.get() && !Thread.currentThread().isInterrupted()) {
            List<Message> messages;
            try {
                messages = sqs.receiveMessages(appToManagerUrl, 5);
            } catch (Exception e) {
                if (terminating.get() || Thread.currentThread().isInterrupted()) {
                    break;
                }
                System.err.println("Transient error polling client requests: " + e.getMessage());
                sleepQuietly(POLL_ERROR_BACKOFF_MS);
                continue;
            }

            for (Message msg : messages) {
                try {
                    String[] parts = MessageProtocol.parse(msg.body());
                    String type = parts[0];

                    if (MessageProtocol.MessageType.TERMINATE.name().equals(type)) {
                        terminating.set(true);
                        sqs.deleteMessage(appToManagerUrl, msg);
                        break;
                    }

                    if (MessageProtocol.MessageType.NEW_TASK.name().equals(type) && !terminating.get()) {
                        String clientId = parts[1];
                        String responseQueueUrl = parts[2];
                        String s3InputKey = parts[3];
                        int n = MessageProtocol.parseNewTaskRatio(parts);

                        sqs.deleteMessage(appToManagerUrl, msg);
                        pendingJobSetups.incrementAndGet();
                        jobSetupPool.submit(() -> {
                            try {
                                handleNewTask(clientId, responseQueueUrl, s3InputKey, n);
                            } finally {
                                pendingJobSetups.decrementAndGet();
                            }
                        });
                    }
                } catch (Exception e) {
                    System.err.println("Failed processing client message: " + e.getMessage());
                }
            }
        }
    }

    void handleNewTask(String clientId, String responseQueueUrl, String s3InputKey, int n) {
        List<String> rawLines = storage.downloadLines(s3InputKey);
        List<String> validUrls = rawLines.stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();

        ClientJob job = new ClientJob(clientId, responseQueueUrl, n, validUrls);

        if (job.getTotalTasks() == 0) {
            completeClientJob(job);
            return;
        }

        activeJobs.put(clientId, job);

        int launched = scaler.scaleForNewJob(job.getTotalTasks(), n);
        System.out.printf("[Client %s] Images: %d, n: %d -> Launched %d new workers%n",
                clientId, job.getTotalTasks(), n, launched);

        List<String> batch = new ArrayList<>(SQS_BATCH_LIMIT);
        for (int i = 0; i < validUrls.size(); i++) {
            batch.add(MessageProtocol.createImageTaskMsg(clientId, i, validUrls.get(i)));
            if (batch.size() == SQS_BATCH_LIMIT) {
                sqs.sendMessagesBatch(managerToWorkersUrl, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            sqs.sendMessagesBatch(managerToWorkersUrl, batch);
        }
    }

    private void pollWorkerResults() {
        while ((!terminating.get() || pendingJobSetups.get() > 0 || !activeJobs.isEmpty())
                && !Thread.currentThread().isInterrupted()) {
            List<Message> messages;
            try {
                messages = sqs.receiveMessages(workersToManagerUrl, 10);
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                System.err.println("Transient error polling worker results: " + e.getMessage());
                sleepQuietly(POLL_ERROR_BACKOFF_MS);
                continue;
            }

            for (Message msg : messages) {
                try {
                    String[] parts = MessageProtocol.parse(msg.body());
                    String type = parts[0];
                    String clientId = parts[1];
                    int taskIndex = MessageProtocol.parseTaskIndex(parts);
                    String imageUrl = parts[3];

                    boolean isError = MessageProtocol.MessageType.OCR_ERROR.name().equals(type);
                    String payload = isError ? parts[4] : MessageProtocol.decodeOcrText(parts[4]);

                    handleWorkerResult(clientId, taskIndex, imageUrl, payload, isError);
                    sqs.deleteMessage(workersToManagerUrl, msg);
                } catch (Exception e) {
                    System.err.println("Failed processing worker result: " + e.getMessage());
                }
            }
        }
    }

    void handleWorkerResult(String clientId, int taskIndex, String imageUrl, String payload, boolean isError) {
        ClientJob job = activeJobs.get(clientId);
        if (job == null) {
            return;
        }
        boolean isLastTask = job.recordResult(taskIndex, imageUrl, payload, isError);
        if (isLastTask) {
            activeJobs.remove(clientId);
            completeClientJob(job);
        }
    }

    private void completeClientJob(ClientJob job) {
        String s3OutputKey = S3Service.summaryKey(job.getClientId());
        storage.uploadText(s3OutputKey, job.buildSummaryContent());
        sqs.sendMessage(job.getResponseQueueUrl(), MessageProtocol.createJobDoneMsg(s3OutputKey));
        System.out.printf("[Client %s] Completed all %d tasks. Uploaded %s%n",
                job.getClientId(), job.getTotalTasks(), s3OutputKey);
    }

    private void shutdownSequence() {
        jobSetupPool.shutdown();
        try {
            jobSetupPool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        compute.terminateAllWorkers();
        compute.terminateSelf();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}