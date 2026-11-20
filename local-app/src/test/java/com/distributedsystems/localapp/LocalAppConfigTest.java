package com.distributedsystems.localapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import software.amazon.awssdk.regions.Region;

class LocalAppConfigTest {

    @TempDir
    Path dir;

    private static Properties minimal() {
        Properties p = new Properties();
        p.setProperty("bucket", "my-bucket");
        p.setProperty("amiId", "ami-07f5ffe53445e23b1");
        return p;
    }

    @Test
    void appliesDefaultsForOptionalSettings() {
        LocalAppConfig config = LocalAppConfig.fromProperties(minimal());

        assertEquals("my-bucket", config.bucket());
        assertEquals("ami-07f5ffe53445e23b1", config.amiId());
        assertEquals(Region.US_EAST_1, config.region());
        assertEquals(Duration.ofMinutes(60), config.waitTimeout());
        assertEquals("jars/manager.jar", config.managerJarKey());
    }

    @Test
    void readsOptionalSettings() {
        Properties p = minimal();
        p.setProperty("region", "eu-west-1");
        p.setProperty("waitTimeoutMinutes", "5");
        p.setProperty("managerJarKey", "jars/manager-v2.jar");

        LocalAppConfig config = LocalAppConfig.fromProperties(p);

        assertEquals(Region.EU_WEST_1, config.region());
        assertEquals(Duration.ofMinutes(5), config.waitTimeout());
        assertEquals("jars/manager-v2.jar", config.managerJarKey());
    }

    @Test
    void rejectsMissingRequiredSettings() {
        Properties noBucket = minimal();
        noBucket.remove("bucket");
        assertThrows(IllegalArgumentException.class, () -> LocalAppConfig.fromProperties(noBucket));

        Properties noAmi = minimal();
        noAmi.setProperty("amiId", "  ");
        assertThrows(IllegalArgumentException.class, () -> LocalAppConfig.fromProperties(noAmi));
    }

    @Test
    void rejectsInvalidValues() {
        Properties badAmi = minimal();
        badAmi.setProperty("amiId", "my-ami");
        assertThrows(IllegalArgumentException.class, () -> LocalAppConfig.fromProperties(badAmi));

        Properties badTimeout = minimal();
        badTimeout.setProperty("waitTimeoutMinutes", "soon");
        assertThrows(IllegalArgumentException.class, () -> LocalAppConfig.fromProperties(badTimeout));

        Properties zeroTimeout = minimal();
        zeroTimeout.setProperty("waitTimeoutMinutes", "0");
        assertThrows(IllegalArgumentException.class, () -> LocalAppConfig.fromProperties(zeroTimeout));
    }

    @Test
    void loadsFromFile() throws IOException {
        Path file = dir.resolve("local-app.properties");
        Files.writeString(file, "# comment\nbucket = file-bucket\namiId=ami-0123456789abcdef0\n");

        LocalAppConfig config = LocalAppConfig.load(file);

        assertEquals("file-bucket", config.bucket()); // surrounding spaces trimmed
        assertEquals("ami-0123456789abcdef0", config.amiId());
    }

    @Test
    void missingFileGivesHelpfulMessage() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LocalAppConfig.load(dir.resolve("local-app.properties")));
        assertTrue(e.getMessage().contains("local-app.properties.example"));
    }

    @Test
    void exampleFileInRepoIsValid() throws IOException {
        // The committed template must parse, so copying it is a working starting point.
        Path example = Path.of("..", "local-app.properties.example");
        assertTrue(Files.isRegularFile(example), "missing " + example.toAbsolutePath());
        LocalAppConfig.load(example);
    }
}
