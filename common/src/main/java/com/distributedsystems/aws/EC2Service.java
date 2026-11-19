package com.distributedsystems.aws;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.IamInstanceProfileSpecification;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ec2.model.TerminateInstancesRequest;

/**
 * Launches, finds and terminates the system's EC2 instances (manager and workers).
 *
 * <p>Instances are marked with a {@code Role} tag ({@code MANAGER} / {@code WORKER}) so they can be found again,
 * e.g. "is a manager running?" (local app) or "how many workers are active?" (manager).
 *
 * <p>Credentials: none are passed. Instances get {@value #INSTANCE_PROFILE}, so code on them reaches S3/SQS through
 * the IAM role; this class itself uses the SDK's default credential chain.
 *
 * <p>Note: counting and launching are not atomic. The assignment allows assuming no races between launchers.
 */
public class EC2Service implements AutoCloseable {

    public enum Role { MANAGER, WORKER }

    /** AWS Academy blocks accounts that run more than this many instances at once. */
    public static final int MAX_INSTANCES = 19;
    public static final String ROLE_TAG = "Role";
    public static final String INSTANCE_PROFILE = "LabInstanceProfile";
    public static final InstanceType INSTANCE_TYPE = InstanceType.T3_MICRO;

    private static final List<String> ACTIVE_STATES = List.of("pending", "running");

    private final Ec2Client ec2;

    public EC2Service(Region region) {
        this.ec2 = Ec2Client.builder().region(region).build();
    }

    /**
     * Launches up to {@code count} instances, never letting the account exceed {@link #MAX_INSTANCES} active
     * instances in total (all roles counted).
     *
     * @param userDataScript plain-text boot script (e.g. from {@link UserData}); Base64-encoded here
     * @return IDs of the instances actually launched (may be fewer than requested, or empty)
     */
    public List<String> launch(Role role, int count, String amiId, String userDataScript) {
        int allowed = allowedToLaunch(count, countAllActive());
        if (allowed == 0) {
            return List.of();
        }

        TagSpecification tags = TagSpecification.builder()
                .resourceType(ResourceType.INSTANCE)
                .tags(Tag.builder().key(ROLE_TAG).value(role.name()).build(),
                      Tag.builder().key("Name").value("ocr-" + role.name().toLowerCase()).build())
                .build();

        RunInstancesRequest request = RunInstancesRequest.builder()
                .imageId(amiId)
                .instanceType(INSTANCE_TYPE)
                .minCount(1)
                .maxCount(allowed)
                .iamInstanceProfile(IamInstanceProfileSpecification.builder().name(INSTANCE_PROFILE).build())
                .userData(Base64.getEncoder().encodeToString(userDataScript.getBytes(StandardCharsets.UTF_8)))
                .tagSpecifications(tags) // tags are applied atomically at launch
                .build();

        return ec2.runInstances(request).instances().stream().map(Instance::instanceId).toList();
    }

    /** IDs of pending/running instances tagged with the given role. */
    public List<String> findActive(Role role) {
        return findActiveIds(List.of(
                stateFilter(),
                Filter.builder().name("tag:" + ROLE_TAG).values(role.name()).build()));
    }

    /** Number of pending/running instances in the account, whatever their role. */
    public int countAllActive() {
        return findActiveIds(List.of(stateFilter())).size();
    }

    public void terminate(List<String> instanceIds) {
        if (instanceIds == null || instanceIds.isEmpty()) {
            return;
        }
        ec2.terminateInstances(TerminateInstancesRequest.builder().instanceIds(instanceIds).build());
    }

    /**
     * How many instances may be launched now. Package-private and pure so it can be unit-tested.
     *
     * @param requested       instances the caller wants
     * @param currentlyActive pending/running instances in the account right now
     */
    static int allowedToLaunch(int requested, int currentlyActive) {
        if (requested < 0) {
            throw new IllegalArgumentException("requested must be >= 0, got " + requested);
        }
        int room = Math.max(0, MAX_INSTANCES - currentlyActive);
        return Math.min(requested, room);
    }

    private List<String> findActiveIds(List<Filter> filters) {
        DescribeInstancesRequest request = DescribeInstancesRequest.builder().filters(filters).build();
        List<String> ids = new ArrayList<>();
        // The paginator follows NextToken, so results are complete even with many instances.
        ec2.describeInstancesPaginator(request).reservations()
                .forEach(reservation -> reservation.instances()
                        .forEach(instance -> ids.add(instance.instanceId())));
        return ids;
    }

    private static Filter stateFilter() {
        return Filter.builder().name("instance-state-name").values(ACTIVE_STATES).build();
    }

    @Override
    public void close() {
        ec2.close();
    }
}
