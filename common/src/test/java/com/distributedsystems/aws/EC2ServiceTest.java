package com.distributedsystems.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Tests the 19-instance cap calculation (pure logic, no AWS calls). */
class EC2ServiceTest {

    @Test
    void launchesEverythingWhenThereIsRoom() {
        assertEquals(5, EC2Service.allowedToLaunch(5, 0));
        assertEquals(5, EC2Service.allowedToLaunch(5, 14)); // 14 + 5 = exactly 19
    }

    @Test
    void launchesOnlyUpToTheCap() {
        assertEquals(2, EC2Service.allowedToLaunch(5, 17)); // manager + 16 workers → room for 2
    }

    @Test
    void launchesNothingWhenAtOrOverTheCap() {
        assertEquals(0, EC2Service.allowedToLaunch(5, 19));
        assertEquals(0, EC2Service.allowedToLaunch(5, 25)); // e.g. someone launched extra by hand
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
