# `common`: the shared toolbox and the shared language

## 1. The role of `common`, in plain words

The system has three programs that run on different machines:
- the **local app** (your PC),
- the **manager** (one EC2 instance),
- the **workers** (many EC2 instances).

They never call each other directly. They only talk through **AWS**:
- **SQS** queues carry small text messages ("here is a job", "this image is done"),
- **S3** stores files (the input URL list, the summary),
- **EC2** is where the manager and workers run.

`common` is **not a program**. It has no `main` and never runs on its own. It is a library jar that is
packed **inside** each of the three programs' jars. It gives them two things:

1. **A toolbox for AWS:** small wrapper classes (`SQSService`, `S3Service`, `EC2Service`, `UserData`) so that no
   other module talks to the raw AWS SDK for everyday operations.
2. **A shared language:** `MessageProtocol` (what a message looks like) and the `OcrResult` types (what "the
   result for one image" is). Because the sender and the receiver use the *same* code to build and read a
   message, they cannot disagree on the format.

An analogy: if the three programs are offices in different cities, `common` is the company's standard set of
forms (`MessageProtocol`), its filing rules (`S3Service`'s folder layout), and its phone/mail/HR department
(`SQSService`, `S3Service`, `EC2Service`).

```
          ┌──────────── local-app.jar ───────────┐   ┌──── manager.jar ────┐   ┌──── worker.jar ────┐
          │  LocalApp, HtmlReport, ...           │   │  ManagerEngine, ... │   │  WorkerLoop, ...   │
          │  ┌────────── common ──────────┐      │   │  ┌──── common ───┐  │   │  ┌──── common ───┐ │
          │  │ SQSService S3Service       │      │   │  │      ...      │  │   │  │      ...      │ │
          │  │ EC2Service UserData        │      │   │  └───────────────┘  │   │  └───────────────┘ │
          │  │ MessageProtocol OcrResult  │      │   └─────────────────────┘   └────────────────────┘
          │  └────────────────────────────┘      │
          └──────────────────────────────────────┘
```

### Who uses what

| Class | local app | manager | worker |
|---|:---:|:---:|:---:|
| `SQSService` | yes | yes | yes |
| `S3Service` | yes (upload input, download summary, clean up) | yes (download input, upload summary) | no |
| `EC2Service` | yes (find / start a manager) | yes (start / count / stop workers, stop itself) | no |
| `UserData` | yes (boot script for a manager) | yes (boot script for workers) | no |
| `MessageProtocol` | yes | yes | yes |
| `OcrResult` / `OcrSuccess` / `OcrFailure` | yes (summary → HTML) | no (it works with raw message fields) | yes (download + OCR result → message) |

Note that the **worker** needs very little: only SQS and the message format. It never touches S3 or EC2. That
keeps the many worker instances simple.

### Credentials: none here
No class in `common` contains or accepts AWS keys. Every AWS client is built with only a `Region`, so the AWS
SDK finds credentials itself through its **default credential chain**:
- on your PC: `~/.aws/credentials`;
- on EC2: the instance's IAM role (`LabInstanceProfile` → `LabRole`), which `EC2Service` attaches to every
  instance it launches.

So no key is ever in the code, in a jar, in a boot script or in the AMI. This is the project's answer to the
assignment's security section.

#### How the default credential chain works
Our clients are built with a region only (e.g. `SqsClient.builder().region(region).build()`), with no
credentials. The SDK then checks a fixed list of places, **in this order**, and uses the first one that has
credentials:

1. Java system properties (`-Daws.accessKeyId=...`)
2. Environment variables (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN`)
3. A web identity token (Kubernetes; not used here)
4. **The credentials file `~/.aws/credentials`** (profile `default`)
5. Container credentials (ECS; not used here)
6. **The EC2 instance metadata service**: an address (`169.254.169.254`) that answers only from inside an EC2
   instance and gives temporary credentials for the instance's IAM role

If none has credentials, the first AWS call fails.

| Where the code runs | Found at | Where they came from |
|---|---|---|
| Your PC (local app) | step 4 | You paste the AWS Academy session keys into `~/.aws/credentials`. |
| EC2 (manager, workers) | step 6 | `EC2Service.launch` attaches `LabInstanceProfile` (role `LabRole`); AWS issues and renews temporary keys for it. |

So the same jar works on both, with no keys inside it. AWS Academy keys expire after a few hours: AWS then
answers `ExpiredToken`, and the fix is to paste fresh keys into `~/.aws/credentials` (no code change).

Official reference: [Default credentials provider chain](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html)
(AWS SDK for Java 2.x Developer Guide).

---

## 2. Package `com.distributedsystems.aws`

### 2.1 `SQSService`: the mail room

**What it is.** A thin wrapper around the AWS `SqsClient`. Each method is one SQS API call with sensible
defaults. It also defines the names of the three fixed queues.

**The queues of the system**

| Constant | Queue name | From → to | Messages |
|---|---|---|---|
| `APP_TO_MANAGER_QUEUE` | `ocr-app-to-manager-queue` | every local app → every manager | `NEW_TASK`, `TERMINATE` |
| `MANAGER_TO_WORKERS_QUEUE` | `ocr-manager-to-workers-queue` | manager → all workers | `IMAGE_TASK` |
| `WORKERS_TO_MANAGER_QUEUE` | `ocr-workers-to-manager-queue` | all workers → manager | `OCR_DONE`, `OCR_ERROR` |
| *(not a constant)* | `ocr-client-response-<clientId>` | manager → **one** local app | `JOB_DONE` |

Why a private response queue per job: if all local apps shared one response queue, app A could receive app B's
`JOB_DONE`. With one queue per job, each app only ever sees its own answer. The local app creates it at the start
of a run and deletes it at the end.

**Background you need: how SQS works**
- SQS is **pull-only**. Nobody "pushes" a message to a program; the program must ask (`ReceiveMessage`).
- **Receiving does not remove a message.** It only hides it from everyone else for the queue's **visibility
  timeout** (e.g. 30 s). The receiver must later **delete** it explicitly. If the receiver crashes before
  deleting, the message becomes visible again and someone else receives it. This is the core of the system's
  fault tolerance.
- A standard queue delivers **at least once** (a duplicate is possible) and **does not keep order**.

**Methods**

| Method | What it does | Details worth knowing |
|---|---|---|
| `SQSService(Region)` | Builds the SDK client. | Credentials come from the default chain. |
| `protected SQSService()` | A constructor with **no** AWS client. | Exists only so tests can subclass `SQSService` with an in-memory fake (see `WorkerLoopTest`, `ManagerLocalTest`, `LocalAppTest`). |
| `getOrCreateQueue(name, visibilitySeconds)` | Returns the queue's URL, creating the queue if it doesn't exist. | **If the queue already exists, its settings are not changed.** The visibility timeout you pass only matters for whoever creates the queue first. New queues also get `ReceiveMessageWaitTimeSeconds=20` (long polling by default). |
| `createClientResponseQueue()` | Creates `ocr-client-response-<random UUID>`. | Only used by the dev test `SQSTest`. The local app names its queue after its `clientId` instead, so the queue, the S3 folder and the messages share one ID. |
| `sendMessage(url, body)` | Sends one message. | |
| `sendMessagesBatch(url, list)` | Sends many messages, **10 per API call** (the SQS limit). | Fewer API calls = cheaper and faster. If any entry of a chunk fails, it throws `RuntimeException`. Entries that did succeed are already in the queue, so a retry may create duplicates; the manager tolerates duplicates (results are stored by index). |
| `receiveMessages(url, max)` | **Long poll**: waits up to **20 s**, returns as soon as at least one message arrives, or an empty list after 20 s. | `max` is clamped to 1..10. Long polling means no busy loop and few empty (billed) requests. |
| `deleteMessage(url, message)` | Removes a message for good. | Uses the message's *receipt handle*, which identifies this particular receive. |
| `changeMessageVisibility(url, message, seconds)` | "I'm still working on it: hide it for N more seconds from now." | The worker's heartbeat calls this. |
| `getApproximateMessageCount(url)` | Number of visible messages (approximate). | Only used by `SQSTest`. The manager scales by the job's image count instead. |
| `deleteQueue(url)` | Deletes a queue. | Ignores "already deleted", so it's safe to call twice. |
| `close()` | Closes the HTTP client. | `AutoCloseable`, so it can be used in try-with-resources. |

### 2.2 `MessageProtocol`: the shared forms

**What it is.** A class of static methods that **build** and **read** every message in the system. The format is
plain text with fields separated by `###`:

```
NEW_TASK###<clientId>###<responseQueueUrl>###<inputKey>###<n>     local app → manager
TERMINATE                                                          local app → manager
IMAGE_TASK###<clientId>###<taskIndex>###<imageUrl>                 manager → worker
OCR_DONE###<clientId>###<taskIndex>###<imageUrl>###<Base64 text>   worker → manager
OCR_ERROR###<clientId>###<taskIndex>###<imageUrl>###<error text>   worker → manager
JOB_DONE###<summaryKey>                                            manager → local app
```

The first field is always the **type** (`MessageType` enum), so a receiver knows how to read the rest.

**Why each field exists**
- `clientId`: a UUID created by the local app. It ties everything of one run together: the S3 folder
  `jobs/<clientId>/`, the response queue, every task and every result. Many clients share the worker queues, so
  the manager uses it to know which job a result belongs to.
- `taskIndex`: the URL's position (0, 1, 2, ...) in the client's input file. Results come back **out of order**
  and possibly **twice**, and an input file can contain the **same URL twice**. The index tells the manager
  exactly which line a result fills, and lets it ignore a second result for a line that is already filled.
- `n` in `NEW_TASK`: `worker-files-n`, which the manager needs to compute how many workers to start.
- `responseQueueUrl` in `NEW_TASK`: where the manager should send `JOB_DONE` for this job.

**Why the OCR text is Base64-encoded**
OCR text can contain anything: line breaks, `###`, Unicode. If it were put in the message as-is, a `###` inside
the text would create an extra field and break parsing. Base64 turns any text into a string of only
`A–Z a–z 0–9 + / =`, which can never contain `###` or a newline. The text is encoded as UTF-8 bytes first, so
non-English characters survive.

Error descriptions are short, so instead of Base64 `createOcrErrorMsg` simply **cleans** them: it replaces
newlines and `###` with spaces, and uses `"Unknown processing exception"` if the error is empty.

**Methods**

| Method | Notes |
|---|---|
| `createNewTaskMsg(clientId, responseQueueUrl, s3InputKey, n)` | Rejects `n <= 0`. |
| `createTerminateMsg()` | Just `TERMINATE`. |
| `createImageTaskMsg(clientId, taskIndex, imageUrl)` | Rejects a negative index. |
| `createOcrDoneMsg(clientId, taskIndex, imageUrl, text)` | Base64-encodes the text (`null` → empty). |
| `createOcrErrorMsg(clientId, taskIndex, imageUrl, error)` | Cleans the error as described above. |
| `createJobDoneMsg(summaryKey)` | |
| `parse(raw)` | Splits on `###` and returns the fields as `String[]`. Uses `split(..., -1)` so empty trailing fields are kept (an empty OCR text still gives 5 fields). Throws on an empty message. |
| `parseNewTaskRatio(parts)` | Checks the message is a 5-field `NEW_TASK` and returns `n` (must be a positive integer). |
| `parseTaskIndex(parts)` | Returns field 2 as a non-negative integer. |
| `decodeOcrText(encoded)` | Base64 → UTF-8 text (empty → empty). |

All validation errors are `IllegalArgumentException`. Receivers use that to recognise a malformed ("poison")
message.

### 2.3 `S3Service`: the filing cabinet and its rules

**What it is.** A thin wrapper around the AWS `S3Client` for **one bucket**, plus — just as important — the
**single source of truth for where things live in the bucket**.

**The bucket layout**
```
<bucket>/
├── jars/                 worker.jar, manager.jar       (uploaded by developers)
├── jobs/<clientId>/      input.txt   (local app writes, manager reads)
│                         summary.txt (manager writes, local app reads)
└── test-data/            sample inputs for development
```
S3 has no real folders: a "folder" is just a key prefix ending in `/`. Grouping everything of one job under
`jobs/<clientId>/` has two benefits:
- two clients can never overwrite each other's files (each `clientId` is a fresh UUID);
- cleaning up a job is one call: delete everything under that prefix.

**Key helpers (static).** Nobody writes a key string by hand; everyone calls these, so the local app and the
manager always agree on paths:

| Helper | Result |
|---|---|
| `jobPrefix(clientId)` | `jobs/<clientId>/` |
| `inputKey(clientId)` | `jobs/<clientId>/input.txt` |
| `summaryKey(clientId)` | `jobs/<clientId>/summary.txt` |
| `jarKey(jarName)` | `jars/<jarName>` |

**Safety checks**
- `requireSafe`: a `clientId` or jar name may only contain `A–Z a–z 0–9 . _ -` and may not be `.` or `..`. So no
  ID can contain `/` and "escape" its own folder (e.g. `../other-client`).
- `requireFolderPrefix`: `deletePrefix` refuses an empty prefix, `/`, or anything not ending in `/`. A bug can
  therefore never delete the whole bucket or a random set of keys.

**Operations**

| Method | Used by | Notes |
|---|---|---|
| `uploadFile(key, path)` | local app (input) | |
| `uploadString(key, text)` | manager (summary) | Stored as UTF-8 with content type `text/plain; charset=utf-8`. |
| `downloadString(key)` | manager (input), local app (summary) | Decodes as UTF-8. |
| `deletePrefix(prefix)` | local app (cleanup) | Lists all keys under the prefix (paginated, so more than 1000 keys work), then deletes them in batches of up to 1000 (the S3 limit per call). Returns how many were deleted. |
| `downloadFile`, `exists`, `deleteObject`, `ensureBucketExists` | dev tools / tests | Not used in the production flow. `downloadFile` deletes an existing target first because the SDK refuses to overwrite. `exists` treats both `NoSuchKey` and a bare 404 as "no". |

AWS errors are **not** caught here: they go up to the caller, who knows what a failure means in its context.

### 2.4 `EC2Service`: hiring and firing machines

**What it is.** The only class that starts, finds and stops EC2 instances. Both the local app (for the manager)
and the manager (for workers) use it.

**How instances are recognised: tags.** Every instance is launched with two tags:
- `Role` = `MANAGER` or `WORKER` (enum `Role`), used to find instances again;
- `Name` = `ocr-manager` / `ocr-worker`, just for a readable AWS console.

The tags are set **inside** the launch request (`tagSpecifications`), so an instance never exists without its
tag. The manager later changes its own `Role` to `TERMINATING_MANAGER` when it shuts down (see the manager docs);
`findActive(MANAGER)` then no longer finds it.

**Constants**

| Constant | Value | Meaning |
|---|---|---|
| `MAX_INSTANCES` | `9` | Global cap: at most 9 pending/running instances **in the whole account**, all roles counted (1 manager + 8 workers). The assignment's hard limit is 19; the team uses 9. |
| `INSTANCE_TYPE` | `t3.micro` | Every instance (manager and workers). |
| `INSTANCE_PROFILE` | `LabInstanceProfile` | The IAM role attached to every instance; this is how code on EC2 gets AWS access without keys. |
| `ROLE_TAG` | `Role` | |
| `ACTIVE_CLIENTS_TAG` | `ActiveClients` | Tag the manager keeps on itself = how many jobs it serves now. The local app reads it to decide whether a manager is "available". |

**Methods**

| Method | What it does |
|---|---|
| `launch(role, count, amiId, userDataScript)` | Starts **up to** `count` instances. First counts every active instance in the account, then asks `allowedToLaunch` how many fit under the cap. If none fit, returns an empty list **without throwing**. Otherwise one `RunInstances` call with: the AMI, `t3.micro`, the IAM profile, the boot script **Base64-encoded** (EC2 requires that), and the tags. Returns the IDs actually launched (possibly fewer than asked). |
| `findActive(role)` | IDs of instances in state `pending` or `running` with tag `Role=<role>`. |
| `isActive(instanceId)` | Is this one instance still pending/running? Uses a filter instead of an ID lookup, so an unknown or long-gone ID returns `false` instead of throwing. The local app uses this as its "is my manager still alive?" check. |
| `getTag(instanceId, key)` | One tag's value, or empty. Also filter-based (no exception for unknown IDs). |
| `countAllActive()` | All pending/running instances, any role. |
| `terminate(ids)` | Terminates the given instances; does nothing for an empty list. |
| `allowedToLaunch(requested, active)` | Pure arithmetic: `min(requested, max(0, 9 − active))`. Package-private and separate from AWS so it can be unit-tested. |

All describe calls use the SDK **paginator**, so the result is complete even with many instances.

**Known limit (accepted):** counting and launching are two separate calls, so two launchers at the same instant
could together pass the cap. The assignment explicitly lets us assume no such races.

### 2.5 `UserData`: the instructions an instance follows when it wakes up

**What it is.** When EC2 starts an instance from our AMI, the machine has Java, Tesseract and the AWS CLI, but
no program of ours. *User data* is a shell script that EC2 runs once, as root, at first boot. `UserData` builds
that script.

**The script it produces** (for a worker):
```bash
#!/bin/bash
set -euo pipefail                                   # stop on the first error
exec >> /var/log/ocr-bootstrap.log 2>&1             # keep a log of the boot script itself
echo "bootstrap started $(date -u)"
mkdir -p /opt/ocr
/snap/bin/aws s3 cp s3://<bucket>/jars/worker.jar /opt/ocr/worker.jar --only-show-errors
cd /opt/ocr
nohup java -jar /opt/ocr/worker.jar >> /var/log/worker.log 2>&1 &
echo "started worker.jar (pid $!)"
```
- The jar is **downloaded from S3 at every boot** instead of being baked into the AMI. So a code change only
  needs a new jar in S3, never a new AMI.
- `aws s3 cp` works without keys because of the instance's IAM role.
- `/snap/bin/aws` is a full path because the AWS CLI was installed with snap, and snap's folder is not always on
  the boot script's `PATH`.
- `nohup ... &` runs the program in the background so the boot script can finish.

**Methods**
- `worker(bucket, jarKey)`: the script above; runs `worker.jar` with no arguments (= SQS mode).
- `manager(bucket, jarKey, workerAmiId)`: same, but runs `manager.jar <bucket> <workerAmiId>` and logs to
  `/var/log/manager.log`. The manager needs the AMI ID to start workers from the same image.
- `forJar(...)`: the shared template behind both.

**Safety:** every value pasted into the script (bucket, jar key, each argument) must match `[A-Za-z0-9._/-]+`.
Characters such as `;`, `$`, space or quotes are rejected, so a bad value can never inject an extra shell
command into a script that runs as root.

---

## 3. Package `com.distributedsystems.ocr.result`: "the result for one image"

### 3.1 `OcrResult` (sealed interface), `OcrSuccess`, `OcrFailure` (records)

**What they are.** The answer for one image URL is always exactly one of two things:
- `OcrSuccess(url, text)`: the image was downloaded and read; `text` may be empty if the image has no text;
- `OcrFailure(url, error)`: something went wrong; `error` is a short description such as `IOException: HTTP 404`.

**Why a sealed interface with two records**
- A **record** is an immutable data class: Java generates the constructor, the getters (`url()`, `text()`,
  `error()`), `equals`, `hashCode` and `toString`.
- **Sealed** (`permits OcrSuccess, OcrFailure`) means no third kind can exist. Code that handles a result with a
  `switch` (like the local app's `HtmlReport`) is checked by the compiler to handle both cases, and an invalid
  state ("success with an error" or "neither") cannot be represented.

**Methods**
- `url()`: the image URL (both kinds).
- `toMessage(clientId, taskIndex)`: turns the result into the worker → manager message. `OcrSuccess` calls
  `MessageProtocol.createOcrDoneMsg` (Base64 text), `OcrFailure` calls `createOcrErrorMsg` (cleaned error). The
  result doesn't know which client or line it belongs to, so the worker passes those in, copied from the
  `IMAGE_TASK` it received.

**Who uses them**
- the **worker** creates them (`Worker.processUrl`) and sends them (`toMessage`);
- the **local app** recreates them from the summary file (`Base64SummaryParser`) and renders them
  (`HtmlReport`).

Why they live in `common` and not in `worker`: the local app needs them too, and it must not depend on the
`worker` module, which would drag Tesseract and its native libraries into the local app. Behaviour that is part
of the shared contract (`url()`, `toMessage`) sits on the types; presentation that only one module owns (HTML)
stays in that module.

---

## 4. How `common` is tested

All unit tests run without AWS.

| Test | What it checks |
|---|---|
| `EC2ServiceTest` | The cap arithmetic: cap is 9, launches all when there's room, only up to the cap, nothing at or over the cap, the cap is global, 0 requested → 0, negative → error. |
| `S3ServiceTest` | Exact key strings; IDs like `../x`, `a/b`, empty, `null` are rejected; `deletePrefix` refuses `""` and `jobs` (no slash); bucket is required. |
| `UserDataTest` | Worker and manager scripts download the jar and start it with the right arguments; an unsafe AMI ID is rejected; values that could inject shell commands are rejected; the script contains no credentials. |
| `OcrResultMessageTest` | Success → `OCR_DONE` with Base64 text (round trip); empty text is valid; failure → `OCR_ERROR`; a multi-line error is flattened to one field. |

Dev programs in `src/test` that **do** call AWS (run by hand, never by `mvn test`):
- `SQSTest`: a manual round trip through the queues;
- `S3Test`: upload, check, download, compare, delete under `jobs/s3test-<uuid>/`;
- `FakeManager`: launches one real worker on EC2, sends it a URL file as tasks, collects the results, terminates
  the worker. This was the first end-to-end worker test on EC2.

---

## 5. Things worth knowing

- **First creator decides the queue settings.** `getOrCreateQueue` never changes an existing queue. Whatever a
  queue got when it was first created is what it has now.
  - **The values asked for:** both the manager and the worker ask for **60 s** on the two worker queues. The
    inbound queue gets 30 s.
  - **Before the fix:** the manager asked for 30 s on the worker queues, which on a fresh account would have
    raced with the worker's heartbeat (e2e test T10).
  - **In our account:** all queues were checked on 2026-11-21. The worker queues are 60 s and the inbound queue is
    30 s.
- **The instance cap counts everything in the account**, including instances that have nothing to do with this
  project.
- **Some methods exist only for dev tools** (`createClientResponseQueue`, `getApproximateMessageCount`,
  `downloadFile`, `exists`, `deleteObject`, `ensureBucketExists`, `TEST_DATA_PREFIX`). They are harmless but not
  part of the production flow.
