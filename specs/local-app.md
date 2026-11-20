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
  - `EC2Service`: `findActive(MANAGER)`, `launch(...)`, the 19-instance cap;
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
   - **Every ~60 s, check liveness (issue 7, the user's fix):** if `!managerLocator.isAlive(handle)` and there's
     no `JOB_DONE` yet, call `ensureAvailableManager` again. The `NEW_TASK` is still in the inbound queue, so the
     new manager picks it up and no resend is needed.
   - **Overall timeout** (configurable, default 60 min), so the app can never hang forever. On timeout it exits
     with a clear error.
7. Download the summary from the key in `JOB_DONE` (whatever the manager uses, so issue 2 doesn't block this).
8. `summaryParser.parse(summary)` gives a `List<OcrResult>` (in input order), then `HtmlReport.render(...)` writes
   `outputFileName`.
9. If `terminate` was given, send `TERMINATE` to `APP_TO_MANAGER_QUEUE` (spec step 17, after the results).
10. **Cleanup in a `finally` block,** so it also runs on failure:
    - delete the response queue;
    - `deletePrefix(jobPrefix(clientId))`;
    - delete the summary key if it lies outside the job folder (today's `outputs/...`, issue 2).

### Seams for the open gaps
| Seam | Implementation now | Gap it isolates | Later change (only here) |
|---|---|---|---|
| `ManagerLocator` interface: `ensureAvailableManager(int managerClientsN)`, `isAlive(ManagerHandle)` | `Ec2ManagerLocator`: available = any `Role=MANAGER` instance that is pending or running; otherwise `EC2Service.launch(MANAGER, 1, amiId, UserData.manager(bucket, "jars/manager.jar"))`. `isAlive` = the instance is still pending or running. | 3 (client count), 5 (terminating marker), 4 (manager jar) | Add a `ActiveClients < managerClientsN` and `State != TERMINATING` check in both methods. The manager jar only has to exist in S3. |
| `UserData.manager(bucket, jarKey)` in `common` | Same pattern as `worker(...)`: download `jars/manager.jar`, `nohup java -jar`, log to `/var/log/manager.log` | 4 | Add arguments if the manager's `main` needs any |
| `SummaryParser` interface: `List<OcrResult> parse(String)` | `PipeSummaryParser` for today's `url \| OCR: text` / `url \| ERROR: e`. Best effort: a line starting with `http` and containing ` \| OCR: ` or ` \| ERROR: ` starts a new entry, and other lines are continuation text. Index = order. | 1 (format) | A new parser class for the agreed format; switch one line in the wiring |
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
| `local-app/src/main/java/com/distributedsystems/localapp/` | **New**: `LocalAppMain`, `LocalAppArgs`, `LocalAppConfig`, `LocalApp`, `ManagerLocator`, `ManagerHandle`, `Ec2ManagerLocator`, `SummaryParser`, `PipeSummaryParser`, `HtmlReport` |
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
5. `ManagerLocator`, `ManagerHandle`, `Ec2ManagerLocator`, plus `UserData.manager`, with tests (`UserData`; the
   locator's decision logic with a stub `EC2Service`).
6. `LocalApp` orchestration and `LocalAppMain`, with flow tests using stub `SQSService`, `S3Service` and
   `ManagerLocator` (subclass or implement and record calls, like `WorkerLoopTest`). Covers:
   - the happy path;
   - a dead manager while waiting, which triggers a relaunch;
   - a timeout;
   - `terminate` sent last;
   - cleanup runs on failure.
7. **Local end to end, without EC2:**
   1. Start the dev-only `StubManager` (test sources). It reads `NEW_TASK`, downloads the input, writes a summary
      in the current format, and sends `JOB_DONE`.
   2. Run the local app against it using a locator that doesn't launch, with real S3 and SQS.
   3. Run **two local apps at once** to check that each gets only its own result.
8. **Blocked on the friend:** a real end-to-end run with the manager on EC2. Fill each gap in its seam as it gets
   decided.

## Verification
- Unit tests for args, config, HTML (escaping), the parser, the locator logic, `UserData.manager`, and the
  `LocalApp` flow with stubs. No AWS calls.
- Step 7: the HTML file opens in a browser with images and text for valid URLs, and link plus error for dead ones.
  The response queue and `jobs/<clientId>/` are gone afterwards. Two concurrent apps each get the correct, separate
  output.
- All existing `common`, `worker` and `manager` tests still pass.
