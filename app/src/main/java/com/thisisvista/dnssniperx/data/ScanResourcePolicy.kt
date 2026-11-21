package com.thisisvista.dnssniperx.data

import android.os.PowerManager

/** Conservative scan scheduling based on device thermal and battery conditions. */
object ScanResourcePolicy {
    const val MAX_SCAN_BATCH_SIZE = 10

    fun shouldPauseForThermal(thermalStatus: Int): Boolean =
        thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL

    fun recommendedParallelism(
        thermalStatus: Int,
        batteryPercent: Int,
        powerSaveMode: Boolean
    ): Int {
        // Unknown or invalid battery readings must not be treated as a full battery.
        if (powerSaveMode || batteryPercent !in 21..100) return 1
        return when {
            thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> 1
            thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> 2
            batteryPercent in 21..35 -> 2
            else -> 4
        }
    }
}
