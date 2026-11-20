# Plan: manager fixes (branch `manager-fixes`, from `main`), 2026-11-20

## Context
The friend won't change the manager on 2026-11-20/21, so we fix the manager side of the open issues ourselves
(`specs/open-issues.md` on `local-app-run`). Guiding rule: **the smallest change that works**. Every fix was
checked for a simpler version, and two were dropped.

| Fix | Issue | What | Status |
|---|---|---|---|
| M1 | 8 | The boot script passes the worker AMI ID to the manager (`UserData.manager`) | **Done** |
| M2 | 5 | On `TERMINATE` the manager changes its own tag `Role=MANAGER` to `Role=TERMINATING_MANAGER` | **Done** |
| M3 | 9 | Keep `NEW_TASK` until `JOB_DONE` | **Dropped**: the local app resends instead |
| M4 | 7 | Release leftover `NEW_TASK`s on `TERMINATE` | **Dropped**: they reappear after 30 s anyway |
| M5 | 6 | Don't delete results for unknown clients; count and terminate only your own workers | **Done** |

Out of scope: issue 10 (an inbound queue per manager), the "least-loaded fallback" TODO.

### Rules for this branch (to merge cleanly with `local-app-run`)
- **Only the `manager` module and this spec change**, plus `UserData` for M1. `EC2Service`, `SQSService` and
  `MessageProtocol` are not touched, because `local-app-run` changes `EC2Service`. No message-format changes.
- `specs/open-issues.md` and `specs/checkpoint.md` are **not** edited here (they differ on `local-app-run`). Their
  statuses are updated on `local-app-run` after both branches are merged (see "Follow-ups").
- The existing `ManagerLocalTest` must keep passing; new tests go there or in small new test classes.

## M1: worker AMI ID in the manager's boot script (issue 8), done
- **Problem:** `ManagerMain` stops with "WORKER_AMI_ID must be provided" unless the AMI comes from env/`args[1]`,
  but `UserData.manager` passed only the bucket. A manager started by the local app exited at once.
- **Fix:** `UserData.manager(bucket, jarKey, workerAmiId)` writes `java -jar manager.jar <bucket> <workerAmiId>`.
  `ManagerMain` already reads `args[1]`, so **the manager code doesn't change**. The local app already has the AMI
  ID (`local-app.properties`). `forJar` now takes any number of jar arguments, each checked by `requireSafe`.
  - Rejected: reading the manager's own AMI from instance metadata (more code and an HTTP call; only useful if
    manager and workers used different images, which they don't).
- **Touches `common`** (`UserData.java` + `UserDataTest`), an exception to the rule above. Safe, because
  `local-app-run` didn't change `UserData.java`.
- **Tests:** the manager script passes bucket and AMI as arguments; an unsafe or missing AMI ID is rejected.

## M2: terminating marker (issue 5), done
- **Fix:** reuse the existing `Role` tag instead of adding a new one.
  - `ComputeClient.markTerminating()` (default no-op, like `updateActiveClients`).
  - `EC2ComputeClient` overwrites its own `Role=MANAGER` with `Role=TERMINATING_MANAGER` (constant
    `TERMINATING_ROLE`).
  - `ManagerEngine` calls it as soon as it reads `TERMINATE`. A failure is logged and doesn't stop the shutdown.
- **Why `Role`:** local apps search for `Role=MANAGER`, so a terminating manager simply isn't found any more. The
  locator needs no change and no new shared constant. Nothing else depends on `Role=MANAGER` (the cap counts all
  instances; worker cleanup uses `Role=WORKER`).
- **Tests:** in `ManagerLocalTest`'s end-to-end test: not marked before `TERMINATE`, marked after.

## M3: DROPPED, the local app resends `NEW_TASK` instead (issue 9)
- **Was:** hold each `NEW_TASK` until `JOB_DONE`, with a heartbeat thread and duplicate merging (~40 lines of
  threaded code in the engine).
