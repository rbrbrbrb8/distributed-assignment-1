# `manager`: the coordinator in the cloud

## 1. The role of the manager, in plain words

The manager is one program running on one EC2 machine. It is the **middleman** between the local apps (the
customers) and the workers (the hands). It never downloads an image or runs OCR itself. Its jobs are:

1. **Take orders.** Read `NEW_TASK` messages from local apps. Each one points to an input file in S3.
2. **Split each order into pieces.** Download the input file and turn every URL into one `IMAGE_TASK` message on
   the shared worker queue.
3. **Make sure there are enough hands.** Start workers according to `n` (one per `n` images), up to a limit.
4. **Collect the pieces.** Read the workers' results, and file each one under the right client and the right line.
5. **Deliver.** When every line of a client's job is filled, write a summary file to S3 and send `JOB_DONE` to
   that client's private queue.
6. **Close the shop on request.** On `TERMINATE`: stop taking new orders, finish the current ones, terminate its
   workers, then terminate itself.

And it does all this for **many clients at the same time** (the assignment's *"the manager must process requests
from local applications simultaneously"*).

An analogy: a restaurant's head waiter. Takes orders from many tables, pins every dish of every order on the
kitchen's shared ticket rail, calls in more cooks when it gets busy, collects the finished dishes, and brings each
table its complete order. When the owner says "closing time", no new tables are seated, the seated tables are
served, the cooks are sent home, and the waiter leaves last.

### Where it sits in the flow
```
 local apps                          MANAGER (1 EC2)                               workers
 ─────────                ┌───────────────────────────────────┐
 NEW_TASK / TERMINATE ──▶ │ client-listener thread            │
 (ocr-app-to-manager)     │    └─▶ job-setup pool ── S3: read jobs/<id>/input.txt
                          │            │ starts workers (EC2) │
                          │            └─ IMAGE_TASK ×N ──────┼──▶ ocr-manager-to-workers-queue ──▶
                          │                                   │
                          │ worker-listener thread  ◀─────────┼─── ocr-workers-to-manager-queue ◀──
                          │    └─▶ all lines filled?          │      (OCR_DONE / OCR_ERROR)
 JOB_DONE ◀───────────────┤        S3: write jobs/<id>/summary.txt
 (ocr-client-response-<id>)└───────────────────────────────────┘
```
In the assignment's numbered flow, the manager does steps **4, 5, 6, 11, 12, 13**.

### How a manager comes to life and dies
1. A **local app** finds no available manager and calls `EC2Service.launch(MANAGER, 1, ...)` with
   `UserData.manager(bucket, jarKey, amiId)`.
2. The instance boots from `ocr-base-ami`, downloads `jars/manager.jar`, and runs
   `java -jar manager.jar <bucket> <workerAmiId>`.
3. It serves jobs until a `TERMINATE` arrives.
4. It terminates its workers and then **its own instance**.

---

## 2. Class map

```
ManagerMain (main): reads config, builds everything, calls engine.start()
   │
   ├── ManagerEngine ─────────── the brain: two listener threads + a job-setup thread pool
   │      ├── ClientJob          one per client: its URLs, its results by line, "am I done?", the summary text
   │      ├── WorkerScaler       "how many workers should I start for this job?"
   │      ├── StorageClient  ◀── interface ──  S3StorageClient  (→ common.S3Service)
   │      └── ComputeClient  ◀── interface ──  EC2ComputeClient (→ common.EC2Service + raw Ec2Client)
   │                        (both interfaces live in CloudInterfaces)
   └── SQSService, MessageProtocol, S3Service.summaryKey, UserData.worker   (from common)
```

| Class | One-line job |
|---|---|
| `ManagerMain` | Entry point: configuration, queues, wiring. |
| `ManagerEngine` | Receives jobs and results, runs jobs in parallel, handles termination. |
| `ClientJob` | The state of one client's job and its summary file. |
| `WorkerScaler` | The worker-count formula: `ceil(images / n)` minus the workers already running, capped. |
| `CloudInterfaces` | Two small interfaces, `StorageClient` and `ComputeClient`, that hide S3 and EC2 from the engine. |
| `S3StorageClient` | `StorageClient` on real S3. |
| `EC2ComputeClient` | `ComputeClient` on real EC2: start/count/stop **its own** workers, update its own tags, terminate itself. |

**Why the interfaces.** `ManagerEngine` never touches S3 or EC2 directly; it only sees `StorageClient` and
`ComputeClient`. In tests (`ManagerLocalTest`) they are replaced by in-memory fakes, so the whole manager flow,
including threads, parallel jobs and termination, can be tested on a laptop without AWS and without paying for
instances.

---

## 3. Deep dive

### 3.1 `ManagerMain`: start-up and wiring

**Configuration.** Every setting is read with the same rule: **environment variable first, then the command-line
argument, then a default**.

| Setting | Env var | Arg | Default | Notes |
|---|---|---|---|---|
| bucket | `S3_BUCKET` | `args[0]` | none: **required** | The boot script passes it. |
| worker AMI | `WORKER_AMI_ID` | `args[1]` | none: **required** | The boot script passes it (same AMI as the manager). |
| worker jar key | `WORKER_JAR_KEY` | `args[2]` | `jars/worker.jar` | |
| max workers | `MAX_WORKERS` | `args[3]` | `8` | A non-number falls back to 8. |
| region | `AWS_REGION` | none | `us-east-1` | |

If the bucket or AMI is missing, it stops at once (`Objects.requireNonNull`). This happened before fix M1
(`specs/manager-fixes.md`), when the boot script didn't pass the AMI.

**Wiring, in order**
1. Build `S3Service`, `SQSService`, `EC2Service`, and a raw `Ec2Client` (needed to write tags, see 3.6).
2. Get or create the three fixed queues, if the manager is the one that creates them:
   - the inbound queue gets a **30 s** visibility timeout;
   - the two worker queues get **60 s**, matching `WorkerApp` and the worker's heartbeat (e2e test T10).
3. Prepare the worker boot script once: `UserData.worker(bucket, workerJarKey)`.
4. Wrap S3 in `S3StorageClient` and EC2 in `EC2ComputeClient`.
5. Build `ManagerEngine` with `maxWorkers` and the queue URLs, print the configuration, and call `engine.start()`,
   which blocks until the manager shuts down.

(`ManagerSqsService` is an empty subclass of `SQSService` with no behaviour of its own: a harmless leftover.)

### 3.2 `ManagerEngine`: the brain

#### Threads: who does what
| Thread | Runs | Why it is separate |
|---|---|---|
| main | `start()`: starts the two listeners and waits for both to end (`join`), then runs the shutdown | |
| `client-listener` | `pollClientRequests()`: reads `NEW_TASK` / `TERMINATE` | So new clients are accepted even while results are being processed. |
| `worker-listener` | `pollWorkerResults()`: reads `OCR_DONE` / `OCR_ERROR` | So results keep flowing even while a big new job is being set up. |
| `jobSetupPool` (cached thread pool, one thread per job being set up) | `handleNewTask(...)` | Setting up a job is slow (S3 download, EC2 launch, sending hundreds of messages). If the client listener did it itself, client B would wait for client A's setup. With the pool, several setups run in parallel. |

This is the manager's answer to *"the manager must not handle each request at a time"*: jobs are set up in
parallel, and once set up, all jobs' tasks are mixed in the same worker queue and progress at the same time.

#### Shared state (all thread-safe)
| Field | Type | Meaning |
|---|---|---|
| `activeJobs` | `ConcurrentHashMap<clientId, ClientJob>` | Jobs that are set up and waiting for results. |
| `finishedClients` | concurrent set of `clientId` | Jobs this manager already completed (to recognise late duplicate results). |
| `pendingJobSetups` | `AtomicInteger` | Jobs accepted but still being set up in the pool. |
| `terminating` | `AtomicBoolean` | Set once `TERMINATE` is received. |

#### The `ActiveClients` tag (how local apps see how busy this manager is)
`syncActiveClientsTag()` writes `ActiveClients = activeJobs.size() + pendingJobSetups` onto the manager's own
EC2 instance. Local apps read it to decide whether this manager is "available" (fewer than their
`manager-clients-n`). It is rewritten whenever the number changes:
- at start-up (0);
- when a `NEW_TASK` is accepted (pending +1);
- when a set-up job joins `activeJobs`;
- when a setup ends, successfully or not (pending −1); see the bug story below;
- when a job completes.

A failure to write the tag is only logged: it must never stop the manager.

> **Bug found in the first cloud run (issue 11):** after a successful setup, the job was in `activeJobs` *and*
> still counted in `pendingJobSetups`, and the tag wasn't rewritten after the decrement, so one client showed as
> `ActiveClients=2`. Fix: the `finally` of every setup always rewrites the tag. For a few milliseconds the tag can
> still read one too high, which is the safe direction (at worst a local app starts an extra manager).

#### Reading client messages: `pollClientRequests()`
Loops while not terminating. Each round receives up to **5** messages (20 s long poll). On an SQS error it logs,
waits 1 s and tries again. For each message:

- **`TERMINATE`:**
  1. set `terminating = true`;
  2. `markTerminating()`: the manager changes its own tag `Role=MANAGER` → `Role=TERMINATING_MANAGER`, so no
     local app finds or picks it any more;
  3. delete the `TERMINATE` message;
  4. `break`: stop handling the rest of this batch. The loop condition is now false, so **the client listener
     ends** and the manager never reads the inbound queue again.

  Any `NEW_TASK` left in that batch was received but not deleted, so it becomes visible again after 30 s for
  another manager. (Why that's fine: see section 5, and issue 7 in `specs/open-issues.md`.)

- **`NEW_TASK`** (only if not terminating):
  1. read `clientId`, `responseQueueUrl`, `inputKey`, and `n` (validated by `parseNewTaskRatio`);
  2. **delete the message right away**;
  3. `pendingJobSetups++` and update the tag;
  4. submit `handleNewTask(...)` to the pool. Its `finally` does `pendingJobSetups--` and updates the tag again.

  An exception inside the setup (e.g. the input file is missing in S3) is caught and logged.

- Any other exception (e.g. a malformed message) is logged, and the message is **not** deleted.

#### Setting up one job: `handleNewTask(clientId, responseQueueUrl, inputKey, n)`
1. Download the input file (`storage.downloadLines`): one line per URL; lines are trimmed and empty lines are
   dropped.
2. Create a `ClientJob` with those URLs.
3. **Empty input:** complete the job immediately (an empty summary and a `JOB_DONE`), then stop.
4. Put the job in `activeJobs` and update the tag. This happens **before** any task is sent, so a result can
   never arrive for a job the manager doesn't know yet.
5. `scaler.scaleForNewJob(imageCount, n)`: start missing workers (see 3.4).
6. Build one `IMAGE_TASK###clientId###i###url` per URL, `i` = the line's index (0, 1, 2, ...), and send them in
   batches of 10.

Note the order: workers are started **before** tasks are sent, but they take ~1 minute to boot, so the tasks are
already waiting when they arrive. If workers from earlier jobs are still running, they start on the new tasks
right away.

#### Reading worker results: `pollWorkerResults()`
Keeps running **while** any of these is true: not terminating, jobs still being set up, or active jobs remain. So
after `TERMINATE` it keeps going until every accepted job is finished: that's *"waits for all the workers to
finish their job"*.

Each round receives up to **10** results. For each:
1. Parse: `type`, `clientId`, `taskIndex`, `imageUrl`, payload.
2. Payload: for `OCR_DONE` the Base64 text is decoded; for `OCR_ERROR` the error text is taken as is.
3. `handleWorkerResult(...)` returns whether this result is **ours**. **Only ours are deleted.**

#### Is this result ours? `handleWorkerResult(clientId, index, url, payload, isError)`
| Situation | Action | Returns (delete?) |
|---|---|---|
| `clientId` is an active job | `job.recordResult(...)`. If that was the last missing line: move the client to `finishedClients`, remove it from `activeJobs`, update the tag, `completeClientJob`. | `true`: delete |
| `clientId` is in `finishedClients` (a late duplicate after the job was done) | nothing | `true`: delete, so it doesn't circulate |
| unknown `clientId` | nothing | `false`: **don't delete it**. The listener hands it back with `changeMessageVisibility(msg, 5 s)` |

Why not delete unknown results: during a hand-over, two managers can be alive at once (an old one finishing
after `TERMINATE`, a new one serving new clients). They share the same results queue. A result one manager doesn't
know probably belongs to the other one, so it is not deleted. (Before fix M5 it was deleted, and the other manager's
job never finished.)

Why it is handed back after **5 s** (`UNKNOWN_RESULT_RETRY_SECONDS`):
- **Before:** the result was simply left alone, so it stayed hidden for the queue's 60 s visibility timeout. In
  e2e test T07, an idle manager kept hiding a busy manager's results this way, and the busy manager's job took
  **9 min instead of ~2**.
- **With 5 s:** the owner gets the result within seconds. The same run took **2 min 9 s** (T07b).
- **Why not 0:** an idle manager would spin non-stop on stale results that nobody owns.

#### Delivering: `completeClientJob(job)`
1. Upload `job.buildSummaryContent()` to `jobs/<clientId>/summary.txt` (`S3Service.summaryKey`).
2. Send `JOB_DONE###jobs/<clientId>/summary.txt` to the job's own response queue.

#### Shutting down: `shutdownSequence()`
Runs in `start()`'s `finally`, after both listener threads have ended:
1. Stop the job-setup pool (wait up to 5 s for running setups).
2. `compute.terminateAllWorkers()`: terminate the workers **this manager launched**.
3. `compute.terminateSelf()`: terminate this manager's own instance.

The full termination story, end to end:
```
TERMINATE received ─▶ terminating = true, Role tag → TERMINATING_MANAGER, client listener ends
                   ─▶ worker listener keeps going until no job is active or being set up
                   ─▶ both threads ended ─▶ setup pool stopped ─▶ own workers terminated ─▶ self terminated
```
This matches the assignment: no more input files, wait for the workers to finish, create the responses for the
jobs, terminate.

### 3.3 `ClientJob`: one client's order ticket

**State**
- `clientId`, `responseQueueUrl`, `n`;
- `imageUrls`: the input URLs, in order (an immutable copy);
- `results`: a `String[]` with one slot per URL, each slot holding the finished summary line or `null`;
- `remainingTasks`: an `AtomicInteger`, starting at the number of URLs.

**`recordResult(taskIndex, imageUrl, payload, isError)` → `true` if this result completed the job**
1. Index out of range → `IllegalArgumentException`.
2. URL: the one from the message, or (if blank) the input URL for that index.
3. Build the slot's line: `url | OCR: <Base64(text)>` or `url | ERROR: <Base64(error)>`.
4. Inside `synchronized (results)`: if the slot is already filled, this is a **duplicate** → return `false` and
   change nothing. Otherwise fill it. Check-and-fill must be one atomic step, otherwise two threads could both see
   an empty slot and both count it.
5. `remainingTasks--`; return `true` if it reached 0.

So results can arrive in **any order** (each one goes into its own slot) and **any number of times** (only the
first one counts), and the job completes exactly once.

**`buildSummaryContent()`**: the slots joined with `\n`, in input order. An empty slot (only possible if the
summary were built for an unfinished job) is written as an error, `Missing result`.

**The summary format**, as read by the local app's `Base64SummaryParser`:
```
https://example.com/a.png | OCR: SGVsbG8KV29ybGQ=
https://example.com/b.png | ERROR: SU9FeGNlcHRpb246IEhUVFAgNDA0
```
Why Base64 here too: OCR text contains newlines. Without encoding, the local app couldn't tell where one image's
text ends and the next line begins. Base64 has no newlines, so **one line = one image**, always (issue 1).

### 3.4 `WorkerScaler`: how many workers to start

`scaleForNewJob(numImages, n)`, called once per new job:
```
required = ceil(numImages / n)        // the assignment's "a worker for every n files"
target   = min(required, maxWorkers)  // our cap: 8 workers per manager
active   = workers of this manager that are still pending/running
toLaunch = max(0, target − active)    // the assignment's "k active, m needed → start m − k"
```
Examples with `maxWorkers = 8`:

| Images | `n` | required | active | starts |
|---|---|---|---|---|
| 24 | 6 | 4 | 0 | 4 |
| 2 | 1 | 2 | 4 | 0 (enough already) |
| 100 | 5 | 20 → 8 | 3 | 5 |

The method is **`synchronized`**: two jobs set up at the same time on two pool threads could otherwise both see
`active = 0` and both start a full set of workers. With the lock, the second one sees the workers the first one
just started.

Two more limits apply on top, inside `EC2Service.launch`: the **global** cap of 9 instances in the account, and
`t3.micro` as the type.

### 3.5 `CloudInterfaces`: the two seams

```java
interface StorageClient {
    List<String> downloadLines(String s3Key);
    void uploadText(String s3Key, String content);
}
interface ComputeClient {
    int  getActiveWorkerCount();
    void launchWorkers(int count);
    void terminateAllWorkers();
    void terminateSelf();
    default void updateActiveClients(int count) {}   // no-op unless implemented
    default void markTerminating() {}                // no-op unless implemented
}
```
The `default` no-op methods let simple test fakes skip the tag features.

### 3.6 `S3StorageClient` and `EC2ComputeClient`: the real implementations

**`S3StorageClient`** delegates to `common`'s `S3Service`. `downloadLines` splits on `\R` (any line break:
`\n`, `\r\n` or `\r`), so input files written on Windows work too.

**`EC2ComputeClient`**
- **Who am I?** On creation it finds its own instance ID, used for tags and self-termination:
  1. env var `MANAGER_INSTANCE_ID`, if set (handy for tests);
  2. otherwise the **EC2 instance metadata service** (IMDSv2): a `PUT` to
     `169.254.169.254/latest/api/token` gets a short-lived token, then a `GET` of `.../meta-data/instance-id`
     with that token returns the ID. That address only answers from inside an EC2 instance;
  3. on a laptop both fail within ~1 s → the ID is `null`, and tag updates and self-termination silently do
     nothing. So the manager can also run locally without terminating anything by mistake.
- **Only its own workers.** It keeps `ownWorkerIds`, the IDs that `launch` returned. `getActiveWorkerCount()` and
  `terminateAllWorkers()` look at active `Role=WORKER` instances **intersected with** that set. So a manager
  never counts or kills another manager's workers (fix M5). If two managers overlap, each terminates only its own.
- **Tags.** `updateActiveClients` writes `ActiveClients=<k>`; `markTerminating` overwrites `Role` with
  `TERMINATING_MANAGER`. Both use the raw `Ec2Client.createTags` call (EC2 overwrites an existing key), because
  `EC2Service` has no tag-writing method.
- `terminateSelf()` terminates its own instance ID.

---

## 4. How the manager is tested

No test calls AWS.

| Test | What it checks |
|---|---|
| `ManagerLocalTest.testWorkerScalingFormulaAndCap` | `ceil(images/n)`, minus active workers, capped by `maxWorkers`. |
| `...testConcurrentScalingThreadSafety` | Many threads calling the scaler at once don't over-launch. |
| `...testClientJobSingleLineBase64OrderingDuplicatesAndErrors` | Out-of-order results land on the right lines, duplicates are ignored, errors and multi-line text become single Base64 lines. |
| `...testFullEndToEndManagerFlowWithInMemoryQueues` | Real `ManagerEngine` with in-memory queues, S3 and EC2 fakes and fake workers: `NEW_TASK` → tasks → results → summary + `JOB_DONE` → `TERMINATE` → not marked before, marked after → workers and self terminated. |
| `...resultsForUnknownClientsAreNotOursButLateDuplicatesAre` | Unknown client → not deleted; known → deleted; late duplicate for a finished job → deleted. |
| `...activeClientsTagShowsOneWhileOneJobRuns` | The issue 11 regression test (expects 1, the old code gave 2). |
| `EC2ComputeClientTest` | Counts and terminates only the instances it launched itself; launched nothing → terminates nothing. |

Cloud runs (in `specs/local-app.md`, step 10): a manager started by the local app, 2 workers, correct HTML; two
local apps on one manager with the tag showing 1 then 2; `TERMINATE` arriving during another client's job, which
the manager finished before shutting down. Every run left no instances behind.

---

## 5. Things worth knowing (known limits and edge cases)

These are candidates for the edge-case test round.

- **A `NEW_TASK` is deleted as soon as it is accepted.** The job state lives only in the manager's memory. If the
  manager dies mid-job, the job is lost on the manager side; the **local app** detects that its manager is gone,
  starts a new one and **resends** the same `NEW_TASK`, and the job restarts from scratch (issue 9).
- **A setup failure leaves the client waiting.** If `handleNewTask` fails (e.g. the input file is missing in S3),
  it is only logged; the `NEW_TASK` is already deleted and no `JOB_DONE` is sent. The local app waits until its
  timeout (60 min by default), because the manager is still alive.
- **Poison messages are not deleted.** A malformed `NEW_TASK`, an unknown message type, or a malformed or
  out-of-range worker result is logged but stays in its queue, so it comes back every 30 s. The worker deletes
  its poison messages; the manager doesn't.
- **Scaling happens only when a new job arrives.** If workers die mid-job, or the instance cap made `launch`
  start fewer than asked, no replacement is started later. The remaining workers still finish the job, only
  slower. With zero workers and no free slot, the tasks wait. Also, `scaleForNewJob` returns how many it
  *asked* for, so at the cap the log line "Launched X new workers" can overstate.
- **`required` is per job, not total load.** Two simultaneous jobs that each need 3 workers get 3 workers in
  total, not 6, because the rule (from the assignment) compares one job's needs with the workers already
  running.
- **The choice of manager isn't enforced (issue 10).** All managers read the same inbound queue, so a "full"
  manager may still take a new client's `NEW_TASK`.
- **Results circulate during hand-overs.** A result for a client no manager owns stays in the queue until SQS's
  retention period (4 days by default) drops it. In e2e tests T06 and T08 this happened with duplicate results
  that arrived after their manager had ended. A running manager re-reads such results every 5 s. That costs some
  SQS requests and nothing else.
- **The summary is written by the worker-listener thread.** While it uploads one job's summary, other results
  wait a moment. Fine at this scale.
- **Logs** on the instance: `/var/log/ocr-bootstrap.log` and `/var/log/manager.log`.
