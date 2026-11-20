package com.thisisvista.dnssniperx.data

import android.os.PowerManager

/** Conservative scan scheduling based on device thermal and battery conditions. */
object ScanResourcePolicy {
    fun recommendedParallelism(
        thermalStatus: Int,
        batteryPercent: Int,
        powerSaveMode: Boolean
    ): Int {
        if (powerSaveMode || batteryPercent in 0..20) return 1
        return when {
            thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> 1
            thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE -> 2
            batteryPercent in 21..35 -> 2
            else -> 4
        }
    }
}
