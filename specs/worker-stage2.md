# Plan: Worker — Stage 2 (run the worker on EC2, launched from Java)

## Context
Stage 1 is done and committed on `ec2-ocr` (commit 809d211). That includes `Worker.processUrl`/`processUrls`,
`TesseractOcrEngine`, `ImageDownloader`, the sealed `ocr/result` types, a fat jar via shade, and 7 passing tests.

The friend's branch `origin/sqs` adds:
- a `common` module with `com.distributedsystems.aws.SQSService`, which has queue-name constants, long polling,
  `changeMessageVisibility` and batch sends;
- `MessageProtocol`, a `"###"`-delimited text protocol: `IMAGE_TASK###clientId###url` in,
  `OCR_DONE###clientId###url###base64text` and `OCR_ERROR###clientId###url###error` out.

It will be merged into `main` via a PR first. **The user does the merges and the AWS credential setup. Claude
implements nothing until the user says "go ahead".**

Goal of stage 2: the worker runs on EC2 as a long-lived SQS consumer. Its instance is launched from Java code in
`common`, so the future Manager reuses the same code to start workers.

## Design
1. **Result → message (OOP, no mapper):** `OcrResult` gets `String toMessage(String clientId)`. `OcrSuccess`
   delegates to `MessageProtocol.createOcrDoneMsg` and `OcrFailure` to `createOcrErrorMsg`. The context the result
   doesn't know (clientId, and a task index if the protocol adds one) is passed in as a parameter.
   - Receiving side (manager): the type is lost on the wire, so exactly **one dispatch on the message-type field**
     at parse time is unavoidable. That's the manager's concern, not part of this stage.
