package com.distributedsystems.aws;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Tests the bucket layout helpers and the safety checks (pure logic, no AWS calls). */
class S3ServiceTest {

    private static final String CLIENT = "3f2a9c1e-7b4d-4e8a-9c0f-1d2e3f4a5b6c";

    @Test
    void jobKeysLiveInTheClientsOwnFolder() {
        assertEquals("jobs/" + CLIENT + "/", S3Service.jobPrefix(CLIENT));
        assertEquals("jobs/" + CLIENT + "/input.txt", S3Service.inputKey(CLIENT));
        assertEquals("jobs/" + CLIENT + "/summary.txt", S3Service.summaryKey(CLIENT));
    }

    @Test
    void jarKeysLiveInJarsFolder() {
        assertEquals("jars/worker.jar", S3Service.jarKey("worker.jar"));
        assertEquals("jars/manager.jar", S3Service.jarKey("manager.jar"));
    }

    @Test
    void idsThatCouldEscapeTheirFolderAreRejected() {
        for (String bad : new String[] {"../x", "a/b", "..", ".", "", " ", "id with space", "x;rm"}) {
            assertThrows(IllegalArgumentException.class, () -> S3Service.inputKey(bad), "should reject: '" + bad + "'");
        }
        assertThrows(IllegalArgumentException.class, () -> S3Service.inputKey(null));
        assertThrows(IllegalArgumentException.class, () -> S3Service.jarKey("../manager.jar"));
    }

    @Test
    void deletePrefixOnlyAcceptsFolders() {
        assertThrows(IllegalArgumentException.class, () -> S3Service.requireFolderPrefix(""));
        assertThrows(IllegalArgumentException.class, () -> S3Service.requireFolderPrefix(null));
        assertThrows(IllegalArgumentException.class, () -> S3Service.requireFolderPrefix("/"));
        assertThrows(IllegalArgumentException.class, () -> S3Service.requireFolderPrefix("jobs")); // no trailing '/'

        assertDoesNotThrow(() -> S3Service.requireFolderPrefix(S3Service.jobPrefix(CLIENT)));
    }

    @Test
    void bucketIsRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> new S3Service(software.amazon.awssdk.regions.Region.US_EAST_1, " "));
    }
}
