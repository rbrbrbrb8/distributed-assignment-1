# `local-app`: the customer's front door

## 1. The role of the local app, in plain words

The local app is the only part a **user** ever runs, on their own PC:
```
java -jar local-app.jar inputFileName outputFileName manager-clients-n worker-files-n [terminate]
```
It takes a text file of image URLs and, a few minutes later, leaves an HTML page with every image and the text
found in it. Everything in between happens in the cloud. The local app's job is to **start that process, wait for
it, and turn the answer into the page**:

1. **Make sure there is a manager to talk to:** find an *available* one, or start a new one on EC2.
2. **Hand over the work:** upload the URL file to S3 and send a `NEW_TASK` message.
3. **Wait for the answer**, and meanwhile keep an eye on the manager: if it dies, start a new one and send the
   task again.
4. **Build the result:** download the summary from S3 and write the HTML file.
5. **Clean up after itself:** if asked, tell the manager to shut down (`terminate`); delete its own queue and its
   own S3 folder.

Several local apps can run at the same time, on the same or different PCs. Each run is completely separate: it has
its own random `clientId`, its own S3 folder, and its own response queue.

An analogy: ordering at a print shop with a ticket number. You drop off your file (S3), get a ticket number
(`clientId`), give your order to the counter (the inbound queue), and wait at your own personal pickup window
(the response queue). If the shop closes unexpectedly, you go to another branch and hand in the same order again.

### Where it sits in the flow
```
 YOUR PC                                         AWS
┌──────────────┐ 1. find / start a manager  ┌──────────────┐
│              │ ─────── EC2 ──────────────▶│   manager    │
│  local app   │ 2. upload input.txt ──▶ S3 │              │
│              │ 3. NEW_TASK ──▶ ocr-app-to-manager-queue ─▶│
│              │                            │   ...work... │
│              │ 4. JOB_DONE ◀── ocr-client-response-<id> ◀─│
│              │ 5. download summary.txt ◀── S3            │
│              │ 6. write output.html (on your PC)         │
│              │ 7. [TERMINATE] ──▶ ocr-app-to-manager-queue
└──────────────┘ 8. delete own queue + jobs/<id>/          └──────────────┘
```
In the assignment's numbered flow, the local app does steps **1, 2, 3, 14, 15, 16, 17**.

### The arguments
| Argument | Meaning | Where it goes |
|---|---|---|
| `inputFileName` | Text file, one image URL per line. | Uploaded to `jobs/<clientId>/input.txt`. |
| `outputFileName` | The HTML file to create. | Written at the end. |
| `manager-clients-n` | The most clients a manager may serve **for this run** to count as available. | Used only here, when choosing a manager. It never reaches the manager. |
| `worker-files-n` (`n`) | Images per worker. | Sent in `NEW_TASK`; the manager starts `ceil(images / n)` workers. |
| `terminate` (optional) | Shut the manager down at the end. | A `TERMINATE` message after the HTML is written. |

Non-secret settings (bucket, AMI ID, region, timeout) come from `local-app.properties` in the working directory.
AWS credentials come from `~/.aws/credentials`, read by the AWS SDK itself; they are never in the properties file
or the code.

---

## 2. Class map

```
LocalAppMain (main)
   │ parses ─▶ LocalAppArgs        (the 4–5 command-line arguments)
   │ loads  ─▶ LocalAppConfig      (local-app.properties)
   │ builds ─▶ LocalApp ──────────────── the run flow
   │              ├── ManagerLocator (interface) ◀── Ec2ManagerLocator ── returns a ManagerHandle
   │              ├── SummaryParser  (interface) ◀── Base64SummaryParser ── returns List<OcrResult>
   │              ├── HtmlReport                                         ── List<OcrResult> → HTML
   │              └── SQSService, S3Service, MessageProtocol   (from common)
```

| Class | One-line job |
|---|---|
| `LocalAppMain` | Entry point: arguments and settings, wiring, exit codes. |
| `LocalAppArgs` | Parses and validates the command line. |
| `LocalAppConfig` | Reads and validates `local-app.properties`. |
| `LocalApp` | The run itself: steps 1–8 above, including liveness checks and cleanup. |
| `manager.ManagerLocator` | Interface: "give me an available manager" and "is it still alive?". |
| `manager.Ec2ManagerLocator` | The EC2 implementation (tags, launch). |
| `manager.ManagerHandle` | Which manager was chosen (its instance ID). |
| `summary.SummaryParser` | Interface: summary text → results. |
| `summary.Base64SummaryParser` | The implementation for the manager's summary format. |
| `HtmlReport` | Results → the output HTML page. |

