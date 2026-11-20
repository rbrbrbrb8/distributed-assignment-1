# Checkpoint: how the OCR system works (2026-11-20)

A short map of the whole system, to understand what each component and class is for. Items marked **TODO** have
a known purpose but aren't built yet. Details live in the other files in `specs/`.

---

## 1. The idea in one paragraph
A user runs the **local app** on their own PC with a file of image URLs. The local app uploads the file to **S3**
and asks a **manager** (an EC2 instance) to process it. The manager splits the file into one task per URL, starts
enough **workers** (EC2 instances), and puts the tasks on a queue. Each worker takes one task at a time, downloads
the image, runs OCR (Tesseract), and sends the text back. When all of a client's URLs are done, the manager writes
a **summary** to S3 and tells the local app, which turns it into an **HTML page**.

## 2. The big picture
```
 YOUR PC                                   AWS
┌────────────┐  NEW_TASK / TERMINATE   ┌──────────────┐  IMAGE_TASK   ┌───────────┐
│ local app  │ ─────────────────────▶  │   manager    │ ────────────▶ │ worker ×N │
│            │  (ocr-app-to-manager)   │   (1 EC2)    │ (manager-to-  │  (EC2)    │
│            │                         │              │   workers)    │           │
│            │ ◀───────────────────── │              │ ◀──────────── │           │
└────────────┘  JOB_DONE               └──────────────┘  OCR_DONE /   └───────────┘
      │         (private queue          │      │           OCR_ERROR       │
      │          per job)               │      │      (workers-to-manager) │
      ▼                                 ▼      ▼                           ▼
   S3: jobs/<clientId>/input.txt  →  read by manager      images are downloaded from the internet
       jobs/<clientId>/summary.txt ← written by manager
```
- **SQS queues** carry small messages. **S3** carries files. **EC2** runs the manager and the workers.
- **One shared inbound queue** for all local apps, and **one private response queue per job**, so apps never steal
  each other's `JOB_DONE`.
- Everything sits in one bucket and one region (`us-east-1`). Credentials: `~/.aws/credentials` on the PC, the IAM
  role `LabInstanceProfile` on EC2. **No keys in code, jars or AMI.**

## 3. Modules and their important classes

