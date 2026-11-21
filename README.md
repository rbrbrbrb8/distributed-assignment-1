# distributed-assignment-1
Assignment 1 of Distributed Systems Development course.
The goal is to create a distributed OCR system.

## Execution Details
- **Worker Ratio (`n` - files per worker):** `TBD`
- **Total Execution Time:** `TBD`

---

## SQS Communication Layer & Message Protocol (`common`)

Communication between the Local Application, Manager, and Workers is handled via **AWS SQS** (`SQSService`) using UTF-8 text messages delimited by `###` (`MessageProtocol`).

### Queues Architecture
- **App $\rightarrow$ Manager Queue:** Receives job submissions (`NEW_TASK`) and shutdown signals (`TERMINATE`).
- **Manager $\rightarrow$ Workers Queue:** Distributes individual image URLs (`IMAGE_TASK`) with configured visibility timeouts for fault tolerance.
- **Workers $\rightarrow$ Manager Queue:** Collects completed OCR results (`OCR_DONE`) and per-image failures (`OCR_ERROR`).
- **Client Response Queue:** A temporary queue created dynamically per Local Application instance to receive its `JOB_DONE` notification.

### Message Formats (`MessageProtocol`)
| Direction | Type | Payload Format |
| :--- | :--- | :--- |
| Local App $\rightarrow$ Manager | `NEW_TASK` | `NEW_TASK###<clientId>###<responseQueueUrl>###<s3InputKey>###<n>` |
| Local App $\rightarrow$ Manager | `TERMINATE` | `TERMINATE` |
| Manager $\rightarrow$ Worker | `IMAGE_TASK` | `IMAGE_TASK###<clientId>###<taskIndex>###<imageUrl>` |
| Worker $\rightarrow$ Manager | `OCR_DONE` | `OCR_DONE###<clientId>###<taskIndex>###<imageUrl>###<base64EncodedText>` |
| Worker $\rightarrow$ Manager | `OCR_ERROR` | `OCR_ERROR###<clientId>###<taskIndex>###<imageUrl>###<errorDescription>` |
| Manager $\rightarrow$ Local App | `JOB_DONE` | `JOB_DONE###<s3OutputKey>` |

- **Payload Safety:** Extracted OCR text in `OCR_DONE` is Base64-encoded (UTF-8) to prevent newlines or `###` sequences inside recognized text from breaking message parsing. Error messages in `OCR_ERROR` are sanitized before transmission.
---

## Known Limitations

- **Leftover Duplicate Results in SQS:** If a worker takes longer than the visibility timeout or finishes just as another worker retries the same image, duplicate OCR_DONE / OCR_ERROR messages may remain in the workers-to-manager queue. Managers ignore duplicate task indices for active jobs and delete late duplicates for recently completed jobs (inishedClients); any remaining orphans expire automatically via SQS's 4-day retention period without affecting correctness.
- **Orphaned Resources on Client Crash/Timeout:** If a Local Application terminates abruptly or hits its wait timeout before receiving JOB_DONE, its dynamically created response queue and S3 directory (jobs/<clientId>/) remain in AWS. All S3 artifacts for a run are isolated under a single jobs/<clientId>/ prefix for straightforward cleanup.
- **Shared Inbound Queue Across Multiple Managers:** While local-app inspects EC2 tags (ActiveClients) to decide whether an existing manager has capacity or a new manager should be spawned, all active managers poll the same App -> Manager SQS queue. Consequently, a client cannot pin its NEW_TASK message to a specific manager instance; any available manager may pick up the job.
