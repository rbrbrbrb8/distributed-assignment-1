package com.distributedsystems.localapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.distributedsystems.ocr.result.OcrFailure;
import com.distributedsystems.ocr.result.OcrResult;
import com.distributedsystems.ocr.result.OcrSuccess;

class HtmlReportTest {

    @TempDir
    Path dir;

    @Test
    void successShowsLinkedImageAndText() {
        String html = HtmlReport.render(List.of(new OcrSuccess("http://x.com/a.png", "3D Text")));

        assertTrue(html.contains("<a href=\"http://x.com/a.png\"><img src=\"http://x.com/a.png\"></a><br>"));
        assertTrue(html.contains("3D Text"));
    }

    @Test
    void failureShowsLinkToInputAndShortError() {
        String html = HtmlReport.render(List.of(new OcrFailure("http://x.com/dead.png", "IOException: HTTP 404")));

        assertTrue(html.contains("<a href=\"http://x.com/dead.png\">http://x.com/dead.png</a> IOException: HTTP 404"));
        assertFalse(html.contains("<img"), "a failed image must not be shown as an image");
    }

    @Test
    void keepsInputOrderWithOneParagraphPerResult() {
        String html = HtmlReport.render(List.of(
                new OcrSuccess("http://x.com/1.png", "first"),
                new OcrFailure("http://x.com/2.png", "ConnectException"),
                new OcrSuccess("http://x.com/3.png", "third")));

        assertTrue(html.indexOf("1.png") < html.indexOf("2.png"));
        assertTrue(html.indexOf("2.png") < html.indexOf("3.png"));
        assertEquals(3, count(html, "<p>"));
        assertEquals(3, count(html, "</p>"));
    }

    @Test
    void escapesTextAndUrlsSoNothingCanBreakOrRunInThePage() {
        String html = HtmlReport.render(List.of(
                new OcrSuccess("http://x.com/a.png?a=1&b=\"2\"", "<script>alert('x')</script> & more"),
                new OcrFailure("http://x.com/<b>.png", "Error <b>bold</b>")));

        assertFalse(html.contains("<script>"));
        assertFalse(html.contains("<b>"));
        assertTrue(html.contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; &amp; more"));
        assertTrue(html.contains("href=\"http://x.com/a.png?a=1&amp;b=&quot;2&quot;\""));
    }

    @Test
    void multiLineTextKeepsItsLineBreaks() {
        String html = HtmlReport.render(List.of(new OcrSuccess("http://x.com/a.png", "ABCDEFGHIJKLM\nNOPQRSTUVWXYZ")));

        assertTrue(html.contains("ABCDEFGHIJKLM<br>"));
        assertTrue(html.contains("NOPQRSTUVWXYZ"));
    }

    @Test
    void emptyResultListGivesValidEmptyPage() {
        String html = HtmlReport.render(List.of());

        assertTrue(html.contains("<title>OCR</title>"));
        assertTrue(html.trim().endsWith("</body></html>"));
        assertEquals(0, count(html, "<p>"));
    }

    @Test
    void matchesTheStructureOfTheAssignmentSample() {
        // The two images of the assignment's sample (output-example.html / words-urls.txt).
        String peaceUrl = "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQJ18MhbltBQjdD1sZJQHAzgZiDEx1EpX9ldQ&usqp=CAU";
        String html = HtmlReport.render(List.of(
                new OcrSuccess("http://markdotto.com/uploads/2011/01/3d-text.jpg", "3D Text"),
                new OcrSuccess(peaceUrl, "PEACE")));

        assertTrue(html.contains("<title>OCR</title>"));
        assertTrue(html.contains("<meta charset=\"UTF-8\">"));
        assertTrue(html.contains("<img src=\"http://markdotto.com/uploads/2011/01/3d-text.jpg\"></a><br>\n\t\t3D Text"));
        assertTrue(html.contains("<img src=\"" + peaceUrl.replace("&", "&amp;") + "\"></a><br>\n\t\tPEACE"));
    }

    @Test
    void writesUtf8File() throws IOException {
        Path out = dir.resolve("out.html");
        HtmlReport.write(List.<OcrResult>of(new OcrSuccess("http://x.com/a.png", "שלום ünïcödé")), out);

        assertTrue(Files.readString(out, StandardCharsets.UTF_8).contains("שלום ünïcödé"));
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) {
            count++;
        }
        return count;
    }
}
