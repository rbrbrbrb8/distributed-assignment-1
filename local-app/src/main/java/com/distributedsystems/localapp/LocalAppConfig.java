package com.distributedsystems.localapp;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.regex.Pattern;

import com.distributedsystems.aws.S3Service;

import software.amazon.awssdk.regions.Region;

/**
 * Non-secret settings of the local app, read from {@value #DEFAULT_FILE} in the working directory.
 * AWS credentials are NOT configured here: the SDK reads them from {@code ~/.aws/credentials}.
 *
 * @param bucket         S3 bucket of the system (jars, jobs, ...)
 * @param amiId          AMI used when this local app has to start a manager
 * @param region         AWS region (default us-east-1)
 * @param waitTimeout    how long to wait for the manager's answer before giving up (default 60 min)
 * @param managerJarKey  S3 key of the manager jar (default {@code jars/manager.jar})
 */
public record LocalAppConfig(String bucket, String amiId, Region region, Duration waitTimeout, String managerJarKey) {

    public static final String DEFAULT_FILE = "local-app.properties";

    private static final Pattern AMI_ID = Pattern.compile("ami-[0-9a-f]{8,17}");

    /** Loads {@code path}; fails with a readable message if the file or a required key is missing. */
    public static LocalAppConfig load(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Config file not found: " + path.toAbsolutePath()
                    + " (copy local-app.properties.example and fill it in)");
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return fromProperties(properties);
    }

    /** Builds the config from already-loaded properties (also used by tests). */
    public static LocalAppConfig fromProperties(Properties properties) {
        String bucket = required(properties, "bucket");

        String amiId = required(properties, "amiId");
        if (!AMI_ID.matcher(amiId).matches()) {
            throw new IllegalArgumentException("amiId must look like ami-0123456789abcdef0, got '" + amiId + "'");
        }

        Region region = Region.of(properties.getProperty("region", "us-east-1").trim());

        long minutes;
        String timeout = properties.getProperty("waitTimeoutMinutes", "60").trim();
        try {
            minutes = Long.parseLong(timeout);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("waitTimeoutMinutes must be a number, got '" + timeout + "'");
        }
        if (minutes <= 0) {
            throw new IllegalArgumentException("waitTimeoutMinutes must be positive, got " + minutes);
        }

        String managerJarKey = properties.getProperty("managerJarKey", S3Service.jarKey("manager.jar")).trim();

        return new LocalAppConfig(bucket, amiId, region, Duration.ofMinutes(minutes), managerJarKey);
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required setting '" + key + "' in " + DEFAULT_FILE);
        }
        return value.trim();
    }
}
