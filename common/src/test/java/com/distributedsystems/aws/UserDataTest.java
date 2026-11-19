package com.distributedsystems.aws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UserDataTest {

    @Test
    void workerScriptDownloadsJarAndStartsIt() {
        String script = UserData.worker("my-bucket", "jars/worker.jar");

        assertTrue(script.startsWith("#!/bin/bash\n"), "must start with a shebang line");
        assertTrue(script.contains("/snap/bin/aws s3 cp s3://my-bucket/jars/worker.jar /opt/ocr/worker.jar"));
        assertTrue(script.contains("nohup java -jar /opt/ocr/worker.jar >> /var/log/worker.log 2>&1 &"));
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
