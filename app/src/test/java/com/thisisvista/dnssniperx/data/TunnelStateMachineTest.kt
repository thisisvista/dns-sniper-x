package com.thisisvista.dnssniperx.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelStateMachineTest {
    @Test fun successfulLifecycleCanReturnToIdle() {
        val machine = TunnelStateMachine()
        assertTrue(machine.transitionTo(TunnelState.STARTING))
        assertTrue(machine.transitionTo(TunnelState.CONNECTED))
        assertTrue(machine.transitionTo(TunnelState.STOPPING))
        assertTrue(machine.transitionTo(TunnelState.IDLE))
        assertEquals(TunnelState.IDLE, machine.state)
    }

    @Test fun invalidTransitionDoesNotChangeState() {
        val machine = TunnelStateMachine()
        assertFalse(machine.transitionTo(TunnelState.CONNECTED))
        assertEquals(TunnelState.IDLE, machine.state)
    }

    @Test fun failureCanOnlyBeResetToIdle() {
        val machine = TunnelStateMachine()
        assertTrue(machine.transitionTo(TunnelState.STARTING))
        assertTrue(machine.transitionTo(TunnelState.FAILED))
        assertFalse(machine.transitionTo(TunnelState.CONNECTED))
        assertTrue(machine.transitionTo(TunnelState.IDLE))
    }

    @Test fun stoppingCannotConnect() {
        val machine = TunnelStateMachine(TunnelState.STOPPING)
        assertFalse(machine.transitionTo(TunnelState.CONNECTED))
        assertTrue(machine.transitionTo(TunnelState.FAILED))
    }
}
