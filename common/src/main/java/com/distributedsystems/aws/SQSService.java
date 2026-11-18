package com.distributedsystems.aws;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.*;

public class SQSService implements AutoCloseable {

    public static final String APP_TO_MANAGER_QUEUE = "ocr-app-to-manager-queue";
    public static final String MANAGER_TO_WORKERS_QUEUE = "ocr-manager-to-workers-queue";
    public static final String WORKERS_TO_MANAGER_QUEUE = "ocr-workers-to-manager-queue";

    private static final int LONG_POLL_WAIT_SECONDS = 20;
    private static final int MAX_BATCH_SIZE = 10;

    private final SqsClient sqsClient;

    public SQSService(Region region) {
        this.sqsClient = SqsClient.builder()
                .region(region)
                .build();
    }

    public String getOrCreateQueue(String queueName, int visibilityTimeoutSeconds) {
        try {
            GetQueueUrlRequest getUrlRequest = GetQueueUrlRequest.builder()
                    .queueName(queueName)
                    .build();
            return sqsClient.getQueueUrl(getUrlRequest).queueUrl();
        } catch (QueueDoesNotExistException e) {
            Map<QueueAttributeName, String> attributes = new HashMap<>();
            attributes.put(QueueAttributeName.VISIBILITY_TIMEOUT, String.valueOf(visibilityTimeoutSeconds));
            attributes.put(QueueAttributeName.RECEIVE_MESSAGE_WAIT_TIME_SECONDS, String.valueOf(LONG_POLL_WAIT_SECONDS));

            CreateQueueRequest createRequest = CreateQueueRequest.builder()
                    .queueName(queueName)
                    .attributes(attributes)
                    .build();

            return sqsClient.createQueue(createRequest).queueUrl();
        }
    }

    public String createClientResponseQueue() {
        String uniqueName = "ocr-client-response-" + UUID.randomUUID();
        return getOrCreateQueue(uniqueName, 30);
    }

    public void sendMessage(String queueUrl, String messageBody) {
        SendMessageRequest request = SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(messageBody)
                .build();
        sqsClient.sendMessage(request);
    }

    public void sendMessagesBatch(String queueUrl, List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        for (int i = 0; i < messages.size(); i += MAX_BATCH_SIZE) {
            List<String> chunk = messages.subList(i, Math.min(i + MAX_BATCH_SIZE, messages.size()));
            List<SendMessageBatchRequestEntry> entries = new ArrayList<>(chunk.size());

            for (int j = 0; j < chunk.size(); j++) {
                entries.add(SendMessageBatchRequestEntry.builder()
                        .id(String.valueOf(j))
                        .messageBody(chunk.get(j))
                        .build());
            }

            SendMessageBatchRequest batchRequest = SendMessageBatchRequest.builder()
                    .queueUrl(queueUrl)
                    .entries(entries)
                    .build();

            SendMessageBatchResponse response = sqsClient.sendMessageBatch(batchRequest);
            if (response.hasFailed() && !response.failed().isEmpty()) {
                throw new RuntimeException("Failed to send batch entries to SQS: " + response.failed());
            }
        }
    }

    public List<Message> receiveMessages(String queueUrl, int maxMessages) {
        int safeMax = Math.max(1, Math.min(maxMessages, MAX_BATCH_SIZE));
        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(safeMax)
                .waitTimeSeconds(LONG_POLL_WAIT_SECONDS)
                .build();

        return sqsClient.receiveMessage(request).messages();
    }

    public void deleteMessage(String queueUrl, Message message) {
        DeleteMessageRequest request = DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build();
        sqsClient.deleteMessage(request);
    }

    public void changeMessageVisibility(String queueUrl, Message message, int newTimeoutSeconds) {
        ChangeMessageVisibilityRequest request = ChangeMessageVisibilityRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .visibilityTimeout(newTimeoutSeconds)
                .build();
        sqsClient.changeMessageVisibility(request);
    }

    public int getApproximateMessageCount(String queueUrl) {
        GetQueueAttributesRequest request = GetQueueAttributesRequest.builder()
                .queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                .build();

        Map<QueueAttributeName, String> attributes = sqsClient.getQueueAttributes(request).attributes();
        String countStr = attributes.getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "0");
        return Integer.parseInt(countStr);
    }

    public void deleteQueue(String queueUrl) {
        try {
            DeleteQueueRequest request = DeleteQueueRequest.builder()
                    .queueUrl(queueUrl)
                    .build();
            sqsClient.deleteQueue(request);
        } catch (QueueDoesNotExistException ignored) {
            // Queue was already deleted
        }
    }

    @Override
    public void close() {
        sqsClient.close();
    }
}