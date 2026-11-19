package com.distributedsystems.aws;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class MessageProtocol {

    public static final String DELIMITER = "###";

    public enum MessageType {
        NEW_TASK,
        TERMINATE,
        IMAGE_TASK,
        OCR_DONE,
        OCR_ERROR,
        JOB_DONE
    }

    // 1. Local App -> Manager
    public static String createNewTaskMsg(String clientId, String responseQueueUrl, String s3InputKey, int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("n (files per worker ratio) must be positive");
        }
        return String.join(DELIMITER,
                MessageType.NEW_TASK.name(),
                clientId,
                responseQueueUrl,
                s3InputKey,
                String.valueOf(n));
    }

    // 2. Local App -> Manager
    public static String createTerminateMsg() {
        return MessageType.TERMINATE.name();
    }

    // 3. Manager -> Worker
    public static String createImageTaskMsg(String clientId, String imageUrl) {
        return String.join(DELIMITER, MessageType.IMAGE_TASK.name(), clientId, imageUrl);
    }

    // 4. Worker -> Manager (Success)
    public static String createOcrDoneMsg(String clientId, String imageUrl, String extractedText) {
        String safeText = (extractedText == null) ? "" : extractedText;
        String encodedText = Base64.getEncoder().encodeToString(safeText.getBytes(StandardCharsets.UTF_8));
        return String.join(DELIMITER, MessageType.OCR_DONE.name(), clientId, imageUrl, encodedText);
    }

    // 5. Worker -> Manager (Exception recovery)
    public static String createOcrErrorMsg(String clientId, String imageUrl, String errorDescription) {
        String safeError = (errorDescription == null || errorDescription.isBlank())
                ? "Unknown processing exception"
                : errorDescription;
        String cleanError = safeError
                .replace("\n", " ")
                .replace("\r", " ")
                .replace(DELIMITER, " ");
        return String.join(DELIMITER, MessageType.OCR_ERROR.name(), clientId, imageUrl, cleanError);
    }

    // 6. Manager -> Local App
    public static String createJobDoneMsg(String s3OutputKey) {
        return String.join(DELIMITER, MessageType.JOB_DONE.name(), s3OutputKey);
    }

    public static String[] parse(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            throw new IllegalArgumentException("Received empty SQS message");
        }
        return rawBody.split(DELIMITER, -1);
    }

    public static int parseNewTaskRatio(String[] parts) {
        if (parts == null || parts.length != 5 || !MessageType.NEW_TASK.name().equals(parts[0])) {
            throw new IllegalArgumentException("Invalid NEW_TASK message format: expected 5 fields");
        }
        try {
            int n = Integer.parseInt(parts[4]);
            if (n <= 0) {
                throw new IllegalArgumentException("n must be positive, got: " + n);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for n in NEW_TASK: " + parts[4], e);
        }
    }

    public static String decodeOcrText(String encodedText) {
        if (encodedText == null || encodedText.isEmpty()) {
            return "";
        }
        byte[] decoded = Base64.getDecoder().decode(encodedText);
        return new String(decoded, StandardCharsets.UTF_8);
    }
}