package com.distributedsystems.localapp.summary;

import java.util.List;

import com.distributedsystems.ocr.result.OcrResult;

/**
 * Turns the manager's summary file (downloaded from S3 as text) back into result objects for the HTML report.
 *
 * <p>The summary format is not final yet (see {@code specs/open-issues.md}, issue 1), so the local app depends only
 * on this interface. Supporting a new format means adding one implementation and choosing it in
 * {@code LocalAppMain}; nothing else changes.
 */
public interface SummaryParser {

    /**
     * @param summary the full summary file content
     * @return one result per input URL, in input order (empty list for an empty summary)
     * @throws IllegalArgumentException if the content doesn't match the expected format
     */
    List<OcrResult> parse(String summary);
}
