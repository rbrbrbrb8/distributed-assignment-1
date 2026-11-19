package com.distributedsystems.aws;

import java.util.regex.Pattern;

/**
 * Builds the boot ("user-data") scripts that EC2 runs once, as root, when an instance starts from our AMI.
 *
 * <p>The AMI already contains Java 21, Tesseract and the AWS CLI; the script only downloads the latest jar from S3
 * (through the instance's IAM role - no credentials in the script) and starts it in the background.
 */
public final class UserData {

    /** Installed by snap; snap's bin dir is not always on cloud-init's PATH, so use the full path. */
    private static final String AWS_CLI = "/snap/bin/aws";
    private static final String INSTALL_DIR = "/opt/ocr";
    /** Values are pasted into a shell script, so only allow characters that can't change its meaning. */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._/-]+");

    private UserData() {
    }

    /** Boot script for a worker: run {@code worker.jar} in SQS mode, logging to {@code /var/log/worker.log}. */
    public static String worker(String bucket, String jarKey) {
        return forJar(bucket, jarKey, "worker.jar", "/var/log/worker.log");
    }

    static String forJar(String bucket, String jarKey, String jarName, String logFile) {
        requireSafe("bucket", bucket);
        requireSafe("jarKey", jarKey);
        String jarPath = INSTALL_DIR + "/" + jarName;
        return String.join("\n",
                "#!/bin/bash",
                "set -euo pipefail",
                "exec >> /var/log/ocr-bootstrap.log 2>&1   # log this script's own output",
                "echo \"bootstrap started $(date -u)\"",
                "mkdir -p " + INSTALL_DIR,
                AWS_CLI + " s3 cp s3://" + bucket + "/" + jarKey + " " + jarPath + " --only-show-errors",
                "cd " + INSTALL_DIR,
                "nohup java -jar " + jarPath + " >> " + logFile + " 2>&1 &",
                "echo \"started " + jarName + " (pid $!)\"",
                "");
    }

    private static void requireSafe(String name, String value) {
        if (value == null || !SAFE.matcher(value).matches()) {
            throw new IllegalArgumentException("Unsafe " + name + " for user-data script: " + value);
        }
    }
}
