package com.distributedsystems.manager;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ClientJob {
    private final String clientId;
    private final String responseQueueUrl;
    private final int n;
    private final List<String> imageUrls;
    private final String[] formattedResults;
    private final AtomicInteger completedCount = new AtomicInteger(0);
    private final AtomicBoolean finished = new AtomicBoolean(false);

    public ClientJob(String clientId, String responseQueueUrl, int n, List<String> imageUrls) {
        this.clientId = clientId;
        this.responseQueueUrl = responseQueueUrl;
        this.n = n;
        this.imageUrls = List.copyOf(imageUrls);
        this.formattedResults = new String[this.imageUrls.size()];
    }

    /**
     * Records the result for a specific taskIndex.
     * Returns true only for the final result that completes all tasks for this client.
     */
    public boolean recordResult(int taskIndex, String imageUrl, String textOrError, boolean isError) {
        if (taskIndex < 0 || taskIndex >= formattedResults.length) {
            return false;
        }

        String line = isError
                ? imageUrl + " | ERROR: " + textOrError
                : imageUrl + " | OCR: " + textOrError;

        synchronized (formattedResults) {
            if (formattedResults[taskIndex] != null) {
                // Duplicate SQS message for the same index - ignore to prevent double-counting
                return false;
            }
            formattedResults[taskIndex] = line;
        }

        int done = completedCount.incrementAndGet();
        return done == formattedResults.length && finished.compareAndSet(false, true);
    }

    public String buildSummaryContent() {
        return String.join("\n", formattedResults);
    }

    public int getRemainingTasks() {
        return Math.max(0, formattedResults.length - completedCount.get());
    }

    public String getClientId() { return clientId; }
    public String getResponseQueueUrl() { return responseQueueUrl; }
    public int getN() { return n; }
    public List<String> getImageUrls() { return imageUrls; }
    public int getTotalTasks() { return imageUrls.size(); }
}