- **Instead (local app, step 8, ~1 line):** when the local app replaces a manager that is **gone** (instance no
  longer pending/running), it resends its `NEW_TASK` (same clientId, input key, response queue and n).
- **Why it's safe without manager changes:** the current manager already tolerates two `NEW_TASK`s for one
  `clientId`. `activeJobs.put` replaces the first job, results are stored by index (duplicates ignored), the job
  completes once and one `JOB_DONE` is sent, and late results for it are deleted harmlessly. Worst case: some OCR
  done twice; the result is still correct.
- **"Gone" means not running, not "terminating":** a terminating manager still finishes the jobs it accepted, so
  replacing it early would only duplicate work. So `isAlive` stays `EC2Service.isActive(id)`.
- **Known limit (README):** if a manager dies, its job restarts from scratch.

## M4: DROPPED, not needed (issue 7)
- A `NEW_TASK` left in the batch after `TERMINATE` is hidden only for the queue's visibility timeout (30 s). The
  only manager that could take it is a **new** one, which takes about a minute to boot. By then the `NEW_TASK` is
  visible again by itself. The `break` is harmless: it ends the client listener of a manager that doesn't take new
  jobs anyway.
- The waiting local app (step 8) notices when its manager is gone, starts a new one and resends (see M3).

## M5: two managers don't hurt each other (issue 6), done
- **Results for unknown clients** (e.g. they belong to another manager):
  - Today: deleted, so the other manager's job never finishes.
  - Fix: **just don't delete them.** They reappear after the visibility timeout and the other manager takes them.
    After a resend (M3), the new job has the same `clientId`, so results from the dead manager's run are even
    reused.
  - **Late duplicates for a job this manager already finished** are still deleted, as before: the engine keeps a
    set of finished client IDs (`finishedClients`). Without it, a duplicate result arriving after `JOB_DONE` would
    look "unknown" and circulate.
  - `handleWorkerResult` returns whether the result is ours; the loop deletes only then.
  - Known limit: truly orphaned results (no manager owns that `clientId`) circulate until SQS drops them (default
    retention 4 days). Rare and cheap (a few receives).
- **Own workers only:**
  - Today: `terminateAllWorkers` kills every `Role=WORKER` instance, including another manager's workers, and
    `getActiveWorkerCount` counts them too. A new manager's job could then stall with zero workers.
  - Fix: `EC2ComputeClient` remembers the IDs that `launch` returned. `getActiveWorkerCount` and
    `terminateAllWorkers` only use its own IDs that are still active. Method names stay, documented as "this
    manager's workers".
  - Known limit: a terminated worker may be mid-task for the other manager. The task reappears after its
    visibility timeout, so nothing is lost.
- **Tests:** `ManagerLocalTest`: an unknown result isn't ours (not deleted), a known one is, and a late duplicate
  for a finished job is too. New `EC2ComputeClientTest` (stub `EC2Service`): counts and terminates only the
  instances it launched.
- **Status: done 2026-11-20.**

## Execution order (one step at a time: stop, explain, wait for the go-ahead)
1. This spec. **Done.**
2. M1. **Done.**
3. M2. **Done.**
4. M5 + tests. **Done.**
5. Build `manager.jar`; all `common`, `worker`, `manager`, `local-app` tests pass. The user opens the PR.

## Follow-ups on `local-app-run` (after both branches are merged into `main`)
- `Ec2ManagerLocator`: pass `amiId` to `UserData.manager(bucket, jarKey, amiId)` (won't compile until then).
- `Ec2ManagerLocator`: **no other change**. Choosing skips terminating managers by itself (`findActive(MANAGER)`),
  and `isAlive` stays "instance pending/running".
- Step 8: while waiting, if the manager is gone, call `ensureAvailableManager` again **and resend `NEW_TASK`**.
- `specs/open-issues.md` / `specs/checkpoint.md`: issues 5, 6, 7, 8, 9 → resolved or partial, with what was done
  here (and why M3/M4 were dropped).
- Step 10 (the full cloud run) is unblocked once `manager.jar` with M1 is uploaded to `jars/manager.jar`.
