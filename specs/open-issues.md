# Open issues: local app ↔ manager (found 2026-11-19)

Status legend: **OPEN** = needs a decision with the friend; **WAITING** = known, the friend is working on it.

## 1. Summary file loses line boundaries (OPEN, discuss later)
- **Today:** `ClientJob.buildSummaryContent()` writes one `url | OCR: text` or `url | ERROR: error` per image,
  joined with `\n`.
- **Problem:** OCR text itself contains newlines (e.g. `ABCDEFGHIJKLM\nNOPQRSTUVWXYZ`), so the local app can't
  tell where one image's result ends and the next begins.
- **Direction:** encode each entry safely, e.g. Base64 text like the worker messages, or one escaped/JSON line per
  image. Keep the index so the order is explicit.

## 2. Summary location doesn't use the agreed S3 layout (WAITING)
- **Today:** the manager uploads to `outputs/summary-<clientId>.txt`.
- **Agreed layout** (`S3Service`, `specs/s3-service.md`): `S3Service.summaryKey(clientId)` =
  `jobs/<clientId>/summary.txt`, so each job lives in one folder and cleanup is a single `deletePrefix`.
- It works either way, since the key travels in `JOB_DONE`, but it should switch to the helper.

## 3. `manager-clients-n` not implemented (WAITING)
- **Spec:** an *available* manager is active **and** serves fewer than `manager-clients-n` clients. Otherwise the
  local app starts a new manager.
- **Today:** the manager doesn't count clients, and nothing exposes the count to local apps.
- **Needs:** a mechanism visible to local apps, e.g. a manager instance tag `ActiveClients=<k>` kept up to date.
  The value of `manager-clients-n` itself must also reach the manager (e.g. in `NEW_TASK` or user-data).

## 4. No manager entry point / jar / boot script yet (WAITING)
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
