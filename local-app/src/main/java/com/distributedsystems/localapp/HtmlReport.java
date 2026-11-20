package com.distributedsystems.localapp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.distributedsystems.ocr.result.OcrFailure;
import com.distributedsystems.ocr.result.OcrResult;
import com.distributedsystems.ocr.result.OcrSuccess;

/**
 * Builds the output HTML page, following the assignment's sample output: a page titled "OCR" with one {@code <p>}
 * per input URL, in input order.
 * <ul>
 *   <li>Success: the image, linked to the original, then the recognized text.</li>
 *   <li>Failure: a link to the input URL, then a short description of the exception (as the spec requires).</li>
 * </ul>
 * HTML formatting is the local app's job, so it lives here (a {@code switch} over the sealed result types) and not
 * on the shared {@link OcrResult} types.
 */
public final class HtmlReport {

    private HtmlReport() {
    }

    /** Renders the results (in the given order) as a complete UTF-8 HTML document. */
    public static String render(List<OcrResult> results) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n")
            .append("<html><head><meta charset=\"UTF-8\"><title>OCR</title></head><body>\n");
        for (OcrResult result : results) {
            html.append("\t<p>\n");
            switch (result) {
                case OcrSuccess success -> {
                    String url = escape(success.url());
                    html.append("\t\t<a href=\"").append(url).append("\"><img src=\"").append(url).append("\"></a><br>\n")
                        .append("\t\t").append(textToHtml(success.text())).append('\n');
                }
                case OcrFailure failure -> {
                    String url = escape(failure.url());
                    html.append("\t\t<a href=\"").append(url).append("\">").append(url).append("</a> ")
                        .append(escape(failure.error())).append('\n');
                }
            }
            html.append("\t</p>\n");
        }
        html.append("</body></html>\n");
        return html.toString();
    }

    /** Renders the results and writes them to {@code outputFile} (UTF-8), replacing any existing file. */
    public static void write(List<OcrResult> results, Path outputFile) throws IOException {
        Files.writeString(outputFile, render(results), StandardCharsets.UTF_8);
    }

    /** Escapes the text and keeps its line breaks visible (HTML would otherwise merge lines). */
    private static String textToHtml(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return escape(text.strip()).replace("\r\n", "\n").replace("\n", "<br>\n\t\t");
    }

    /** Escapes the characters that have a meaning in HTML text and attribute values. */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
