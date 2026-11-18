# Plan: Worker — Stage 1 (local) detailed, Stage 2 (EC2) outline

## Context
Assignment 1 (OCR in the Cloud) is due 22/11. Work is split: the friend owns SQS setup, the user owns the Worker.
The user wants to build it incrementally:
1. **Stage 1 (this round):** a Worker that runs **locally**, exposing a function that takes a list of image URLs,
   downloads + OCRs each one, and returns the results (text or error per URL). No AWS at all.
2. **Stage 2 (next round, outline only):** run the same worker on EC2, launched from Java code, which the Manager
   will later reuse to start workers.

Current state (branch `ec2-ocr`): multi-module Maven parent (`pom.xml`, Java 21, JUnit BOM 5.11.0) with a `worker`
module that has tess4j 5.20.0 and four **empty** skeleton classes plus an empty test class:
- `worker/src/main/java/com/distributedsystems/worker/Worker.java`
- `worker/src/main/java/com/distributedsystems/worker/download/ImageDownloader.java`
- `worker/src/main/java/com/distributedsystems/worker/ocr/OcrEngine.java`
- `worker/src/main/java/com/distributedsystems/worker/ocr/TesseractOcrEngine.java`
- `worker/src/test/java/com/distributedsystems/worker/ocr/TesseractOcrEngineTest.java`

Environment: Java 21 and Maven 3.9.16 are installed; tess4j 5.20.0 is already in `~/.m2`.

---

## Stage 1 — Local worker (implement now)

### Design
- **`OcrResult`** (sealed interface, `com.distributedsystems.worker.ocr.result`) `permits OcrSuccess, OcrFailure`, with one
  method, `url()`. The two records are **`OcrSuccess(url, text)`** and **`OcrFailure(url, error)`**, so invalid
  states (both fields or neither) are impossible. Add more interface methods only when a caller needs them. Logic
  that belongs to another component (e.g. HTML formatting) stays in that component, using a `switch` on the type.
  This is the future payload of the "done OCR" message. For SQS it will be JSON via Jackson
  (`@JsonTypeInfo`/`@JsonSubTypes`, with a `status` field), agreed with the SQS side. It may move to `common` later.
- **`ImageDownloader`**: `BufferedImage download(String url) throws IOException`.
  Uses `java.net.http.HttpClient` (follows redirects, connect timeout and request timeout of about 15–30s). It fails
  with a clear message when the status is not 2xx, and when `ImageIO.read` returns `null` ("unsupported image format").
  It is a plain class with an overridable method, so tests can stub it.
- **`OcrEngine`** (interface): `String extractText(BufferedImage image) throws Exception`.
- **`TesseractOcrEngine implements OcrEngine`**: wraps `net.sourceforge.tess4j.Tesseract`, language `"eng"`.
  **Language data:** Tesseract needs a trained model file (`eng.traineddata`, the English model) to recognize text.
  The tess4j 5.20.0 jar already bundles it (verified: `tessdata/eng.traineddata` is inside the jar). The
  default constructor extracts it with tess4j's own `LoadLibrary.extractTessResources("tessdata")` and points
  `setDatapath` at that folder. There's no manual download and no extra dependency, and the same jar works on EC2.
  An optional `TESSDATA_PREFIX` env var overrides the path. Instances are **not thread-safe**, so each thread needs
  its own engine.
- **`Worker`**:
  - Constructor `(ImageDownloader, OcrEngine)`.
  - `OcrResult processUrl(String url)`: download, then OCR, returning `OcrSuccess`. It catches `Exception` and
    returns `new OcrFailure(url, ExceptionClass + ": " + message)`, so one bad URL never stops the batch, as the spec requires.
    This single-URL method is what the SQS loop will call later.
  - `List<OcrResult> processUrls(List<String> urls)`: loops over `processUrl`, sequentially in this stage.
    It returns results in input order.
  - `public static void main(String[] args)`: for local runs. With `args[0]`, a path to a URL file (one URL per line,
    blanks skipped), it builds a real engine, calls `processUrls`, and prints each result.

