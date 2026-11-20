package com.distributedsystems.ocr.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import com.distributedsystems.aws.MessageProtocol;

/** Checks that results serialize into the worker → manager protocol and can be read back by the manager. */
class OcrResultMessageTest {

    private static final String CLIENT = "client-1";
    private static final String URL = "http://example.test/img.png";

    @Test
    void successBecomesOcrDoneMessage() {
        OcrResult result = new OcrSuccess(URL, "hello\nworld ###");

        String[] parts = MessageProtocol.parse(result.toMessage(CLIENT, 7));

        assertEquals(5, parts.length);
        assertEquals("OCR_DONE", parts[0]);
        assertEquals(CLIENT, parts[1]);
        assertEquals(7, MessageProtocol.parseTaskIndex(parts));
        assertEquals(URL, parts[3]);
        // Text is Base64-encoded, so newlines / "###" inside it don't break the message format.
        assertEquals("hello\nworld ###", MessageProtocol.decodeOcrText(parts[4]));
    }

    @Test
    void emptyTextIsAValidSuccess() {
        String[] parts = MessageProtocol.parse(new OcrSuccess(URL, "").toMessage(CLIENT, 0));

        assertEquals("OCR_DONE", parts[0]);
        assertEquals("", MessageProtocol.decodeOcrText(parts[4]));
    }

    @Test
    void failureBecomesOcrErrorMessage() {
        OcrResult result = new OcrFailure(URL, "IOException: HTTP 404");

        String[] parts = MessageProtocol.parse(result.toMessage(CLIENT, 3));

        assertEquals(5, parts.length);
        assertEquals("OCR_ERROR", parts[0]);
        assertEquals(CLIENT, parts[1]);
        assertEquals(3, MessageProtocol.parseTaskIndex(parts));
        assertEquals(URL, parts[3]);
        assertEquals("IOException: HTTP 404", parts[4]);
    }

    @Test
    void multiLineErrorIsFlattenedToOneField() {
        OcrResult result = new OcrFailure(URL, "line one\nline two ### more");

        String[] parts = MessageProtocol.parse(result.toMessage(CLIENT, 1));

        assertEquals(5, parts.length);
        assertFalse(parts[4].contains("\n"));
    }
}
