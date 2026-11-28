package com.thisisvista.dnssniperx.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.thisisvista.dnssniperx.MainActivity
import com.thisisvista.dnssniperx.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns long-running user-initiated DNS scans independently of the Activity/Compose
 * lifecycle. Progress is persisted so the UI can be recreated while the service runs.
 */
class DnsScanForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.thisisvista.dnssniperx.action.START_SCAN"
        const val ACTION_STOP = "com.thisisvista.dnssniperx.action.STOP_SCAN"
        const val EXTRA_RETRY_UNSUCCESSFUL = "retry_unsuccessful"
        const val EXTRA_SOURCE_FILTER = "source_filter"
        const val PREFERENCES = "dns_scan_runtime"
        private const val CHANNEL_ID = "dns_scan_channel"
        private const val NOTIFICATION_ID = 4107
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null
    private val state by lazy {
        getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    }
    private val notifications by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                state.edit()
                    .putBoolean("paused", true)
                    .putString("message", "Pausing scan…")
                    .apply()
                val job = scanJob
                if (job != null) {
                    job.cancel()
                } else {
                    publishState(false, state.getString("progress", "") ?: "", "Scan paused. Completed results are saved; resume to continue.")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val resumePrevious = state.getBoolean("paused", false)
                val retry = if (resumePrevious) state.getBoolean("retry", false)
                    else intent.getBooleanExtra(EXTRA_RETRY_UNSUCCESSFUL, false)
                val filter = if (resumePrevious) state.getString("source_filter", "ALL") ?: "ALL"
                    else intent.getStringExtra(EXTRA_SOURCE_FILTER) ?: "ALL"
                state.edit()
                    .putBoolean("retry", retry)
                    .putString("source_filter", filter)
                    .putBoolean("paused", false)
                    .apply()
                if (scanJob?.isActive != true) {
                    startAsForeground()
                    launchScan(retry, filter)
                }
                return START_STICKY
            }

            null -> {
                // Android may recreate a sticky foreground service after process pressure.
                if (state.getBoolean("running", false)) {
                    startAsForeground()
                    launchScan(
                        state.getBoolean("retry", false),
                        state.getString("source_filter", "ALL") ?: "ALL"
                    )
                    return START_STICKY
                }
                stopSelf(startId)
                return START_NOT_STICKY
            }

            else -> return START_NOT_STICKY
        }
    }

    private fun launchScan(retryUnsuccessful: Boolean, sourceFilter: String) {
        scanJob = serviceScope.launch {
            var completed = 0
            var total = 0
            var pausedForThermal = false
            try {
                publishState(true, "0 / 0", "Preparing DNS scan…")
                val dao = ResolverDatabase.getInstance(applicationContext).resolverDao()
                total = dao.countPendingScan(retryUnsuccessful, sourceFilter)
                if (total == 0) {
                    val emptyMessage = if (retryUnsuccessful) {
                        "No previously tested DNS entries need a retry."
                    } else {
                        "No untested DNS entries remain. Use Retry unsuccessful / warnings to test them again."
                    }
                    publishState(false, "0 / 0", emptyMessage)
                    return@launch
                }

                publishState(true, "0 / $total", "DNS scan started.")
                var lastId = 0L
                var batchIndex = 0

                while (true) {
                    val batch = dao.getPendingScanBatch(
                        retryUnsuccessful = retryUnsuccessful,
                        sourceFilter = sourceFilter,
                        afterId = lastId,
                        limit = ScanResourcePolicy.MAX_SCAN_BATCH_SIZE
                    )
                    if (batch.isEmpty()) break
                    lastId = batch.last().id
                    batchIndex++

                    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                    val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    val batteryPercent = batteryManager
                        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                        .takeIf { it in 0..100 } ?: -1
                    val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        powerManager.currentThermalStatus
                    } else {
                        PowerManager.THERMAL_STATUS_NONE
                    }

                    if (ScanResourcePolicy.shouldPauseForThermal(thermalStatus)) {
                        pausedForThermal = true
                        state.edit().putBoolean("paused", true).apply()
                        publishState(
                            false,
                            "$completed / $total",
                            "Scan paused automatically because the device is critically hot. Let it cool, then resume."
                        )
                        break
                    }

                    val parallelism = ScanResourcePolicy.recommendedParallelism(
                        thermalStatus = thermalStatus,
                        batteryPercent = batteryPercent,
                        powerSaveMode = powerManager.isPowerSaveMode
                    )
                    val totalBatches = (total + ScanResourcePolicy.MAX_SCAN_BATCH_SIZE - 1) /
                        ScanResourcePolicy.MAX_SCAN_BATCH_SIZE
                    publishState(
                        true,
                        "$completed / $total",
                        "Scanning batch $batchIndex of $totalBatches… (parallelism $parallelism)"
                    )

                    DnsProbe.scan(batch, parallelism = parallelism) { result ->
                        val best = listOfNotNull(
                            result.udp.latencyMs.takeIf { result.udp.status == "PASS" },
                            result.tcp.latencyMs.takeIf { result.tcp.status == "PASS" }
                        ).minOrNull()
                        val overall = when {
                            result.udp.status == "PASS" || result.tcp.status == "PASS" -> "DNS_PASS"
                            result.udp.status == "TIMEOUT" || result.tcp.status == "TIMEOUT" -> "TIMEOUT"
                            else -> "FAILED"
                        }
                        val resolver = batch.firstOrNull { it.id == result.resolverId }
                        val testedAt = System.currentTimeMillis()
                        dao.saveScanResult(
                            history = ScanHistoryEntity(
                                resolverAddress = resolver?.address ?: "Unknown",
                                resolverPort = resolver?.port ?: 53,
                                testedAt = testedAt,
                                udpStatus = result.udp.status,
                                udpLatencyMs = result.udp.latencyMs,
                                tcpStatus = result.tcp.status,
                                tcpLatencyMs = result.tcp.latencyMs,
                                scanMode = if (retryUnsuccessful) "RETRY" else "SCAN"
                            ),
                            resolverId = result.resolverId,
                            udpStatus = result.udp.status,
                            udpLatencyMs = result.udp.latencyMs,
                            tcpStatus = result.tcp.status,
                            tcpLatencyMs = result.tcp.latencyMs,
                            testedAt = testedAt,
                            overallStatus = overall,
                            bestLatency = best,
                            note = DnsProbe.buildDiagnosticNote(result.udp.detail, result.tcp.detail)
                        )
                        completed++
                        // Persist and refresh progress in small increments rather than
                        // writing preferences and updating the notification for every DNS.
                        if (completed % 5 == 0 || completed == total) {
                            publishState(true, "$completed / $total", "Scanning DNS…")
                        }
                    }
                    publishState(true, "$completed / $total", "Scanning DNS…")
                }

                if (!pausedForThermal) {
                    publishState(false, "$completed / $total", "Scan complete: $completed DNS entries tested and saved.")
                }
            } catch (cancelled: CancellationException) {
                publishState(false, "$completed / $total", "Scan paused. Completed results are saved; resume to continue with the remaining DNS entries.")
                throw cancelled
            } catch (exception: Exception) {
                publishState(false, "$completed / $total", "Scan stopped: ${exception.message ?: "Unknown error"}. Completed results remain saved; you can retry.")
            } finally {
                scanJob = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun publishState(running: Boolean, progress: String, message: String) {
        state.edit()
            .putBoolean("running", running)
            .putString("progress", progress)
            .putString("message", message)
            .apply()

        if (running) {
            notifications.notify(NOTIFICATION_ID, buildNotification(message, progress))
        }
    }

    private fun startAsForeground() {
        val notification = buildNotification("Preparing DNS scan…", "Starting")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DNS scan",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while DNS SniperX scans resolvers in the background."
            }
            notifications.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String, progress: String): Notification {
        val stopIntent = Intent(this, DnsScanForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            2,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("DNS SniperX scan")
            .setContentText("$message ($progress)")
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(null, "Pause", stopPendingIntent).build()
            )
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scanJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
