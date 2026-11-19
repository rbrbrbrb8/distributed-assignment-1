package com.distributedsystems.aws;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.Arrays;
import java.util.List;

public class SQSTest {

    public static void main(String[] args) throws InterruptedException {
        try (SQSService sqs = new SQSService(Region.US_EAST_1)) {

            // 1. Create the 3 core system queues + 1 dynamic client reply queue
            String appToManagerUrl = sqs.getOrCreateQueue(SQSService.APP_TO_MANAGER_QUEUE, 30);
            String managerToWorkersUrl = sqs.getOrCreateQueue(SQSService.MANAGER_TO_WORKERS_QUEUE, 90);
            String workersToManagerUrl = sqs.getOrCreateQueue(SQSService.WORKERS_TO_MANAGER_QUEUE, 30);
            String clientReplyUrl = sqs.createClientResponseQueue();

            System.out.println("Queues created in AWS SQS:");
            System.out.println(" - App -> Manager:     " + appToManagerUrl);
            System.out.println(" - Manager -> Workers: " + managerToWorkersUrl);
            System.out.println(" - Workers -> Manager: " + workersToManagerUrl);
            System.out.println(" - Client Reply Queue: " + clientReplyUrl);

            // 2. Simulate full assignment message flow
            String clientId = "client-101";
            int expectedN = 5;

            // Local App -> Manager: NEW_TASK (including n)
            sqs.sendMessage(appToManagerUrl,
                    MessageProtocol.createNewTaskMsg(clientId, clientReplyUrl, "inputs/urls.txt", expectedN));

            // Manager -> Workers: Batch of IMAGE_TASK
            List<String> tasks = Arrays.asList(
                    MessageProtocol.createImageTaskMsg(clientId, "http://example.com/valid.jpg"),
                    MessageProtocol.createImageTaskMsg(clientId, "http://example.com/broken.jpg")
            );
            sqs.sendMessagesBatch(managerToWorkersUrl, tasks);

            System.out.println("\nTasks waiting in Manager->Workers queue: "
                    + sqs.getApproximateMessageCount(managerToWorkersUrl));

            // 3. Pause so you can inspect the live queues & messages in AWS Console
            System.out.println("\n>>> Open AWS SQS Console (us-east-1) now to see the queues and messages (waiting 25s)...");
            Thread.sleep(25000);

            // 4. Consume and verify messages from each queue
            Message newTaskMsg = sqs.receiveMessages(appToManagerUrl, 1).get(0);
            String[] newTaskParts = MessageProtocol.parse(newTaskMsg.body());
            int parsedN = MessageProtocol.parseNewTaskRatio(newTaskParts);
            System.out.println("\nManager received: " + Arrays.toString(newTaskParts) + " | Parsed n = " + parsedN);

            if (newTaskParts.length != 5 || parsedN != expectedN) {
                throw new AssertionError("Expected 5 parts with n=" + expectedN + ", but got n=" + parsedN);
            }
            sqs.deleteMessage(appToManagerUrl, newTaskMsg);

            List<Message> workerTasks = sqs.receiveMessages(managerToWorkersUrl, 10);
            for (Message task : workerTasks) {
                String[] parts = MessageProtocol.parse(task.body());
                String url = parts[2];

                if (url.contains("valid")) {
                    sqs.sendMessage(workersToManagerUrl,
                            MessageProtocol.createOcrDoneMsg(clientId, url, "Extracted Text\nLine 2"));
                } else {
                    sqs.sendMessage(workersToManagerUrl,
                            MessageProtocol.createOcrErrorMsg(clientId, url, "HTTP 404 Not Found"));
                }
                sqs.deleteMessage(managerToWorkersUrl, task);
            }

            List<Message> workerResults = sqs.receiveMessages(workersToManagerUrl, 10);
            for (Message res : workerResults) {
                String[] parts = MessageProtocol.parse(res.body());
                if (parts[0].equals(MessageProtocol.MessageType.OCR_DONE.name())) {
                    System.out.println("Manager got OCR_DONE: " + MessageProtocol.decodeOcrText(parts[3]).replace("\n", " | "));
                } else {
                    System.out.println("Manager got OCR_ERROR: " + parts[3]);
                }
                sqs.deleteMessage(workersToManagerUrl, res);
            }

            // Manager -> Local App: JOB_DONE
            sqs.sendMessage(clientReplyUrl, MessageProtocol.createJobDoneMsg("outputs/summary-client-101.txt"));
            Message jobDone = sqs.receiveMessages(clientReplyUrl, 1).get(0);
            System.out.println("Local App received: " + Arrays.toString(MessageProtocol.parse(jobDone.body())));
            sqs.deleteMessage(clientReplyUrl, jobDone);

            // Verify validation rejects non-positive n
            try {
                MessageProtocol.createNewTaskMsg(clientId, clientReplyUrl, "inputs/urls.txt", 0);
                throw new AssertionError("Expected IllegalArgumentException for n <= 0");
            } catch (IllegalArgumentException expected) {
                System.out.println("Validation check passed: rejected n <= 0");
            }

            // 5. Cleanup temporary client reply queue (keep the 3 core queues ready, or delete all)
            sqs.deleteQueue(clientReplyUrl);
            System.out.println("\nClient reply queue deleted. Core 3 queues remain ready in AWS SQS!");
        }
    }
}