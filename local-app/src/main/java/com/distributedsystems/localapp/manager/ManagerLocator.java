package com.distributedsystems.localapp.manager;

/**
 * Finds a manager for this local app's job. An interface so the run flow ({@code LocalApp}) can be tested with a
 * stub, and so the open manager issues (5: terminating marker, 10: routing) change only the implementation.
 */
public interface ManagerLocator {

    /**
     * Returns an available manager, starting one if none is available.
     *
     * @param managerClientsN this run's {@code manager-clients-n}: a manager serving this many clients or more is
     *                        full for this app
     * @throws IllegalStateException if no manager is available and none can be launched (instance cap reached)
     */
    ManagerHandle ensureAvailableManager(int managerClientsN);

    /** Whether the given manager is still running (used while waiting for {@code JOB_DONE}). */
    boolean isAlive(ManagerHandle manager);
}
