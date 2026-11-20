package com.distributedsystems.ocr.result;

/**
 * Outcome of processing one image URL: either {@link OcrSuccess} (recognized text)
 * or {@link OcrFailure} (short description of what went wrong). No other outcomes exist.
 */
public sealed interface OcrResult permits OcrSuccess, OcrFailure {

    /** The image URL this result belongs to. */
    String url();

    /**
     * Serializes this result into the worker → manager SQS message body (see {@code MessageProtocol}).
     *
     * @param clientId  the client the task belongs to (copied from the IMAGE_TASK message)
     * @param taskIndex the task's line index in the client's input file (copied from the IMAGE_TASK message)
     */
    String toMessage(String clientId, int taskIndex);
}
