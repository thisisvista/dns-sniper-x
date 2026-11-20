package com.thisisvista.dnssniperx

import android.os.Bundle
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.LinkProperties
import android.net.NetworkCapabilities
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thisisvista.dnssniperx.data.DnsProbe
import com.thisisvista.dnssniperx.data.ScanResourcePolicy
import com.thisisvista.dnssniperx.data.DohProbe
import com.thisisvista.dnssniperx.data.DohProbeResult
import com.thisisvista.dnssniperx.data.DotProbe
import com.thisisvista.dnssniperx.data.DotHistoryEntity
import com.thisisvista.dnssniperx.data.DotProbeResult
import com.thisisvista.dnssniperx.data.DohHistoryEntity
import com.thisisvista.dnssniperx.data.ResolverCollector
import com.thisisvista.dnssniperx.data.ResolverDao
import com.thisisvista.dnssniperx.data.ResolverDatabase
import com.thisisvista.dnssniperx.data.ResolverEntity
import com.thisisvista.dnssniperx.data.ScanHistoryEntity
import com.thisisvista.dnssniperx.data.SourceCollectionHistoryEntity
import com.thisisvista.dnssniperx.data.SlipNetExportFormatter
import com.thisisvista.dnssniperx.data.ResolverListParser
import com.thisisvista.dnssniperx.ui.theme.DnsSniperXTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dao = ResolverDatabase.getInstance(applicationContext).resolverDao()
        setContent { DnsSniperXTheme { DnsSniperXApp(dao) } }
    }
}