### Files
| File | Change |
|---|---|
| `worker/pom.xml` | Add `junit-jupiter` (test scope, version from the BOM) and `maven-shade-plugin` with `Main-Class=com.distributedsystems.worker.Worker`, producing a runnable fat jar (also needed in stage 2) |
| `pom.xml` (parent) | Pin `maven-shade-plugin` in `pluginManagement` |
| `worker/.../ocr/result/OcrResult.java`, `OcrSuccess.java`, `OcrFailure.java` | **new**: sealed interface + 2 records |
| `worker/.../download/ImageDownloader.java` | implement |
| `worker/.../ocr/OcrEngine.java` | implement interface |
| `worker/.../ocr/TesseractOcrEngine.java` | implement |
| `worker/.../Worker.java` | `processUrl`, `processUrls`, `main` |
| `worker/src/test/.../ocr/TesseractOcrEngineTest.java` | Render "HELLO WORLD" into a `BufferedImage` with `Graphics2D`, OCR it, and assert the result contains it (no downloaded image needed) |
| `worker/src/test/.../WorkerTest.java` | **new**: stub downloader + stub engine covering **both a success case and a failure case**: success URLs return the stub text, a failing URL (downloader throws) yields a `failure` result, and an OCR-engine exception also yields `failure`. The batch continues after a failure and the order is preserved |
| `worker/src/test/resources/sample-urls.txt` | **new**: a few URLs, ideally the assignment's sample input, plus one broken URL |

### Execution order
0. Before any code:
   1. Save this plan as `specs/worker-stage1.md`, a new `specs/` folder at the repo root.
   2. Record in memory the preference: "after plan approval, first write the plan into `specs/`".
1. (No tessdata download needed. The model ships inside the tess4j jar.)
2. `OcrEngine` interface, then `TesseractOcrEngine`, then `TesseractOcrEngineTest`. Add `junit-jupiter` to
   `worker/pom.xml`. Run `mvn -pl worker test`, which proves Tesseract plus its native DLLs work locally.
3. `OcrResult` sealed interface + `OcrSuccess` / `OcrFailure` records.
4. `ImageDownloader`.
5. `Worker.processUrl` / `processUrls`, then `WorkerTest` with stubs. Run `mvn -pl worker test`.
6. `Worker.main` plus `sample-urls.txt`. Run it locally on the sample file.
7. Add the shade plugin, run `mvn -pl worker package`, then
   `java -jar worker/target/worker-1.0-SNAPSHOT.jar worker/src/test/resources/sample-urls.txt`.
8. Commit on `ec2-ocr`, after the user asks.

### Verification
- `mvn -pl worker test`: the OCR test reads back the rendered text, and the WorkerTest success and failure cases pass.
- A run of the fat jar on `sample-urls.txt` prints the OCR text for valid image URLs and
  `failure: <ExceptionClass>: <msg>` for the broken URL, without crashing.

---

## Stage 2 — Worker on EC2 (outline; detail it after stage 1 works and the lecture notes are shared)
1. **New `common` module**, added to the parent `<modules>`, with AWS SDK `ec2` 2.57.0. Coordinate with the friend,
   who may put the SQS helpers here too.
   - `Ec2Launcher.launch(role, count, userData)`:
     1. Calls `RunInstances` with the AMI id, an instance type (e.g. `t2.micro`/`t3.micro`), the
        `LabInstanceProfile` IAM profile, and base64-encoded user-data.
     2. Tags each instance `Role=worker|manager`.
     3. Before launching, counts running instances and refuses to go past **19**.
   - `findRunning(role)`: uses the tag filter, for "is a Manager running" later.
2. **Packaging/bootstrapping:**
   1. Upload the worker fat jar to an S3 bucket under a single prefix, so cleanup is easy.
   2. A user-data bash script (Ubuntu): install Java 21 and the Tesseract native library (`libtesseract5`), pull
      the jar from S3, then run it. The tess4j jar bundles native binaries only for Windows and macOS, so on Linux
      the native library must come from the OS package manager. The language data still comes from the jar.
   3. Prefer a custom AMI with everything preinstalled if the lab allows it (faster boot, which matters for time
      and cost). Otherwise use user-data only.
3. **Credentials:** never put credentials inside the jar. Locally, use `~/.aws/credentials`. On EC2, use the
   instance profile (the IAM role).
4. **Smoke test:**
   1. Launch one worker from a small Java main in `common`.
   2. The worker runs `processUrls` on URLs baked into the user-data or args, and logs or uploads the results to S3.
   3. Inspect the results, then terminate the instance. Verify in the console that nothing is left running.
5. **After that (joint work with the friend):** replace `main` with the SQS loop: receive → `processUrl` → send
   the result → delete the message, with the visibility timeout handled.

**Open items for stage 2:**
- The lecture notes (AMI and region, LabRole/LabInstanceProfile name, preferred distro).
- The assignment's sample input file.
