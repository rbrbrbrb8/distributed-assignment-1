# Plan: `S3Service`, a shared S3 helper in `common`

## Context
The manager (the friend's part) and the local app (the user's next part) both need S3:
- the local app uploads the input file and downloads the summary;
- the manager downloads the input file and uploads the summary;
- developers upload jars.

No S3 code exists yet. `common` has `SQSService`, `EC2Service`, `UserData` and `MessageProtocol`, and the parent pom
already manages `software.amazon.awssdk:s3:2.57.0`. `S3Service` is built first, on branch `s3-service`, and merged
to `main` quickly so the friend can use it for the manager.

The user wants **order in the bucket**: one bucket with folders, never files dumped together.

## Bucket layout (single source of truth, defined in `S3Service`)
```
<bucket>/
├── jars/                 worker.jar, manager.jar
├── jobs/<clientId>/      input.txt    (local app uploads)
│                         summary.txt  (manager writes)
└── test-data/            sample-urls.txt, other dev inputs
```
- **S3 has no real folders.** A "folder" is a key prefix ending in `/`, and the console displays it as a folder.
- **Grouped per job:** everything about one job sits under one prefix. Cleanup is one `deletePrefix`, and since
  each local app has a unique `clientId` (UUID), concurrent clients can never overwrite each other's files.
- **Nobody writes key strings by hand.** Everyone uses the helpers below, so the local app and the manager can't
  disagree on paths.

## Design: `common/src/main/java/com/distributedsystems/aws/S3Service.java`
Same style as `SQSService`: a `Region` in the constructor, `AutoCloseable`, the SDK's default credential chain (no
credentials in code), and AWS exceptions passed on to the caller.

```java
public class S3Service implements AutoCloseable {
    public static final String JARS_PREFIX = "jars/";
    public static final String TEST_DATA_PREFIX = "test-data/";
    public static final String JOBS_PREFIX = "jobs/";

    public S3Service(Region region, String bucket)  // the bucket is a parameter, never hardcoded

    public static String jobPrefix(String clientId)  // "jobs/<clientId>/"
    public static String inputKey(String clientId)   // "jobs/<clientId>/input.txt"
    public static String summaryKey(String clientId) // "jobs/<clientId>/summary.txt"
    public static String jarKey(String jarName)      // "jars/<jarName>"

    public void   ensureBucketExists()                     // headBucket; createBucket if missing
    public void   uploadFile(String key, Path file)        // putObject + RequestBody.fromFile
    public void   uploadString(String key, String content) // UTF-8
    public void   downloadFile(String key, Path target)    // overwrites target
    public String downloadString(String key)               // UTF-8
    public boolean exists(String key)                      // headObject; NoSuchKey → false
    public void   deleteObject(String key)
    public void   deletePrefix(String prefix)              // list (paginated) + deleteObjects, max 1000 per call
    public String getBucket()
}
```
- **Validation:** `clientId` and `jarName` must match `[A-Za-z0-9._-]+`, with no `/` and no `..`. A crafted
  ID can then never point outside its own `jobs/<clientId>/` folder. Same idea as the validation in `UserData`.
- **`deletePrefix` is protected:** it rejects an empty prefix, or one without a trailing `/`, so a bug can't wipe
  the whole bucket.
- The bucket stays **private**, the S3 default. Access goes through credentials or the IAM role, never public URLs.

## Files
| File | Change |
|---|---|
| `common/pom.xml` | Add the `software.amazon.awssdk:s3` dependency (version from the parent) |
| `common/src/main/java/com/distributedsystems/aws/S3Service.java` | **New** |
| `common/src/test/java/com/distributedsystems/aws/S3ServiceTest.java` | **New**: unit tests for the key helpers and validation (no AWS) |
| `common/src/test/java/com/distributedsystems/aws/S3Test.java` | **New**: a dev `main`, like the friend's `SQSTest`, that does a real round trip |
| `specs/s3-service.md` | **New**: this plan |

## Execution order (stop after each step, explain, and wait for the user's go-ahead)
1. **After approval:** write this plan to `specs/s3-service.md`. **Then stop and wait.**
2. Add the `s3` dependency, then `S3Service` with the key helpers and validation, then `S3ServiceTest`. Run the
   `common` tests.
3. Write `S3Test` and run it against `ocr-assignment-c8f120d5`. It:
   1. uploads a string and a file under `jobs/s3test-<uuid>/`;
   2. checks `exists`, then downloads both and compares them;
   3. runs `deletePrefix` and checks nothing is left.
4. **(Separate small step, only if the user wants it)** Move the real bucket to the new layout: copy
   `jars/sample-urls.txt` to `test-data/sample-urls.txt`, delete the old key, and update the re-run command in
   `specs/worker-stage2.md`.
5. The user commits, opens the PR and merges it into `main` for the friend.

## Verification
- `S3ServiceTest` checks:
  - the exact key strings;
  - that IDs like `../x`, `a/b`, empty strings and `null` are rejected;
  - that `deletePrefix` refuses `""` and `"jobs"` (no trailing slash).
- `S3Test` (real AWS): the round-trip contents match, and after cleanup `aws s3 ls s3://<bucket>/jobs/` shows no
  leftover `s3test-*` folder.
- All existing `common` and `worker` tests still pass.
