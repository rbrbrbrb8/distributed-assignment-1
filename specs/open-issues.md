# Open issues: local app ↔ manager (found 2026-11-19)

Status legend: **OPEN** = needs a decision with the friend; **WAITING** = known, the friend is working on it;
**RESOLVED** = fixed in `main`; **PARTIAL** = partly fixed.

## Status after the manager fixes PR #10 (checked 2026-11-20; details in `specs/manager-fixes.md`)
| # | Issue | Status | Local-app follow-up |
|---|---|---|---|
| 1 | Summary format loses line boundaries | **RESOLVED** (single-line Base64 entries) | `Base64SummaryParser` (done) |
| 2 | Summary location | **RESOLVED** (`S3Service.summaryKey`) | none |
| 3 | `manager-clients-n` | **RESOLVED** (`ActiveClients` tag + locator) | Locator compares the tag with this run's `manager-clients-n` (done, step 7) |
| 4 | Manager main / jar / boot script | **RESOLVED** (with issue 8) | none |
| 5 | Terminating manager looks available | **RESOLVED** (M2: `Role=TERMINATING_MANAGER`) | none: `findActive(MANAGER)` skips it |
| 6 | Two managers alive at once clash | **RESOLVED** (M5: unknown results kept; own workers only) | none |
| 7 | `TERMINATE` + `NEW_TASK` race strands a job | **RESOLVED by design** (M4 dropped: the `NEW_TASK` reappears after 30 s) | Step 8: replace a gone manager and resend |
| 8 | Manager started by the local app gets no worker AMI ID | **RESOLVED** (M1: AMI in the boot script) | `Ec2ManagerLocator` passes `amiId` (done) |
| 9 | A manager crash loses the job | **RESOLVED by the local app** (M3 dropped) | Step 8: resend `NEW_TASK` when replacing a gone manager |
| 10 | The local app's choice of manager isn't enforced (shared inbound queue) | **OPEN** (accepted limit) | none now; future TODO: fall back to the least-loaded manager when no instance can be launched |
| 11 | `ActiveClients` tag counts a running job twice | **RESOLVED** (branch `local-app-run`, 2026-11-21) | none |

## 1. Summary file loses line boundaries (RESOLVED in PR #8)
- **Resolution:** `ClientJob` now writes one line per image, in input order, separated by `\n`:
  `url | OCR: <Base64(text)>` or `url | ERROR: <Base64(error)>` (UTF-8 inside the Base64). Base64 contains no
  newlines, so each entry is exactly one line. A missing result is written as an ERROR entry ("Missing result").
- **Local app:** parse it with a new `Base64SummaryParser`; the old `PipeSummaryParser` assumed plain text.

(Original description:)
- **Today:** `ClientJob.buildSummaryContent()` writes one `url | OCR: text` or `url | ERROR: error` per image,
  joined with `\n`.
- **Problem:** OCR text itself contains newlines (e.g. `ABCDEFGHIJKLM\nNOPQRSTUVWXYZ`), so the local app can't
  tell where one image's result ends and the next begins.
- **Direction:** encode each entry safely, e.g. Base64 text like the worker messages, or one escaped/JSON line per
  image. Keep the index so the order is explicit.

## 2. Summary location doesn't use the agreed S3 layout (RESOLVED in PR #8: `ManagerEngine` uses `S3Service.summaryKey`)
- **Today:** the manager uploads to `outputs/summary-<clientId>.txt`.
- **Agreed layout** (`S3Service`, `specs/s3-service.md`): `S3Service.summaryKey(clientId)` =
  `jobs/<clientId>/summary.txt`, so each job lives in one folder and cleanup is a single `deletePrefix`.
- It works either way, since the key travels in `JOB_DONE`, but it should switch to the helper.

## 3. `manager-clients-n` not implemented (PARTIAL in PR #8)
- **Done:** the manager keeps an `ActiveClients=<k>` tag on its own instance up to date
  (`EC2ComputeClient.updateActiveClients`, tag key `EC2ComputeClient.ACTIVE_CLIENTS_TAG_KEY`).
