package net.minto.hoppingelytra;

import org.junit.jupiter.api.Test;

import static net.minto.hoppingelytra.RemoteGlidingRecovery.Action.NONE;
import static net.minto.hoppingelytra.RemoteGlidingRecovery.Action.SEND_REQUEST;
import static net.minto.hoppingelytra.RemoteGlidingRecovery.State.CONFIRMED;
import static net.minto.hoppingelytra.RemoteGlidingRecovery.State.IDLE;
import static net.minto.hoppingelytra.RemoteGlidingRecovery.State.REQUEST_IN_FLIGHT;
import static net.minto.hoppingelytra.RemoteGlidingRecovery.State.WAIT_AIRBORNE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteGlidingRecoveryTest {
    @Test
    void initialRequestIsSentOnlyOnce() {
        RemoteGlidingRecovery recovery = new RemoteGlidingRecovery();

        assertEquals(SEND_REQUEST, recovery.begin());
        assertEquals(NONE, recovery.begin());
        assertEquals(REQUEST_IN_FLIGHT, recovery.state());
        assertEquals(1, recovery.requestsSent());
    }

    @Test
    void ticksNeverResendWhileRequestIsInFlight() {
        RemoteGlidingRecovery recovery = new RemoteGlidingRecovery();
        recovery.begin();

        for (int tick = 0; tick < 20; tick++) {
            assertEquals(NONE, recovery.tick(true, true));
            assertEquals(REQUEST_IN_FLIGHT, recovery.state());
            assertEquals(1, recovery.requestsSent());
        }
    }

    @Test
    void serverTrueConfirmsWithoutAnotherRequest() {
        RemoteGlidingRecovery recovery = new RemoteGlidingRecovery();
        recovery.begin();

        assertEquals(NONE, recovery.onServerGlidingState(true, true, true));
        assertEquals(CONFIRMED, recovery.state());
        assertEquals(NONE, recovery.tick(true, true));
    }

    @Test
    void serverFalseRetriesOnceWhenAirborne() {
        RemoteGlidingRecovery recovery = new RemoteGlidingRecovery();
        recovery.begin();

        assertEquals(SEND_REQUEST, recovery.onServerGlidingState(false, true, true));
        assertEquals(REQUEST_IN_FLIGHT, recovery.state());
        assertEquals(2, recovery.requestsSent());
    }

    @Test
    void serverFalseWaitsOnGroundAndSendsOnNextTakeoff() {
        RemoteGlidingRecovery recovery = new RemoteGlidingRecovery();
        recovery.begin();

        assertEquals(NONE, recovery.onServerGlidingState(false, false, true));
        assertEquals(WAIT_AIRBORNE, recovery.state());
        assertEquals(NONE, recovery.tick(false, true));
        assertEquals(SEND_REQUEST, recovery.tick(true, true));
        assertEquals(REQUEST_IN_FLIGHT, recovery.state());
        assertEquals(2, recovery.requestsSent());
    }

    @Test
    void invalidContinuationResetsImmediately() {
        RemoteGlidingRecovery recovery = new RemoteGlidingRecovery();
        recovery.begin();

        assertEquals(NONE, recovery.tick(true, false));
        assertEquals(IDLE, recovery.state());
        assertFalse(recovery.isActive());
    }

    @Test
    void requestLimitEndsRecovery() {
        RemoteGlidingRecovery recovery = new RemoteGlidingRecovery(4, 60, 15);
        assertEquals(SEND_REQUEST, recovery.begin());
        assertEquals(SEND_REQUEST, recovery.onServerGlidingState(false, true, true));
        assertEquals(SEND_REQUEST, recovery.onServerGlidingState(false, true, true));
        assertEquals(SEND_REQUEST, recovery.onServerGlidingState(false, true, true));

        assertEquals(NONE, recovery.onServerGlidingState(false, true, true));
        assertEquals(IDLE, recovery.state());
    }

    @Test
    void recoveryTimeLimitEndsOnlyUnconfirmedRecovery() {
        RemoteGlidingRecovery recovery = new RemoteGlidingRecovery(4, 3, 15);
        recovery.begin();

        assertEquals(NONE, recovery.tick(true, true));
        assertEquals(NONE, recovery.tick(true, true));
        assertEquals(NONE, recovery.tick(true, true));
        assertEquals(IDLE, recovery.state());

        recovery.begin();
        recovery.onServerGlidingState(true, true, true);
        for (int tick = 0; tick < 10; tick++) {
            assertEquals(NONE, recovery.tick(true, true));
        }
        assertEquals(CONFIRMED, recovery.state());
    }

    @Test
    void continuousGroundContactEndsRecovery() {
        RemoteGlidingRecovery recovery = new RemoteGlidingRecovery(4, 60, 3);
        recovery.begin();

        assertEquals(NONE, recovery.tick(false, true));
        assertEquals(NONE, recovery.tick(false, true));
        assertEquals(NONE, recovery.tick(false, true));
        assertEquals(IDLE, recovery.state());
    }

    @Test
    void delayedResponseCannotRestartFinishedSession() {
        RemoteGlidingRecovery recovery = new RemoteGlidingRecovery();
        recovery.begin();
        recovery.reset();

        assertEquals(NONE, recovery.onServerGlidingState(true, true, true));
        assertEquals(NONE, recovery.onServerGlidingState(false, true, true));
        assertEquals(IDLE, recovery.state());
    }

    @Test
    void integratedServerDisablesRemoteRecovery() {
        assertFalse(HoppingElytraClient.shouldUseRemoteRecovery(true));
        assertTrue(HoppingElytraClient.shouldUseRemoteRecovery(false));
    }

    @Test
    void ordinaryJumpAndOrdinaryHighAltitudeGlideDoNotStartHop() {
        assertFalse(HoppingElytraClient.isHopTakeoff(true, true, true, false, true));
        assertFalse(HoppingElytraClient.isHopTakeoff(true, false, true, true, true));
        assertFalse(HoppingElytraClient.isHopTakeoff(false, true, true, true, true));
        assertTrue(HoppingElytraClient.isHopTakeoff(true, true, true, true, true));
    }
}
