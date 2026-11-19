package com.distributedsystems.aws;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

import software.amazon.awssdk.regions.Region;

/**
 * Dev-only round trip against a real bucket (like {@code SQSTest}; not a unit test, not shipped).
 *
 * <pre>
 * S3Test &lt;bucket&gt; all                 upload → check → download → compare → delete (one go)
 * S3Test &lt;bucket&gt; keep                same, but stop before deleting so the files can be inspected in the console
 * S3Test &lt;bucket&gt; cleanup &lt;clientId&gt;  delete the job folder left by "keep" and verify it is gone
 * </pre>
 */
public final class S3Test {

    private static final Region REGION = Region.US_EAST_1;

    private S3Test() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: S3Test <bucket> all|keep|cleanup <clientId>");
            System.exit(1);
        }
        String bucket = args[0];
        String mode = args[1];

        try (S3Service s3 = new S3Service(REGION, bucket)) {
            switch (mode) {
                case "all" -> {
                    String clientId = roundTrip(s3);
                    cleanup(s3, clientId);
                }
                case "keep" -> {
                    String clientId = roundTrip(s3);
                    System.out.println();
                    System.out.println("Files kept for inspection. Console folder:");
                    System.out.println("  " + consoleUrl(bucket, S3Service.jobPrefix(clientId)));
                    System.out.println("Clean up later with: S3Test " + bucket + " cleanup " + clientId);
                }
                case "cleanup" -> {
                    if (args.length < 3) {
                        throw new IllegalArgumentException("cleanup needs a clientId");
                    }
                    cleanup(s3, args[2]);
                }
                default -> throw new IllegalArgumentException("Unknown mode: " + mode);
            }
        }
    }

    /** Uploads two files, checks they exist, downloads and compares them. Returns the test client ID. */
    private static String roundTrip(S3Service s3) throws Exception {
        String clientId = "s3test-" + UUID.randomUUID();
        String inputKey = S3Service.inputKey(clientId);
        String summaryKey = S3Service.summaryKey(clientId);
        System.out.println("Test job folder: s3://" + s3.getBucket() + "/" + S3Service.jobPrefix(clientId));

        // 1. Upload: a local file as input.txt, a string as summary.txt (non-ASCII text checks UTF-8 handling).
        String inputContent = "http://example.com/a.png\nhttp://example.com/b.png\n";
        String summaryContent = "0###http://example.com/a.png###OK###text with ünïcödé and שלום\n";
        Path localInput = Files.createTempFile("s3test-input", ".txt");
        Files.writeString(localInput, inputContent);
        s3.uploadFile(inputKey, localInput);
        s3.uploadString(summaryKey, summaryContent);
        System.out.println("1. Uploaded " + inputKey + " (from a file) and " + summaryKey + " (from a string)");

        // 2. exists(): true for both, false for a key that isn't there.
        check(s3.exists(inputKey), "input.txt exists");
        check(s3.exists(summaryKey), "summary.txt exists");
        check(!s3.exists(S3Service.jobPrefix(clientId) + "missing.txt"), "missing.txt does not exist");

        // 3. Download both ways and compare byte-for-byte with what was uploaded.
        Path downloaded = Files.createTempFile("s3test-download", ".txt");
        s3.downloadFile(inputKey, downloaded); // target already exists: must be overwritten
        check(Arrays.equals(Files.readAllBytes(localInput), Files.readAllBytes(downloaded)),
                "downloadFile(input.txt) matches the uploaded file");
        check(summaryContent.equals(s3.downloadString(summaryKey)),
                "downloadString(summary.txt) matches the uploaded string (incl. non-ASCII)");

        Files.deleteIfExists(localInput);
        Files.deleteIfExists(downloaded);
        return clientId;
    }

    private static void cleanup(S3Service s3, String clientId) {
        String prefix = S3Service.jobPrefix(clientId);
        int deleted = s3.deletePrefix(prefix);
        System.out.println("4. deletePrefix(" + prefix + ") deleted " + deleted + " object(s)");
        check(!s3.exists(S3Service.inputKey(clientId)), "input.txt is gone");
        check(!s3.exists(S3Service.summaryKey(clientId)), "summary.txt is gone");
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new IllegalStateException("FAILED: " + what);
        }
        System.out.println("   OK: " + what);
    }

    private static String consoleUrl(String bucket, String prefix) {
        return "https://s3.console.aws.amazon.com/s3/buckets/" + bucket + "?region=" + REGION.id()
                + "&prefix=" + prefix;
    }
}
