package com.distributedsystems.worker.ocr.result;

/** The image was downloaded and OCR completed; {@code text} may be empty if the image has no text. */
public record OcrSuccess(String url, String text) implements OcrResult {
}
