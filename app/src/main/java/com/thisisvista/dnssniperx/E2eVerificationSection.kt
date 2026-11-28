package com.thisisvista.dnssniperx

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thisisvista.dnssniperx.data.ActiveNetworkState
import com.thisisvista.dnssniperx.data.DnsttMobileE2eEngine
import com.thisisvista.dnssniperx.data.E2eHistoryDao
import com.thisisvista.dnssniperx.data.E2eVerificationCoordinator
import com.thisisvista.dnssniperx.data.E2eVerificationMode
import com.thisisvista.dnssniperx.data.ResolverDao
import com.thisisvista.dnssniperx.data.ResolverListParser
import com.thisisvista.dnssniperx.data.SlipNetE2eEngine
import com.thisisvista.dnssniperx.data.SlipNetE2eExportFormatter
import com.thisisvista.dnssniperx.data.SlipNetE2eExportRow
import com.thisisvista.dnssniperx.data.TunnelProfile
import com.thisisvista.dnssniperx.data.TunnelProfileMapper
import com.thisisvista.dnssniperx.data.TunnelProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun E2eVerificationSection(
    resolverDao: ResolverDao,
    historyDao: E2eHistoryDao,
    network: ActiveNetworkState,
    enabled: Boolean,
    visible: Boolean = true
) {
    var resolverIp by rememberSaveable { mutableStateOf("") }
    var dnsSource by rememberSaveable { mutableStateOf("COLLECT") }
    var deviceDnsFileName by rememberSaveable { mutableStateOf("") }
    var deviceDnsCandidates by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var tunnelDomain by rememberSaveable { mutableStateOf("") }
    var publicKey by rememberSaveable { mutableStateOf("") }
    var running by rememberSaveable { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("Not tested") }
    var detail by rememberSaveable { mutableStateOf("") }
    var elapsed by rememberSaveable { mutableStateOf<Long?>(null) }
    var savedCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadingCandidates by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportMessage by rememberSaveable { mutableStateOf("") }
    var testJob by remember { mutableStateOf<Job?>(null) }
    var batchJob by remember { mutableStateOf<Job?>(null) }
    var batchTotal by rememberSaveable { mutableStateOf(0) }
    var batchCompleted by rememberSaveable { mutableStateOf(0) }
    var batchPassed by rememberSaveable { mutableStateOf(0) }
    var batchFailed by rememberSaveable { mutableStateOf(0) }
    var batchSkipped by rememberSaveable { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(context) {
        val prefs = context.getSharedPreferences("e2e_device_dns", Context.MODE_PRIVATE)
        dnsSource = prefs.getString("source", "COLLECT") ?: "COLLECT"
        deviceDnsFileName = prefs.getString("filename", "") ?: ""
        deviceDnsCandidates = prefs.getString("entries", "").orEmpty()
            .lineSequence().map { it.trim() }.filter { it.isNotBlank() }.distinct().toList()

        // Recover a batch interrupted by process death as a resumable session.
        val batchPrefs = context.getSharedPreferences("e2e_batch_resume", Context.MODE_PRIVATE)
        if (batchPrefs.getString("ui_batch_status", "") == "BATCH_RUNNING") {
            batchPrefs.edit()
                .putString("ui_batch_status", "BATCH_PAUSED")
                .putString("ui_batch_detail", "The previous run was interrupted. Resume to continue unfinished DNS entries.")
                .apply()
            status = "BATCH_PAUSED"
            detail = "The previous run was interrupted. Completed results are saved; Resume continues with unfinished DNS entries."
        } else {
            status = batchPrefs.getString("ui_batch_status", status) ?: status
            detail = batchPrefs.getString("ui_batch_detail", detail) ?: detail
        }
    }
    val e2eTxtPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Could not read the selected TXT file.")
                require(raw.length <= 1_000_000) { "TXT file is too large (maximum 1 MB)." }
                val entries = ResolverListParser.parse(raw).map { it.address }.distinct()
                require(entries.isNotEmpty()) { "No valid DNS addresses were found in this TXT file." }
                val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                } ?: "Selected TXT file"
                deviceDnsFileName = name
                deviceDnsCandidates = entries
                dnsSource = "DEVICE"
                context.getSharedPreferences("e2e_device_dns", Context.MODE_PRIVATE).edit()
                    .putString("source", "DEVICE")
                    .putString("filename", name)
                    .putString("entries", entries.joinToString("\n"))
                    .apply()
            }.onFailure { failure ->
                status = "FILE_ERROR"
                detail = failure.message ?: "Could not import DNS TXT file."
            }
        }
    }
    val profiles by resolverDao.observeTunnelProfiles().collectAsState(initial = emptyList())
    val savedDnsttProfile = profiles.firstOrNull { it.protocol == TunnelProtocol.DNSTT.name }
        ?.let(TunnelProfileMapper::toModel)
    LaunchedEffect(
        savedDnsttProfile?.id,
        savedDnsttProfile?.serverHost,
        savedDnsttProfile?.dnsttServerPublicKey
    ) {
        if (savedDnsttProfile != null) {
            tunnelDomain = savedDnsttProfile.serverHost
            publicKey = savedDnsttProfile.dnsttServerPublicKey.orEmpty()
        }
    }
    val attemptsFlow = remember(resolverIp) {
        if (resolverIp.isBlank()) flowOf(emptyList())
        else historyDao.observeResolverAttempts(resolverIp.trim(), 53, 10)
    }
    val attempts by attemptsFlow.collectAsState(initial = emptyList())
    val recentE2eAttempts by historyDao.observeRecentAttempts(8).collectAsState(initial = emptyList())
    val resolverListExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) scope.launch {
            exporting = true
            try {
                val rows = loadAllSlipNetE2eExportRows(historyDao)
                val result = SlipNetE2eExportFormatter.format(rows)
                check(result.exportedCount > 0) { "No FULL HTTP-verified DNSTT/VayDNS results are available yet." }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                        it.write(result.resolverListText)
                    } ?: error("Could not create the selected file.")
                }
                exportMessage = "Exported ${result.exportedCount} FULL E2E-verified DNS address(es) for SlipNet."
            } catch (failure: Exception) {
                exportMessage = failure.message ?: "SlipNet resolver export failed."
            } finally {
                exporting = false
            }
        }
    }
    val profileReportExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) scope.launch {
            exporting = true
            try {
                val rows = loadAllSlipNetE2eExportRows(historyDao)
                val result = SlipNetE2eExportFormatter.format(rows)
                check(result.exportedCount > 0) { "No FULL HTTP-verified DNSTT/VayDNS results are available yet." }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                        it.write(result.reportJson)
                    } ?: error("Could not create the selected file.")
                }
                exportMessage = "Exported detailed engine/profile evidence for ${result.exportedCount} result(s)."
            } catch (failure: Exception) {
                exportMessage = failure.message ?: "SlipNet E2E report export failed."
            } finally {
                exporting = false
            }
        }
    }

    if (visible) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("DNS source", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            listOf("COLLECT" to "Scan all working DNS from Collect tab", "DEVICE" to "Select DNS from your device").forEach { (key, label) ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        dnsSource = key
                        context.getSharedPreferences("e2e_device_dns", Context.MODE_PRIVATE).edit().putString("source", key).apply()
                    }.padding(vertical = 5.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = dnsSource == key,
                        onClick = {
                            dnsSource = key
                            context.getSharedPreferences("e2e_device_dns", Context.MODE_PRIVATE).edit().putString("source", key).apply()
                        }
                    )
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (dnsSource == "DEVICE") {
                OutlinedButton(
                    onClick = { e2eTxtPicker.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (deviceDnsCandidates.isEmpty()) "Choose TXT file from device" else "Change TXT file") }
                if (deviceDnsFileName.isNotBlank()) {
                    Text("$deviceDnsFileName · ${deviceDnsCandidates.size} DNS", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            } else {
                Text("Working DNS saved in Collect will be used.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = resolverIp,
                onValueChange = { resolverIp = it },
                label = { Text("Resolver IP (UDP port 53)") },
                placeholder = { Text("e.g. 1.2.3.4") },
                singleLine = true,
                enabled = !running,
                modifier = Modifier.fillMaxWidth()
            )
            if (dnsSource == "COLLECT") {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            loadingCandidates = true
                            try {
                                savedCandidates = resolverDao.getSlipNetExportBatch(limit = 15, offset = 0)
                                    .map { it.address }
                                    .distinct()
                            } finally {
                                loadingCandidates = false
                            }
                        }
                    },
                    enabled = !running && !loadingCandidates,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (loadingCandidates) "Loading saved DNS candidates…" else "Choose from saved working DNS")
                }
                savedCandidates.take(10).forEach { candidate ->
                    OutlinedButton(
                        onClick = { resolverIp = candidate },
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(candidate) }
                }
            }
            OutlinedTextField(
                value = tunnelDomain,
                onValueChange = { tunnelDomain = it },
                label = { Text("Domain") },
                placeholder = { Text("The domain configured on your DNSTT server") },
                singleLine = true,
                enabled = !running,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "The client sends DNS over UDP/53 to the resolver above. The tunnel domain and server-side SOCKS5 upstream must match your DNSTT server configuration; there is no separate client-side DNSTT server port in this binding.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = publicKey,
                onValueChange = { publicKey = it.filter { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }.take(64) },
                label = { Text("Public key (64 hex characters)") },
                singleLine = true,
                enabled = !running,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Saved DNSTT profiles: ${profiles.count { it.protocol == TunnelProtocol.DNSTT.name }}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    val job = scope.launch(start = CoroutineStart.LAZY) {
                        running = true
                        status = "Running"
                        detail = "Preparing DNSTT E2E verification…"
                        elapsed = null
                        try {
                            val profile = TunnelProfile(
                                id = savedDnsttProfile?.id ?: "dnstt-e2e-manual",
                                name = savedDnsttProfile?.name ?: "DNSTT E2E test profile",
                                protocol = TunnelProtocol.DNSTT,
                                serverHost = tunnelDomain.trim(),
                                // DNSTT's client binding has no separate server-port setting.
                                // The resolver endpoint is supplied independently to verify().
                                serverPort = savedDnsttProfile?.serverPort ?: 53,
                                dnsttServerPublicKey = publicKey.trim()
                            )
                            val errors = profile.validate()
                            require(errors.isEmpty()) { errors.joinToString("; ") }
                            require(resolverIp.isNotBlank()) { "Enter a resolver IP address." }
                            resolverDao.saveTunnelProfile(
                                TunnelProfileMapper.toEntity(profile, resolverDao.findTunnelProfile(profile.id))
                            )
                            val coordinator = E2eVerificationCoordinator(
                                historyDao,
                                mapOf(TunnelProtocol.DNSTT to (DnsttMobileE2eEngine() as SlipNetE2eEngine))
                            )
                            val result = coordinator.verify(
                                resolverAddress = resolverIp.trim(),
                                resolverPort = profile.serverPort,
                                profile = profile,
                                mode = E2eVerificationMode.FULL,
                                network = network,
                                timeoutMs = 25_000,
                                verificationUrl = "http://www.gstatic.com/generate_204",
                                expectedHttpStatusCode = 204,
                                onPhase = { phase -> scope.launch { detail = phase } }
                            )
                            status = result.status.name
                            detail = result.errorMessage ?: "HTTP ${result.httpStatusCode ?: "-"} received through the tunnel."
                            elapsed = result.elapsedMs
                        } catch (cancelled: CancellationException) {
                            status = "CANCELLED"
                            detail = "The test was cancelled. The attempt is saved in history."
                            throw cancelled
                        } catch (failure: Exception) {
                            status = "NOT_TESTED"
                            detail = failure.message ?: failure.javaClass.simpleName
                        } finally {
                            running = false
                            testJob = null
                        }
                    }
                    testJob = job
                    job.start()
                },
                enabled = enabled && !running && testJob == null && batchJob == null &&
                    resolverIp.isNotBlank() && tunnelDomain.isNotBlank() && publicKey.length == 64,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (running) {
                    CircularProgressIndicator()
                    Text("  Testing DNSTT tunnel…")
                } else {
                    Text("Save profile and run real E2E test")
                }
            }
            Button(
                onClick = {
                    if (running && batchJob != null) {
                        batchJob?.cancel(CancellationException("Batch paused by user"))
                    } else {
                    val job = scope.launch(start = CoroutineStart.LAZY) {
                        running = true
                        status = "BATCH_RUNNING"
                        detail = "Loading all saved Working DNS candidates…"
                        context.getSharedPreferences("e2e_batch_resume", Context.MODE_PRIVATE).edit()
                            .putString("ui_batch_status", "BATCH_RUNNING")
                            .putString("ui_batch_detail", detail)
                            .apply()
                        batchCompleted = 0
                        batchPassed = 0
                        batchFailed = 0
                        batchSkipped = 0
                        try {
                            val profile = TunnelProfile(
                                id = savedDnsttProfile?.id ?: "dnstt-e2e-manual",
                                name = savedDnsttProfile?.name ?: "DNSTT E2E test profile",
                                protocol = TunnelProtocol.DNSTT,
                                serverHost = tunnelDomain.trim(),
                                serverPort = savedDnsttProfile?.serverPort ?: 53,
                                dnsttServerPublicKey = publicKey.trim()
                            )
                            val errors = profile.validate()
                            require(errors.isEmpty()) { errors.joinToString("; ") }
                            resolverDao.saveTunnelProfile(
                                TunnelProfileMapper.toEntity(profile, resolverDao.findTunnelProfile(profile.id))
                            )

                            // The session start is stored locally and scoped to the exact
                            // DNSTT profile. It survives leaving this screen and app restarts.
                            val resumePrefs = context.getSharedPreferences("e2e_batch_resume", Context.MODE_PRIVATE)
                            val profileKey = listOf(profile.id, profile.serverHost, profile.dnsttServerPublicKey.orEmpty(), dnsSource, if (dnsSource == "DEVICE") deviceDnsCandidates.joinToString("|").hashCode().toString() else "saved")
                                .joinToString("|")
                                .hashCode()
                                .toUInt()
                                .toString(16)
                            val sessionStartKey = "session_started_$profileKey"
                            var sessionStartedAt = resumePrefs.getLong(sessionStartKey, 0L)
                            if (sessionStartedAt <= 0L) {
                                sessionStartedAt = System.currentTimeMillis()
                                resumePrefs.edit().putLong(sessionStartKey, sessionStartedAt).apply()
                            }
                            batchPassed = historyDao.countPassedBatchAttemptsSince(
                                profile.serverPort, profile.id, profile.serverHost,
                                profile.dnsttServerPublicKey.orEmpty(), sessionStartedAt
                            )
                            batchFailed = historyDao.countFailedBatchAttemptsSince(
                                profile.serverPort, profile.id, profile.serverHost,
                                profile.dnsttServerPublicKey.orEmpty(), sessionStartedAt
                            )
                            batchCompleted = historyDao.countCompletedBatchAttemptsSince(
                                profile.serverPort, profile.id, profile.serverHost,
                                profile.dnsttServerPublicKey.orEmpty(), sessionStartedAt
                            )

                            // Count first, then read and process one Room page at a time.
                            // This avoids holding the complete resolver library in memory.
                            val pageSize = 100
                            val useDeviceList = dnsSource == "DEVICE"
                            val deviceCandidates = deviceDnsCandidates.toList()
                            if (useDeviceList) {
                                require(deviceCandidates.isNotEmpty()) { "Choose a TXT file containing DNS addresses first." }
                                batchTotal = deviceCandidates.size
                            } else {
                                var countOffset = 0
                                batchTotal = 0
                                while (true) {
                                    val countPage = resolverDao.getSlipNetExportBatch(pageSize, countOffset)
                                    if (countPage.isEmpty()) break
                                    batchTotal += countPage.size
                                    countOffset += countPage.size
                                    if (countPage.size < pageSize) break
                                }
                            }
                            require(batchTotal > 0) { "No DNS candidates were found for E2E verification." }

                            val coordinator = E2eVerificationCoordinator(
                                historyDao,
                                mapOf(TunnelProtocol.DNSTT to (DnsttMobileE2eEngine() as SlipNetE2eEngine))
                            )
                            // Three simultaneous tunnels provide a useful speed-up while
                            // keeping phone CPU, memory, and remote DNS server load bounded.
                            // Each page is still fetched from Room in a bounded window.
                            val concurrency = 3
                            detail = "Starting FULL HTTP verification for $batchTotal DNS resolvers (up to $concurrency at once)…"
                            var offset = 0
                            while (true) {
                                val page = if (useDeviceList) {
                                    deviceCandidates.drop(offset).take(pageSize)
                                } else {
                                    resolverDao.getSlipNetExportBatch(pageSize, offset).map { it.address }
                                }
                                if (page.isEmpty()) break

                                val pending = mutableListOf<String>()
                                for (address in page) {
                                    // Resume skips every finished attempt from this session,
                                    // regardless of PASS/FAIL. A cancelled in-flight attempt is retried.
                                    val completedInSession = historyDao.hasCompletedBatchAttemptSince(
                                        address = address,
                                        port = profile.serverPort,
                                        profileId = profile.id,
                                        serverHost = profile.serverHost,
                                        publicKey = profile.dnsttServerPublicKey.orEmpty(),
                                        sessionStartedAt = sessionStartedAt
                                    )
                                    val passedBeforeSession = historyDao.hasFullPassForProfile(
                                        address = address,
                                        port = profile.serverPort,
                                        profileId = profile.id,
                                        serverHost = profile.serverHost,
                                        publicKey = profile.dnsttServerPublicKey.orEmpty()
                                    )
                                    if (completedInSession) {
                                        // Counters were restored from the durable E2E history above.
                                    } else if (passedBeforeSession) {
                                        batchSkipped++
                                        batchCompleted++
                                    } else {
                                        pending.add(address)
                                    }
                                }

                                // Run small groups concurrently. Child coroutines inherit the UI
                                // dispatcher for safe progress updates; the engine itself moves
                                // network work to Dispatchers.IO. Cancellation still stops the
                                // whole active group and each attempt records its cancellation.
                                for (group in pending.chunked(concurrency)) {
                                    resolverIp = group.first()
                                    detail = "Testing ${batchCompleted + 1}–${batchCompleted + group.size}/$batchTotal (${group.size} in parallel)…"
                                    coroutineScope {
                                        group.map { address ->
                                            async {
                                                val result = coordinator.verify(
                                                    resolverAddress = address,
                                                    resolverPort = profile.serverPort,
                                                    profile = profile,
                                                    mode = E2eVerificationMode.FULL,
                                                    network = network,
                                                    timeoutMs = 25_000,
                                                    verificationUrl = "http://www.gstatic.com/generate_204",
                                                    expectedHttpStatusCode = 204,
                                                    onPhase = { phase ->
                                                        scope.launch { detail = "$address · $phase" }
                                                    }
                                                )
                                                batchCompleted++
                                                if (result.status.name == "FULL_PASS" && result.httpStatusCode == 204) {
                                                    batchPassed++
                                                } else {
                                                    batchFailed++
                                                }
                                                status = "BATCH_RUNNING"
                                            }
                                        }.awaitAll()
                                    }
                                }
                                offset += page.size
                                if (page.size < pageSize) break
                            }
                            status = "BATCH_COMPLETED"
                            resumePrefs.edit()
                                .remove(sessionStartKey)
                                .putString("ui_batch_status", "BATCH_COMPLETED")
                                .putString("ui_batch_detail", "Finished: $batchPassed FULL_PASS · $batchFailed failed · $batchSkipped previously passed and skipped.")
                                .apply()
                            detail = "Finished: $batchPassed FULL_PASS · $batchFailed failed · $batchSkipped previously passed and skipped."
                        } catch (cancelled: CancellationException) {
                            status = "BATCH_PAUSED"
                            detail = "Paused at $batchCompleted/$batchTotal. Completed results are saved; Resume continues with unfinished DNS entries."
                            context.getSharedPreferences("e2e_batch_resume", Context.MODE_PRIVATE).edit()
                                .putString("ui_batch_status", status)
                                .putString("ui_batch_detail", detail)
                                .apply()
                        } catch (failure: Exception) {
                            status = "BATCH_ERROR"
                            detail = failure.message ?: failure.javaClass.simpleName
                            context.getSharedPreferences("e2e_batch_resume", Context.MODE_PRIVATE).edit()
                                .putString("ui_batch_status", status)
                                .putString("ui_batch_detail", detail)
                                .apply()
                        } finally {
                            running = false
                            batchJob = null
                        }
                    }
                    batchJob = job
                    job.start()
                    }
                },
                enabled = (running && batchJob != null) || (enabled && !running && testJob == null && batchJob == null &&
                    tunnelDomain.isNotBlank() && publicKey.length == 64 && (dnsSource != "DEVICE" || deviceDnsCandidates.isNotEmpty())),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(when {
                    running && batchJob != null -> "Pause E2E"
                    status == "BATCH_PAUSED" -> "Resume E2E"
                    else -> "Start E2E"
                })
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Results", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (batchTotal > 0) {
                        val fraction = (batchCompleted.toFloat() / batchTotal.toFloat()).coerceIn(0f, 1f)
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "$batchCompleted/$batchTotal · PASS $batchPassed · FAIL $batchFailed · skipped $batchSkipped",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (recentE2eAttempts.isEmpty()) {
                        Text("No E2E results yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        recentE2eAttempts.forEach { attempt ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                Text(attempt.resolverAddress, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                Text(
                                    if (attempt.status == "FULL_PASS" && attempt.httpStatusCode == 204) "PASS" else attempt.status,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (attempt.status == "FULL_PASS" && attempt.httpStatusCode == 204) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            if (running && testJob != null) {
                OutlinedButton(
                    onClick = { testJob?.cancel(CancellationException("Cancelled by user")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel test")
                }
            }
            Text("Network: ${network.transport.name} · ${network.providerName ?: network.operatorName ?: "provider unknown"}", style = MaterialTheme.typography.labelSmall)
            Text("Result: $status${elapsed?.let { " · ${it} ms" } ?: ""}", fontWeight = FontWeight.SemiBold)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "SlipNet export includes only the latest FULL HTTP-verified DNSTT/VayDNS successes. UDP/TCP DNS checks and handshake-only results are never exported as E2E passes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { resolverListExport.launch("dns-sniperx-slipnet-e2e.txt") },
                enabled = !running && !exporting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (exporting) "Preparing verified export…" else "Export FULL E2E DNS list for SlipNet")
            }
            OutlinedButton(
                onClick = { profileReportExport.launch("dns-sniperx-slipnet-e2e-report.json") },
                enabled = !running && !exporting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Export engine and profile evidence (JSON)")
            }
            if (exportMessage.isNotBlank()) {
                Text(exportMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (attempts.isNotEmpty()) {
                Text("Recent attempts for this resolver", fontWeight = FontWeight.SemiBold)
                attempts.take(5).forEach { attempt ->
                    Text(
                        "${attempt.status} · ${attempt.tunnelProtocol} · ${attempt.networkTransport} · ${attempt.elapsedMs} ms",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (!attempt.errorMessage.isNullOrBlank()) {
                        Text(attempt.errorMessage, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    }
}

/**
 * Export every qualifying record in bounded Room pages. The export list is
 * intentionally not capped at an arbitrary first 1,000 successful resolvers.
 */
private suspend fun loadAllSlipNetE2eExportRows(
    historyDao: E2eHistoryDao
): List<SlipNetE2eExportRow> {
    val pageSize = 500
    val rows = mutableListOf<SlipNetE2eExportRow>()
    var offset = 0
    while (true) {
        val page = historyDao.getSlipNetE2eExportBatch(limit = pageSize, offset = offset)
        if (page.isEmpty()) break
        rows.addAll(page)
        offset += page.size
        if (page.size < pageSize) break
    }
    return rows
}
