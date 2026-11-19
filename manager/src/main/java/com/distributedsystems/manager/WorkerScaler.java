package com.distributedsystems.manager;

import com.distributedsystems.manager.CloudInterfaces.ComputeClient;

public class WorkerScaler {
    private final ComputeClient computeClient;
    private final int maxWorkers;

    public WorkerScaler(ComputeClient computeClient, int maxWorkers) {
        if (maxWorkers <= 0) {
            throw new IllegalArgumentException("maxWorkers must be positive");
        }
        this.computeClient = computeClient;
        this.maxWorkers = maxWorkers;
    }

    /**
     * Calculates and launches missing Workers in a synchronized manner
     * (prevents race conditions between concurrent clients).
     *
     * @param numImages number of images in the new job
     * @param n         files-per-worker ratio for this job
     * @return number of new instances actually launched
     */
    public synchronized int scaleForNewJob(int numImages, int n) {
        if (numImages <= 0) {
            return 0;
        }

        // Assignment formula: ceil(numImages / n)
        int requiredForJob = (int) Math.ceil((double) numImages / n);
        int targetWorkers = Math.min(requiredForJob, maxWorkers);

        int activeWorkers = computeClient.getActiveWorkerCount();
        int toLaunch = Math.max(0, targetWorkers - activeWorkers);

        if (toLaunch > 0) {
            computeClient.launchWorkers(toLaunch);
        }
        return toLaunch;
    }
}