From `common`: `SQSService`, `S3Service`, `EC2Service`, `UserData`, `MessageProtocol`, and the result types
`OcrResult` / `OcrSuccess` / `OcrFailure`.

**Why two interfaces (`ManagerLocator`, `SummaryParser`).** They were built while the manager side was still
changing (see `specs/local-app.md`, "seams"). Everything that depended on an open decision sits behind one of
them, so a change touched one class only. They also make `LocalApp` testable: `LocalAppTest` runs the whole flow
with a stub locator, an in-memory SQS and an in-memory S3.

---

## 3. Deep dive

### 3.1 `LocalAppMain`: the entry point

1. `LocalAppArgs.parse(argv)` and `LocalAppConfig.load("local-app.properties")`. If either fails, it prints the
   reason and the usage line and exits with code **2**.
2. Opens `SQSService`, `S3Service` and `EC2Service` for the configured region, in a try-with-resources block so
   they are closed at the end.
3. Builds `LocalApp` with an `Ec2ManagerLocator`, a `Base64SummaryParser`, the configured wait timeout and a
   **60 s** liveness interval, and calls `run(args)`.
4. Any exception from the run → prints `[local-app] Failed: <reason>` and exits with code **1**. Success → code
   **0**.

| Exit code | Meaning |
|---|---|
| 0 | Done: the HTML was written. |
| 1 | The run failed (timeout, no manager could be started, AWS error, ...). |
| 2 | Bad arguments or settings: nothing was sent to AWS. |

### 3.2 `LocalAppArgs`: the command line

A record `(inputFile, outputFile, managerClientsN, workerFilesN, terminate)`. `parse` checks, with a readable
message for each problem:
- 4 or 5 arguments;
- the input file exists;
- the output file's folder exists (so the run doesn't do all the cloud work and then fail to write the result);
- `manager-clients-n` and `worker-files-n` are positive integers;
- the 5th argument, if present, is exactly `terminate`.

### 3.3 `LocalAppConfig`: the settings file

A record read from `local-app.properties` (template: `local-app.properties.example` at the repo root):

| Key | Required | Default | Check |
|---|---|---|---|
| `bucket` | yes | | not blank |
| `amiId` | yes | | looks like `ami-` + 8 to 17 hex digits |
| `region` | no | `us-east-1` | |
| `waitTimeoutMinutes` | no | `60` | positive number |
| `managerJarKey` | no | `jars/manager.jar` | |

A missing file gives a message that tells you to copy the example. The real file is git-ignored because it is
per user.

### 3.4 `LocalApp`: the run, step by step

**`run(args)`**
1. `clientId = random UUID`. It names everything of this run: `jobs/<clientId>/`, the response queue, every task.
2. `manager = managerLocator.ensureAvailableManager(managerClientsN)`: find or start a manager (see 3.5). This
   comes first, so a new manager starts booting (about a minute) while the rest is being prepared.
3. In a `try`:
   1. upload the input file to `jobs/<clientId>/input.txt`;
   2. create the response queue `ocr-client-response-<clientId>`;
   3. look up the shared inbound queue `ocr-app-to-manager-queue`;
   4. build `NEW_TASK###clientId###responseQueueUrl###inputKey###n` and send it;
   5. `waitForJobDone(...)` → the summary's S3 key;
   6. download the summary, `summaryParser.parse(...)` → `List<OcrResult>`;
   7. `HtmlReport.write(results, outputFile)`.
4. `finally`: `cleanUp(...)`, also when anything above failed.

**`waitForJobDone(...)`: waiting, with a watchdog**
```
deadline = now + waitTimeout          (default 60 min)
nextCheck = now + 60 s
while now < deadline:
    receive from my response queue    (long poll, waits up to 20 s)
        JOB_DONE###<key>  → return key
        anything else     → log "Ignoring unexpected message"
    if now ≥ nextCheck:
        nextCheck = now + 60 s
        if my manager is no longer running:
            manager = ensureAvailableManager(...)     // find or start another one
            send the same NEW_TASK again
throw "No result after N minutes; giving up"
```
Why the watchdog (issues 7 and 9 in `specs/open-issues.md`): the manager keeps a job only in memory and deletes
the `NEW_TASK` when it accepts it. If the manager instance dies, nobody remembers the job, and the app would wait
until the timeout for nothing. So every minute the app asks EC2 "is my manager still pending or running?". If not,
it gets another manager and resends **the exact same** `NEW_TASK` (same `clientId`, same input file, same response
queue, same `n`). The new manager restarts the job from scratch. This is correct even if a result from the dead
manager's workers arrives late: results are stored by index, and duplicates are ignored.

