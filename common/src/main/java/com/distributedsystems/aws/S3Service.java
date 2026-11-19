package com.distributedsystems.aws;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Shared S3 helper for the local app, the manager and dev tools. One bucket per system, organised in folders
 * (S3 "folders" are just key prefixes ending in {@code /}):
 * <pre>
 * &lt;bucket&gt;/
 * ├── jars/                 worker.jar, manager.jar
 * ├── jobs/&lt;clientId&gt;/      input.txt (local app), summary.txt (manager)
 * └── test-data/            dev inputs
 * </pre>
 * Always build keys with the helpers below ({@link #inputKey}, {@link #summaryKey}, ...) so every component agrees
 * on the layout.
 *
 * <p>Credentials come from the SDK's default chain ({@code ~/.aws/credentials} locally, the IAM role on EC2).
 * AWS errors are not swallowed: they propagate to the caller, like in {@link SQSService}.
 */
public class S3Service implements AutoCloseable {

    public static final String JARS_PREFIX = "jars/";
    public static final String TEST_DATA_PREFIX = "test-data/";
    public static final String JOBS_PREFIX = "jobs/";

    /** IDs and file names become part of a key: no '/', so they can never escape their folder. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");
    /** S3's limit for one DeleteObjects call. */
    private static final int MAX_DELETE_BATCH = 1000;

    private final S3Client s3;
    private final String bucket;

    public S3Service(Region region, String bucket) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("bucket must not be empty");
        }
        this.s3 = S3Client.builder().region(region).build();
        this.bucket = bucket;
    }

    // ---------------------------------------------------------------- bucket layout (single source of truth)

    /** {@code jobs/<clientId>/}: everything belonging to one local-app run. */
    public static String jobPrefix(String clientId) {
        return JOBS_PREFIX + requireSafe("clientId", clientId) + "/";
    }

    /** {@code jobs/<clientId>/input.txt}: the URL list uploaded by the local app. */
    public static String inputKey(String clientId) {
        return jobPrefix(clientId) + "input.txt";
    }

    /** {@code jobs/<clientId>/summary.txt}: the results written by the manager. */
    public static String summaryKey(String clientId) {
        return jobPrefix(clientId) + "summary.txt";
    }

    /** {@code jars/<jarName>}, e.g. {@code jars/worker.jar}. */
    public static String jarKey(String jarName) {
        return JARS_PREFIX + requireSafe("jarName", jarName);
    }

    // ---------------------------------------------------------------- operations

    public String getBucket() {
        return bucket;
    }

    /** Creates the bucket if it doesn't exist yet (private by default). */
    public void ensureBucketExists() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
    }

    public void uploadFile(String key, Path file) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromFile(file));
    }

    /** Uploads text as UTF-8. */
    public void uploadString(String key, String content) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType("text/plain; charset=utf-8").build(),
                RequestBody.fromString(content, StandardCharsets.UTF_8));
    }

    /** Downloads an object to {@code target}, replacing the file if it already exists. */
    public void downloadFile(String key, Path target) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.deleteIfExists(target); // the SDK refuses to overwrite an existing file
        s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(), ResponseTransformer.toFile(target));
    }

    /** Downloads an object and decodes it as UTF-8. */
    public String downloadString(String key) {
        return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .asString(StandardCharsets.UTF_8);
    }

    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) { // HEAD responses have no body, so S3 sometimes reports a plain 404
                return false;
            }
            throw e;
        }
    }

    public void deleteObject(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    /**
     * Deletes every object whose key starts with {@code prefix}, e.g. a whole job folder.
     *
     * @param prefix must be non-empty and end with '/', so a bug can never wipe the whole bucket
     * @return how many objects were deleted
     */
    public int deletePrefix(String prefix) {
        requireFolderPrefix(prefix);

        List<ObjectIdentifier> toDelete = new ArrayList<>();
        ListObjectsV2Request listRequest = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build();
        // The paginator follows continuation tokens, so folders with more than 1000 objects are fully listed.
        s3.listObjectsV2Paginator(listRequest).contents()
                .forEach(object -> toDelete.add(ObjectIdentifier.builder().key(object.key()).build()));

        for (int i = 0; i < toDelete.size(); i += MAX_DELETE_BATCH) {
            List<ObjectIdentifier> batch = toDelete.subList(i, Math.min(i + MAX_DELETE_BATCH, toDelete.size()));
            s3.deleteObjects(DeleteObjectsRequest.builder()
                    .bucket(bucket)
                    .delete(Delete.builder().objects(batch).quiet(true).build())
                    .build());
        }
        return toDelete.size();
    }

    @Override
    public void close() {
        s3.close();
    }

    // ---------------------------------------------------------------- validation (package-private for tests)

    static String requireSafe(String name, String value) {
        if (value == null || !SAFE_NAME.matcher(value).matches() || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value);
        }
        return value;
    }

    static void requireFolderPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty() || !prefix.endsWith("/") || prefix.equals("/")) {
            throw new IllegalArgumentException("Refusing to delete prefix '" + prefix + "': must be a folder like 'jobs/<id>/'");
        }
    }
}
