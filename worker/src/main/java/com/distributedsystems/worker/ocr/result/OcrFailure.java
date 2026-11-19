package com.distributedsystems.worker.ocr.result;

import com.distributedsystems.aws.MessageProtocol;

/** Downloading or OCR failed; {@code error} is a short description, e.g. "IOException: HTTP 404". */
public record OcrFailure(String url, String error) implements OcrResult {

    /** Builds an {@code OCR_ERROR} message; {@link MessageProtocol} strips newlines and delimiters from the error. */
    @Override
    public String toMessage(String clientId, int taskIndex) {
        return MessageProtocol.createOcrErrorMsg(clientId, taskIndex, url, error);
    }
}
