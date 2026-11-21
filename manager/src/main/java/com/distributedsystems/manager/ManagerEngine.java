package com.distributedsystems.manager;

import com.distributedsystems.aws.MessageProtocol;
import com.distributedsystems.aws.S3Service;
import com.distributedsystems.aws.SQSService;
import com.distributedsystems.manager.CloudInterfaces.ComputeClient;
import com.distributedsystems.manager.CloudInterfaces.StorageClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ManagerEngine {
    private static final int SQS_BATCH_LIMIT = 10;
    private static final long POLL_ERROR_BACKOFF_MS = 1000;
    /**
     * How long a result for an unknown client stays hidden after this manager hands it back. Short, so the manager
     * that owns it gets it soon (with the queue's 60 s it slowed a busy manager ~4x, e2e test T07); not 0, so an idle
     * manager doesn't spin on stale results nobody owns.
     */
    static final int UNKNOWN_RESULT_RETRY_SECONDS = 5;

    private final SQSService sqs;
    private final StorageClient storage;
    private final ComputeClient compute;
    private final WorkerScaler scaler;

    private final String appToManagerUrl;
    private final String managerToWorkersUrl;
    private final String workersToManagerUrl;

    private final ConcurrentHashMap<String, ClientJob> activeJobs = new ConcurrentHashMap<>();
    /** Jobs this manager completed; their late duplicate results are deleted instead of left for others. */
    private final Set<String> finishedClients = ConcurrentHashMap.newKeySet();
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
        syncActiveClientsTag();

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

    private void syncActiveClientsTag() {
        int activeCount = activeJobs.size() + pendingJobSetups.get();
        try {
            compute.updateActiveClients(activeCount);
        } catch (Exception e) {
            System.err.println("Failed updating ActiveClients tag (" + activeCount + "): " + e.getMessage());
        }
    }

    /** Tells local apps not to pick this manager any more. A failure is only logged: shutdown must go on. */
    private void markTerminating() {
        try {
            compute.markTerminating();
        } catch (Exception e) {
            System.err.println("Failed marking this manager as terminating: " + e.getMessage());
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
                        markTerminating();
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
                        syncActiveClientsTag();

                        jobSetupPool.submit(() -> {
                            try {
                                handleNewTask(clientId, responseQueueUrl, s3InputKey, n);
                            } catch (Exception e) {
                                System.err.println("Failed handling NEW_TASK for client " + clientId + ": " + e.getMessage());
                            } finally {
                                // The job is no longer "being set up": rewrite the tag, else it counts the job twice
                                // (pending + active) until the next change.
                                pendingJobSetups.decrementAndGet();
                                syncActiveClientsTag();
                            }
                        });
                    }
                } catch (Exception e) {
                    System.err.println("Failed processing client message: " + e.getMessage());
                }
            }
        }
    }

    boolean handleNewTask(String clientId, String responseQueueUrl, String s3InputKey, int n) {
        List<String> rawLines = storage.downloadLines(s3InputKey);
        List<String> validUrls = rawLines.stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();

        ClientJob job = new ClientJob(clientId, responseQueueUrl, n, validUrls);

        if (job.getTotalTasks() == 0) {
            completeClientJob(job);
            return false;
        }

        activeJobs.put(clientId, job);
        syncActiveClientsTag();

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
        return true;
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

                    // A result for a client we don't know may belong to another manager: don't delete it, hand it
                    // back to the queue after a short delay so its owner can take it.
                    if (handleWorkerResult(clientId, taskIndex, imageUrl, payload, isError)) {
                        sqs.deleteMessage(workersToManagerUrl, msg);
                    } else {
                        sqs.changeMessageVisibility(workersToManagerUrl, msg, UNKNOWN_RESULT_RETRY_SECONDS);
                    }
                } catch (Exception e) {
                    System.err.println("Failed processing worker result: " + e.getMessage());
                }
            }
        }
    }

    /**
     * @return true if the result is ours (an active job, or a late duplicate for a job we already finished), so it
     *         can be deleted; false if this manager never had a job for {@code clientId}
     */
    boolean handleWorkerResult(String clientId, int taskIndex, String imageUrl, String payload, boolean isError) {
        ClientJob job = activeJobs.get(clientId);
        if (job == null) {
            return finishedClients.contains(clientId);
        }
        boolean isLastTask = job.recordResult(taskIndex, imageUrl, payload, isError);
        if (isLastTask) {
            finishedClients.add(clientId);
            activeJobs.remove(clientId);
            syncActiveClientsTag();
            completeClientJob(job);
        }
        return true;
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