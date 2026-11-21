# `specs/`: the project's design memory

## 1. The role of `specs/`, in plain words

`specs/` contains no code and nothing in it runs. It is the project's **written memory**: the plans made
before each piece was built, the decisions taken on the way (and the options that were rejected, and why), the
problems found between the components, and the results of the real AWS runs.

The code tells you **what** the system does. The specs tell you **why** it does it that way: why one queue per
job, why the worker takes one message at a time, why the manager doesn't delete unknown results, why the local app
resends `NEW_TASK`. These are exactly the questions asked at a frontal grading, and much of the README's
"explain your design" part can be taken from here.

How the folder was used during development:
1. Before building a piece, its plan was written here (`<piece>.md`) and approved.
2. The piece was built in small, numbered steps; the spec's step list was updated as each one was done.
3. Problems found between components went into `open-issues.md`, with a status.
4. `checkpoint.md` was kept as a one-page overview of the whole system.

The **per-module `documentation.md` files** (`common/`, `worker/`, `manager/`, `local-app/`) are the current
explanation of the code, class by class. Where a spec and the code disagree, **the code (and those documentation
files) win**; section 4 lists the known out-of-date statements.

---

## 2. Reading order

| If you want ... | Read |
|---|---|
| The whole system on one page | `checkpoint.md` |
| How a module works, class by class | that module's `documentation.md` |
| Why a contract between two components looks the way it does | `open-issues.md` |
| What was changed in the manager and why some fixes were dropped | `manager-fixes.md` |
| Measured timings and results of real runs (for the README) | `worker-stage2.md` ("Results"), `local-app.md` (step 10) |
| The history of how each piece was built | `worker-stage1.md` → `worker-stage2.md` → `s3-service.md` → `local-app.md` → `manager-fixes.md` |

---

## 3. The files, one by one

### 3.1 `checkpoint.md`: the one-page map (current)
**Purpose:** an overview for both teammates of how the whole system works, written on 2026-11-20 and updated
after the full cloud runs.

**Contents:**
1. the idea in one paragraph;
2. a diagram of the components, queues and S3;
3. a table of every important class with its purpose;
4. the workflows:
   - a job from start to finish, with the exact message formats;
   - what happens on each kind of failure (bad image, worker crash, slow worker, duplicates, out-of-order
     results, manager crash, terminating manager, two managers);
   - termination;
5. where things live (bucket layout, AMI, instance cap of 9);
6. the status table of all open items;
7. pointers to the other specs.

**Status:** current. The only remaining items are issue 10 (accepted limit), the "least-loaded fallback" future
TODO, and the README.

### 3.2 `open-issues.md`: the contract problems between the local app and the manager (current)
**Purpose:** a list of every place where the local app and the manager (built by different people) did not fit
together, found while building the local app, with the decision for each.

**Contents:** a status table at the top, then one section per issue (problem, scenario, options, decision):

| # | Issue in one line | Outcome |
|---|---|---|
| 1 | The summary lost line boundaries (OCR text has newlines) | One Base64 line per image |
| 2 | The summary was stored outside the job folder | `S3Service.summaryKey` |
| 3 | `manager-clients-n` wasn't implemented | `ActiveClients` tag on the manager; the local app compares |
| 4 | No manager entry point, jar or boot script | `ManagerMain`, `manager.jar`, `UserData.manager` |
| 5 | A terminating manager still looked available | `Role` tag → `TERMINATING_MANAGER` |
| 6 | Two managers alive at once deleted each other's results and killed each other's workers | Don't delete unknown results; track own workers |
| 7 | `TERMINATE` and `NEW_TASK` together could strand a job | By design: the task reappears; the local app replaces a gone manager |
| 8 | A manager started by the local app got no worker AMI ID | The AMI ID is passed in the boot script |
| 9 | A manager crash lost the job | The local app resends `NEW_TASK` |
| 10 | The local app's choice of manager isn't enforced (shared inbound queue) | **Open**, accepted limit |
| 11 | `ActiveClients` counted a running job twice | Fixed, with a regression test |

**Good to know:** the bodies of issues 5, 6 and 7 still have "(OPEN)" in their headings, from when they were
written. The **status table at the top is the up-to-date one**.

### 3.3 `manager-fixes.md`: the manager-side fixes M1–M5 (current)
**Purpose:** the plan and result for fixing the manager side of issues 5–9 on 2026-11-20, under the rule
"the smallest change that works".

**Contents:** one section per fix with problem, fix, rejected alternatives and tests:
- **M1** (issue 8): the AMI ID is passed in the boot script. Rejected: reading it from instance metadata.
- **M2** (issue 5): the terminating marker reuses the `Role` tag.
- **M3** (issue 9): **dropped**. Holding `NEW_TASK` until `JOB_DONE` would have needed ~40 lines of threaded code;
  the local app resending the task does the same with ~1 line.
- **M4** (issue 7): **dropped**, because a leftover `NEW_TASK` reappears after 30 s by itself.
- **M5** (issue 6): unknown results stay in the queue (with a `finishedClients` set for late duplicates); each
  manager counts and terminates only its own workers.

