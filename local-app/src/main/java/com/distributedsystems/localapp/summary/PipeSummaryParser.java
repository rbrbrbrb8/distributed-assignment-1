package com.distributedsystems.localapp.summary;

import java.util.ArrayList;
import java.util.List;

import com.distributedsystems.ocr.result.OcrFailure;
import com.distributedsystems.ocr.result.OcrResult;
import com.distributedsystems.ocr.result.OcrSuccess;

/**
 * Best-effort parser for the manager's current summary format: one entry per image, joined by newlines:
 * <pre>
 * &lt;url&gt; | OCR: &lt;text&gt;
 * &lt;url&gt; | ERROR: &lt;error&gt;
 * </pre>
 * OCR text may itself contain newlines, so an entry can span several lines. Rule: a line that starts with
 * {@code http} and contains {@code " | OCR: "} or {@code " | ERROR: "} starts a new entry; any other line continues
 * the previous entry's text.
 *
 * <p>Known limitation: OCR text containing a line that itself looks like an entry start would be split wrongly.
 * A line-safe format (open issue 1) removes the need for this guessing.
 */
public class PipeSummaryParser implements SummaryParser {

    static final String OCR_MARKER = " | OCR: ";
    static final String ERROR_MARKER = " | ERROR: ";

    @Override
    public List<OcrResult> parse(String summary) {
        List<OcrResult> results = new ArrayList<>();
        if (summary == null || summary.isBlank()) {
            return results;
        }

        Entry current = null;
        for (String line : summary.split("\r?\n", -1)) {
            Entry started = tryStart(line);
            if (started != null) {
                if (current != null) {
                    results.add(current.toResult());
                }
                current = started;
            } else if (current != null) {
                current.text.append('\n').append(line);
            } else if (!line.isBlank()) {
                throw new IllegalArgumentException("Summary does not start with an entry: '" + line + "'");
            }
        }
        if (current != null) {
            results.add(current.toResult());
        }
        return results;
    }

    /** Returns a new entry if {@code line} starts one, otherwise null. */
    private static Entry tryStart(String line) {
        if (!line.startsWith("http")) {
            return null;
        }
        int ocr = line.indexOf(OCR_MARKER);
        int error = line.indexOf(ERROR_MARKER);
        // Use whichever marker comes first: the URL itself never contains " | ".
        if (ocr >= 0 && (error < 0 || ocr < error)) {
            return new Entry(line.substring(0, ocr), false, line.substring(ocr + OCR_MARKER.length()));
        }
        if (error >= 0) {
            return new Entry(line.substring(0, error), true, line.substring(error + ERROR_MARKER.length()));
        }
        return null;
    }

    private static final class Entry {
        final String url;
        final boolean isError;
        final StringBuilder text;

        Entry(String url, boolean isError, String firstLine) {
            this.url = url;
            this.isError = isError;
            this.text = new StringBuilder(firstLine);
        }

        OcrResult toResult() {
            String value = text.toString().stripTrailing(); // drop the trailing newline(s) of the file
            return isError ? new OcrFailure(url, value) : new OcrSuccess(url, value);
        }
    }
}
