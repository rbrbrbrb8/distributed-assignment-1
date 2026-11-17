package com.distributedsystems.aws;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.Arrays;
import java.util.List;

public class SQSTest {
    public static void main(String[] args) throws InterruptedException {
        SQSService sqs = new SQSService(Region.US_EAST_1);

        String testQueueUrl = sqs.getOrCreateQueue("ocr-test-queue-" + System.currentTimeMillis(), 5);
        System.out.println("Created queue: " + testQueueUrl);

        try {
            String msg1 = MessageProtocol.createOcrDoneMsg("client-1", "http://img.com/1.jpg", "Line 1\nLine 2");
            String msg2 = MessageProtocol.createImageTaskMsg("client-1", "http://img.com/2.jpg");
            sqs.sendMessagesBatch(testQueueUrl, Arrays.asList(msg1, msg2));
            System.out.println("Sent 2 messages in batch.");

            List<Message> received = sqs.receiveMessages(testQueueUrl, 1);
            Message first = received.get(0);
            String[] parts = MessageProtocol.parse(first.body());
            System.out.println("Parsed type: " + parts[0]);
            if (parts[0].equals("OCR_DONE")) {
                System.out.println("Decoded OCR text:\n" + MessageProtocol.decodeOcrText(parts[3]));
            }
            sqs.deleteMessage(testQueueUrl, first);
            System.out.println("Deleted first message.");

            Message second = sqs.receiveMessages(testQueueUrl, 1).get(0);
            System.out.println("Worker crashed while holding: " + second.body());

            System.out.println("Waiting 6 seconds for Visibility Timeout to expire...");
            Thread.sleep(6000);

            List<Message> recovered = sqs.receiveMessages(testQueueUrl, 1);
            System.out.println("Recovered message after timeout: " + recovered.get(0).body());
            sqs.deleteMessage(testQueueUrl, recovered.get(0));

        } finally {
            sqs.deleteQueue(testQueueUrl);
            sqs.close();
            System.out.println("Test queue deleted and client closed.");
        }
    }
}