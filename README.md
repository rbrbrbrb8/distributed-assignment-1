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
| Manager $\rightarrow$ Worker | `IMAGE_TASK` | `IMAGE_TASK###<clientId>###<imageUrl>` |
| Worker $\rightarrow$ Manager | `OCR_DONE` | `OCR_DONE###<clientId>###<imageUrl>###<base64EncodedText>` |
| Worker $\rightarrow$ Manager | `OCR_ERROR` | `OCR_ERROR###<clientId>###<imageUrl>###<errorDescription>` |
| Manager $\rightarrow$ Local App | `JOB_DONE` | `JOB_DONE###<s3OutputKey>` |

- **Payload Safety:** Extracted OCR text in `OCR_DONE` is Base64-encoded (UTF-8) to prevent newlines or `###` sequences inside recognized text from breaking message parsing. Error messages in `OCR_ERROR` are sanitized before transmission.