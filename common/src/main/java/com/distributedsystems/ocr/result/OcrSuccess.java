package com.distributedsystems.ocr.result;

import com.distributedsystems.aws.MessageProtocol;

/** The image was downloaded and OCR completed; {@code text} may be empty if the image has no text. */
public record OcrSuccess(String url, String text) implements OcrResult {

    /** Builds an {@code OCR_DONE} message; the text is Base64-encoded by {@link MessageProtocol}. */
    @Override
    public String toMessage(String clientId, int taskIndex) {
        return MessageProtocol.createOcrDoneMsg(clientId, taskIndex, url, text);
    }
}
