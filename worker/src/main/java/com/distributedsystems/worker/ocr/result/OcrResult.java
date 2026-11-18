package com.distributedsystems.worker.ocr.result;

/**
 * Outcome of processing one image URL: either {@link OcrSuccess} (recognized text)
 * or {@link OcrFailure} (short description of what went wrong). No other outcomes exist.
 */
public sealed interface OcrResult permits OcrSuccess, OcrFailure {

    /** The image URL this result belongs to. */
    String url();
}
