package com.distributedsystems.manager;

import com.distributedsystems.aws.EC2Service;
import com.distributedsystems.aws.EC2Service.Role;
import com.distributedsystems.aws.UserData;
import com.distributedsystems.manager.CloudInterfaces.ComputeClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * AWS EC2 implementation of {@link ComputeClient} delegating instance lifecycle operations to {@link EC2Service}.
 */
public class EC2ComputeClient implements ComputeClient {

    private static final String IMDS_BASE_URL = "http://169.254.169.254/latest";
    private static final Duration IMDS_TIMEOUT = Duration.ofSeconds(2);

    private final EC2Service ec2Service;
    private final String amiId;
    private final String bucketName;
    private final String workerJarKey;

    public EC2ComputeClient(EC2Service ec2Service, String amiId, String bucketName, String workerJarKey) {
        if (amiId == null || amiId.isBlank()) {
            throw new IllegalArgumentException("amiId must not be blank");
        }
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalArgumentException("bucketName must not be blank");
        }
        if (workerJarKey == null || workerJarKey.isBlank()) {
            throw new IllegalArgumentException("workerJarKey must not be blank");
        }
        this.ec2Service = ec2Service;
        this.amiId = amiId;
        this.bucketName = bucketName;
        this.workerJarKey = workerJarKey;
    }

    @Override
    public int getActiveWorkerCount() {
        return ec2Service.findActive(Role.WORKER).size();
    }

    @Override
    public void launchWorkers(int count) {
        if (count <= 0) {
            return;
        }
        String userDataScript = UserData.worker(bucketName, workerJarKey);
        List<String> launchedIds = ec2Service.launch(Role.WORKER, count, amiId, userDataScript);
        System.out.println("[EC2] Launched worker instances: " + launchedIds);
    }

    @Override
    public void terminateAllWorkers() {
        List<String> activeWorkerIds = ec2Service.findActive(Role.WORKER);
        if (!activeWorkerIds.isEmpty()) {
            System.out.println("[EC2] Terminating active workers: " + activeWorkerIds);
            ec2Service.terminate(activeWorkerIds);
        }
    }

    @Override
    public void terminateSelf() {
        Optional<String> selfInstanceId = fetchMetadata("/meta-data/instance-id");
        if (selfInstanceId.isPresent()) {
            System.out.println("[EC2] Terminating manager instance: " + selfInstanceId.get());
            ec2Service.terminate(List.of(selfInstanceId.get()));
        } else {
            System.err.println("[EC2] Could not resolve self instance ID via IMDSv2; skipping self-termination.");
        }
    }

    /**
     * Resolves the current EC2 instance's AMI ID via IMDSv2 so workers can be launched from the same image.
     */
    public static Optional<String> resolveCurrentAmiId() {
        return fetchMetadata("/meta-data/ami-id");
    }

    private static Optional<String> fetchMetadata(String path) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(IMDS_TIMEOUT)
                    .build();

            HttpRequest tokenRequest = HttpRequest.newBuilder()
                    .uri(URI.create(IMDS_BASE_URL + "/api/token"))
                    .timeout(IMDS_TIMEOUT)
                    .header("X-aws-ec2-metadata-token-ttl-seconds", "60")
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> tokenResponse = client.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
            if (tokenResponse.statusCode() != 200 || tokenResponse.body().isBlank()) {
                return Optional.empty();
            }

            HttpRequest metadataRequest = HttpRequest.newBuilder()
                    .uri(URI.create(IMDS_BASE_URL + path))
                    .timeout(IMDS_TIMEOUT)
                    .header("X-aws-ec2-metadata-token", tokenResponse.body())
                    .GET()
                    .build();

            HttpResponse<String> metadataResponse = client.send(metadataRequest, HttpResponse.BodyHandlers.ofString());
            if (metadataResponse.statusCode() == 200 && !metadataResponse.body().isBlank()) {
                return Optional.of(metadataResponse.body().trim());
            }
        } catch (Exception ignored) {
            // Not running on EC2 or IMDS unavailable
        }
        return Optional.empty();
    }
}