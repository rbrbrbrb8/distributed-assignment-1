package com.distributedsystems.localapp.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

import org.junit.jupiter.api.Test;

import com.distributedsystems.aws.EC2Service;
import com.distributedsystems.manager.EC2ComputeClient;

import software.amazon.awssdk.regions.Region;

class Ec2ManagerLocatorTest {

    /** In-memory EC2: active managers with their tags, plus a record of launches. No AWS calls. */
    private static class StubEc2 extends EC2Service {
        /** instanceId → ActiveClients tag value (null = no tag yet). Insertion order = AWS listing order. */
        final Map<String, String> managers = new LinkedHashMap<>();
        final Set<String> alive = new HashSet<>();
        final List<String> launchedUserData = new ArrayList<>();
        boolean capReached = false;
        private int nextId = 1;

        StubEc2() {
            super(Region.US_EAST_1); // builds a client object but never uses it
        }

        void addManager(String id, String activeClients) {
            managers.put(id, activeClients);
            alive.add(id);
        }

        @Override
        public List<String> findActive(Role role) {
            return role == Role.MANAGER ? List.copyOf(managers.keySet()) : List.of();
        }

        @Override
        public Optional<String> getTag(String instanceId, String key) {
            Map<String, String> tags = new HashMap<>();
            if (managers.get(instanceId) != null) {
                tags.put(ACTIVE_CLIENTS_TAG, managers.get(instanceId));
            }
            return Optional.ofNullable(tags.get(key));
        }

        @Override
        public List<String> launch(Role role, int count, String amiId, String userDataScript) {
            if (capReached) {
                return List.of();
            }
            launchedUserData.add(userDataScript);
            String id = "i-new" + nextId++;
            addManager(id, null);
            return List.of(id);
        }

        @Override
        public boolean isActive(String instanceId) {
            return alive.contains(instanceId);
        }
    }

    private final StubEc2 ec2 = new StubEc2();
    private final ManagerLocator locator = new Ec2ManagerLocator(ec2, "ami-0123456789abcdef0", "my-bucket",
            "jars/manager.jar");

    @Test
    void usesAManagerBelowTheLimit() {
        ec2.addManager("i-m1", "1");

        assertEquals(new ManagerHandle("i-m1"), locator.ensureAvailableManager(2));
        assertTrue(ec2.launchedUserData.isEmpty());
    }

    @Test
    void startsANewManagerWhenTheOnlyOneIsFull() {
        ec2.addManager("i-m1", "2");

        ManagerHandle handle = locator.ensureAvailableManager(2);

        assertEquals(new ManagerHandle("i-new1"), handle);
        assertEquals(1, ec2.launchedUserData.size());
        assertTrue(ec2.launchedUserData.get(0).contains("jars/manager.jar"));
        // the manager gets bucket and worker AMI as its two arguments (open issue 8)
        assertTrue(ec2.launchedUserData.get(0).contains("manager.jar my-bucket ami-0123456789abcdef0"));
    }

    @Test
    void startsAManagerWhenNoneExists() {
        assertEquals(new ManagerHandle("i-new1"), locator.ensureAvailableManager(3));
    }

    @Test
    void fullnessDependsOnThisRunsLimit() {
        // The same manager is full for an app with manager-clients-n=3 but available for one with 5.
        ec2.addManager("i-m1", "3");

        assertEquals(new ManagerHandle("i-m1"), locator.ensureAvailableManager(5));
        assertEquals(new ManagerHandle("i-new1"), locator.ensureAvailableManager(3));
    }

    @Test
    void missingTagCountsAsZero() {
        ec2.addManager("i-booting", null); // just started, hasn't written ActiveClients yet

        assertEquals(new ManagerHandle("i-booting"), locator.ensureAvailableManager(1));
        assertTrue(ec2.launchedUserData.isEmpty());
    }

    @Test
    void picksTheLeastLoadedAvailableManager() {
        ec2.addManager("i-m1", "2");
        ec2.addManager("i-m2", "0");
        ec2.addManager("i-m3", "1");

        assertEquals(new ManagerHandle("i-m2"), locator.ensureAvailableManager(3));
    }

    @Test
    void unreadableTagSkipsThatManager() {
        ec2.addManager("i-weird", "lots");

        assertEquals(new ManagerHandle("i-new1"), locator.ensureAvailableManager(5));
    }

    @Test
    void failsClearlyWhenNoneIsAvailableAndTheCapIsReached() {
        ec2.addManager("i-m1", "2");
        ec2.capReached = true;

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> locator.ensureAvailableManager(2));
        assertTrue(e.getMessage().contains("instance cap"));
    }

    @Test
    void rejectsNonPositiveLimit() {
        assertThrows(IllegalArgumentException.class, () -> locator.ensureAvailableManager(0));
    }

    @Test
    void isAliveFollowsTheInstanceState() {
        ec2.addManager("i-m1", "0");
        ManagerHandle handle = locator.ensureAvailableManager(1);
        assertTrue(locator.isAlive(handle));

        ec2.alive.remove("i-m1"); // terminated or crashed
        assertFalse(locator.isAlive(handle));
    }

    @Test
    void contractTerminatingManagerIsNoLongerTaggedAsManager() {
        // The locator relies on findActive(MANAGER) skipping a terminating manager (open issue 5).
        assertNotEquals(EC2Service.Role.MANAGER.name(), EC2ComputeClient.TERMINATING_ROLE);
    }

    @Test
    void contractTagNameMatchesWhatTheManagerWrites() {
        // The manager writes this tag; if it renames it, the local app would see every manager as empty.
        assertEquals(EC2ComputeClient.ACTIVE_CLIENTS_TAG_KEY, EC2Service.ACTIVE_CLIENTS_TAG);
    }
}
