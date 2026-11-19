package com.thisisvista.dnssniperx.data

enum class TunnelState { IDLE, STARTING, CONNECTED, STOPPING, FAILED }

/**
 * Pure lifecycle guard for future tunnel orchestration.
 * This class performs no networking and does not establish a VPN connection.
 */
class TunnelStateMachine(initialState: TunnelState = TunnelState.IDLE) {
    var state: TunnelState = initialState
        private set

    fun transitionTo(next: TunnelState): Boolean {
        if (next == state) return true
        val allowed = when (state) {
            TunnelState.IDLE -> next == TunnelState.STARTING
            TunnelState.STARTING -> next == TunnelState.CONNECTED ||
                next == TunnelState.STOPPING || next == TunnelState.FAILED
            TunnelState.CONNECTED -> next == TunnelState.STOPPING ||
                next == TunnelState.FAILED
            TunnelState.STOPPING -> next == TunnelState.IDLE || next == TunnelState.FAILED
            TunnelState.FAILED -> next == TunnelState.IDLE
        }
        if (allowed) state = next
        return allowed
    }
}
