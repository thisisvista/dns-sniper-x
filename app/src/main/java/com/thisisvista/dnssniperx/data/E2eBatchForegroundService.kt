package com.thisisvista.dnssniperx.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.thisisvista.dnssniperx.MainActivity
import com.thisisvista.dnssniperx.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns batch E2E verification independently of the Compose screen.
 * Every attempt is persisted by E2eVerificationCoordinator before progress advances.
 */
class E2eBatchForegroundService : Service() {
    companion object {
        const val ACTION_START = "com.thisisvista.dnssniperx.action.START_E2E_BATCH"
        const val ACTION_PAUSE = "com.thisisvista.dnssniperx.action.PAUSE_E2E_BATCH"
        private const val CHANNEL_ID = "e2e_batch_channel"
        private const val NOTIFICATION_ID = 4110
        private const val PREFS = "e2e_batch_resume"

        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_PROFILE_NAME = "profile_name"
        const val EXTRA_SERVER_HOST = "server_host"
        const val EXTRA_SERVER_PORT = "server_port"
        const val EXTRA_PUBLIC_KEY = "public_key"
        const val EXTRA_SOURCE = "source"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var batchJob: kotlinx.coroutines.Job? = null
    private var heartbeatJob: kotlinx.coroutines.Job? = null
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val notifications by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                prefs.edit().putBoolean("service_pause_requested", true).apply()
                batchJob?.cancel(CancellationException("E2E batch paused by user"))
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                if (intent?.action == ACTION_START) {
                    prefs.edit()
                        .putString("profile_id", intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty())
                        .putString("profile_name", intent.getStringExtra(EXTRA_PROFILE_NAME).orEmpty())
                        .putString("server_host", intent.getStringExtra(EXTRA_SERVER_HOST).orEmpty())
                        .putInt("server_port", intent.getIntExtra(EXTRA_SERVER_PORT, 53))
                        .putString("public_key", intent.getStringExtra(EXTRA_PUBLIC_KEY).orEmpty())
                        .putString("source", intent.getStringExtra(EXTRA_SOURCE) ?: "COLLECT")
                        .putBoolean("service_pause_requested", false)
                        .apply()
                }
                if (batchJob?.isActive != true) {
                    startAsForeground()
                    heartbeatJob?.cancel()
                    heartbeatJob = serviceScope.launch {
                        while (isActive) {
                            prefs.edit().putLong("ui_batch_heartbeat", System.currentTimeMillis()).apply()
                            delay(5_000)
                        }
                    }
                    batchJob = serviceScope.launch { runBatch() }
                }
                return START_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    private suspend fun runBatch() {
        val wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:E2eBatch").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        val dao = ResolverDatabase.getInstance(applicationContext).resolverDao()
        val historyDao = ResolverDatabase.getInstance(applicationContext).e2eHistoryDao()
        val monitor = ActiveNetworkMonitor(applicationContext)
        try {
            val profile = TunnelProfile(
                id = prefs.getString("profile_id", "").orEmpty(),
                name = prefs.getString("profile_name", "DNSTT E2E profile").orEmpty(),
                protocol = TunnelProtocol.DNSTT,
                serverHost = prefs.getString("server_host", "").orEmpty(),
                serverPort = prefs.getInt("server_port", 53),
                dnsttServerPublicKey = prefs.getString("public_key", "").orEmpty()
            )
            require(profile.validate().isEmpty()) { profile.validate().joinToString("; ") }
            dao.saveTunnelProfile(TunnelProfileMapper.toEntity(profile, dao.findTunnelProfile(profile.id)))

            val source = prefs.getString("source", "COLLECT") ?: "COLLECT"
            val devicePrefs = getSharedPreferences("e2e_device_dns", Context.MODE_PRIVATE)
            val deviceCandidates = if (source == "DEVICE") {
                devicePrefs.getString("entries", "").orEmpty()
                    .lineSequence().map { it.trim() }.filter { it.isNotBlank() }.distinct().toList()
            } else emptyList()
            require(source != "DEVICE" || deviceCandidates.isNotEmpty()) {
                "Choose a TXT file containing DNS addresses first."
            }

            val profileKeyInput = listOf(
                profile.id, profile.serverHost, profile.dnsttServerPublicKey.orEmpty(),
                source, if (source == "DEVICE") deviceCandidates.joinToString("|") else "saved"
            ).joinToString("|")
            val profileKey = MessageDigest.getInstance("SHA-256")
                .digest(profileKeyInput.toByteArray(Charsets.UTF_8))
                .take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val sessionStartKey = "session_started_$profileKey"
            var sessionStartedAt = prefs.getLong(sessionStartKey, 0L)
            if (sessionStartedAt <= 0L) {
                sessionStartedAt = System.currentTimeMillis()
                prefs.edit().putLong(sessionStartKey, sessionStartedAt).apply()
            }

            val providers = dao.observeProviders().first()
            monitor.updateProviders(providers)
            monitor.start()
            val activeNetwork = withTimeoutOrNull(15_000) {
                monitor.state.first { it.isConnected && it.transport != ActiveNetworkTransport.NONE }
            } ?: error("No active network is available. Connect to Wi-Fi or mobile data, then resume.")

            val pageSize = 100
            var total = 0
            if (source == "DEVICE") {
                total = deviceCandidates.size
            } else {
                var countOffset = 0
                while (true) {
                    val page = dao.getSlipNetExportBatch(pageSize, countOffset)
                    if (page.isEmpty()) break
                    total += page.size
                    countOffset += page.size
                    if (page.size < pageSize) break
                }
            }
            require(total > 0) { "No DNS candidates were found for E2E verification." }

            val completed = AtomicInteger(historyDao.countCompletedBatchAttemptsSince(
                profile.serverPort, profile.id, profile.serverHost,
                profile.dnsttServerPublicKey.orEmpty(), sessionStartedAt
            ))
            val passed = AtomicInteger(historyDao.countPassedBatchAttemptsSince(
                profile.serverPort, profile.id, profile.serverHost,
                profile.dnsttServerPublicKey.orEmpty(), sessionStartedAt
            ))
            val failed = AtomicInteger(historyDao.countFailedBatchAttemptsSince(
                profile.serverPort, profile.id, profile.serverHost,
                profile.dnsttServerPublicKey.orEmpty(), sessionStartedAt
            ))
            val skipped = AtomicInteger(0)
            publish("BATCH_RUNNING", "Preparing $total DNS candidates…", total, completed.get(), passed.get(), failed.get(), skipped.get())

            val coordinator = E2eVerificationCoordinator(
                historyDao,
                mapOf(TunnelProtocol.DNSTT to (DnsttMobileE2eEngine() as SlipNetE2eEngine))
            )
            var offset = 0
            while (true) {
                ensureNotPaused()
                val page = if (source == "DEVICE") {
                    deviceCandidates.drop(offset).take(pageSize)
                } else {
                    dao.getSlipNetExportBatch(pageSize, offset).map { it.address }
                }
                if (page.isEmpty()) break

                val pending = mutableListOf<String>()
                for (address in page) {
                    ensureNotPaused()
                    val completedInSession = historyDao.hasCompletedBatchAttemptSince(
                        address, profile.serverPort, profile.id, profile.serverHost,
                        profile.dnsttServerPublicKey.orEmpty(), sessionStartedAt
                    )
                    val passedBeforeSession = historyDao.hasFullPassForProfile(
                        address, profile.serverPort, profile.id, profile.serverHost,
                        profile.dnsttServerPublicKey.orEmpty()
                    )
                    if (!completedInSession && passedBeforeSession) {
                        skipped.incrementAndGet()
                        completed.incrementAndGet()
                    } else if (!completedInSession) {
                        pending.add(address)
                    }
                }
                publish("BATCH_RUNNING", "Checking DNS batch · ${completed.get()}/$total", total, completed.get(), passed.get(), failed.get(), skipped.get())

                for (group in pending.chunked(3)) {
                    ensureNotPaused()
                    publish("BATCH_RUNNING", "Testing ${group.size} DNS resolvers in parallel…", total, completed.get(), passed.get(), failed.get(), skipped.get())
                    coroutineScope {
                        group.map { address ->
                            async {
                                val result = coordinator.verify(
                                    resolverAddress = address,
                                    resolverPort = profile.serverPort,
                                    profile = profile,
                                    mode = E2eVerificationMode.FULL,
                                    network = monitor.state.value.takeIf { it.isConnected } ?: activeNetwork,
                                    timeoutMs = 25_000,
                                    verificationUrl = "http://www.gstatic.com/generate_204",
                                    expectedHttpStatusCode = 204
                                )
                                completed.incrementAndGet()
                                if (result.status == E2eStatus.FULL_PASS && result.httpStatusCode == 204) {
                                    passed.incrementAndGet()
                                } else {
                                    failed.incrementAndGet()
                                }
                                publish("BATCH_RUNNING", "E2E verified ${completed.get()}/$total", total, completed.get(), passed.get(), failed.get(), skipped.get())
                            }
                        }.awaitAll()
                    }
                }
                offset += page.size
                if (page.size < pageSize) break
            }

            val finalDetail = "Finished: ${passed.get()} FULL_PASS · ${failed.get()} failed · ${skipped.get()} previously passed and skipped."
            prefs.edit().remove(sessionStartKey).putBoolean("service_pause_requested", false).apply()
            publish("BATCH_COMPLETED", finalDetail, total, completed.get(), passed.get(), failed.get(), skipped.get())
        } catch (cancelled: CancellationException) {
            val total = prefs.getInt("ui_batch_total", 0)
            val completed = prefs.getInt("ui_batch_completed", 0)
            publish("BATCH_PAUSED", "Paused at $completed/$total. Completed results are saved; Resume continues with unfinished DNS entries.", total,
                completed, prefs.getInt("ui_batch_passed", 0), prefs.getInt("ui_batch_failed", 0), prefs.getInt("ui_batch_skipped", 0))
            throw cancelled
        } catch (failure: Exception) {
            val detail = failure.message ?: failure.javaClass.simpleName
            publish("BATCH_ERROR", detail, prefs.getInt("ui_batch_total", 0),
                prefs.getInt("ui_batch_completed", 0), prefs.getInt("ui_batch_passed", 0),
                prefs.getInt("ui_batch_failed", 0), prefs.getInt("ui_batch_skipped", 0))
        } finally {
            monitor.stop()
            if (wakeLock.isHeld) wakeLock.release()
            heartbeatJob?.cancel()
            heartbeatJob = null
            batchJob = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun ensureNotPaused() {
        if (prefs.getBoolean("service_pause_requested", false)) {
            throw CancellationException("E2E batch paused by user")
        }
    }

    private fun publish(status: String, detail: String, total: Int, completed: Int, passed: Int, failed: Int, skipped: Int) {
        prefs.edit()
            .putString("ui_batch_status", status)
            .putString("ui_batch_detail", detail)
            .putInt("ui_batch_total", total)
            .putInt("ui_batch_completed", completed)
            .putInt("ui_batch_passed", passed)
            .putInt("ui_batch_failed", failed)
            .putInt("ui_batch_skipped", skipped)
            .apply()
        if (status == "BATCH_RUNNING") {
            notifications.notify(NOTIFICATION_ID, buildNotification(detail, "$completed/$total"))
        }
    }

    private fun startAsForeground() {
        val notification = buildNotification("Preparing E2E verification…", "Starting")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifications.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "E2E verification", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows progress while DNS SniperX verifies DNS tunnels." })
        }
    }

    private fun buildNotification(detail: String, progress: String): Notification {
        val pauseIntent = Intent(this, E2eBatchForegroundService::class.java).apply { action = ACTION_PAUSE }
        val pausePending = PendingIntent.getService(this, 21, pauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(this, 22, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("DNS SniperX · E2E")
            .setContentText("$detail ($progress)")
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Pause", pausePending).build())
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        batchJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
