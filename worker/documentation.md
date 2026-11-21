# `worker`: the hands that do the actual OCR

## 1. The role of the worker, in plain words

A worker is a small program that runs on its own EC2 machine (`t3.micro`). There can be several of them at once
(up to 8 in our setup). They are all identical and they all do the same, very simple thing, forever:

> Take **one** image task from the task queue → download the image → read its text with Tesseract → send the text
> (or the error) back to the manager → **only then** remove the task from the queue → repeat.

The worker knows nothing about clients, jobs, `n`, S3 or other workers. It doesn't even know how many workers
exist. It only knows two queues: where tasks come from and where results go. That simplicity is what makes it
easy to scale (just start more identical workers) and easy to recover (any worker can do any task).

An analogy: workers are cashiers in a supermarket with **one shared line**. Whoever is free calls the next
customer. Nobody assigns customers to cashiers, so a fast cashier naturally serves more people, and if one cashier
faints, the customer they were serving goes back to the front of the line.

### Where it sits in the flow
```
                     ocr-manager-to-workers-queue
   manager ──IMAGE_TASK###clientId###index###url──▶ ┌────────┐
                                                    │ worker │ ──HTTP GET──▶ the image on the internet
   manager ◀──OCR_DONE / OCR_ERROR───────────────── │  (×N)  │
                     ocr-workers-to-manager-queue   └────────┘
```
In the assignment's numbered flow, the worker does steps **7–10**: get a message, download the image, run OCR,
put the result on a queue.

### How a worker comes to life and dies
1. The **manager** calls `EC2Service.launch(WORKER, ...)` with the boot script from `UserData.worker(...)`.
2. EC2 boots the instance from our AMI `ocr-base-ami` (Ubuntu + Java 21 + Tesseract 5.5 + AWS CLI).
3. The boot script downloads `jars/worker.jar` from S3 and runs `java -jar worker.jar` in the background.
4. The worker loops until the instance is terminated.
5. The **manager** terminates the instance when it shuts down. The worker never stops by itself.

Measured: about **55 s** from launch until the first result (boot + jar download), then a few seconds per image.

---

## 2. Class map

```
WorkerApp (main)
   │ builds
   ├── Worker ──────────┬── ImageDownloader      (HTTP download → BufferedImage)
   │                    └── OcrEngine (interface)
   │                            └── TesseractOcrEngine   (BufferedImage → text)
   └── WorkerLoop ── uses SQSService, MessageProtocol, OcrResult   (from common)
```

| Class | One-line job |
|---|---|
| `WorkerApp` | Entry point: chooses the mode, wires everything, handles shutdown. |
| `WorkerLoop` | The "take a task, do it, report, delete" loop, with the visibility heartbeat. |
| `Worker` | Does one URL: download + OCR. Never throws: errors become an `OcrFailure`. |
| `download.ImageDownloader` | Downloads a URL and decodes it into an image. |
| `ocr.OcrEngine` | Interface: "image in, text out". |
| `ocr.TesseractOcrEngine` | The real implementation, using Tesseract through tess4j. |

From `common`: `SQSService` (queues), `MessageProtocol` (message format), `OcrResult` / `OcrSuccess` /
`OcrFailure` (the result type and its `toMessage`).

The classes are split by **responsibility**: `Worker` knows nothing about SQS, and `WorkerLoop` knows nothing
about HTTP or Tesseract. So `Worker` can be tested with fake downloaders/engines, and `WorkerLoop` with a fake
queue and a fake `Worker`.

---

## 3. Deep dive

### 3.1 `WorkerApp`: the entry point

The jar's `Main-Class` (set in `worker/pom.xml`). It has two modes:

| Command | Mode | Used for |
|---|---|---|
| `java -jar worker.jar` | **SQS mode** | What runs on EC2. |
| `java -jar worker.jar local <url-file>` | **Local debug mode** | OCR a file of URLs on your PC and print the results. No AWS at all. |
| anything else | prints usage, exits with code 1 | |

**SQS mode (`runSqs`), step by step**
1. Region = env var `AWS_REGION`, default `us-east-1`.
2. Build a `Worker` with a real `ImageDownloader` and `TesseractOcrEngine`.
3. Open an `SQSService` and look up (or create) the two queues by their constant names, so the worker needs **no
   configuration** besides the region. If the worker happens to be the first to create a queue, it uses a 60 s
   visibility timeout.
4. Build a `WorkerLoop`.
5. Register a **JVM shutdown hook** (code the JVM runs when the process is asked to stop, e.g. by `SIGTERM` when
   EC2 terminates the instance). The hook calls `loop.stop()` and waits up to **25 s** for the main thread, so
   the current task can finish cleanly. 25 s covers one 20 s long poll plus a margin.
6. `loop.run()` (blocks until stopped).

If the worker is killed hard anyway, nothing is lost: the task it was doing was not deleted, so it reappears for
another worker (see 3.2).

**Local mode (`runLocal`)**: reads the file, trims lines, skips blanks, calls `worker.processUrls(urls)`, prints
each result and a summary line (`Processed N URLs (k failed) in X ms`).

### 3.2 `WorkerLoop`: the heart of the worker

