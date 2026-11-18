package com.distributedsystems.worker.ocr.result;

/** Downloading or OCR failed; {@code error} is a short description, e.g. "IOException: HTTP 404". */
public record OcrFailure(String url, String error) implements OcrResult {
}
