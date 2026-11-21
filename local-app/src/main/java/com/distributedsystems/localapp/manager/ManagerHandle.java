package com.distributedsystems.localapp.manager;

/**
 * The manager this local app chose (or started) for its job.
 *
 * <p>A record so it can grow without changing callers, e.g. a per-manager inbound queue URL if open issue 10 is
 * fixed with one queue per manager.
 *
 * @param instanceId EC2 instance ID of the manager
 */
public record ManagerHandle(String instanceId) {

    public ManagerHandle {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
    }
}