**What it does.** Runs the consumer loop. This is where the assignment's fault-tolerance requirements live.

**The loop (`run`)**
```
backoff = 1 s
while running:
    try:
        messages = receive up to 1 message (long poll, waits ≤ 20 s)
        for each message: handle(message)
        backoff = 1 s                          // a successful round resets it
    catch any exception:                       // SQS / network trouble
        log, sleep(backoff), backoff = min(backoff × 2, 30 s)
```
- **Why only 1 message per receive.** A receive hides the messages it returns. If a worker took 10 tasks at once,
  it would hide all 10 while slowly doing them one by one, and idle workers would find the queue "empty". Taking
  1 keeps the remaining tasks visible to everyone, so all workers stay busy. This is the answer to the
  assignment's question *"Are all your workers working hard? Or some are slacking?"*.
- **Why exponential backoff.** If SQS or the network is down, retrying immediately in a tight loop would waste
  CPU and requests. Waiting 1 s, 2 s, 4 s ... up to 30 s is polite and still recovers quickly.
- **`running` is `volatile`** because it is written by the shutdown-hook thread and read by the loop thread;
  `volatile` guarantees the loop sees the change.

**Handling one message (`handle`)**
1. **Parse and validate.** The body must be `IMAGE_TASK###clientId###taskIndex###url`: exactly 4 fields, the
   right type, and a non-negative integer index. If not, it is a **poison message**: no retry could ever succeed,
   so it is **logged and deleted** (otherwise it would come back every 30–60 s forever).
2. **Start the heartbeat** for this message (see below).
3. `result = worker.processUrl(url)`. This **never throws**: a bad image gives an `OcrFailure`, not an exception.
4. `sqs.sendMessage(resultsQueue, result.toMessage(clientId, taskIndex))`: `OCR_DONE` or `OCR_ERROR`.
5. `sqs.deleteMessage(tasksQueue, message)`: **only after** the result was sent.
6. `finally`: stop the heartbeat.

**Why "send, then delete" (and not the other way round)**

| The worker dies ... | Effect |
|---|---|
| before sending | The task was not deleted → it reappears after the visibility timeout → another worker does it. Nothing lost. |
| after sending, before deleting | The result is already with the manager, and the task also reappears → it is done a second time → the manager gets a **duplicate** result and ignores it (it already has that index). Nothing lost, nothing wrong. |
| after deleting | Everything is done. |

So in every case each image ends up with exactly one recorded result. "Delete first" would instead risk losing a
task forever. This is the assignment's *"If a worker stops working unexpectedly before finishing its work on a
message, some other worker should be able to handle that message."*

If **sending** or **deleting** throws (SQS trouble), the exception goes up to `run`, which backs off and goes on.
The task was not deleted, so it will be redone. `WorkerLoopTest.failedSendLeavesTaskInQueue` checks exactly this.

**The visibility heartbeat**

Problem: the task queue hides a received message for its visibility timeout (e.g. 60 s). A slow download plus OCR
could take longer. The message would then reappear while this worker is still working on it, and a second worker
would do the same image again (wasted work).

Solution: while a task is in progress, a background thread calls
`changeMessageVisibility(message, 60 s)` **every 30 s**, i.e. "hide it for another 60 s from now". As long as
the worker is alive, the task stays hidden. If the worker dies, the heartbeat dies with it and the task
reappears within 60 s.

Implementation details:
- One `ScheduledExecutorService` with a **single daemon thread** named `visibility-heartbeat`. Daemon = it never
  keeps the JVM alive by itself.
- `scheduleAtFixedRate(..., 30 s, 30 s)` starts the first beat 30 s after the task starts.
- The heartbeat's code catches every exception and only logs it. This is important: in Java, if a scheduled task
  throws once, the scheduler silently cancels **all** its future runs.
- `heartbeat.cancel(false)` in `finally` stops it as soon as the task ends.
- The heartbeat period is a constructor parameter (package-private constructor) so tests can use 50 ms instead of
  30 s.

**Threads in the worker:** exactly two. The main thread runs the loop and the OCR; the heartbeat thread only
extends visibility. There is deliberately **no** thread pool for OCR: a `t3.micro` has 2 vCPUs, Tesseract
instances are not thread-safe, and the design scales by **adding instances**, not threads. This is the answer to
*"Threads in your application, when is it a good idea?"* for the worker.

**`stop()` / `close()`**: `stop` sets `running = false` (the loop ends after the current task and long poll).
`close` also shuts the heartbeat thread down.

### 3.3 `Worker`: one URL in, one result out

**What it does.** The business logic, without any queue:
```java
public OcrResult processUrl(String url) {
    try {
        BufferedImage image = downloader.download(url);
        String text = ocrEngine.extractText(image);
        return new OcrSuccess(url, text);
    } catch (Exception e) {
        return new OcrFailure(url, describe(e));
    }
}
```
- **It never throws.** Any exception (bad URL, 404, timeout, not an image, OCR failure) becomes an
  `OcrFailure`. This is the assignment's *"If an exception occurs, the worker should recover from it, send a
  message to the manager ... together with a short description of the exception, and continue working on the next
  message."*