@Composable
private fun DnsSniperXApp(dao: ResolverDao) {
    val resolvers by dao.observeAll().collectAsState(initial = emptyList())
    val count by dao.observeCount().collectAsState(initial = 0)
    val recentHistory by dao.observeRecentHistory().collectAsState(initial = emptyList())
    val recentDohHistory by dao.observeRecentDohHistory().collectAsState(initial = emptyList())
    val recentDotHistory by dao.observeRecentDotHistory().collectAsState(initial = emptyList())
    val recentSourceHistory by dao.observeRecentSourceCollectionHistory().collectAsState(initial = emptyList())
    var importText by rememberSaveable { mutableStateOf("") }
    var pendingExportText by rememberSaveable { mutableStateOf("") }
    var pendingExportSuccessMessage by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("Your DNS library is stored on this device.") }
    var isCollecting by rememberSaveable { mutableStateOf(false) }
    var isImporting by rememberSaveable { mutableStateOf(false) }
    var importProgress by rememberSaveable { mutableStateOf("") }
    var isScanning by rememberSaveable { mutableStateOf(false) }
    var scanProgress by rememberSaveable { mutableStateOf("") }
    var isDohTesting by rememberSaveable { mutableStateOf(false) }
    var dohResults by androidx.compose.runtime.remember { mutableStateOf(emptyList<DohProbeResult>()) }
    var isDotTesting by rememberSaveable { mutableStateOf(false) }
    var dotResults by androidx.compose.runtime.remember { mutableStateOf(emptyList<DotProbeResult>()) }
    var scanJob by androidx.compose.runtime.remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val txtExportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.write(pendingExportText)
                } ?: error("Could not create the selected file.")
                message = pendingExportSuccessMessage.ifBlank { "TXT export completed." }
            } catch (exception: Exception) {
                message = "TXT export failed: ${exception.message ?: "Could not write file"}"
            }
        }
    }
    val txtFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isImporting = true
                var validCount = 0
                var inserted = 0
                var processedLines = 0
                try {
                    val reader = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?: error("Could not open the selected file.")
                    reader.use { input ->
                        input.lineSequence().chunked(500).forEach { lines ->
                            processedLines += lines.size
                            val parsed = ResolverListParser.parse(lines.joinToString("\n"), source = "txt-import")
                            validCount += parsed.size
                            if (parsed.isNotEmpty()) {
                                inserted += dao.insertOrMergeSources(parsed)
                            }
                            importProgress = "Processed $processedLines lines; saved $inserted new DNS entries…"
                        }
                    }
                    val skipped = validCount - inserted
                    message = when {
                        validCount == 0 -> "No valid DNS entries found in the TXT file."
                        inserted == 0 -> "The file contained $validCount valid entries, but all were already saved."
                        skipped > 0 -> "Imported $inserted new DNS entries from TXT; skipped $skipped duplicates."
                        else -> "Imported $inserted DNS entries from TXT successfully."
                    }
                } catch (cancelled: CancellationException) {
                    message = "TXT import cancelled. Entries saved from completed batches remain in your library."
                    throw cancelled
                } catch (exception: Exception) {
                    message = "TXT import failed: ${exception.message ?: "Could not read file"}. Entries saved from completed batches remain in your library."
                } finally {
                    isImporting = false
                    importProgress = ""
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text("DNS SniperX", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("DNS discovery and diagnostics", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    ) { innerPadding ->
        HomeContent(
            contentPadding = innerPadding,
            count = count,
            resolvers = resolvers,
            history = recentHistory,
            dohHistory = recentDohHistory,
            dotHistory = recentDotHistory,
            sourceHistory = recentSourceHistory,
            importText = importText,
            onImportTextChange = { importText = it },
            message = message,
            isCollecting = isCollecting,
            isImporting = isImporting,
            importProgress = importProgress,
            isScanning = isScanning,
            scanProgress = scanProgress,
            isDohTesting = isDohTesting,
            dohResults = dohResults,
            isDotTesting = isDotTesting,
            dotResults = dotResults,
            onTestDot = {
                if (!isDotTesting && !isDohTesting && !isCollecting && !isImporting && !isScanning) {
                    scope.launch {
                        isDotTesting = true
                        dotResults = emptyList()
                        message = "Testing DNS-over-TLS providers…"
                        try {
                            dotResults = DotProbe.testAll()
                            val testedAt = System.currentTimeMillis()
                            dao.insertDotHistory(dotResults.map { result ->
                                DotHistoryEntity(provider = result.provider, hostname = result.hostname, testedAt = testedAt, status = result.status, latencyMs = result.latencyMs, detail = result.detail)
                            })
                            val passed = dotResults.count { it.status == "PASS" }
                            message = "DoT checks complete: $passed of ${dotResults.size} providers returned a valid DNS response."
                        } finally {
                            isDotTesting = false
                        }
                    }
                }
            },
            onTestDoh = {
                if (!isDohTesting && !isDotTesting && !isCollecting && !isImporting && !isScanning) {
                    scope.launch {
                        isDohTesting = true
                        dohResults = emptyList()
                        message = "Testing DNS-over-HTTPS providers…"
                        try {
                            dohResults = DohProbe.testAll()
                            val testedAt = System.currentTimeMillis()
                            dao.insertDohHistory(dohResults.map { result ->
                                DohHistoryEntity(provider = result.provider, endpoint = result.endpoint, testedAt = testedAt, status = result.status, latencyMs = result.latencyMs, detail = result.detail)
                            })
                            val passed = dohResults.count { it.status == "PASS" }
                            message = "DoH checks complete: $passed of ${dohResults.size} providers returned a valid DNS response."
                        } finally {
                            isDohTesting = false
                        }
                    }
                }
            },
            onCollectNetworkDns = { provider ->
                scope.launch {
                    try {
                        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                        val network: Network? = connectivity.activeNetwork
                        val properties: LinkProperties? = network?.let { connectivity.getLinkProperties(it) }
                        val capabilities: NetworkCapabilities? = network?.let { connectivity.getNetworkCapabilities(it) }
                        val networkType = when {
                            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
                            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile"
                            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
                            else -> "Other or unknown"
                        }
                        val addresses = properties?.dnsServers.orEmpty()
                            .map { it.hostAddress?.substringBefore('%') }
                            .filterNotNull()
                            .distinct()
                        if (addresses.isEmpty()) {
                            message = "No DNS servers were exposed by the active network."
                        } else {
                            val entries = ResolverListParser.parse(addresses.joinToString("\n"), "current-network ($networkType; user label: $provider)")
                            val inserted = if (entries.isEmpty()) 0 else dao.insertOrMergeSources(entries)
                            message = "Detected ${addresses.size} DNS server(s) on $networkType; saved $inserted new DNS entries with label: $provider. ISP ownership is not verified."
                        }
                    } catch (exception: Exception) {
                        message = "Could not read current network DNS: ${exception.message ?: "Unknown error"}"
                    }
                }
            },
            onCollect = {
                if (!isCollecting && !isImporting) {
                    scope.launch {
                        isCollecting = true
                        message = "Streaming public resolver lists in small batches…"
                        try {
                            val summary = ResolverCollector.collectAndStore(
                                onBatch = { batch -> dao.insertOrMergeSources(batch) },
                                onProgress = { sourceName, found, inserted ->
                                    message = "$sourceName: received $found candidates; saved $inserted new entries…"
                                }
                            )
                            dao.saveSourceCollectionHistory(summary.results.map { result ->
                                SourceCollectionHistoryEntity(
                                    sourceId = result.sourceId,
                                    sourceName = result.source,
                                    sourceUrl = result.sourceUrl,
                                    retrievedAt = result.retrievedAt,
                                    foundCount = result.found,
                                    error = result.error,
                                    termsNote = null
                                )
                            })
                            val failures = summary.results.filter { it.error != null }
                            val sourceReport = summary.results.joinToString { result ->
                                if (result.error == null) "${result.source}: ${result.found}" else "${result.source}: unavailable"
                            }
                            message = buildString {
                                append("Found ${summary.found} candidates; saved ${summary.inserted} new DNS entries.")
                                if (failures.isNotEmpty()) append(" ${failures.size} source(s) could not be reached.")
                                append("\\n").append(sourceReport)
                                if (summary.found > summary.inserted) append("\\nExisting duplicates or repeated entries were skipped.")
                            }
                        } catch (exception: Exception) {
                            message = "Collection failed: ${exception.message ?: "Unknown error"}"
                        } finally {
                            isCollecting = false
                        }
                    }
                }
            },
            onScan = { retryUnsuccessful ->
                if (isScanning) {
                    scanJob?.cancel()
                    message = "Pause requested. The current batch will stop; completed batches are already saved."
                } else {
                    scanJob = scope.launch {
                        isScanning = true
                        var completed = 0
                        try {
                            // Keep the scan job active while counting so Pause works even
                            // when the library is large and the initial query takes time.
                            val total = dao.countPendingScan(retryUnsuccessful)
                            if (total == 0) {
                                message = if (retryUnsuccessful) {
                                    "No previously tested DNS entries need a retry."
                                } else {
                                    "No untested DNS entries remain. Use Retry unsuccessful / warnings to test them again."
                                }
                                return@launch
                            }

                            var lastId = 0L
                            scanProgress = "0 / $total"
                            var pausedForThermal = false
                            var batchIndex = 0
                            while (true) {
                                val batch = dao.getPendingScanBatch(
                                    retryUnsuccessful = retryUnsuccessful,
                                    afterId = lastId,
                                    limit = ScanResourcePolicy.MAX_SCAN_BATCH_SIZE
                                )
                                if (batch.isEmpty()) break
                                lastId = batch.last().id
                                batchIndex += 1

                                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                                val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                                val batteryPercent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                                    .takeIf { it in 0..100 } ?: 100
                                val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    powerManager.currentThermalStatus
                                } else {
                                    PowerManager.THERMAL_STATUS_NONE
                                }
                                if (ScanResourcePolicy.shouldPauseForThermal(thermalStatus)) {
                                    pausedForThermal = true
                                    message = "Scan paused automatically because the device is critically hot. Let it cool, then resume."
                                    break
                                }

                                val scanParallelism = ScanResourcePolicy.recommendedParallelism(
                                    thermalStatus = thermalStatus,
                                    batteryPercent = batteryPercent,
                                    powerSaveMode = powerManager.isPowerSaveMode
                                )
                                val totalBatches = (total + ScanResourcePolicy.MAX_SCAN_BATCH_SIZE - 1) /
                                    ScanResourcePolicy.MAX_SCAN_BATCH_SIZE
                                message = "Scanning batch $batchIndex of $totalBatches… (parallelism $scanParallelism)"
                                DnsProbe.scan(batch, parallelism = scanParallelism) { result ->
                                    val best = listOfNotNull(
                                        result.udp.latencyMs.takeIf { result.udp.status == "PASS" },
                                        result.tcp.latencyMs.takeIf { result.tcp.status == "PASS" }
                                    ).minOrNull()
                                    val overall = when {
                                        result.udp.status == "PASS" || result.tcp.status == "PASS" -> "DNS_PASS"
                                        result.udp.status == "TIMEOUT" || result.tcp.status == "TIMEOUT" -> "TIMEOUT"
                                        else -> "FAILED"
                                    }
                                    val notes = DnsProbe.buildDiagnosticNote(result.udp.detail, result.tcp.detail)
                                    val historyResolver = batch.firstOrNull { it.id == result.resolverId }
                                    dao.insertHistory(
                                        ScanHistoryEntity(
                                            resolverAddress = historyResolver?.address ?: "Unknown",
                                            resolverPort = historyResolver?.port ?: 53,
                                            testedAt = System.currentTimeMillis(),
                                            udpStatus = result.udp.status,
                                            udpLatencyMs = result.udp.latencyMs,
                                            tcpStatus = result.tcp.status,
                                            tcpLatencyMs = result.tcp.latencyMs,
                                            scanMode = if (retryUnsuccessful) "RETRY" else "SCAN"
                                        )
                                    )
                                    dao.updateScanResult(
                                        id = result.resolverId,
                                        udpStatus = result.udp.status,
                                        udpLatencyMs = result.udp.latencyMs,
                                        tcpStatus = result.tcp.status,
                                        tcpLatencyMs = result.tcp.latencyMs,
                                        testedAt = System.currentTimeMillis(),
                                        overallStatus = overall,
                                        bestLatency = best,
                                        note = notes
                                    )
                                    completed += 1
                                    scanProgress = "$completed / $total"
                                }
                            }
                            if (!pausedForThermal) {
                                message = "Scan complete: $completed DNS entries tested and saved."
                            }
                        } catch (cancelled: CancellationException) {
                            message = "Scan paused. Completed results were saved; press Resume to continue."
                            throw cancelled
                        } catch (exception: Exception) {
                            message = "Scan stopped: ${exception.message ?: "Unknown error"}. Completed results remain saved; you can retry."
                        } finally {
                            isScanning = false
                            scanJob = null
                        }
                    }
                }
            },
            onImportFile = { txtFilePicker.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) },
            onExportHealthy = {
                val healthy = resolvers
                    .filter { it.udpStatus == "PASS" || it.tcpStatus == "PASS" }
                    .sortedBy { resolver ->
                        listOfNotNull(
                            resolver.udpLatencyMs.takeIf { resolver.udpStatus == "PASS" },
                            resolver.tcpLatencyMs.takeIf { resolver.tcpStatus == "PASS" }
                        ).minOrNull() ?: Long.MAX_VALUE
                    }
                if (healthy.isEmpty()) {
                    message = "No working DNS entries are available to export yet. Run a scan first."
                } else {
                    pendingExportText = buildString {
                        appendLine("# DNS SniperX - working DNS resolvers")
                        appendLine("# Exported: ${java.time.Instant.now()}")
                        appendLine("# A resolver is included when UDP or TCP returned a valid DNS response.")
                        appendLine("# Sorted by the fastest successful transport latency; entries without latency are last.")
                        healthy.forEach { appendLine(formatResolverEndpoint(it.address, it.port, includeDefaultPort = false)) }
                    }
                    txtExportPicker.launch("dns-sniperx-working.txt")
                }
            },
            onExportSlipNet = {
                val result = SlipNetExportFormatter.format(resolvers)
                if (result.exportedCount == 0) {
                    message = "No working IPv4 DNS entries on port 53 are available for SlipNet export."
                } else {
                    pendingExportText = result.text
                    pendingExportSuccessMessage = "Exported ${result.exportedCount} IPv4 resolvers for SlipNet; skipped ${result.skippedCount} incompatible or duplicate entries."
                    txtExportPicker.launch("dns-sniperx-slipnet-ipv4.txt")
                }
            },
            onImport = {
                val parsed = ResolverListParser.parse(importText)
                scope.launch {
                    val inserted = if (parsed.isEmpty()) 0 else dao.insertOrMergeSources(parsed)
                    val skipped = parsed.size - inserted
                    message = when {
                        parsed.isEmpty() -> "No valid DNS entries found. Check the format."
                        inserted == 0 -> "No new entries. These DNS addresses are already saved."
                        skipped > 0 -> "Saved $inserted new DNS entries; skipped $skipped duplicates."
                        else -> "Saved $inserted DNS entries successfully."
                    }
                    if (inserted > 0) importText = ""
                }
            },
            onDelete = { id -> scope.launch { dao.deleteById(id) } }
        )
    }
}

