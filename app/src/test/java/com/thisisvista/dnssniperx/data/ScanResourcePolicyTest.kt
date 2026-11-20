package com.thisisvista.dnssniperx.data

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanResourcePolicyTest {
    @Test fun normalConditionsUseConservativeDefault() {
        assertEquals(4, ScanResourcePolicy.recommendedParallelism(PowerManager.THERMAL_STATUS_NONE, 80, false))
    }
    @Test fun moderateThermalStateReducesConcurrency() {
        assertEquals(2, ScanResourcePolicy.recommendedParallelism(PowerManager.THERMAL_STATUS_MODERATE, 80, false))
    }
    @Test fun severeThermalStateUsesMinimumConcurrency() {
        assertEquals(1, ScanResourcePolicy.recommendedParallelism(PowerManager.THERMAL_STATUS_SEVERE, 80, false))
    }
    @Test fun criticalThermalStateRequestsPause() {
        assertEquals(true, ScanResourcePolicy.shouldPauseForThermal(PowerManager.THERMAL_STATUS_CRITICAL))
    }
    @Test fun severeThermalStateDoesNotRequestCriticalPause() {
        assertEquals(false, ScanResourcePolicy.shouldPauseForThermal(PowerManager.THERMAL_STATUS_SEVERE))
    }
    @Test fun normalThermalStateDoesNotRequestPause() {
        assertEquals(false, ScanResourcePolicy.shouldPauseForThermal(PowerManager.THERMAL_STATUS_NONE))
    }
    @Test fun batterySaverUsesMinimumConcurrency() {
        assertEquals(1, ScanResourcePolicy.recommendedParallelism(PowerManager.THERMAL_STATUS_NONE, 80, true))
    }
    @Test fun lowBatteryUsesMinimumConcurrency() {
        assertEquals(1, ScanResourcePolicy.recommendedParallelism(PowerManager.THERMAL_STATUS_NONE, 15, false))
    }
    @Test fun mediumBatteryReducesConcurrency() {
        assertEquals(2, ScanResourcePolicy.recommendedParallelism(PowerManager.THERMAL_STATUS_NONE, 30, false))
    }
}