- `describe(e)` builds the short description: `ExceptionClass: message`, or just the class name if there is no
  message. Examples seen in real runs: `IOException: HTTP 404`, `ConnectException`.
- `processUrls(list)` simply calls `processUrl` for each URL, in order. Only the local debug mode uses it.
- The downloader and the engine are passed in through the constructor (**dependency injection**), so tests can
  pass fakes.

### 3.4 `ImageDownloader`: getting the image

**What it does.** `BufferedImage download(String url) throws IOException`.

1. Build an HTTP `GET` request. An invalid URL (`URI.create` fails) becomes `IOException("Invalid URL: ...")`.
2. Send it with Java's built-in `HttpClient`:
   - **connect timeout 15 s**, **request timeout 30 s**: a dead server can't block the worker forever;
   - **follows redirects** (`Redirect.NORMAL`, e.g. http → https);
   - header `User-Agent: ocr-worker/1.0` (some servers reject requests without one).
3. Any status outside 200–299 → `IOException("HTTP <status>")`.
4. Decode the bytes with `ImageIO.read`. It returns `null` if the bytes are not an image format Java understands
   (e.g. an HTML error page served with status 200) → `IOException("Unsupported image format")`.

The image is only held in memory; nothing is written to disk. The class is not `final` and `download` can be
overridden, so tests can replace it with a stub.

### 3.5 `OcrEngine` and `TesseractOcrEngine`: reading the text

`OcrEngine` is a one-method interface: `String extractText(BufferedImage image) throws Exception`. `Worker`
depends only on this interface, so the OCR library could be swapped (javaOCR, Asprise) without touching `Worker`.

`TesseractOcrEngine` is the implementation, using **tess4j** (a Java wrapper around the native Tesseract library):
- **Language model.** Tesseract needs a trained model file, `eng.traineddata`. The tess4j jar already contains
  one. On startup, `LoadLibs.extractTessResources("tessdata")` extracts it from the jar to a temp folder, and
  Tesseract is pointed there. So no manual download is needed. The env var `TESSDATA_PREFIX` can override the
  folder.
- **Native library.** tess4j bundles the native Tesseract binaries for Windows and macOS only. On Linux (EC2) it
  uses `libtesseract` installed in the AMI (`tesseract-ocr` 5.5.0).
- `extractText` rejects `null`, runs `doOCR`, and **trims** the result.
- **Not thread-safe**: one instance per thread. The worker has only one OCR thread, so one instance.

Known accuracy limit (not a bug): on the assignment's sample, the second image reads `3 = a = i ote` where the
sample output shows `PEACE`. That's Tesseract's accuracy on a stylised image, not a pipeline problem.

---

## 4. How the worker is tested

| Test | What it checks |
|---|---|
| `TesseractOcrEngineTest` | Draws "HELLO WORLD" into an image in code, OCRs it, expects the text back (proves Tesseract and its native library load); `null` image is rejected. |
| `WorkerTest` (fake downloader + fake engine) | Success returns the text; a download failure and an OCR failure both become `OcrFailure`; a batch continues after a failure and keeps the order; empty batch → empty list. |
| `WorkerLoopTest` (fake in-memory `SQSService`) | Valid task → result sent, then task deleted (in that order); failed OCR → `OCR_ERROR` sent and task deleted; malformed message → deleted, no result; **failed send → task stays in the queue**; slow task → visibility is extended repeatedly and the heartbeat stops afterwards. |

Real-world tests done (see `specs/worker-stage2.md`):
- local worker + real SQS: 3/3 results with correct client and index, task queue empty afterwards;
- **kill test**: a worker hard-killed mid-task → the task reappeared after the visibility timeout → another worker
  did it → exactly one result per index;
- EC2 via `FakeManager`: 1 × `t3.micro`, 24 sample URLs → 24/24 results (12 OK, 12 dead links) in 80 s.

---

## 5. Things worth knowing

- **Heartbeat timing vs. the queue's real visibility timeout (to verify).** The heartbeat assumes the task queue
  hides a message for **at least ~60 s**: its first beat comes 30 s after receiving. But `getOrCreateQueue` keeps
  the settings of whoever created the queue first, and the manager creates it with **30 s**. If the queue really
  has 30 s, the first heartbeat races with the expiry: a task that takes more than ~30 s could briefly become
  visible and be done twice. The result would still be correct (the manager ignores duplicates), only some work
  is wasted. Check the real value with `aws sqs get-queue-attributes`; a fix would be a shorter first beat, or
  making the manager create the queue with 60 s.
- **Workers never stop by themselves.** Only the manager terminates them. If the manager dies, its workers keep
  running (and costing money) until someone terminates them by hand. They do keep processing tasks from the
  shared queue, so they still help a replacement manager, but that manager doesn't know them and won't terminate
  them (it only terminates workers it launched itself).
- **The worker doesn't know `n`.** `n` only decides how many workers the manager starts. Which worker does which
  task is decided by "who asks first", as the assignment describes.
- **Logs** on the instance: `/var/log/ocr-bootstrap.log` (boot script) and `/var/log/worker.log` (the program).