@Composable
private fun HomeContent(
    contentPadding: PaddingValues,
    count: Int,
    resolvers: List<ResolverEntity>,
    history: List<ScanHistoryEntity>,
    dohHistory: List<DohHistoryEntity>,
    dotHistory: List<DotHistoryEntity>,
    sourceHistory: List<SourceCollectionHistoryEntity>,
    importText: String,
    onImportTextChange: (String) -> Unit,
    message: String,
    isCollecting: Boolean,
    isImporting: Boolean,
    importProgress: String,
    isScanning: Boolean,
    scanProgress: String,
    isDohTesting: Boolean,
    dohResults: List<DohProbeResult>,
    onTestDoh: () -> Unit,
    isDotTesting: Boolean,
    dotResults: List<DotProbeResult>,
    onTestDot: () -> Unit,
    onCollect: () -> Unit,
    onCollectNetworkDns: (String) -> Unit,
    onScan: (retryUnsuccessful: Boolean) -> Unit,
    onImportFile: () -> Unit,
    onExportHealthy: () -> Unit,
    onExportSlipNet: () -> Unit,
    onImport: () -> Unit,
    onDelete: (Long) -> Unit
) {
    var selectedProvider by rememberSaveable { mutableStateOf("Unspecified") }
    var selectedFilter by rememberSaveable { mutableStateOf("ALL") }
    var selectedNetworkType by rememberSaveable { mutableStateOf("ALL") }
    var selectedProviderLabel by rememberSaveable { mutableStateOf("ALL") }
    var fastestFirst by rememberSaveable { mutableStateOf(false) }
    var resolverSearch by rememberSaveable { mutableStateOf("") }
    var visibleResolverLimit by rememberSaveable { mutableStateOf(100) }
    val networkTypes = resolvers.mapNotNull { resolver ->
        resolver.source.takeIf { it.startsWith("current-network (") }
            ?.substringAfter("current-network (")?.substringBefore(";")
    }.distinct().sorted()
    val providerLabels = resolvers.mapNotNull { resolver ->
        resolver.source.takeIf { it.startsWith("current-network (") }
            ?.substringAfter("user label: ")?.substringBefore(")")
    }.distinct().sorted()
    val healthyCount = resolvers.count { it.udpStatus == "PASS" || it.tcpStatus == "PASS" }
    val udpPassCount = resolvers.count { it.udpStatus == "PASS" }
    val tcpPassCount = resolvers.count { it.tcpStatus == "PASS" }
    val untestedCount = resolvers.count { it.udpStatus == "NOT_TESTED" && it.tcpStatus == "NOT_TESTED" }
    val warningCount = resolvers.count { it.note?.contains("WARNING:", ignoreCase = true) == true }
    val filteredResolvers = resolvers.filter { resolver ->
        val networkType = resolver.source.takeIf { it.startsWith("current-network (") }
            ?.substringAfter("current-network (")?.substringBefore(";")
        val providerLabel = resolver.source.takeIf { it.startsWith("current-network (") }
            ?.substringAfter("user label: ")?.substringBefore(")")
        val sourceMatches = (selectedNetworkType == "ALL" || networkType == selectedNetworkType) &&
            (selectedProviderLabel == "ALL" || providerLabel == selectedProviderLabel)
        val searchMatches = resolverSearch.isBlank() ||
            resolver.address.contains(resolverSearch.trim(), ignoreCase = true) ||
            resolver.port.toString() == resolverSearch.trim() ||
            resolver.source.contains(resolverSearch.trim(), ignoreCase = true) ||
            resolver.note?.contains(resolverSearch.trim(), ignoreCase = true) == true
        val statusMatches = when (selectedFilter) {
            "WORKING" -> resolver.udpStatus == "PASS" || resolver.tcpStatus == "PASS"
            "UDP" -> resolver.udpStatus == "PASS"
            "TCP" -> resolver.tcpStatus == "PASS"
            "UNTESTED" -> resolver.udpStatus == "NOT_TESTED" && resolver.tcpStatus == "NOT_TESTED"
            "WARNINGS" -> resolver.note?.contains("WARNING:", ignoreCase = true) == true
            "TIMEOUT" -> resolver.udpStatus == "TIMEOUT" || resolver.tcpStatus == "TIMEOUT"
            "DNS_ERROR" -> resolver.udpStatus == "DNS_ERROR" || resolver.tcpStatus == "DNS_ERROR"
            "NO_ANSWER" -> resolver.udpStatus == "NO_ANSWER" || resolver.tcpStatus == "NO_ANSWER"
            "TRUNCATED" -> resolver.udpStatus == "TRUNCATED" || resolver.tcpStatus == "TRUNCATED"
            "FAIL" -> resolver.udpStatus == "FAIL" || resolver.tcpStatus == "FAIL"
            else -> true
        }
        sourceMatches && statusMatches && searchMatches
    }.let { list ->
        if (fastestFirst) list.sortedBy { item ->
            listOfNotNull(
                item.udpLatencyMs.takeIf { item.udpStatus == "PASS" },
                item.tcpLatencyMs.takeIf { item.tcpStatus == "PASS" }
            ).minOrNull() ?: Long.MAX_VALUE
        } else list
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("Local DNS library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                Text("$count DNS entries saved on this device.", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Text("Live overview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            listOf(
                "Saved" to count,
                "Working" to healthyCount,
                "UDP pass" to udpPassCount,
                "TCP pass" to tcpPassCount,
                "Untested" to untestedCount,
                "Warnings" to warningCount
            ).forEach { (label, value) ->
                Card(
                    modifier = Modifier.padding(bottom = 2.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Text("Discover public DNS lists", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Download candidates from the configured public sources. Internet access is required; downloaded addresses are not yet tested.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("Optional network label", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text("Choose the network provider you are currently using. This is a user label, not automatic ISP verification.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Unspecified", "Irancell", "MCI", "Rightel", "TCI", "Shatel", "Asiatech", "Sabanet", "Pars Online", "Other").forEach { provider ->
                FilterChip(selected = selectedProvider == provider, onClick = { selectedProvider = provider }, label = { Text(provider) })
            }
        }
        Button(onClick = { onCollectNetworkDns(selectedProvider) }, enabled = !isCollecting && !isImporting && !isScanning, modifier = Modifier.fillMaxWidth()) {
            Text("Detect and save current network DNS")
        }
        Text(
            "Reads DNS servers configured on the phone’s active Wi-Fi or mobile network. This does not prove which ISP owns them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onCollect, enabled = !isCollecting && !isImporting && !isScanning, modifier = Modifier.fillMaxWidth()) {
            if (isCollecting) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                Text("  Collecting…")
            } else Text("Fetch public DNS lists")
        }

        Text("DNS-over-HTTPS (DoH) diagnostics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Checks a small built-in set of public DoH services over HTTPS using the RFC 8484 DNS message format. These results are separate from the UDP/TCP resolver list and do not test tunnel compatibility.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = onTestDoh,
            enabled = !isDohTesting && !isDotTesting && !isCollecting && !isImporting && !isScanning,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isDohTesting) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                Text("  Testing DoH providers…")
            } else Text("Test public DoH providers")
        }
        if (dohResults.isNotEmpty()) {
            dohResults.forEach { result ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(result.provider, fontWeight = FontWeight.SemiBold)
                        Text("${result.status}${result.latencyMs?.let { " • ${it} ms" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                        Text(result.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Text("Recent DoH history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Latest 100 DoH checks are saved locally and remain available after closing the app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (dohHistory.isEmpty()) {
            Text("No DoH history yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            dohHistory.take(20).forEach { entry ->
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(entry.provider, fontWeight = FontWeight.SemiBold)
                        Text(entry.status + (entry.latencyMs?.let { " • " + it + " ms" } ?: ""), style = MaterialTheme.typography.bodySmall)
                        Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.testedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(entry.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Text("DNS-over-TLS (DoT) diagnostics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Checks DNS-over-TLS on TCP port 853 with platform certificate validation and hostname verification. DoT results are separate from UDP/TCP resolver checks and do not test tunnel compatibility.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = onTestDot,
            enabled = !isDotTesting && !isDohTesting && !isCollecting && !isImporting && !isScanning,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isDotTesting) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                Text("  Testing DoT providers…")
            } else Text("Test public DoT providers")
        }
        dotResults.forEach { result ->
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(result.provider + " (" + result.hostname + ")", fontWeight = FontWeight.SemiBold)
                    Text(result.status + (result.latencyMs?.let { " • " + it + " ms" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    Text(result.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("Recent DoT history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Latest 100 DoT checks are saved locally and remain available after closing the app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (dotHistory.isEmpty()) {
            Text("No DoT history yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            dotHistory.take(20).forEach { entry ->
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(entry.provider + " (" + entry.hostname + ")", fontWeight = FontWeight.SemiBold)
                        Text(entry.status + (entry.latencyMs?.let { " • " + it + " ms" } ?: ""), style = MaterialTheme.typography.bodySmall)
                        Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.testedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(entry.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Text("DNS network scan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Sends a standard DNS query over UDP and TCP. All untested entries are scanned automatically in batches of up to 500, with 8 simultaneous resolvers. Completed batches are saved so scanning can resume after interruption.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Result guide: PASS = valid answer; DNS error = server replied with an error (for example, blocked or nonexistent domain); No answer = valid reply without an address; Truncated = reply was cut short; Timeout = no reply in time; Failed = network or protocol problem.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = { onScan(false) }, enabled = count > 0 && !isCollecting && !isImporting, modifier = Modifier.fillMaxWidth()) {
            if (isScanning) Text("Pause scan") else Text("Scan / Resume untested DNS (UDP + TCP)")
        }
        Button(
            onClick = { onScan(true) },
            enabled = count > 0 && !isScanning && !isCollecting && !isImporting,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Retry unsuccessful / warnings")
        }
        if (scanProgress.isNotBlank()) Text("Progress: $scanProgress", style = MaterialTheme.typography.bodySmall)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)

        Text("Import DNS addresses manually", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Paste one address per line. Supported: IP, IP:port, hostname, or [IPv6]:port. Port 53 is used when omitted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onImportFile, enabled = !isScanning && !isCollecting && !isImporting, modifier = Modifier.fillMaxWidth()) {
            Text("Choose TXT file")
        }
        if (isImporting && importProgress.isNotBlank()) {
            Text(importProgress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        OutlinedTextField(
            value = importText,
            onValueChange = onImportTextChange,
            enabled = !isImporting,
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            label = { Text("DNS addresses") },
            placeholder = { Text("1.1.1.1\n8.8.8.8:53\n[2001:db8::1]:53") }
        )
        Button(onClick = onImport, enabled = importText.isNotBlank() && !isScanning && !isCollecting && !isImporting, modifier = Modifier.fillMaxWidth()) {
            Text("Import to local library")
        }

        Text("Saved resolvers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = resolverSearch,
            onValueChange = { resolverSearch = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search DNS address, port, or source") },
            placeholder = { Text("Example: 1.2.3.4 or iran-public") }
        )
        Button(onClick = onExportHealthy, enabled = !isScanning && !isCollecting && !isImporting, modifier = Modifier.fillMaxWidth()) {
            Text("Export working DNS as TXT")
        }
        OutlinedButton(onClick = onExportSlipNet, enabled = !isScanning && !isCollecting && !isImporting, modifier = Modifier.fillMaxWidth()) {
            Text("Export for SlipNet scanner (IPv4)")
        }
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "ALL" to "All", "WORKING" to "Working", "UDP" to "UDP", "TCP" to "TCP",
                "UNTESTED" to "Untested", "WARNINGS" to "Warnings", "TIMEOUT" to "Timeout",
                "DNS_ERROR" to "DNS error", "NO_ANSWER" to "No answer", "TRUNCATED" to "Truncated",
                "FAIL" to "Failed"
            ).forEach { (key, label) ->
                FilterChip(selected = selectedFilter == key, onClick = { selectedFilter = key }, label = { Text(label) })
            }
        }
        if (networkTypes.isNotEmpty()) {
            Text("Filter by network type", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf("ALL") + networkTypes).forEach { type ->
                    FilterChip(selected = selectedNetworkType == type, onClick = { selectedNetworkType = type }, label = { Text(if (type == "ALL") "All networks" else type) })
                }
            }
        }
        if (providerLabels.isNotEmpty()) {
            Text("Filter by provider label", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf("ALL") + providerLabels).forEach { provider ->
                    FilterChip(selected = selectedProviderLabel == provider, onClick = { selectedProviderLabel = provider }, label = { Text(if (provider == "ALL") "All providers" else provider) })
                }
            }
        }
        FilterChip(selected = fastestFirst, onClick = { fastestFirst = !fastestFirst }, label = { Text(if (fastestFirst) "Fastest first" else "Best overall first") })
        Text("${filteredResolvers.size} matching DNS entries", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (resolvers.isEmpty()) {
            Text("No DNS addresses saved yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (filteredResolvers.isEmpty()) {
            Text("No DNS entries match this filter.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            filteredResolvers.take(visibleResolverLimit).forEach { resolver ->
                val sourceDescription = if (resolver.source.startsWith("current-network (")) {
                    val networkType = resolver.source.substringAfter("current-network (").substringBefore(";")
                    val providerLabel = resolver.source.substringAfter("user label: ", "Unspecified").substringBefore(")")
                    "Network: $networkType • Provider label: $providerLabel"
                } else {
                    val allSources = (listOf(resolver.source) + resolver.additionalSources.lineSequence().toList())
                        .filter { it.isNotBlank() }.distinct()
                    "Sources: ${allSources.joinToString()}"
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(formatResolverEndpoint(resolver.address, resolver.port), fontWeight = FontWeight.SemiBold)
                        Text(
                            "UDP: ${friendlyProbeStatus(resolver.udpStatus)}${resolver.udpLatencyMs.takeIf { resolver.udpStatus == "PASS" }?.let { " • ${it} ms" } ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "TCP: ${friendlyProbeStatus(resolver.tcpStatus)}${resolver.tcpLatencyMs.takeIf { resolver.tcpStatus == "PASS" }?.let { " • ${it} ms" } ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(sourceDescription, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "Last tested: ${resolver.lastTestedAt?.let { testedAt -> java.text.DateFormat.getDateTimeInstance().format(java.util.Date(testedAt)) } ?: "Not tested"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        resolver.note?.takeIf { it.isNotBlank() }?.let { detail ->
                            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = { onDelete(resolver.id) }) { Text("Remove") }
                    }
                }
            }
            if (filteredResolvers.size > visibleResolverLimit) {
                Text("Showing ${visibleResolverLimit} of ${filteredResolvers.size} matching entries.", style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = { visibleResolverLimit += 100 },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Show 100 more DNS entries")
                }
            }
        }

        Text("Recent public source collection history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Latest 100 source attempts are saved locally. A missing terms note means the source terms have not been documented in this app; it is not a license assertion.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (sourceHistory.isEmpty()) {
            Text("No source collection history yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            sourceHistory.take(20).forEach { entry ->
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(entry.sourceName + " • " + entry.foundCount + " candidates", fontWeight = FontWeight.SemiBold)
                        Text(entry.sourceId, style = MaterialTheme.typography.bodySmall)
                        Text(entry.sourceUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.retrievedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (entry.error == null) "Fetch completed" else "Fetch failed: ${entry.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(entry.termsNote ?: "Terms/license: not documented", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Text("Recent scan history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Latest 100 saved probe results. Each row records the resolver endpoint, time, UDP/TCP outcomes, and whether it was a first scan or retry.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (history.isEmpty()) {
            Text("No scan history yet. Results will appear here after the next scan.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            history.take(30).forEach { entry ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(formatResolverEndpoint(entry.resolverAddress, entry.resolverPort), fontWeight = FontWeight.SemiBold)
                        Text("UDP: ${friendlyProbeStatus(entry.udpStatus)}${entry.udpLatencyMs?.let { " • ${it} ms" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                        Text("TCP: ${friendlyProbeStatus(entry.tcpStatus)}${entry.tcpLatencyMs?.let { " • ${it} ms" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                        Text("${entry.scanMode} • ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.testedAt))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Text(
            "DNS PASS only confirms a valid DNS response. It does not prove DNSTT, Slipstream, or internet-tunnel compatibility.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }
}


private fun formatResolverEndpoint(address: String, port: Int, includeDefaultPort: Boolean = true): String {
    val host = if (address.contains(':')) "[$address]" else address
    return if (port == 53 && !includeDefaultPort) address else "$host:$port"
}

private fun friendlyProbeStatus(status: String): String = when (status) {
    "PASS" -> "PASS — working response"
    "DNS_ERROR" -> "DNS error — server replied with an error"
    "NO_ANSWER" -> "No answer — server replied without an address"
    "TRUNCATED" -> "Truncated — response was incomplete"
    "TIMEOUT" -> "Timeout — no reply in time"
    "FAIL" -> "Failed — network or protocol error"
    "NOT_TESTED" -> "Not tested"
    else -> status
}

private fun summarizeStatuses(statuses: List<String>): String {
    val labels = listOf(
        "PASS" to "passed",
        "DNS_ERROR" to "DNS errors",
        "NO_ANSWER" to "no answer",
        "TRUNCATED" to "truncated",
        "TIMEOUT" to "timeouts",
        "FAIL" to "failed"
    )
    return labels.mapNotNull { (status, label) ->
        statuses.count { it == status }.takeIf { it > 0 }?.let { "$it $label" }
    }.joinToString(", ").ifBlank { "no results" }
}
