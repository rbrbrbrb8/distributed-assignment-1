package com.distributedsystems.localapp.summary;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import com.distributedsystems.ocr.result.OcrFailure;
import com.distributedsystems.ocr.result.OcrResult;
import com.distributedsystems.ocr.result.OcrSuccess;

/**
 * Parser for the manager's summary format (manager {@code ClientJob}): one line per input URL, in input order:
 * <pre>
 * &lt;url&gt; | OCR: &lt;Base64(UTF-8 text)&gt;
 * &lt;url&gt; | ERROR: &lt;Base64(UTF-8 error description)&gt;
 * </pre>
 * Because the text is Base64-encoded, it can never contain a newline or the separator, so every entry is exactly one
 * line and no guessing is needed. Blank lines (e.g. a trailing newline) are ignored.
 */
public class Base64SummaryParser implements SummaryParser {

    static final String OCR_MARKER = " | OCR: ";
    static final String ERROR_MARKER = " | ERROR: ";

    @Override
    public List<OcrResult> parse(String summary) {
        List<OcrResult> results = new ArrayList<>();
        if (summary == null || summary.isBlank()) {
            return results;
        }

        String[] lines = summary.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }
            results.add(parseLine(line, i + 1));
        }
        return results;
    }

    private static OcrResult parseLine(String line, int lineNumber) {
        int ocr = line.indexOf(OCR_MARKER);
        int error = line.indexOf(ERROR_MARKER);
        boolean isError;
        int markerAt;
        int markerLength;
        // Whichever marker appears first separates the URL from the payload.
        if (ocr >= 0 && (error < 0 || ocr < error)) {
            isError = false;
            markerAt = ocr;
            markerLength = OCR_MARKER.length();
        } else if (error >= 0) {
            isError = true;
            markerAt = error;
            markerLength = ERROR_MARKER.length();
        } else {
            throw new IllegalArgumentException("Summary line " + lineNumber + " has no ' | OCR: ' or ' | ERROR: ': " + line);
        }

        String url = line.substring(0, markerAt);
        if (url.isBlank()) {
            throw new IllegalArgumentException("Summary line " + lineNumber + " has no URL: " + line);
        }

        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(line.substring(markerAt + markerLength).trim()),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Summary line " + lineNumber + " has invalid Base64: " + line, e);
        }

        return isError ? new OcrFailure(url, decoded) : new OcrSuccess(url, decoded);
    }
}
