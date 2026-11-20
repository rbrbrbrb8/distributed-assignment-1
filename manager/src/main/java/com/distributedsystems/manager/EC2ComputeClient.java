package com.distributedsystems.manager;

import com.distributedsystems.aws.EC2Service;
import com.distributedsystems.manager.CloudInterfaces.ComputeClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateTagsRequest;
import software.amazon.awssdk.services.ec2.model.Tag;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class EC2ComputeClient implements ComputeClient {
    public static final String ACTIVE_CLIENTS_TAG_KEY = "ActiveClients";
    /**
     * Value the manager writes into its own {@code Role} tag on TERMINATE (replacing {@code MANAGER}). Local apps
     * search for {@code Role=MANAGER}, so a terminating manager is no longer found or picked for new jobs.
     */
    public static final String TERMINATING_ROLE = "TERMINATING_MANAGER";
    private static final String IMDS_TOKEN_URL = "http://169.254.169.254/latest/api/token";
    private static final String IMDS_INSTANCE_ID_URL = "http://169.254.169.254/latest/meta-data/instance-id";

    private final EC2Service ec2Service;
    private final Ec2Client rawEc2Client;
    private final String workerAmiId;
    private final String workerUserData;
    private final String selfInstanceId;
    /** IDs of the workers this manager launched (another manager may run its own workers at the same time). */
    private final Set<String> ownWorkerIds = ConcurrentHashMap.newKeySet();

    public EC2ComputeClient(EC2Service ec2Service,
                            Ec2Client rawEc2Client,
                            String workerAmiId,
                            String workerUserData) {
        this.ec2Service = ec2Service;
        this.rawEc2Client = rawEc2Client;
        this.workerAmiId = workerAmiId;
        this.workerUserData = workerUserData;
        this.selfInstanceId = resolveSelfInstanceId();
    }

    /** Number of workers launched by this manager that are still pending/running (not other managers' workers). */
    @Override
    public int getActiveWorkerCount() {
        return activeOwnWorkers().size();
    }

    @Override
    public void launchWorkers(int count) {
        if (count <= 0) {
            return;
        }
        ownWorkerIds.addAll(ec2Service.launch(EC2Service.Role.WORKER, count, workerAmiId, workerUserData));
    }

    /** Terminates only the workers this manager launched, so another manager's workers keep running. */
    @Override
    public void terminateAllWorkers() {
        List<String> activeWorkerIds = activeOwnWorkers();
        if (!activeWorkerIds.isEmpty()) {
            ec2Service.terminate(activeWorkerIds);
        }
    }

    private List<String> activeOwnWorkers() {
        return ec2Service.findActive(EC2Service.Role.WORKER).stream()
                .filter(ownWorkerIds::contains)
                .toList();
    }

    @Override
    public void terminateSelf() {
        if (selfInstanceId != null && !selfInstanceId.isBlank()) {
            ec2Service.terminate(List.of(selfInstanceId));
        }
    }

    @Override
    public void updateActiveClients(int activeClientCount) {
        if (rawEc2Client == null || selfInstanceId == null || selfInstanceId.isBlank()) {
            return;
        }
        rawEc2Client.createTags(CreateTagsRequest.builder()
                .resources(selfInstanceId)
                .tags(Tag.builder()
                        .key(ACTIVE_CLIENTS_TAG_KEY)
                        .value(String.valueOf(Math.max(0, activeClientCount)))
                        .build())
                .build());
    }

    @Override
    public void markTerminating() {
        if (rawEc2Client == null || selfInstanceId == null || selfInstanceId.isBlank()) {
            return;
        }
        // createTags overwrites an existing key, so Role=MANAGER becomes Role=TERMINATING_MANAGER
        rawEc2Client.createTags(CreateTagsRequest.builder()
                .resources(selfInstanceId)
                .tags(Tag.builder()
                        .key(EC2Service.ROLE_TAG)
                        .value(TERMINATING_ROLE)
                        .build())
                .build());
    }

    private static String resolveSelfInstanceId() {
        String envId = System.getenv("MANAGER_INSTANCE_ID");
        if (envId != null && !envId.isBlank()) {
            return envId.trim();
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(1))
                    .build();

            HttpRequest tokenReq = HttpRequest.newBuilder()
                    .uri(URI.create(IMDS_TOKEN_URL))
                    .header("X-aws-ec2-metadata-token-ttl-seconds", "60")
                    .timeout(Duration.ofSeconds(1))
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> tokenRes = client.send(tokenReq, HttpResponse.BodyHandlers.ofString());
            if (tokenRes.statusCode() != 200) {
                return null;
            }

            HttpRequest idReq = HttpRequest.newBuilder()
                    .uri(URI.create(IMDS_INSTANCE_ID_URL))
                    .header("X-aws-ec2-metadata-token", tokenRes.body())
                    .timeout(Duration.ofSeconds(1))
                    .GET()
                    .build();
            HttpResponse<String> idRes = client.send(idReq, HttpResponse.BodyHandlers.ofString());
            if (idRes.statusCode() == 200 && !idRes.body().isBlank()) {
                return idRes.body().trim();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}