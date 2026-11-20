package com.distributedsystems.localapp.summary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.distributedsystems.ocr.result.OcrFailure;
import com.distributedsystems.ocr.result.OcrResult;
import com.distributedsystems.ocr.result.OcrSuccess;

class PipeSummaryParserTest {

    private final SummaryParser parser = new PipeSummaryParser();

    @Test
    void parsesSingleLineEntriesInOrder() {
        String summary = String.join("\n",
                "http://a.com/1.png | OCR: 3D Text",
                "http://a.com/2.png | ERROR: IOException: HTTP 404",
                "http://a.com/3.png | OCR: PEACE");

        assertEquals(List.of(
                new OcrSuccess("http://a.com/1.png", "3D Text"),
                new OcrFailure("http://a.com/2.png", "IOException: HTTP 404"),
                new OcrSuccess("http://a.com/3.png", "PEACE")), parser.parse(summary));
    }

    @Test
    void multiLineOcrTextStaysWithItsImage() {
        String summary = String.join("\n",
                "http://a.com/1.png | OCR: ABCDEFGHIJKLM",
                "NOPQRSTUVWXYZ",
                "abcdefghijklm",
                "http://a.com/2.png | ERROR: ConnectException");

        List<OcrResult> results = parser.parse(summary);

        assertEquals(2, results.size());
        assertEquals(new OcrSuccess("http://a.com/1.png", "ABCDEFGHIJKLM\nNOPQRSTUVWXYZ\nabcdefghijklm"), results.get(0));
        assertEquals(new OcrFailure("http://a.com/2.png", "ConnectException"), results.get(1));
    }

    @Test
    void blankLinesInsideTextAreKept() {
        String summary = "http://a.com/1.png | OCR: Compare OCR-A fonts\n\nCompare OCR-A font\n";

        assertEquals(List.of(new OcrSuccess("http://a.com/1.png", "Compare OCR-A fonts\n\nCompare OCR-A font")),
                parser.parse(summary));
    }

    @Test
    void emptyOcrTextIsAValidSuccess() {
        assertEquals(List.of(new OcrSuccess("http://a.com/1.png", "")), parser.parse("http://a.com/1.png | OCR: "));
    }

    @Test
    void windowsLineEndingsAreHandled() {
        String summary = "http://a.com/1.png | OCR: line1\r\nline2\r\nhttp://a.com/2.png | OCR: x\r\n";

        assertEquals(List.of(
                new OcrSuccess("http://a.com/1.png", "line1\nline2"),
                new OcrSuccess("http://a.com/2.png", "x")), parser.parse(summary));
    }

    @Test
    void urlsWithQueryStringsAreKeptWhole() {
        String url = "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQJ18&usqp=CAU";

        assertEquals(List.of(new OcrSuccess(url, "PEACE")), parser.parse(url + " | OCR: PEACE"));
    }

    @Test
    void emptySummaryGivesNoResults() {
        assertTrue(parser.parse("").isEmpty());
        assertTrue(parser.parse("\n\n").isEmpty());
        assertTrue(parser.parse(null).isEmpty());
    }

    @Test
    void textBeforeTheFirstEntryIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("garbage\nhttp://a.com/1.png | OCR: x"));
    }

    @Test
    void knownLimitationOcrTextThatLooksLikeAnEntryIsSplit() {
        // Documents WHY the format must change (open issue 1): an image whose text contains a line that
        // looks like an entry start is wrongly split into two results.
        String summary = String.join("\n",
                "http://a.com/1.png | OCR: screenshot of a summary:",
                "http://fake.com/x.png | OCR: not a real entry");

        assertEquals(2, parser.parse(summary).size()); // ideally 1
    }
}
