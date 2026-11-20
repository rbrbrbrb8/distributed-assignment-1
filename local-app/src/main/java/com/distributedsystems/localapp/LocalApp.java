package com.distributedsystems.localapp;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.distributedsystems.aws.MessageProtocol;
import com.distributedsystems.aws.S3Service;
import com.distributedsystems.aws.SQSService;
import com.distributedsystems.localapp.manager.ManagerHandle;
import com.distributedsystems.localapp.manager.ManagerLocator;
import com.distributedsystems.localapp.summary.SummaryParser;
import com.distributedsystems.ocr.result.OcrResult;

import software.amazon.awssdk.services.sqs.model.Message;

/**
 * One run of the local application: input file of image URLs in, HTML report out.
 *
 * <ol>
 *   <li>Find an available manager, or start one.</li>
 *   <li>Upload the input to {@code jobs/<clientId>/input.txt}.</li>
 *   <li>Create this job's own response queue and send {@code NEW_TASK}.</li>
 *   <li>Wait for {@code JOB_DONE}. Meanwhile, check now and then that the manager is still running; if it's gone,
 *       start a new one and resend the same {@code NEW_TASK} (open issues 7 and 9). Give up after a timeout.</li>
 *   <li>Download and parse the summary, write the HTML.</li>
 *   <li>Always (also on failure): send {@code TERMINATE} if asked, delete the response queue and the job folder.</li>
 * </ol>
 */
public class LocalApp {

    /** Visibility timeout for the queues this app creates (the inbound queue usually exists already). */
    private static final int QUEUE_VISIBILITY_SECONDS = 30;

    private final SQSService sqs;
    private final S3Service s3;
    private final ManagerLocator managerLocator;
    private final SummaryParser summaryParser;
    private final Duration waitTimeout;
    private final Duration livenessInterval;

    /**
     * @param waitTimeout      how long to wait for {@code JOB_DONE} before giving up
     * @param livenessInterval how often to check that the manager is still running while waiting
     */
    public LocalApp(SQSService sqs, S3Service s3, ManagerLocator managerLocator, SummaryParser summaryParser,
                    Duration waitTimeout, Duration livenessInterval) {
        this.sqs = sqs;
        this.s3 = s3;
        this.managerLocator = managerLocator;
        this.summaryParser = summaryParser;
        this.waitTimeout = waitTimeout;
        this.livenessInterval = livenessInterval;
    }

    /**
     * Runs the whole job.
     *
     * @throws IllegalStateException on timeout, or if no manager can be found or started
     * @throws IOException           if the HTML can't be written
     */
    public void run(LocalAppArgs args) throws IOException {
        String clientId = UUID.randomUUID().toString();
        String inputKey = S3Service.inputKey(clientId);
        log("Job " + clientId);

        ManagerHandle manager = managerLocator.ensureAvailableManager(args.managerClientsN());
        log("Using manager " + manager.instanceId());

        String inboundQueueUrl = null;
        String responseQueueUrl = null;
        try {
            s3.uploadFile(inputKey, args.inputFile());

            responseQueueUrl = sqs.getOrCreateQueue("ocr-client-response-" + clientId, QUEUE_VISIBILITY_SECONDS);
            inboundQueueUrl = sqs.getOrCreateQueue(SQSService.APP_TO_MANAGER_QUEUE, QUEUE_VISIBILITY_SECONDS);
            String newTask = MessageProtocol.createNewTaskMsg(clientId, responseQueueUrl, inputKey,
                    args.workerFilesN());
            sqs.sendMessage(inboundQueueUrl, newTask);
            log("Task sent, waiting for the result...");

            String summaryKey = waitForJobDone(args, manager, inboundQueueUrl, responseQueueUrl, newTask);

            List<OcrResult> results = summaryParser.parse(s3.downloadString(summaryKey));
            HtmlReport.write(results, args.outputFile());
            log("Wrote " + results.size() + " results to " + args.outputFile().toAbsolutePath());
        } finally {
            cleanUp(args, clientId, inboundQueueUrl, responseQueueUrl);
        }
    }

    /** Polls the response queue until {@code JOB_DONE}; returns the summary's S3 key. */
    private String waitForJobDone(LocalAppArgs args, ManagerHandle manager, String inboundQueueUrl,
                                  String responseQueueUrl, String newTask) {
        long deadline = System.nanoTime() + waitTimeout.toNanos();
        long nextLivenessCheck = System.nanoTime() + livenessInterval.toNanos();

        while (System.nanoTime() < deadline) {
            for (Message message : sqs.receiveMessages(responseQueueUrl, 1)) { // long poll, up to 20 s
                String[] parts = MessageProtocol.parse(message.body());
                if (MessageProtocol.MessageType.JOB_DONE.name().equals(parts[0])) {
                    return parts[1];
                }
                log("Ignoring unexpected message: " + message.body());
            }

            if (System.nanoTime() >= nextLivenessCheck) {
                nextLivenessCheck = System.nanoTime() + livenessInterval.toNanos();
                if (!managerLocator.isAlive(manager)) {
                    // Its in-memory job state is lost: start (or find) another manager and send the task again.
                    log("Manager " + manager.instanceId() + " is gone; replacing it and resending the task");
                    manager = managerLocator.ensureAvailableManager(args.managerClientsN());
                    sqs.sendMessage(inboundQueueUrl, newTask);
                    log("Using manager " + manager.instanceId());
                }
            }
        }
        throw new IllegalStateException("No result after " + waitTimeout.toMinutes() + " minutes; giving up");
    }

    /** Best effort: each step runs even if an earlier one fails, and a failure here never hides the real error. */
    private void cleanUp(LocalAppArgs args, String clientId, String inboundQueueUrl, String responseQueueUrl) {
        if (args.terminate()) {
            bestEffort("send TERMINATE", () -> sqs.sendMessage(
                    inboundQueueUrl != null ? inboundQueueUrl
                            : sqs.getOrCreateQueue(SQSService.APP_TO_MANAGER_QUEUE, QUEUE_VISIBILITY_SECONDS),
                    MessageProtocol.createTerminateMsg()));
        }
        if (responseQueueUrl != null) {
            String queue = responseQueueUrl;
            bestEffort("delete the response queue", () -> sqs.deleteQueue(queue));
        }
        bestEffort("delete the job folder", () -> s3.deletePrefix(S3Service.jobPrefix(clientId)));
    }

    private static void bestEffort(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            System.err.println("[local-app] Failed to " + what + ": " + e.getMessage());
        }
    }

    private static void log(String message) {
        System.out.println("[local-app] " + message);
    }
}