- **Remaining (local app):** treat a manager as available only if `ActiveClients < manager-clients-n`. The value of
  `manager-clients-n` doesn't need to reach the manager, because the local app does the comparison.

(Original description:)
- **Spec:** an *available* manager is active **and** serves fewer than `manager-clients-n` clients. Otherwise the
  local app starts a new manager.
- **Today:** the manager doesn't count clients, and nothing exposes the count to local apps.
- **Needs:** a mechanism visible to local apps, e.g. a manager instance tag `ActiveClients=<k>` kept up to date.
  The value of `manager-clients-n` itself must also reach the manager (e.g. in `NEW_TASK` or user-data).

## 4. No manager entry point / jar / boot script yet (PARTIAL in PR #8)
- **Done:** `ManagerMain`, a shaded `manager.jar`, and `UserData.manager(bucket, jarKey)`, which runs
  `java -jar manager.jar <bucket>`. The remaining gap is issue 8.

(Original description:)
- The local app's "start a manager" step needs a manager `main`, a `jars/manager.jar` in S3, and a
  `UserData.manager(...)` boot script (same pattern as `UserData.worker`).

## 5. A terminating manager must not look "available" (OPEN)
- **Spec (manager on TERMINATE):** stop accepting input, finish the current jobs and send their responses,
  terminate the workers, then terminate itself. `ManagerEngine` already does this.
- **Spec (local app):** if no *available* manager exists, **start a new one**. A terminating manager is not
  available, so a later local app must **start a new manager, not be rejected**.
- **Today:** `EC2Service.findActive(MANAGER)` still returns the terminating manager.
- **Needs:** the manager marks itself on TERMINATE, e.g. tag `State=TERMINATING`, and the local app skips such
  managers. This could share the mechanism with issue 3.

## 6. Two managers alive at once clash (OPEN, important)
Overlap scenario: the old manager is finishing its jobs after TERMINATE while a new manager serves new clients.
Both use the same queues and the same `Role=WORKER` tag.
1. **Stolen results.** `ManagerEngine.pollWorkerResults` ignores results for unknown clients (`job == null`) **but
   still deletes them**. The old manager can swallow results meant for the new manager, so those jobs never
   complete.
2. **Killed workers.** `terminateAllWorkers()` terminates every `Role=WORKER` instance, **including the new
   manager's workers**. Their tasks reappear after the visibility timeout, so no data is lost, but the new manager
   is left without workers and doesn't know it.

Possible fixes, to decide together:
- give each manager generation its own identity: a manager ID in its queue names and in its workers' tags, so
  generations never share anything; or
- at minimum, **don't delete results for unknown clients** (leave them for the other manager), and terminate
  **only the workers this manager launched**, tracked by instance ID.

Note: the spec lets us assume no race between two local apps **starting** a manager at the same moment (page 8),
but it doesn't cover this overlap, which is a normal sequence of events. It's also a good grading answer for
"termination" and "persistence".

## 7. `TERMINATE` and `NEW_TASK` arriving together: the late job is stranded (OPEN)
**Owner:** the local-app side (fix 1 below) is **the user's responsibility, as part of the local-app feature**.
The manager side (fix 2) has **no owner yet**.

**Scenario:** app A sends `TERMINATE` and app B sends `NEW_TASK` to a running manager at about the same time.
`ocr-app-to-manager-queue` is an SQS *standard* queue, so **order is not guaranteed**: the manager may see
`TERMINATE` first even if `NEW_TASK` was sent first.

**Expected (spec):** after `TERMINATE` the manager "does not accept any more input files", but B's job must still
get done. Per the local-app rule, when no *available* manager exists, a new one is started.

**Actual (current `ManagerEngine.pollClientRequests`):**
- If `TERMINATE` is first in the received batch, the manager sets `terminating`, deletes `TERMINATE`, and hits
  `break`. The rest of the batch, including B's `NEW_TASK`, is **left undeleted**. The loop then exits, so the
  inbound queue is **never read again** by this manager.
