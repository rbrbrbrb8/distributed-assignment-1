package com.distributedsystems.aws;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.distributedsystems.aws.EC2Service.Role;
import com.distributedsystems.aws.MessageProtocol.MessageType;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * Dev-only end-to-end test that plays the manager's role towards the workers (not shipped, lives in test sources):
 * launches one worker on EC2, sends every URL of a file as an IMAGE_TASK, collects all results, writes them to a
 * file and terminates the worker.
 *
 * <p>Usage (from the repo root): {@code FakeManager <amiId> <bucket> <urlFile> [outputFile]}
 *
 * <p>Results are read but NOT deleted from the results queue (they stay inspectable in the SQS console);
 * duplicates are ignored by task index, exactly as the real manager must do.
 */
public final class FakeManager {

    private static final String CLIENT_ID = "fake-manager";
    private static final String WORKER_JAR_KEY = "jars/worker.jar";
    private static final Duration TIMEOUT = Duration.ofMinutes(10);

    private FakeManager() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("Usage: FakeManager <amiId> <bucket> <urlFile> [outputFile]");
            System.exit(1);
        }
        String amiId = args[0];
        String bucket = args[1];
        List<String> urls = Files.readAllLines(Path.of(args[2])).stream()
                .map(String::trim).filter(line -> !line.isEmpty()).toList();
        Path outputFile = Path.of(args.length > 3 ? args[3] : "fake-manager-output.txt");

        Instant start = Instant.now();
        Map<Integer, String> results = new TreeMap<>();
        Instant firstResultAt = null;

        try (EC2Service ec2 = new EC2Service(Region.US_EAST_1);
             SQSService sqs = new SQSService(Region.US_EAST_1)) {

            String tasksQueue = sqs.getOrCreateQueue(SQSService.MANAGER_TO_WORKERS_QUEUE, 60);
            String resultsQueue = sqs.getOrCreateQueue(SQSService.WORKERS_TO_MANAGER_QUEUE, 60);

            List<String> instanceIds = ec2.launch(Role.WORKER, 1, amiId, UserData.worker(bucket, WORKER_JAR_KEY));
            if (instanceIds.isEmpty()) {
                throw new IllegalStateException("No worker launched (instance cap reached?)");
            }
            log(start, "Launched worker " + instanceIds);

            try {
                List<String> tasks = new ArrayList<>();
                for (int i = 0; i < urls.size(); i++) {
                    tasks.add(MessageProtocol.createImageTaskMsg(CLIENT_ID, i, urls.get(i)));
                }
                sqs.sendMessagesBatch(tasksQueue, tasks);
                log(start, "Sent " + tasks.size() + " IMAGE_TASK messages, waiting for results...");

                while (results.size() < urls.size()) {
                    if (Duration.between(start, Instant.now()).compareTo(TIMEOUT) > 0) {
                        log(start, "TIMEOUT: only " + results.size() + "/" + urls.size() + " results");
                        break;
                    }
                    for (Message message : sqs.receiveMessages(resultsQueue, 10)) {
                        String[] parts = MessageProtocol.parse(message.body());
                        if (parts.length != 5 || !CLIENT_ID.equals(parts[1])) {
                            continue; // another client's result - not ours
                        }
                        int index = MessageProtocol.parseTaskIndex(parts);
                        if (results.containsKey(index)) {
                            continue; // duplicate delivery: already have this line
                        }
                        if (firstResultAt == null) {
                            firstResultAt = Instant.now();
                        }
                        results.put(index, format(parts));
                        log(start, "Result " + results.size() + "/" + urls.size() + " (index " + index + ", " + parts[0] + ")");
                    }
                }
            } finally {
                ec2.terminate(instanceIds); // always clean up, even on failure
                log(start, "Terminated " + instanceIds);
            }
        }

        Duration total = Duration.between(start, Instant.now());
        StringBuilder out = new StringBuilder();
        out.append("Results: ").append(results.size()).append("/").append(urls.size())
           .append(", total ").append(total.toSeconds()).append(" s");
        if (firstResultAt != null) {
            out.append(", first result after ").append(Duration.between(start, firstResultAt).toSeconds())
               .append(" s (includes instance boot)");
        }
        out.append("\n\n");
        results.forEach((index, line) -> out.append("[").append(index).append("] ").append(line).append("\n\n"));
        Files.writeString(outputFile, out.toString());
        log(start, "Wrote " + outputFile.toAbsolutePath());
    }

    private static String format(String[] parts) {
        String url = parts[3];
        if (MessageType.OCR_DONE.name().equals(parts[0])) {
            return "OK    " + url + "\n" + MessageProtocol.decodeOcrText(parts[4]);
        }
        return "ERROR " + url + "\n" + parts[4];
    }

    private static void log(Instant start, String message) {
        System.out.printf("[%4ds] %s%n", Duration.between(start, Instant.now()).toSeconds(), message);
    }
}
