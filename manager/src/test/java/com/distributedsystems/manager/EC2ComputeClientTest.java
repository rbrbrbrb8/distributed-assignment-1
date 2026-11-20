package com.distributedsystems.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.distributedsystems.aws.EC2Service;

import software.amazon.awssdk.regions.Region;

/** M5: a manager counts and terminates only the workers it launched. No AWS calls. */
class EC2ComputeClientTest {

    /** In-memory EC2: active workers, plus what was launched and terminated. */
    private static class StubEc2 extends EC2Service {
        final List<String> activeWorkers = new ArrayList<>();
        final List<String> terminated = new ArrayList<>();
        private int nextId = 1;

        StubEc2() {
            super(Region.US_EAST_1); // builds a client object but never uses it
        }

        @Override
        public List<String> launch(Role role, int count, String amiId, String userDataScript) {
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                ids.add("i-mine" + nextId++);
            }
            activeWorkers.addAll(ids);
            return ids;
        }

        @Override
        public List<String> findActive(Role role) {
            return role == Role.WORKER ? List.copyOf(activeWorkers) : List.of();
        }

        @Override
        public void terminate(List<String> instanceIds) {
            terminated.addAll(instanceIds);
            activeWorkers.removeAll(instanceIds);
        }
    }

    private final StubEc2 ec2 = new StubEc2();
    // rawEc2Client = null: tagging is skipped (not on EC2)
    private final EC2ComputeClient compute = new EC2ComputeClient(ec2, null, "ami-0123456789abcdef0", "#!/bin/bash");

    @Test
    void countsOnlyItsOwnActiveWorkers() {
        ec2.activeWorkers.add("i-other1"); // launched by another manager
        compute.launchWorkers(2);

        assertEquals(2, compute.getActiveWorkerCount());

        ec2.activeWorkers.remove("i-mine1"); // one of ours died
        assertEquals(1, compute.getActiveWorkerCount());
    }

    @Test
    void terminatesOnlyItsOwnWorkers() {
        ec2.activeWorkers.add("i-other1");
        compute.launchWorkers(2);

        compute.terminateAllWorkers();

        assertEquals(List.of("i-mine1", "i-mine2"), ec2.terminated);
        assertEquals(List.of("i-other1"), ec2.activeWorkers); // the other manager's worker keeps running
    }

    @Test
    void terminatesNothingWhenItLaunchedNothing() {
        ec2.activeWorkers.add("i-other1");

        compute.terminateAllWorkers();

        assertEquals(List.of(), ec2.terminated);
    }
}
