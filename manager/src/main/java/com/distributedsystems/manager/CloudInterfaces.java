package com.distributedsystems.manager;

import java.util.List;

public class CloudInterfaces {

    public interface StorageClient {
        List<String> downloadLines(String s3Key);
        void uploadText(String s3Key, String content);
    }

    public interface ComputeClient {
        /** Returns the number of Worker instances currently in PENDING or RUNNING state */
        int getActiveWorkerCount();

        /** Launches count new EC2 instances tagged with Role=Worker */
        void launchWorkers(int count);

        /** Terminates all active Worker instances */
        void terminateAllWorkers();

        /** Terminates the Manager instance itself */
        void terminateSelf();
    }
}