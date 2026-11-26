package com.thisisvista.dnssniperx

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thisisvista.dnssniperx.data.ActiveNetworkState
import com.thisisvista.dnssniperx.data.DnsttMobileE2eEngine
import com.thisisvista.dnssniperx.data.E2eHistoryDao
import com.thisisvista.dnssniperx.data.E2eVerificationCoordinator
import com.thisisvista.dnssniperx.data.E2eVerificationMode
import com.thisisvista.dnssniperx.data.ResolverDao
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
    enabled: Boolean
) {
    var resolverIp by rememberSaveable { mutableStateOf("") }
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Real tunnel E2E test · DNSTT", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "This test starts the DNSTT client and sends an HTTP request through its SOCKS5-forwarded tunnel. A DNS reply or a running client alone is never counted as success.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = resolverIp,
                onValueChange = { resolverIp = it },
                label = { Text("Resolver IP (UDP port 53)") },
                placeholder = { Text("e.g. 1.2.3.4") },
                singleLine = true,
                enabled = !running,
                modifier = Modifier.fillMaxWidth()
            )
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
                ) {
                    Text(candidate)
                }
            }
            OutlinedTextField(
                value = tunnelDomain,
                onValueChange = { tunnelDomain = it },
                label = { Text("DNSTT tunnel domain") },
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
                label = { Text("DNSTT server public key (64 hex characters)") },
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
                    val job = scope.launch(start = CoroutineStart.LAZY) {
                        running = true
                        status = "BATCH_RUNNING"
                        detail = "Loading all saved Working DNS candidates…"
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

                            // Count first, then read and process one Room page at a time.
                            // This avoids holding the complete resolver library in memory.
                            val pageSize = 100
                            // Count the exact same paged query used below. This keeps the
                            // progress denominator aligned with the candidate pages, including
                            // SQLite's deduplication and endpoint filters, without loading all
                            // resolver rows into memory.
                            var countOffset = 0
                            batchTotal = 0
                            while (true) {
                                val countPage = resolverDao.getSlipNetExportBatch(pageSize, countOffset)
                                if (countPage.isEmpty()) break
                                batchTotal += countPage.size
                                countOffset += countPage.size
                                if (countPage.size < pageSize) break
                            }
                            require(batchTotal > 0) { "No saved Working DNS candidates on UDP/TCP port 53 were found." }

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
                                val page = resolverDao.getSlipNetExportBatch(pageSize, offset)
                                if (page.isEmpty()) break

                                val pending = mutableListOf<String>()
                                for (candidate in page) {
                                    val address = candidate.address
                                    // A previous FULL_PASS for this exact profile is durable evidence and is not retested.
                                    val alreadyPassed = historyDao.hasFullPassForProfile(
                                        address = address,
                                        port = profile.serverPort,
                                        profileId = profile.id,
                                        serverHost = profile.serverHost,
                                        publicKey = profile.dnsttServerPublicKey.orEmpty()
                                    )
                                    if (alreadyPassed) {
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
                            detail = "Finished: $batchPassed FULL_PASS · $batchFailed failed · $batchSkipped previously passed and skipped."
                        } catch (cancelled: CancellationException) {
                            status = "BATCH_PAUSED"
                            detail = "Paused at $batchCompleted/$batchTotal. Verified FULL_PASS results are saved; start again to resume and skip those successes."
                        } catch (failure: Exception) {
                            status = "BATCH_ERROR"
                            detail = failure.message ?: failure.javaClass.simpleName
                        } finally {
                            running = false
                            batchJob = null
                        }
                    }
                    batchJob = job
                    job.start()
                },
                enabled = enabled && !running && testJob == null && batchJob == null &&
                    tunnelDomain.isNotBlank() && publicKey.length == 64,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start / Resume FULL E2E for all Working DNS")
            }
            if (batchTotal > 0) {
                Text(
                    "Batch progress: $batchCompleted/$batchTotal · PASS $batchPassed · FAIL $batchFailed · skipped $batchSkipped",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (running && batchJob != null) {
                OutlinedButton(
                    onClick = { batchJob?.cancel(CancellationException("Batch paused by user")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Pause batch E2E")
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