- B's `NEW_TASK` isn't lost (it reappears after its visibility timeout), but **nobody is listening**. The old
  manager finishes its existing jobs and shuts down.
- B saw a running manager, sent its task, and now **waits forever** for `JOB_DONE`. Its job only runs if some
  later local app happens to start a new manager while B is still waiting.
- If `NEW_TASK` is first in the batch, it's accepted and then termination happens normally. **The outcome depends
  on timing.**

**Fixes (they combine well):**
1. **Local-app side (the user's, part of the local-app feature).** While waiting for `JOB_DONE`, the local app
   periodically checks **the manager it sent to**: is it still running and not marked terminating (see issue 5)?
   If the manager is gone or terminating and no `JOB_DONE` has arrived, the local app **starts a new manager**.
   The `NEW_TASK` is still in the inbound queue, so the new manager picks it up and no resend is needed. This also
   covers a **manager crash** mid-job (the spec's "what if a node dies?").
2. **Manager side (owner TBD, optional but nicer).** On `TERMINATE`, drain the remaining `NEW_TASK`s and answer
   each with a new `REJECTED` message on that client's response queue, so the local app reacts immediately instead
   of noticing on its next check. This needs a new `MessageType` in `MessageProtocol`. The spec's "Creates
   response messages for the jobs, if needed" may hint at this.

## 8. A manager started by the local app gets no worker AMI ID (OPEN, friend's side, found 2026-11-20)
- **Today:** `ManagerMain` requires the worker AMI ID (`WORKER_AMI_ID` env var or `args[1]`) and stops with
  "WORKER_AMI_ID must be provided" otherwise. But `UserData.manager(bucket, jarKey)` starts it with **only the
  bucket**: `java -jar manager.jar <bucket>`. So a manager launched by the local app would exit immediately.
- **Fix options:**
  - **(recommended)** when no AMI ID is given, `ManagerMain` reads **its own AMI ID** from instance metadata
    (`http://169.254.169.254/latest/meta-data/ami-id`, with the same IMDSv2 token flow `EC2ComputeClient` already
    uses for the instance ID). Manager and workers share `ocr-base-ami`, so nothing has to be passed around.
  - or `UserData.manager` also passes the AMI ID as a second argument.
- **Blocks:** the full cloud run (local app → manager on EC2 → workers). Local testing with a stub manager isn't
  affected.

## 9. A manager crash loses the job (OPEN, found 2026-11-20)
**Owner:** fix 1 is **the user's (local-app side, PR 2)**; fix 2 is **the friend's (manager side)**.

**Scenario:** the manager crashes (or its instance dies) after the workers finished a client's images but before it
wrote the summary and sent `JOB_DONE`.

**Today:** the job can't be recovered:
- the manager keeps results **only in memory** and has already deleted the worker results from the queue;
- the workers have deleted their tasks;
- the manager deleted the client's `NEW_TASK` **as soon as it accepted the job**, so no copy is left in any queue.

A new manager (started by the local app's liveness check, issue 7) doesn't know the job exists, so the local app
waits until its timeout and fails.

**Fixes (they combine well):**
1. **Local-app side (the user's, PR 2):** when the local app replaces a dead manager, it **resends its `NEW_TASK`**
   (same clientId, input key, response queue and n). The new manager **restarts the job from scratch**: some OCR is
   redone, but the result is correct. Before launching, the local app waits a random 0–10 s and checks again for an
   active manager, so several waiting apps don't each start one. The spec allows assuming no launch races (page 8);
   this only makes duplicates unlikely.
2. **Manager side (friend's):** delete `NEW_TASK` only **after** `JOB_DONE` is sent, extending its visibility while
   the job runs (the same heartbeat idea as the worker). If the manager dies, the `NEW_TASK` reappears and any
   manager restarts the job, even if the local app is slow to notice.
3. **Optional, more work:** *resume* instead of restart. The manager saves partial results to S3
   (e.g. `jobs/<clientId>/partial/…`) and a new manager loads them before sending only the missing tasks.

## 10. The local app's choice of manager isn't enforced (OPEN, found 2026-11-20)
**Owner:** both sides (any real fix touches the manager); no owner yet.

**Background:** `manager-clients-n` is a **per-run** argument of the local app (each run may pass a different
value). It never reaches the manager. The manager only publishes its count (`ActiveClients` tag), and each local
app compares that count with **its own** limit (issue 3). So "available" is judged from the asking app's point of
view.

**Scenario** (`manager-clients-n = 2`):
1. Manager M1 runs with `ActiveClients=2`, so it's full.
2. Local app C sees that and starts manager M2, as the spec says.
3. C sends `NEW_TASK` to `ocr-app-to-manager-queue`, which **all** managers read.
4. M1 is full but keeps polling (nothing tells it to stop), while M2 is still booting.
5. M1 takes C's job and now has 3 clients. M2 sits idle.

**Effect:** the limit isn't enforced, extra managers may start and do nothing (cost), and issue 6 becomes more
likely. Same root cause as why each job has its own response queue: a shared queue can't route to a chosen
receiver.

**Fixes:**
1. **(preferred) An inbound queue per manager**, e.g. `ocr-app-to-manager-<instanceId>`. The local app sends
   `NEW_TASK` to the queue of the manager it chose (`ManagerHandle` can carry the queue URL). Works naturally with
   per-run `manager-clients-n` values. Needs manager changes.
2. **Full managers stop polling** while `ActiveClients ≥ manager-clients-n`. But the manager doesn't know the
   limit, and different apps may pass different values, so a rule is needed (first client's? smallest? fixed in the
   boot script?). Still racy: a manager at 1 of 2 can grab two tasks at once.

### Related: what happens at the instance cap
`EC2Service.MAX_INSTANCES` = **9**, counted **globally** (all instances in the account, every role). `launch`
never throws because of the cap: it launches **fewer or none** and returns the IDs it did launch.
- **Manager wants more workers:** it gets fewer. The job still finishes, more slowly, because the remaining
  workers keep pulling tasks. Edge case: zero workers and zero free slots → tasks wait until the local app's
  timeout. `WorkerScaler` only scales when a new job arrives, so a short launch isn't retried later.
- **Local app wants a new manager but `launch` returns nothing:** today the locator (step 7) will **fail with a
  clear error**.
  - **Future TODO (not planned now, judged overkill for the assignment):** fall back to the **least-loaded
    existing manager**, even if it's over this app's `manager-clients-n`. A slower job beats no job, and with
    issue 10 the job might land on a full manager anyway.
- **AWS itself refuses** (Academy limits): `runInstances` throws. Keeping our cap at 9 makes our own check stop us
  first.

## 11. The `ActiveClients` tag counts a running job twice (RESOLVED 2026-11-21, found in the first cloud run)
- **Seen:** one client, but the manager's tag said `ActiveClients=2`.
- **Cause:** the tag is `activeJobs.size() + pendingJobSetups`. When a job's setup finished, `handleNewTask` added
  it to `activeJobs` and rewrote the tag while `pendingJobSetups` still counted it (1 + 1 = 2). The `finally` that
  decrements `pendingJobSetups` rewrote the tag only when the setup **failed**, so after a success the 2 stayed.
- **Effect:** each manager looked one client fuller than it was. With `manager-clients-n=2`, a second app would
  have started a new manager instead of joining.
- **Fix (`ManagerEngine`):** the `finally` always rewrites the tag after decrementing. The tag can still read one
  too high for the few milliseconds in between, which is the safe direction (at worst an extra manager, never an
  overloaded one).
- **Test:** `ManagerLocalTest.activeClientsTagShowsOneWhileOneJobRuns` (fails with `expected: <1> but was: <2>`
  on the old code). Confirmed on AWS in run 3 of `specs/local-app.md` step 10: the tag showed 1, then 2 with two
  clients.
- **Note:** `jars/manager.jar` in S3 was built from `local-app-run` with this fix, so it is ahead of `main` until
  that branch is merged.