It is the best source for *"what happens if a node dies / two managers / termination"* answers, including the
known limits of each fix.

### 3.4 `local-app.md`: the local app's plan and cloud results (current, with one stale row)
**Purpose:** the design of the `local-app` module and its step list (PR 1 and PR 2).

**Contents:**
- the run flow, step by step, including the liveness check, resend and cleanup-in-`finally`;
- the "seams" (`ManagerLocator`, `SummaryParser`) that isolated the then-open issues;
- why the result types moved to `common`, and why HTML stays in the local app (a `switch` over sealed types);
- the HTML layout rules;
- the numbered steps 0–10 with what was done in each;
- **step 10: the three full cloud runs**, with timings (e.g. 2 URLs, cold start: `JOB_DONE` after ~1.5 min,
  everything terminated after ~2.5 min; 24 URLs with 4 warm workers: ~40 s; two overlapping clients with
  `TERMINATE` in the middle: 2 min 18 s).

### 3.5 `s3-service.md`: the shared S3 helper (current)
**Purpose:** the plan for `S3Service` in `common`.

**Contents:** the bucket layout (`jars/`, `jobs/<clientId>/`, `test-data/`) and why it is grouped per job; the
method list; the safety checks (IDs can't escape their folder; `deletePrefix` can't wipe the bucket); the
verification done against the real bucket.

### 3.6 `worker-stage1.md`: the worker, local version (historical)
**Purpose:** the first plan of the project: a worker that downloads and OCRs a list of URLs on a PC, with no AWS.

**Contents:** the design of `Worker`, `ImageDownloader`, `OcrEngine`, `TesseractOcrEngine` and the result types;
why the English model needs no download (it is inside the tess4j jar); the tests; an outline of stage 2.

### 3.7 `worker-stage2.md`: the worker on EC2 (historical, with valuable results)
**Purpose:** turning the local worker into an SQS consumer on EC2, and building the EC2 tooling in `common`.

**Contents:**
- how SQS consumption works (pull-only, long polling, the message life cycle), and why listener libraries were
  rejected;
- the `WorkerLoop` design: one message at a time, send-then-delete, heartbeat, poison messages, backoff;
- `EC2Service`, `UserData`, the shared AMI;
- **Results (2026-11-19):** the AWS resources (AMI `ocr-base-ami` = `ami-07f5ffe53445e23b1`, `t3.micro`,
  `LabInstanceProfile`, bucket `ocr-assignment-c8f120d5`), and the measured runs: the kill test, and 24 URLs on
  one EC2 worker in 80 s;
- how to rerun the EC2 test with `FakeManager`;
- notes for the manager (results out of order, duplicates possible).

---

## 4. Known out-of-date statements

These were true when written and later changed. The code and the module `documentation.md` files are correct.

| File | Says | Now |
|---|---|---|
| `worker-stage1.md` | Result types in package `com.distributedsystems.worker.ocr.result` | Moved to `common`: `com.distributedsystems.ocr.result` |
| `worker-stage1.md` | The result message will be JSON via Jackson | The `###` text protocol of `MessageProtocol` is used; no Jackson |
| `worker-stage1.md` | `Worker.main` runs the local mode | The main class is `WorkerApp`; local mode is `java -jar worker.jar local <file>` |
| `worker-stage2.md` | Instance cap of **19** (at most 18 workers) | The cap is **9** (`EC2Service.MAX_INSTANCES`), with at most 8 workers per manager |
| `worker-stage2.md` | The manager reads its own AMI ID from instance metadata | Rejected in M1: the AMI ID is passed as an argument in the boot script |
| `worker-stage2.md` | `OcrResult.toMessage(clientId)` | `toMessage(clientId, taskIndex)` |
| `worker-stage2.md` (context) | `IMAGE_TASK###clientId###url`, results without an index | Every task and result message carries `taskIndex` |
| `local-app.md` (seams table) | Cleanup also deletes the summary if it is outside the job folder; locator = any `Role=MANAGER`; `UserData.manager(bucket, jarKey)` | Dropped once issue 2 was fixed; locator checks `ActiveClients`; `UserData.manager(bucket, jarKey, amiId)` (later rows of the same file say so) |
| `s3-service.md` | `deletePrefix` returns `void` | It returns the number of deleted objects |
| `open-issues.md` | "(OPEN)" in the headings of issues 5, 6, 7 | Resolved; see the status table at the top |

---

## 5. What is not here yet

- **Edge cases and their expected behaviour, tested on a real run**: planned as the next step, written to a file in
  the repo root.
- **The README** required by the assignment: names and IDs, how to run, how it works, AMI + instance type, run
  time, the `n` used, and the answers to the "Mandatory Requirements" questions (security, scalability,
  persistence, threads, termination, ...). Most of the material for it is in `checkpoint.md`, `manager-fixes.md`,
  the module documentation files and the measured runs above.
