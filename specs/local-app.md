# Plan: Local application (new `local-app` module), built so the open gaps plug in later

## Context
The user owns the local app. The friend's manager is in `main` (`manager/.../ManagerEngine.java`), but several
contract pieces are still open. They're listed in `specs/open-issues.md`:
1. summary format;
2. summary location;
3. `manager-clients-n`;
4. manager jar/main/boot script;
5. terminating marker;
6. two managers clashing;
7. a stranded `NEW_TASK` on terminate. The local-app side of issue 7 is the user's.

Goal: build and test the whole local app **now**. Each part that depends on an open issue sits behind a small
seam (an interface, or one isolated class), so filling a gap later means changing that one class and nothing else.

Already available and reused:
- `common`:
  - `S3Service`: `inputKey`, `summaryKey`, `jobPrefix`, upload/download, `deletePrefix`;
  - `SQSService`: `getOrCreateQueue`, `sendMessage`, `receiveMessages` (20 s long poll), `deleteMessage`,
    `deleteQueue`, and the `APP_TO_MANAGER_QUEUE` constant;
  - `MessageProtocol`: `createNewTaskMsg(clientId, responseQueueUrl, s3InputKey, n)`, `createTerminateMsg()`,
    `parse`, the `JOB_DONE` type;
  - `EC2Service`: `findActive(MANAGER)`, `launch(...)`, the global instance cap (9 since PR 2, was 19);
  - `UserData`: the `forJar(...)` pattern.
- The manager in `main` already accepts `NEW_TASK` and `TERMINATE` and replies `JOB_DONE###<summaryKey>` on the
  given response queue.

**Note:** the `local-app` branch was cut from the `s3-service` commit, so it doesn't have the `manager` module
from `main` yet. The user merges `main` into it first (step 0).

## Run command (spec)
```
java -jar local-app.jar inputFileName outputFileName manager-clients-n worker-files-n [terminate]
```
Non-secret settings (bucket, AMI ID, region) come from `local-app.properties` in the working directory, with no
credentials in it. AWS credentials come from `~/.aws/credentials` through the SDK's default chain.

## Design: package `com.distributedsystems.localapp`

### Flow (`LocalApp.run()`), following the spec's steps
1. Parse and validate the arguments (`LocalAppArgs`). Generate `clientId = UUID`.
2. **`managerLocator.ensureAvailableManager(managerClientsN)`**: find an available manager or start one. Returns
   a `ManagerHandle` (instance ID).
3. Upload the input to `S3Service.inputKey(clientId)`, i.e. `jobs/<clientId>/input.txt`.
4. Create **this job's own response queue**, `ocr-client-response-<clientId>` (via `getOrCreateQueue`), named
   after the client ID so the queue, S3 folder and messages share one ID.
5. Send `NEW_TASK` to `APP_TO_MANAGER_QUEUE`.
6. **Wait for `JOB_DONE`** on the response queue with 20 s long polls:
   - **Every ~60 s, check liveness (issues 7 and 9):** if `!managerLocator.isAlive(handle)` (the instance is no
     longer pending/running) and there's no `JOB_DONE` yet, call `ensureAvailableManager` again **and resend the
     same `NEW_TASK`** (decided 2026-11-20, see `specs/manager-fixes.md` M3). A terminating manager still counts as
     alive: it finishes the jobs it accepted.
   - **Overall timeout** (configurable, default 60 min), so the app can never hang forever. On timeout it exits
     with a clear error.