2. **`WorkerLoop` (worker module):**
   1. Receive 1 message from `SQSService.MANAGER_TO_WORKERS_QUEUE` (long poll).
   2. Parse it with `MessageProtocol.parse`.
   3. Call `Worker.processUrl(url)`.
   4. Send `result.toMessage(clientId)` to `WORKERS_TO_MANAGER_QUEUE`.
   5. **Only then** delete the task message.

   Details:
   - **Heartbeat:** a single scheduler thread extends the visibility timeout (e.g. every 30 s) while a task runs.
     It stops when the task ends. If the worker crashes, the heartbeat stops, the message reappears, and another
     worker takes it.
   - **One task at a time:** a micro instance has 1 vCPU and Tesseract isn't thread-safe, so we scale with more
     instances, not more threads.
   - **Poison messages:** a malformed message is logged and deleted.
   **How SQS consumption works (there is no push listener):**
   - SQS is **pull-only**. Nothing calls the worker when a message arrives; the worker must ask with
     `ReceiveMessage`. The plain AWS SDK v2 (`software.amazon.awssdk:sqs`, the dependency the assignment gives) has
     no listener or callback API.
   - **Long polling** makes "asking" cheap. `ReceiveMessage` with `waitTimeSeconds=20` keeps the HTTP request open
     for up to 20 s and returns as soon as a message arrives. If the queue is empty, it returns an empty list
     after 20 s. There's no busy-looping, few empty requests (requests are what SQS bills), and near-instant
     pickup. The friend's `SQSService.receiveMessages(queueUrl, max)` already does this (`LONG_POLL_WAIT_SECONDS
     = 20`), so we reuse it.
   - **Libraries that offer a listener and were rejected:**
     - The Amazon SQS Java Messaging Library (JMS `MessageListener`) polls internally anyway and adds JMS concepts
       plus an extra dependency.
     - Spring Cloud AWS `@SqsListener` would require Spring.
     - An SQS-triggered Lambda isn't EC2, which the assignment requires.
     - The SDK's `SqsAsyncBatchManager` is for batching/buffering, not listening.

     A hand-written loop is ~40 lines, has no new dependencies, and is easy to explain at grading.
   - **Message life cycle** (this is what makes crash recovery work):
     1. `receive` makes the message **invisible** to other consumers for the visibility timeout. It is *not*
        removed.
     2. The worker processes it and sends the result.
     3. `delete` (using the message's receipt handle) removes it permanently.
     4. If step 3 never happens (crash, stall, or a lost network connection), the timeout expires, the message
        becomes visible again, and another worker receives it.

   **Loop structure (`WorkerLoop.run()`):**
   ```
   while (running):                                   // running = false on JVM shutdown hook
       try:
           msgs = sqs.receiveMessages(tasksQueue, 1)  // blocks ≤20 s; 1 msg = fair spread across workers
           if msgs empty: continue                    // empty long poll → just ask again
           msg = msgs[0]
           heartbeat = scheduler.scheduleAtFixedRate(
               () -> sqs.changeMessageVisibility(tasksQueue, msg, 60), 30, 30, SECONDS)
           try:
               parts = MessageProtocol.parse(msg.body())
               if parts[0] != IMAGE_TASK or wrong field count:
                   log + delete(msg); continue        // poison message: never retry
               result = worker.processUrl(parts[url])     // never throws (stage 1)
               sqs.sendMessage(resultsQueue, result.toMessage(parts[clientId]))
               sqs.deleteMessage(tasksQueue, msg)     // only after the result is safely sent
           finally:
               heartbeat.cancel()
       catch (SdkException e):                        // SQS/network trouble, not the image's fault
           log; sleep with backoff (1 s → 2 s → … max 30 s); loop again
           // the message wasn't deleted → it reappears for someone → nothing lost
   ```
   - **Why 1 message per receive:** with up to 10 per receive, a worker would hide 10 tasks while slowly OCRing
     them one by one, and idle workers would starve. Taking 1 at a time keeps all workers busy, which answers the
     spec's "are all your workers working hard?" question.
   - **Threads:** the main thread runs the loop and OCR. One daemon scheduler thread runs the heartbeat. That's
     all.
   - **Shutdown:** the manager terminates the EC2 instance. A JVM shutdown hook sets `running=false`. Even a hard
     kill mid-task is safe, because the undeleted message reappears.
   - **Queue URLs:** looked up once at startup via `SQSService.getOrCreateQueue(name, 60)`, using the constant
     names from `SQSService`, so the worker needs no configuration besides the region.

3. **Two-mode entry point:**
   - `java -jar worker.jar` runs the SQS loop (EC2 mode).
   - `java -jar worker.jar local <file>` keeps stage 1 behaviour, for debugging.
4. **`EC2Service` (common, `com.distributedsystems.aws`):**
   - `launch(Role, count, userData)` passes the AMI id, instance type, `LabInstanceProfile` (no credentials in
     jars) and base64 user-data, and tags each instance `Role=WORKER|MANAGER`. Before launching, it counts
     running and pending instances and **never exceeds 19 in total**.
   - `findActive(Role)`: tag plus state filter.
   - **Where `n` (`worker-files-n`) fits:** the worker never sees `n`. The manager computes
     `m = ceil(urlCount / n)` and `k = findActive(WORKER).size()`, then calls `launch(WORKER, m - k, …)`
     when `m > k`. `launch` caps the total at 19 instances, manager included, so at most 18 workers.
     Workers share one queue, so `n` sets how many workers exist, not how many messages each one handles.
   - `terminate(ids)`.
   - `UserData.worker(bucket, jarKey)` builds the boot script: download the jar from S3, run it, and log to
     `/var/log/worker.log`.
5. **Bootstrapping:** a custom Ubuntu AMI with Java 21 and Tesseract preinstalled.
   - **One AMI (`ocr-base-ami`) for both manager and workers.** The role is decided by the user-data, i.e. which
     jar it downloads and runs. The manager reads its own AMI id from the instance metadata service and uses it
     to launch workers, so the id is never hardcoded. The tess4j jar has no Linux
   native library, so Tesseract comes from the OS. The jar is pulled from S3 at boot, so code changes need no new
   AMI. Fallback if creating AMIs is blocked: install everything in user-data.

## Execution order
1. **Right after approval, the only action:** save this plan as `specs/worker-stage2.md`. Then **stop and wait**.
2. **User does (no Claude action):**
   1. Merge the friend's `sqs` PR into `main`, then merge `main` into `ec2-ocr`. The `worker/pom.xml`
      conflict resolves by keeping `common` + tess4j + JUnit + shade.
   2. Start the AWS Academy lab.
   3. Put the credentials in `~/.aws/credentials`.
   4. Note the region (likely `us-east-1`).
   5. Share the lecture notes if relevant.
   6. Tell Claude "go ahead".
3. **Manual EC2 experiment:**
   1. Launch an Ubuntu instance from the console with `LabInstanceProfile`.
   2. Install Java and Tesseract, then pull the jar from S3.
   3. Run `local sample-urls.txt` to verify tess4j finds the Linux library.
   4. Record the commands, then create the AMI.
4. Add `toMessage` with tests, then `WorkerLoop` with the heartbeat, then the two-mode `main`. Run the worker
   **locally against real SQS**: send a few `IMAGE_TASK` messages and read the results back.
5. Add `EC2Service` and `UserData` in `common`.
6. **Dev "fake manager" main:**
   1. Launch 1 worker via `EC2Service`.
   2. Send the sample URLs as tasks.
   3. Collect the results.
   4. Terminate the instance.
7. Verify in the console that nothing is left running. Record the AMI, instance type and timings for the README.

## Raise with the friend
- **Duplicate URLs:** the sample input repeats URLs. Add a **task index** to
  `IMAGE_TASK`/`OCR_DONE`/`OCR_ERROR` so the manager can place results by line.
- **Duplicate results:** SQS can deliver a message twice, and a worker can crash after sending but before
  deleting. The manager must ignore a result for an index it has already filled.
- **Visibility timeout:** around 60 s on the worker queue is enough with the heartbeat.
- **`n` is missing:** `NEW_TASK` has no `worker-files-n` field. The manager needs it to compute `m = ceil(urls/n)`.

## Verification
- Unit tests: `toMessage` output for success and failure, and message parsing in the loop.
- Local worker plus real SQS: sending N tasks produces N results in the results queue, and the task queue is
  empty afterwards.
- Kill test: stop the local worker mid-task, restart it, and the task is processed after the visibility timeout.
- EC2: the fake-manager run gets results for every sample URL from an EC2 worker, and the instance is terminated.

---

## Results (2026-11-19)

### What was built
| Piece | Where | Notes |
|---|---|---|
| `OcrResult.toMessage(clientId, taskIndex)` | `worker/.../ocr/result/` | `OcrSuccess` → `OCR_DONE`, `OcrFailure` → `OCR_ERROR`, both via `MessageProtocol` |
| `WorkerLoop` | `worker/.../WorkerLoop.java` | Long-poll 1 message → `processUrl` → send result → **then** delete. Heartbeat extends visibility by 60 s every 30 s. Poison messages are deleted. SQS errors are retried with backoff (1 s → 30 s). |
| `WorkerApp` (jar main class) | `worker/.../WorkerApp.java` | `java -jar worker.jar` = SQS mode; `java -jar worker.jar local <file>` = debug mode. Shutdown hook waits up to 25 s for the current task. Region from `AWS_REGION` (default `us-east-1`). |
| `EC2Service` | `common/.../aws/EC2Service.java` | `launch(role, count, amiId, userData)` with the 19-instance cap, `findActive(role)`, `countAllActive()`, `terminate(ids)`. Uses `t3.micro`, `LabInstanceProfile`, tags `Role` + `Name` set at launch. |
| `UserData.worker(bucket, jarKey)` | `common/.../aws/UserData.java` | Boot script: `/snap/bin/aws s3 cp` the jar to `/opt/ocr`, then `nohup java -jar` (log: `/var/log/worker.log`, bootstrap log: `/var/log/ocr-bootstrap.log`). Bucket and key are validated against shell injection. |
| `FakeManager` (dev only) | `common/src/test/.../FakeManager.java` | Launches 1 worker, sends a URL file as tasks, collects results by index (no deletes), terminates the worker. |

Tests: 24 unit tests (8 in `common`, 16 in `worker`). None of them call AWS.

### AWS resources (account of the user, `us-east-1`)
| Resource | Value |
|---|---|
| AMI | `ocr-base-ami` = `ami-07f5ffe53445e23b1`: Ubuntu 26.04.1 LTS (x86_64), `openjdk-21-jre-headless` 21.0.13, `tesseract-ocr` 5.5.0, AWS CLI v2 (snap). **No jar inside.** Shared by manager and workers. |
| Instance type | `t3.micro` |
| IAM | `LabInstanceProfile` (role `LabRole`). No credentials on instances or in jars. |
| S3 | `ocr-assignment-c8f120d5`: `jars/worker.jar`, `jars/sample-urls.txt` |
| SQS | `ocr-manager-to-workers-queue`, `ocr-workers-to-manager-queue` |

### Measured runs
| Test | Result |
|---|---|
| Local worker + real SQS (3 tasks) | 3/3 results with correct client and index; task queue empty afterwards |
| Kill test (local) | Worker hard-killed mid-task → task stayed in flight, no result → a new worker processed it after the 60 s visibility timeout → exactly one result per index |
| **EC2, FakeManager, 1 × t3.micro worker, 24 sample URLs** | **24/24 results (12 OK, 12 dead links) in 80 s total**; first result after 55 s (instance boot + jar download); ~25 s of OCR; instance terminated by the code |

### How to re-run the EC2 test
```
mvn -pl worker -am package "-Dtest=!SQSTest" -Dsurefire.failIfNoSpecifiedTests=false
aws s3 cp worker/target/worker-1.0-SNAPSHOT.jar s3://ocr-assignment-c8f120d5/jars/worker.jar
mvn -q -pl common test-compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -cp "common/target/classes;common/target/test-classes;$(cat common/target/cp.txt)" \
     com.distributedsystems.aws.FakeManager ami-07f5ffe53445e23b1 ocr-assignment-c8f120d5 \
     worker/src/test/resources/sample-urls.txt fake-manager-output.txt
```

### Notes for the manager (and the README)
- **Results arrive out of order** (SQS standard queues don't keep order). Place them by task index.
- **Duplicates are possible** (a worker crash between send and delete, or at-least-once delivery). Ignore a result for an index that is already filled.
- Visibility timeout: whoever creates `ocr-manager-to-workers-queue` first sets it (the worker uses 60 s, `SQSTest` uses 90 s). Either works, because the heartbeat extends it.
- The manager reads its own AMI ID from instance metadata and passes it to `EC2Service.launch`.