A manager that is **terminating** (it received another client's `TERMINATE`) still counts as alive on purpose: it
finishes every job it already accepted, so replacing it would only duplicate work.

The overall timeout guarantees that the app never hangs forever.

**`cleanUp(...)`: always, best effort**
1. If `terminate` was given: send `TERMINATE` to the inbound queue. This is the last message the app sends, after
   the HTML is written (assignment step 17). It is also sent when the run **failed**, so a failed run doesn't leave
   paid instances running.
2. Delete the response queue.
3. Delete everything under `jobs/<clientId>/` (the input and the summary).

Each step is wrapped in `bestEffort`: a failure is printed and the next step still runs, and a cleanup failure
never hides the real error of the run.

Note: if `ensureAvailableManager` itself fails (step 2 of `run`, before the `try`), nothing was created yet, so
there is nothing to clean up, and no `TERMINATE` is sent.

### 3.5 Choosing a manager: `ManagerLocator`, `Ec2ManagerLocator`, `ManagerHandle`

**`ManagerLocator`** (interface)
- `ManagerHandle ensureAvailableManager(int managerClientsN)`: an available manager, starting one if needed;
  throws `IllegalStateException` if none can be started.
- `boolean isAlive(ManagerHandle)`: is that manager still running?

**`ManagerHandle`**: a record holding the manager's EC2 instance ID (must not be blank). It's a record rather than
a plain `String` so it could later carry more, such as a per-manager queue URL (issue 10's preferred fix).

**`Ec2ManagerLocator`**: the assignment defines *"An available Manager is an active Manager which currently serving
less than manager-clients-n clients"*. Implemented with EC2 tags:

```
candidates = all pending/running instances tagged Role=MANAGER
for each: clients = its ActiveClients tag
            missing tag      → 0       (it just booted and hasn't written it yet)
            not a number     → skip it
keep those with clients < manager-clients-n
pick the one with the fewest clients            ← least loaded
none? → launch a new manager
```
- **Launching:** `EC2Service.launch(MANAGER, 1, amiId, UserData.manager(bucket, managerJarKey, amiId))`. The same
  AMI ID is passed to the manager as its worker AMI (one shared image for both). If the launch returns nothing
  (the global cap of 9 instances is reached), it fails with a clear message: *"No manager is available and a new
  one can't be started: the instance cap (9) is reached. Try again later."*
- **Terminating managers are skipped automatically:** on `TERMINATE` the manager changes its tag to
  `Role=TERMINATING_MANAGER`, so the `Role=MANAGER` search doesn't return it.
- **`isAlive`** = `EC2Service.isActive(instanceId)`: the instance is pending or running. An unknown ID simply
  gives `false`.
- **Fullness is per run:** each run passes its own `manager-clients-n`, so the same manager (say with 2 clients)
  is full for a run with limit 2 but available for a run with limit 3. That's why the manager only *publishes*
  its count and the local app does the comparison.

### 3.6 Reading the summary: `SummaryParser`, `Base64SummaryParser`

The manager's summary (`jobs/<clientId>/summary.txt`) has **one line per input URL, in input order**:
```
<url> | OCR: <Base64 of the UTF-8 text>
<url> | ERROR: <Base64 of the UTF-8 error description>
```
`Base64SummaryParser.parse(text)`:
1. Split on line breaks (`\n` or `\r\n`); skip blank lines (e.g. a trailing newline).
2. For each line, find ` | OCR: ` and ` | ERROR: `. **Whichever comes first** separates the URL from the payload.
3. The part before it is the URL (must not be blank); the part after is Base64-decoded to UTF-8 text.
4. Return `OcrSuccess(url, text)` or `OcrFailure(url, error)`.

A line with no marker, no URL or invalid Base64 → `IllegalArgumentException` that names the **line number**, so a
format mismatch is easy to find.

Why Base64 makes this simple: the decoded OCR text may contain newlines, but the encoded form never does. So
"one line = one image" always holds, and no guessing is needed (issue 1).

### 3.7 Writing the page: `HtmlReport`

`render(List<OcrResult>)` builds the page following the assignment's sample output: title `OCR`, one `<p>` per
URL, in input order. A `switch` over the sealed result type (the compiler checks both cases are handled):

| Result | HTML |
|---|---|
| `OcrSuccess` | `<a href="URL"><img src="URL"></a><br>` then the text, with each line break turned into `<br>` |
| `OcrFailure` | `<a href="URL">URL</a> short error description`, which is exactly the assignment's *"<a link to the input file> <a short description of the exception>"* |

Example:
```html
<!DOCTYPE html>
<html><head><meta charset="UTF-8"><title>OCR</title></head><body>
	<p>
		<a href="https://x/a.png"><img src="https://x/a.png"></a><br>
		Hello<br>
		World
	</p>
	<p>
		<a href="https://x/b.png">https://x/b.png</a> IOException: HTTP 404
	</p>
</body></html>
```
- **Everything is HTML-escaped** (`& < > " '`), URLs and text alike. OCR text such as `<script>` shows up as
  text and can never run or break the page.
- The file is written as **UTF-8** and replaces any existing file.
- HTML formatting lives here, not on the shared `OcrResult` types: only the local app shows HTML, so the worker and
  the manager never carry HTML code.

The images are not copied anywhere: the page links to the original URLs, so the browser loads them from the
internet.

---

## 4. How the local app is tested

No test calls AWS.

| Test | What it checks |
|---|---|
| `LocalAppArgsTest` | 4 args; optional `terminate`; wrong count; missing input; missing output folder; non-positive or non-numeric counts; an unknown 5th argument. |
| `LocalAppConfigTest` | Defaults; optional keys; missing required keys; invalid values; loading from a file; a helpful message for a missing file; the repo's example file is valid. |
| `HtmlReportTest` | Success and failure markup; order and one `<p>` per result; escaping; multi-line text; empty list; same structure as the assignment's sample; UTF-8 file. |
| `Base64SummaryParserTest` | Success and error lines in order; multi-line and Unicode text; empty text; URLs with query strings, trailing newline, CRLF; empty summary; malformed lines with line numbers. **Contract tests:** it parses what the manager's real `ClientJob` writes, including a "Missing result". |
| `Ec2ManagerLocatorTest` (stub `EC2Service`) | Uses a manager below the limit; starts one when the only one is full or none exists; fullness depends on this run's limit; missing tag = 0; least loaded is picked; unreadable tag is skipped; clear error at the cap; `isAlive` follows the instance state. **Contract tests:** the tag name and `TERMINATING_MANAGER` match what the manager writes. |
| `LocalAppTest` (in-memory SQS with a fake "manager" that answers `NEW_TASK`) | Happy path writes the HTML and cleans up; `TERMINATE` is sent last, after the HTML exists; a gone manager is replaced and the same task is resent; a live manager is not replaced; a timeout fails clearly and still cleans up and terminates; a failure after `JOB_DONE` still cleans up. |

The manager module is a **test-only** dependency (for the contract tests). It is not packaged into
`local-app.jar`.

Cloud runs (in `specs/local-app.md`, step 10): one app starting the manager itself; two apps sharing one manager,
each HTML with only its own URLs; one app sending `terminate` while the other's job still ran.

---

## 5. Things worth knowing

- **The choice of manager is advisory (issue 10).** `NEW_TASK` goes to the shared inbound queue, which every
  manager reads. The app picks manager X to check the limit and to watch it, but manager Y could take the task.
  The job still completes either way.
- **The watchdog watches the chosen manager, not the one doing the work.** Combined with the previous point: if Y
  took the job and then dies, the app doesn't notice (X is still alive) and waits until the timeout.
- **The watchdog runs between long polls**, so a dead manager is noticed after roughly 60–80 s.
- **A setup failure on the manager** (e.g. the input couldn't be read) sends no answer. The manager is still
  alive, so the app waits for the full timeout (60 min by default).
- **An empty input file** gives an immediate `JOB_DONE` with an empty summary, and the HTML page has no
  paragraphs.
- **Blank lines in the input** are dropped by the manager; **repeated URLs** each get their own line in the
  output.
- **A failed run still sends `TERMINATE`** if `terminate` was given. That's on purpose (no paid instances left
  behind), but it also ends the manager for any other client that was about to use it; those clients see it
  disappear and start a new one.
