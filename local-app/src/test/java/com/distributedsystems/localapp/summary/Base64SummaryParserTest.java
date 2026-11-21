package com.distributedsystems.localapp.summary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.distributedsystems.manager.ClientJob;
import com.distributedsystems.ocr.result.OcrFailure;
import com.distributedsystems.ocr.result.OcrSuccess;

class Base64SummaryParserTest {

    private final SummaryParser parser = new Base64SummaryParser();

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesSuccessAndErrorLinesInOrder() {
        String summary = String.join("\n",
                "http://a.com/1.png | OCR: " + b64("3D Text"),
                "http://a.com/2.png | ERROR: " + b64("IOException: HTTP 404"),
                "http://a.com/3.png | OCR: " + b64("PEACE"));

        assertEquals(List.of(
                new OcrSuccess("http://a.com/1.png", "3D Text"),
                new OcrFailure("http://a.com/2.png", "IOException: HTTP 404"),
                new OcrSuccess("http://a.com/3.png", "PEACE")), parser.parse(summary));
    }

    @Test
    void multiLineAndUnicodeTextSurvive() {
        String text = "ABCDEFGHIJKLM\nNOPQRSTUVWXYZ\n\nשלום ünïcödé | OCR: not a separator";

        assertEquals(List.of(new OcrSuccess("http://a.com/1.png", text)),
                parser.parse("http://a.com/1.png | OCR: " + b64(text)));
    }

    @Test
    void emptyTextIsAValidSuccess() {
        assertEquals(List.of(new OcrSuccess("http://a.com/1.png", "")), parser.parse("http://a.com/1.png | OCR: "));
    }

    @Test
    void urlsWithQueryStringsAndTrailingNewlinesAndCrLf() {
        String url = "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQJ18&usqp=CAU";
        String summary = url + " | OCR: " + b64("PEACE") + "\r\nhttp://a.com/2.png | OCR: " + b64("x") + "\r\n\n";

        assertEquals(List.of(new OcrSuccess(url, "PEACE"), new OcrSuccess("http://a.com/2.png", "x")),
                parser.parse(summary));
    }

    @Test
    void emptySummaryGivesNoResults() {
        assertTrue(parser.parse("").isEmpty());
        assertTrue(parser.parse("\n").isEmpty());
        assertTrue(parser.parse(null).isEmpty());
    }

    @Test
    void malformedLinesAreRejectedWithLineNumber() {
        IllegalArgumentException noMarker = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("http://a.com/1.png | OCR: " + b64("ok") + "\ngarbage"));
        assertTrue(noMarker.getMessage().contains("line 2"));

        assertThrows(IllegalArgumentException.class, () -> parser.parse("http://a.com/1.png | OCR: %%%not-base64%%%"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(" | OCR: " + b64("no url")));
    }

    @Test
    void contractParsesWhatTheRealManagerWrites() {
        // Uses the manager's own ClientJob to build the summary, so a format change on the manager side fails here.
        List<String> urls = List.of("http://a.com/1.png", "http://a.com/2.png", "http://a.com/3.png");
        ClientJob job = new ClientJob("client-1", "queue-url", 10, urls);
        job.recordResult(2, urls.get(2), "line one\nline two", false); // results may arrive out of order
        job.recordResult(0, urls.get(0), "3D Text", false);
        job.recordResult(1, urls.get(1), "IOException: HTTP 404", true);

        assertEquals(List.of(
                new OcrSuccess("http://a.com/1.png", "3D Text"),
                new OcrFailure("http://a.com/2.png", "IOException: HTTP 404"),
                new OcrSuccess("http://a.com/3.png", "line one\nline two")), parser.parse(job.buildSummaryContent()));
    }

    @Test
    void contractMissingResultFromManagerBecomesAnError() {
        // The manager writes "Missing result" (as an ERROR entry) for an index that never got a result.
        List<String> urls = List.of("http://a.com/1.png", "http://a.com/2.png");
        ClientJob job = new ClientJob("client-1", "queue-url", 10, urls);
        job.recordResult(0, urls.get(0), "ok", false);

        assertEquals(List.of(
                new OcrSuccess("http://a.com/1.png", "ok"),
                new OcrFailure("http://a.com/2.png", "Missing result")), parser.parse(job.buildSummaryContent()));
    }
}