7. Download the summary from the key in `JOB_DONE` (whatever the manager uses, so issue 2 doesn't block this).
8. `summaryParser.parse(summary)` gives a `List<OcrResult>` (in input order), then `HtmlReport.render(...)` writes
   `outputFileName`.
9. If `terminate` was given, send `TERMINATE` to `APP_TO_MANAGER_QUEUE` (spec step 17, after the results).
   **Also on failure or timeout** (it's in the `finally`), so a failed run doesn't leave paid instances running.
10. **Cleanup in a `finally` block,** so it also runs on failure. Each step is best effort (a failure is logged and
    never hides the real error):
    - delete the response queue;
    - `deletePrefix(jobPrefix(clientId))`. The summary lives inside the job folder since issue 2 was fixed, so the
      planned "delete the summary key if it's outside the folder" was dropped.

### Seams for the open gaps
| Seam | Implementation now | Gap it isolates | Later change (only here) |
|---|---|---|---|
| `ManagerLocator` interface: `ensureAvailableManager(int managerClientsN)`, `isAlive(ManagerHandle)` | `Ec2ManagerLocator`: available = any `Role=MANAGER` instance that is pending or running; otherwise `EC2Service.launch(MANAGER, 1, amiId, UserData.manager(bucket, "jars/manager.jar"))`. `isAlive` = the instance is still pending or running. | 3 (client count), 5 (terminating marker), 4 (manager jar) | Add a `ActiveClients < managerClientsN` and `State != TERMINATING` check in both methods. The manager jar only has to exist in S3. |
| `UserData.manager(bucket, jarKey)` in `common` | Same pattern as `worker(...)`: download `jars/manager.jar`, `nohup java -jar`, log to `/var/log/manager.log` | 4 | Add arguments if the manager's `main` needs any |
| `SummaryParser` interface: `List<OcrResult> parse(String)` | Was `PipeSummaryParser` (best effort, plain text). **Since PR 2: `Base64SummaryParser`** for the manager's final format, one line per image: `url \| OCR: <Base64>` / `url \| ERROR: <Base64>`. `PipeSummaryParser` was deleted. | 1 (format), now resolved | A new parser class if the format changes again; switch one line in the wiring |
| Summary key from `JOB_DONE` | Used as given; cleanup also deletes it if it lies outside `jobs/<clientId>/` | 2 | Nothing needed once the manager uses `summaryKey(clientId)` |

### Result model and HTML (local-app logic, independent of the gaps)
- **Shared result types (decided 2026-11-20):** the worker's sealed `OcrResult` / `OcrSuccess(url, text)` /
  `OcrFailure(url, error)` moved from `worker` to **`common`** (`com.distributedsystems.ocr.result`). They are the
  same concept in every component: "the outcome for one image URL". So the local app uses them directly, with no
  duplicate types. The local app can't depend on `worker`, which would pull in tess4j and the native OCR
  libraries.
  - **No index field:** the summary lists results in input order, so the position in the list is the index.
- `HtmlReport.render(List<OcrResult>)` uses a **`switch` over the sealed types**, not a `toHtml()` method on them.
  - **Rule:** behaviour that's part of the shared contract (`url()`, `toMessage`) goes on the type. Presentation
    that only one component owns (HTML, owned by the local app) stays in that component. Putting `toHtml()` in
    `common` would make the worker and manager carry HTML code, and any layout change would touch shared code.
  - The `switch` is compiler-checked for completeness, because the interface is sealed.
- **Markup follows the assignment's sample** (`output-example.html`): `<title>OCR</title>`, one `<p>` per image.
  - **Success:** `<a href=url><img src=url></a><br>text`. The image is linked to the original, as the spec text
    requires. Newlines in the text become `<br>`.
  - **Error:** `<p><a href=url>url</a> error</p>`, exactly the spec's "`<link to input file> <short description>`".
  - **UTF-8.** Everything (text and URLs) is HTML-escaped.
- **The sample input is `worker/src/test/resources/words-urls.txt`.** It matches the two images in
  `output-example.html`.

### Files
| File | Change |
|---|---|
| `pom.xml` (root) | Add `<module>local-app</module>` |
| `local-app/pom.xml` | **New**: depends on `common`, JUnit; shade plugin with `mainClass` `LocalAppMain`, `createDependencyReducedPom=false` |
| `local-app/src/main/java/com/distributedsystems/localapp/` | **New**: `LocalAppMain`, `LocalAppArgs`, `LocalAppConfig`, `LocalApp`, `ManagerLocator`, `ManagerHandle`, `Ec2ManagerLocator`, `SummaryParser`, `Base64SummaryParser` (replaced `PipeSummaryParser`), `HtmlReport` |
| `common/src/main/java/com/distributedsystems/ocr/result/` (moved from `worker`) | `OcrResult`, `OcrSuccess`, `OcrFailure` (+ `OcrResultMessageTest`); worker imports updated |
| `common/.../aws/UserData.java` (+ test) | Add `manager(bucket, jarKey)` |
| `local-app/src/test/java/...` | Unit tests (below), plus a dev-only `StubManager` `main` |
| `local-app.properties.example` | **New**: documents `bucket=`, `amiId=`, `region=`, `waitTimeoutMinutes=` (no secrets) |
| `specs/local-app.md` | **New**: this plan |

## Execution order (one step at a time: stop, explain, wait for the go-ahead)
0. **User:** merge `main` into `local-app`.
1. Save this plan as `specs/local-app.md`. **Stop.**
2. Module skeleton (`pom`, root module entry), then `LocalAppArgs` (parse and validate) and `LocalAppConfig`
   (properties), with tests.
3a. Move `OcrResult` / `OcrSuccess` / `OcrFailure` (+ test) from `worker` to `common`, and update the worker
    imports. **Done 2026-11-20.**
3b. `HtmlReport` (a `switch` over the sealed types), with tests: escaping, success and error lines, order, empty
    list, multi-line text.
4. `SummaryParser` and `PipeSummaryParser`, with tests (multi-line OCR text, errors, empty summary).
   **Steps 2–4 merged as PR 1 (#9, "initial local app").**

### PR 2 (branch `local-app-run`), revised 2026-11-20 after the friend's manager PR #8
The friend's PR resolved open issues 1 and 2 and partly 3 and 4 (see `specs/open-issues.md`). It also added
`UserData.manager(bucket, jarKey)`, so the stashed duplicate was dropped. Only `EC2Service.isActive` was kept from
the stash.

5. **Specs:** update `specs/open-issues.md` (statuses, plus new issue 8: the manager AMI bug) and this step list.
   **Done 2026-11-20.**
6. **(Done 2026-11-20; `PipeSummaryParser` deleted. Includes contract tests against the manager's real
   `ClientJob`, via a test-scope dependency on `manager` that is not packaged into the jar.)**
   `Base64SummaryParser implements SummaryParser` for the manager's format: one line per image,
   `url | OCR: <Base64>` / `url | ERROR: <Base64>`. It becomes the default parser. Tests cover:
   - round trip with multi-line text and Unicode;
   - errors;
   - an empty summary;
   - a malformed line (clear error).
7. **(Done 2026-11-20; package `localapp.manager`. The least-loaded available manager is chosen; a manager with
   an unreadable tag is skipped; `EC2Service.ACTIVE_CLIENTS_TAG` has a contract test against the manager's
   constant.)**
   `ManagerLocator`, `ManagerHandle`, `Ec2ManagerLocator`, plus a small tag reader in `EC2Service`, e.g.
   `getTag(instanceId, key)`.
   - **Available** = pending/running `Role=MANAGER`, **and** its `ActiveClients` tag `< manager-clients-n` (open
     issue 3). A missing tag counts as 0 (a manager that just booted).
   - Otherwise launch with `UserData.manager(bucket, jarKey)` from `main`. If `launch` returns no instance (the
     global cap of 9 is reached), fail with a clear error. Falling back to the least-loaded manager is a future
     TODO (issue 10), not part of this step.
   - `isTerminating` stays a TODO (open issue 5).
   - Tests use a stub `EC2Service`.
8. **(Done 2026-11-20. `LocalAppTest`: 6 tests with an in-memory SQS whose fake "manager" answers `NEW_TASK`.
   Liveness is checked every 60 s in the jar. The random wait + re-check before relaunching was dropped: the spec
   lets us assume no launch races, and the locator re-checks anyway. Exit codes: 0 done, 1 run failed, 2 bad
   arguments/settings.)**
   `LocalApp` orchestration and `LocalAppMain`, with flow tests using stub `SQSService`, `S3Service` and
   `ManagerLocator`. Covers:
   - the happy path;
   - a dead manager while waiting, which triggers a relaunch **and a resend of `NEW_TASK`** (issues 7 and 9);
   - a timeout;
   - `terminate` sent last;
   - cleanup runs on failure.
9. **(Skipped 2026-11-21: folded into step 10.)** Once the real manager could run on EC2 (PR #10), a stub manager
   was no longer worth building: the cloud runs in step 10 cover the same checks, including two apps at once.
10. **(Done 2026-11-21.)** Full cloud runs: local app → manager on EC2 → workers. Watched live through the app's
    output and a read-only status script polling EC2, SQS and S3 every 15 s (no SSH to the instances).
    `waitTimeoutMinutes` was set to 10 in `local-app.properties` for the runs.

    | Run | Setup | Result |
    |---|---|---|
    | 1 | `words-urls.txt` (2 URLs), `n=1`, `terminate` | Manager started by the app, 2 workers launched by the manager (first real use of `LabRole`), HTML matches the sample's layout. `JOB_DONE` ~1.5 min after the start (cold), everything terminated ~2.5 min after the start. **Found the `ActiveClients` bug (issue 11).** |
    | 2 | App A (2 URLs, `n=1`), then app B (24 URLs `sample-urls.txt`, `n=6`, `terminate`), `manager-clients-n=2` | Both used the same manager; 2 → 4 workers; each HTML has only its own URLs. A finished before B arrived, so no real overlap. B's 24 images took ~40 s with 4 warm workers. |
    | 3 | B first (24 URLs, `n=6`), A started once the tag showed 1 (`n=1`, **A** sends `terminate`) | Real overlap: A joined B's manager, tag showed `ActiveClients=2`, `TERMINATE` arrived while B's job ran and the manager finished B (24 results) before terminating. 2 min 18 s in total. |

    In every run, cleanup left no instances, no response queue and an empty `jobs/`. The OCR of the sample's second
    image reads "3 = a = i ote" where `output-example.html` shows "PEACE": a Tesseract accuracy limit, not a
    pipeline bug. In `sample-urls.txt`, 12 of 24 links are dead today (HTTP 404, `ConnectException`); they show as
    link + error, as the spec asks.

## Verification
- Unit tests for args, config, HTML (escaping), the parser, the locator logic, `UserData.manager`, and the
  `LocalApp` flow with stubs. No AWS calls.
- Step 7: the HTML file opens in a browser with images and text for valid URLs, and link plus error for dead ones.
  The response queue and `jobs/<clientId>/` are gone afterwards. Two concurrent apps each get the correct, separate
  output.
- All existing `common`, `worker` and `manager` tests still pass.
