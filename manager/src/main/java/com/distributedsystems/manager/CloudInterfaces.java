package com.distributedsystems.manager;

import java.util.List;

public class CloudInterfaces {

    public interface StorageClient {
        List<String> downloadLines(String s3Key);
        void uploadText(String s3Key, String content);
    }

    public interface ComputeClient {
        int getActiveWorkerCount();
        void launchWorkers(int count);
        void terminateAllWorkers();
        void terminateSelf();
        default void updateActiveClients(int activeClientCount) {
        }
        /** Marks this manager as shutting down, so local apps no longer pick it for new jobs. */
        default void markTerminating() {
        }
    }
}