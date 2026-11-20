package com.distributedsystems.manager;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class ClientJob {
    private static final String ENTRY_SEPARATOR = "\n";

    private final String clientId;
    private final String responseQueueUrl;
    private final int n;
    private final List<String> imageUrls;
    private final String[] results;
    private final AtomicInteger remainingTasks;

    public ClientJob(String clientId, String responseQueueUrl, int n, List<String> imageUrls) {
        this.clientId = clientId;
        this.responseQueueUrl = responseQueueUrl;
        this.n = n;
        this.imageUrls = List.copyOf(imageUrls);
        this.results = new String[this.imageUrls.size()];
        this.remainingTasks = new AtomicInteger(this.imageUrls.size());
    }

    public String getClientId() {
        return clientId;
    }

    public String getResponseQueueUrl() {
        return responseQueueUrl;
    }

    public int getN() {
        return n;
    }

    public int getTotalTasks() {
        return imageUrls.size();
    }

    public int getRemainingTasks() {
        return remainingTasks.get();
    }

    public boolean recordResult(int taskIndex, String imageUrl, String payload, boolean isError) {
        if (taskIndex < 0 || taskIndex >= results.length) {
            throw new IllegalArgumentException("Out-of-range taskIndex " + taskIndex + " for client " + clientId);
        }

        String resolvedUrl = (imageUrl != null && !imageUrl.isBlank()) ? imageUrl : imageUrls.get(taskIndex);
        String safePayload = payload == null ? "" : payload;
        String encodedPayload = Base64.getEncoder().encodeToString(safePayload.getBytes(StandardCharsets.UTF_8));
        String formattedEntry = isError
                ? resolvedUrl + " | ERROR: " + encodedPayload
                : resolvedUrl + " | OCR: " + encodedPayload;

        synchronized (results) {
            if (results[taskIndex] != null) {
                return false;
            }
            results[taskIndex] = formattedEntry;
        }

        return remainingTasks.decrementAndGet() == 0;
    }

    public String buildSummaryContent() {
        StringBuilder sb = new StringBuilder();
        synchronized (results) {
            for (int i = 0; i < results.length; i++) {
                if (i > 0) {
                    sb.append(ENTRY_SEPARATOR);
                }
                String entry = results[i] != null
                        ? results[i]
                        : imageUrls.get(i) + " | ERROR: " + Base64.getEncoder().encodeToString("Missing result".getBytes(StandardCharsets.UTF_8));
                sb.append(entry);
            }
        }
        return sb.toString();
    }
}