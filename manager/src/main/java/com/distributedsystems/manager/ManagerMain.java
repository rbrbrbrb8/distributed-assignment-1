package com.distributedsystems.manager;

import com.distributedsystems.aws.EC2Service;
import com.distributedsystems.aws.S3Service;
import com.distributedsystems.aws.SQSService;
import com.distributedsystems.aws.UserData;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;

import java.util.Objects;

public class ManagerMain {
    private static final int DEFAULT_MAX_WORKERS = 8;
    private static final int DEFAULT_VISIBILITY_TIMEOUT_SECONDS = 30;

    static final class ManagerSqsService extends SQSService {
        ManagerSqsService(Region region) {
            super(region);
        }
    }

    public static void main(String[] args) {
        String bucketName = resolveConfig("S3_BUCKET", args, 0, null);
        String workerAmiId = resolveConfig("WORKER_AMI_ID", args, 1, null);
        String workerJarKey = resolveConfig("WORKER_JAR_KEY", args, 2, S3Service.jarKey("worker.jar"));
        int maxWorkers = parseIntOrDefault(resolveConfig("MAX_WORKERS", args, 3, String.valueOf(DEFAULT_MAX_WORKERS)), DEFAULT_MAX_WORKERS);
        Region region = resolveRegion();

        Objects.requireNonNull(bucketName, "S3_BUCKET must be provided via env or args[0]");
        Objects.requireNonNull(workerAmiId, "WORKER_AMI_ID must be provided via env or args[1]");

        S3Service s3Service = new S3Service(region, bucketName);
        SQSService sqsService = new ManagerSqsService(region);
        EC2Service ec2Service = new EC2Service(region);
        Ec2Client rawEc2Client = Ec2Client.builder().region(region).build();

        String appToManagerUrl = sqsService.getOrCreateQueue(SQSService.APP_TO_MANAGER_QUEUE, DEFAULT_VISIBILITY_TIMEOUT_SECONDS);
        String managerToWorkersUrl = sqsService.getOrCreateQueue(SQSService.MANAGER_TO_WORKERS_QUEUE, DEFAULT_VISIBILITY_TIMEOUT_SECONDS);
        String workersToManagerUrl = sqsService.getOrCreateQueue(SQSService.WORKERS_TO_MANAGER_QUEUE, DEFAULT_VISIBILITY_TIMEOUT_SECONDS);

        String workerUserData = UserData.worker(bucketName, workerJarKey);

        S3StorageClient storageClient = new S3StorageClient(s3Service);
        EC2ComputeClient computeClient = new EC2ComputeClient(ec2Service, rawEc2Client, workerAmiId, workerUserData);

        ManagerEngine engine = new ManagerEngine(
                sqsService,
                storageClient,
                computeClient,
                maxWorkers,
                appToManagerUrl,
                managerToWorkersUrl,
                workersToManagerUrl
        );

        System.out.printf("Starting Manager (region=%s, bucket=%s, workerAmi=%s, workerJarKey=%s, maxWorkers=%d)%n",
                region.id(), bucketName, workerAmiId, workerJarKey, maxWorkers);
        engine.start();
    }

    private static Region resolveRegion() {
        String envRegion = System.getenv("AWS_REGION");
        if (envRegion != null && !envRegion.isBlank()) {
            return Region.of(envRegion.trim());
        }
        return Region.US_EAST_1;
    }

    private static String resolveConfig(String envKey, String[] args, int argIndex, String defaultVal) {
        String envVal = System.getenv(envKey);
        if (envVal != null && !envVal.isBlank()) {
            return envVal.trim();
        }
        if (args != null && argIndex < args.length && args[argIndex] != null && !args[argIndex].isBlank()) {
            return args[argIndex].trim();
        }
        return defaultVal;
    }

    private static int parseIntOrDefault(String raw, int defaultVal) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            return defaultVal;
        }
    }
}