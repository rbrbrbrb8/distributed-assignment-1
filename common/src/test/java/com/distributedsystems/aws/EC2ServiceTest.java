package com.distributedsystems.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Tests the global 9-instance cap calculation (pure logic, no AWS calls). */
class EC2ServiceTest {

    @Test
    void capIsNine() {
        assertEquals(9, EC2Service.MAX_INSTANCES); // 1 manager + up to 8 workers
    }

    @Test
    void launchesEverythingWhenThereIsRoom() {
        assertEquals(5, EC2Service.allowedToLaunch(5, 0));
        assertEquals(8, EC2Service.allowedToLaunch(8, 1)); // manager + 8 workers = exactly 9
    }

    @Test
    void launchesOnlyUpToTheCap() {
        assertEquals(2, EC2Service.allowedToLaunch(5, 7)); // manager + 6 workers → room for 2
    }

    @Test
    void capIsGlobalAcrossManagers() {
        // Two managers with 3 workers each = 8 active: only 1 slot left, whoever asks first gets it.
        assertEquals(1, EC2Service.allowedToLaunch(8, 8));
    }

    @Test
    void launchesNothingWhenAtOrOverTheCap() {
        assertEquals(0, EC2Service.allowedToLaunch(5, 9));
        assertEquals(0, EC2Service.allowedToLaunch(5, 12)); // e.g. someone launched extra by hand
    }

    @Test
    void zeroRequestedLaunchesNothing() {
        assertEquals(0, EC2Service.allowedToLaunch(0, 0));
    }

    @Test
    void negativeRequestIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> EC2Service.allowedToLaunch(-1, 0));
    }
}