### `common`: shared building blocks (used by all three apps)
| Class | Purpose |
|---|---|
| `aws.SQSService` | Thin SQS wrapper: create/find queues, send (also batches), long-poll receive (20 s), delete, change visibility. Defines the 3 fixed queue names. |
| `aws.S3Service` | Thin S3 wrapper **and the single source of truth for the bucket layout** (`inputKey`, `summaryKey`, `jobPrefix`, `jarKey`). Upload/download as file or string, `deletePrefix` for cleanup. |
| `aws.EC2Service` | Launch instances (AMI + `t3.micro` + IAM role + boot script + `Role` tag), find active ones by role, terminate. Enforces the instance cap. `isActive(id)` checks one instance. |
| `aws.UserData` | Builds the boot scripts EC2 runs at startup: download a jar from S3 and start it (`worker(...)`, `manager(...)`). Validates inputs against shell injection. |
| `aws.MessageProtocol` | **The message contract**: builds and parses every SQS message (`###`-separated text; OCR text is Base64 so it can't break the format). |
| `ocr.result.OcrResult` | Sealed interface: "the outcome for one image URL", either `OcrSuccess(url, text)` or `OcrFailure(url, error)`. `toMessage(clientId, index)` turns it into a worker→manager message. Shared by worker and local app. |

### `worker`: runs on EC2, many copies
| Class | Purpose |
|---|---|
| `WorkerApp` | Entry point. `java -jar worker.jar` = SQS mode; `local <file>` = debug mode. Registers a shutdown hook. |
| `WorkerLoop` | The consumer loop: receive **1** task, process it, send the result, **then** delete the task. A heartbeat thread extends the visibility while working. Malformed messages are deleted. SQS errors are retried with backoff. |
| `Worker` | Download plus OCR for one URL. **Never throws**: any exception becomes an `OcrFailure`. |
| `download.ImageDownloader` | HTTP download (timeouts, redirects), decodes the image. |
| `ocr.TesseractOcrEngine` | OCR via tess4j. The English model comes from inside the jar; on Linux the Tesseract library comes from the AMI. |

### `manager`: runs on EC2, one instance (the friend's module)
| Class | Purpose |
|---|---|
| `ManagerMain` | Entry point. Reads bucket and worker AMI (env or args; the boot script passes both), creates queues and clients, starts the engine. |
| `ManagerEngine` | Two threads: **client listener** (`NEW_TASK`, `TERMINATE`) and **worker listener** (results). Each new job is set up on a thread pool, so jobs are handled in parallel. On `TERMINATE`: re-tag itself `Role=TERMINATING_MANAGER`, stop accepting, finish active jobs, terminate its workers, then itself. Results for clients it never had are left in the queue (they may belong to another manager). |
| `ClientJob` | State of one client's job: URLs, results by index (duplicates ignored), "is it done?". Builds the **summary**: one line per URL, `url \| OCR: <Base64>` or `url \| ERROR: <Base64>`. |
| `WorkerScaler` | Needed workers = `ceil(images / n)`, capped at `maxWorkers` (default 8); launches only the missing ones. |
| `EC2ComputeClient` / `S3StorageClient` | AWS implementations of the engine's `ComputeClient` / `StorageClient` interfaces (so the engine can be tested without AWS). Keeps the manager's **`ActiveClients` tag** up to date. Counts and terminates **only the workers this manager launched**. |

### `local-app`: runs on the user's PC
| Class | Purpose |
|---|---|
| `LocalAppArgs` | Parses and validates `inputFile outputFile manager-clients-n n [terminate]`. |
| `LocalAppConfig` | Reads `local-app.properties` (bucket, AMI ID, region, timeout). No secrets. |
| `summary.SummaryParser` / `Base64SummaryParser` | Interface plus implementation: summary text → `List<OcrResult>`. Swappable if the format changes. |
| `HtmlReport` | `List<OcrResult>` → the output HTML (sample layout; a `switch` over the result types; everything escaped). |
| `manager.ManagerLocator` (+ `ManagerHandle`, `Ec2ManagerLocator`) | Finds an *available* manager (`ActiveClients < manager-clients-n`, missing tag = 0, least loaded first) or starts one; fails clearly at the instance cap. `isAlive` checks that "my" manager is still running. |
| `LocalApp` / `LocalAppMain` | The run flow in section 4 (find/start a manager, upload, `NEW_TASK`, wait with liveness checks and resend, HTML, `TERMINATE`, cleanup in `finally`), plus the jar's entry point. |

## 4. Workflows

### A job, from start to finish
1. **Local app:**
   1. validates the arguments and reads the settings;
   2. creates a `clientId` (UUID);
   3. finds or starts a manager (`ManagerLocator`).
2. **Local app:**
   1. uploads the input to `jobs/<clientId>/input.txt`;
   2. creates its private queue `ocr-client-response-<clientId>`;
   3. sends `NEW_TASK###clientId###responseQueueUrl###inputKey###n`.
3. **Manager:**
   1. downloads the input;
   2. starts `ceil(urls / n) − running` workers;
   3. sends one `IMAGE_TASK###clientId###index###url` per URL.
4. **Each worker** takes one task, OCRs it, and sends `OCR_DONE###clientId###index###url###<Base64 text>` or
   `OCR_ERROR###…###error`. Only then does it delete the task.
5. **Manager** stores each result by `(clientId, index)` and ignores duplicates. When a job is complete it:
   1. writes `jobs/<clientId>/summary.txt`;
   2. sends `JOB_DONE###summaryKey` to that client's private queue.
6. **Local app:**
   1. receives `JOB_DONE`;
   2. downloads and parses the summary;
   3. writes the HTML;
   4. sends `TERMINATE` if asked;
   5. deletes its queue and its `jobs/<clientId>/` folder.

   (While waiting, every 60 s, it checks that its manager is still running; if it's gone, it starts a new one and
   resends `NEW_TASK`. `TERMINATE` and cleanup also run on failure or timeout.)

### When something fails
- **Bad image or dead link:** the worker reports `OCR_ERROR` and continues. The HTML shows "link + short error".
- **Worker crashes mid-task:** the task was never deleted, so after its visibility timeout another worker takes it.
  Tested by killing a worker.
- **Worker is slow but alive:** the heartbeat keeps the task hidden, so it isn't processed twice.
- **Duplicate results:** the manager ignores a second result for an index it already has.
- **Results arrive out of order:** the index puts each one on the right line.
- **Manager dies while a client waits (issue 9):** the local app notices the instance is gone,
  starts a new manager and **resends** `NEW_TASK`. The job restarts from scratch. A duplicate `NEW_TASK` for the
  same client is harmless: the manager replaces the job, and results are stored by index.
- **Manager is terminating when a `NEW_TASK` arrives (issue 7):** it doesn't take the task; the task reappears
  after 30 s. The terminating manager is tagged `Role=TERMINATING_MANAGER`, so no new app picks it. Once it's
  gone, the waiting app starts a new manager (same rule as above).
- **Two managers at once (issue 6):** each one leaves the other's results in the queue and only terminates its
  own workers.

### Termination
A local app started with `terminate` sends `TERMINATE` after getting its own results. The manager stops reading
new jobs, re-tags itself `Role=TERMINATING_MANAGER` (so new apps start a new manager instead), finishes the
active jobs, terminates the workers it launched, then terminates itself.

## 5. Where things live
- **S3 bucket** (one per AWS account):
  ```
  jars/worker.jar, jars/manager.jar
  jobs/<clientId>/input.txt, summary.txt
  test-data/
  ```
- **AMI `ocr-base-ami`**: Ubuntu 26.04, Java 21, Tesseract 5.5, AWS CLI. Shared by manager and workers. No jars
  inside: each instance downloads its jar from S3 at boot.
- **Instance cap:** we run **at most 9 instances** (1 manager + up to 8 workers, the manager's `maxWorkers`
  default). Enforced in one place, `EC2Service.MAX_INSTANCES = 9`, counted **globally** (every active instance in
  the account, all roles). At the cap, `launch` starts fewer instances or none, without throwing; see issue 10 for
  what that means for workers and managers.

## 6. Open items (details in `specs/open-issues.md`)
| What | Owner | Status |
|---|---|---|
| Manager locator (`ManagerLocator`) | user | **DONE** (PR 2, step 7) |
| Local app run flow (`LocalApp`, `LocalAppMain`) | user | **DONE** (PR 2, step 8); real-AWS runs in steps 9–10 |
| Instance cap 9 in `EC2Service.MAX_INSTANCES` (global) | user | **DONE** (PR 2) |
| Manager started by the local app gets no worker AMI ID (issue 8) | user | **DONE** (PR #10, M1) |
| A terminating manager still looks available (issue 5) | user | **DONE** (PR #10, M2: `Role` tag) |
| Two managers alive at once clash (issue 6) | user | **DONE** (PR #10, M5) |
| `TERMINATE` + `NEW_TASK` race (issue 7) | user | **DONE by design** (manager change dropped); local-app side in step 8 |
| A manager crash loses the job (issue 9) | user | **DONE** (step 8: the local app resends `NEW_TASK`) |
| The local app's choice of manager isn't enforced, shared inbound queue (issue 10) | both | **OPEN**, accepted limit (fix if ever: a queue per manager) |
| Fall back to the least-loaded manager when no instance can be launched (issue 10) | user | **Future TODO**, not planned now |
| README (design answers, AMI/type, timings, n) | both | **TODO** |

## 7. Further reading
- `specs/worker-stage1.md`, `specs/worker-stage2.md`: the worker, EC2 bootstrapping, measured runs.
- `specs/s3-service.md`: bucket layout and `S3Service`.
- `specs/local-app.md`: local-app design and step list.
- `specs/open-issues.md`: everything still to decide between the local app and the manager.
- `specs/manager-fixes.md`: the manager fixes M1–M5 (what was done, what was dropped and why).