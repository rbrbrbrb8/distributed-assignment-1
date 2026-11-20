package com.distributedsystems.localapp.manager;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import com.distributedsystems.aws.EC2Service;
import com.distributedsystems.aws.EC2Service.Role;
import com.distributedsystems.aws.UserData;

/**
 * {@link ManagerLocator} backed by EC2.
 *
 * <p><b>Available</b> = a pending/running {@code Role=MANAGER} instance whose {@value EC2Service#ACTIVE_CLIENTS_TAG}
 * tag is below this run's {@code manager-clients-n} (open issue 3). A missing tag counts as 0: the manager just
 * booted and hasn't written it yet. Among several available managers the least loaded one is chosen.
 *
 * <p>If none is available, a new manager is launched with {@link UserData#manager}. If the launch returns nothing
 * (the global instance cap is reached), this fails with a clear error. Falling back to the least-loaded full manager
 * is a future TODO (open issue 10), not done on purpose.
 *
 * <p>A terminating manager is never chosen (open issue 5): on {@code TERMINATE} it changes its own tag to
 * {@code Role=TERMINATING_MANAGER}, so {@code findActive(MANAGER)} no longer returns it. {@link #isAlive} still
 * counts it as alive, on purpose: it finishes the jobs it already accepted, so replacing it would only duplicate work.
 *
 * <p>Known gap (open issue 10): the choice isn't enforced, because {@code NEW_TASK} goes to the shared inbound queue.
 */
public class Ec2ManagerLocator implements ManagerLocator {

    private final EC2Service ec2;
    private final String amiId;
    private final String bucket;
    private final String managerJarKey;

    public Ec2ManagerLocator(EC2Service ec2, String amiId, String bucket, String managerJarKey) {
        this.ec2 = ec2;
        this.amiId = amiId;
        this.bucket = bucket;
        this.managerJarKey = managerJarKey;
    }

    @Override
    public ManagerHandle ensureAvailableManager(int managerClientsN) {
        if (managerClientsN <= 0) {
            throw new IllegalArgumentException("manager-clients-n must be positive, got " + managerClientsN);
        }
        return findAvailable(managerClientsN).orElseGet(this::launchManager);
    }

    @Override
    public boolean isAlive(ManagerHandle manager) {
        return ec2.isActive(manager.instanceId());
    }

    /** The least-loaded active manager with fewer than {@code managerClientsN} clients, if any. */
    private Optional<ManagerHandle> findAvailable(int managerClientsN) {
        record Candidate(String instanceId, int clients) { }

        return ec2.findActive(Role.MANAGER).stream()
                // a manager with an unreadable tag yields no candidate (skipped)
                .flatMap(id -> activeClients(id).stream().mapToObj(clients -> new Candidate(id, clients)))
                .filter(c -> c.clients() < managerClientsN)
                .min(Comparator.comparingInt(Candidate::clients))
                .map(c -> new ManagerHandle(c.instanceId()));
    }

    /** The manager's client count: 0 if the tag is missing, empty if the tag is unreadable (manager skipped). */
    private OptionalInt activeClients(String instanceId) {
        Optional<String> tag = ec2.getTag(instanceId, EC2Service.ACTIVE_CLIENTS_TAG);
        if (tag.isEmpty()) {
            return OptionalInt.of(0);
        }
        try {
            return OptionalInt.of(Integer.parseInt(tag.get().trim()));
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    private ManagerHandle launchManager() {
        // One shared AMI: the manager boots from it and starts its workers from it too.
        List<String> launched = ec2.launch(Role.MANAGER, 1, amiId, UserData.manager(bucket, managerJarKey, amiId));
        if (launched.isEmpty()) {
            throw new IllegalStateException("No manager is available and a new one can't be started: the instance cap ("
                    + EC2Service.MAX_INSTANCES + ") is reached. Try again later.");
        }
        return new ManagerHandle(launched.get(0));
    }
}
