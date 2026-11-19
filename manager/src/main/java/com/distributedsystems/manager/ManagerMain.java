package com.distributedsystems.manager;

import com.distributedsystems.aws.EC2Service;
import com.distributedsystems.aws.S3Service;
import com.distributedsystems.aws.SQSService;
import software.amazon.awssdk.regions.Region;

/**
 * Entry point for the Manager node running on EC2 (or locally for integration testing).
 *
 * <p>Configuration is resolved from CLI arguments or environment variables:
 * <ul>
 *   <li>{@code S3_BUCKET} (or {@code args[0]}): S3 bucket holding input/output files and worker JAR.</li>
 *   <li>{@code WORKER_AMI_ID} (or {@code args[1]}, or auto-detected via IMDSv2): AMI ID for worker instances.</li>
 *   <li>{@code WORKER_JAR_KEY} (optional, defaults to {@code jars/worker.jar}): S3 key of the worker fat JAR.</li>
 *   <li>{@code AWS_REGION} (optional, defaults to {@code us-east-1}): AWS region.</li>
 * </ul>
 */
public final class ManagerMain {

    private static final int QUEUE_VISIBILITY_TIMEOUT_SECONDS = 60;

    private ManagerMain() {
    }

    public static void main(String[] args) {
        Region region = Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1"));

        String bucketName = args.length >= 1 ? args[0] : System.getenv("S3_BUCKET");
        if (bucketName == null || bucketName.isBlank()) {
            System.err.println("Missing required S3 bucket name (set S3_BUCKET env var or pass as args[0]).");
            System.exit(1);
        }

        String amiId = args.length >= 2 ? args[1] : System.getenv("WORKER_AMI_ID");
        if (amiId == null || amiId.isBlank()) {
            amiId = EC2ComputeClient.resolveCurrentAmiId().orElse(null);
        }
        if (amiId == null || amiId.isBlank()) {
            System.err.println("Missing required WORKER_AMI_ID (set env var, pass as args[1], or run on EC2).");
            System.exit(1);
        }

        String workerJarKey = System.getenv().getOrDefault("WORKER_JAR_KEY", S3Service.jarKey("worker.jar"));
        int maxWorkers = Math.max(1, EC2Service.MAX_INSTANCES - 1);

        System.out.printf("Starting Manager [region=%s, bucket=%s, amiId=%s, workerJarKey=%s, maxWorkers=%d]%n",
                region.id(), bucketName, amiId, workerJarKey, maxWorkers);

        try (SQSService sqs = new SQSService(region);
             S3Service s3 = new S3Service(region, bucketName);
             EC2Service ec2 = new EC2Service(region)) {

            String appToManagerUrl = sqs.getOrCreateQueue(
                    SQSService.APP_TO_MANAGER_QUEUE, QUEUE_VISIBILITY_TIMEOUT_SECONDS);
            String managerToWorkersUrl = sqs.getOrCreateQueue(
                    SQSService.MANAGER_TO_WORKERS_QUEUE, QUEUE_VISIBILITY_TIMEOUT_SECONDS);
            String workersToManagerUrl = sqs.getOrCreateQueue(
                    SQSService.WORKERS_TO_MANAGER_QUEUE, QUEUE_VISIBILITY_TIMEOUT_SECONDS);

            S3StorageClient storageClient = new S3StorageClient(s3);
            EC2ComputeClient computeClient = new EC2ComputeClient(ec2, amiId, bucketName, workerJarKey);

            ManagerEngine engine = new ManagerEngine(
                    sqs,
                    storageClient,
                    computeClient,
                    maxWorkers,
                    appToManagerUrl,
                    managerToWorkersUrl,
                    workersToManagerUrl
            );

            engine.start();
            System.out.println("Manager shutdown complete.");
        }
    }
}