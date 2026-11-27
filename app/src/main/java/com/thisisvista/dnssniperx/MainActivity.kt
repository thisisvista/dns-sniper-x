package com.thisisvista.dnssniperx

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thisisvista.dnssniperx.data.AppLogger
import com.thisisvista.dnssniperx.data.ActiveNetworkMonitor
import com.thisisvista.dnssniperx.data.ResolverEvidenceEntity
import com.thisisvista.dnssniperx.data.EvidenceType
import com.thisisvista.dnssniperx.data.OwnershipStatus
import com.thisisvista.dnssniperx.data.DnsProbe
import com.thisisvista.dnssniperx.data.ScanResourcePolicy
import com.thisisvista.dnssniperx.data.DohProbe
import com.thisisvista.dnssniperx.data.DohProbeResult
import com.thisisvista.dnssniperx.data.DotProbe
import com.thisisvista.dnssniperx.data.DotHistoryEntity
import com.thisisvista.dnssniperx.data.DotProbeResult
import com.thisisvista.dnssniperx.data.DohHistoryEntity
import com.thisisvista.dnssniperx.data.ResolverCollector
import com.thisisvista.dnssniperx.data.ProviderDiscoveryCoordinator
import com.thisisvista.dnssniperx.data.ProviderPrefixDiscovery
import com.thisisvista.dnssniperx.data.IranPrefixDiscovery
import com.thisisvista.dnssniperx.data.ResolverDao
import com.thisisvista.dnssniperx.data.ResolverDatabase
import com.thisisvista.dnssniperx.data.ResolverEntity
import com.thisisvista.dnssniperx.data.ScanHistoryEntity
import com.thisisvista.dnssniperx.data.SourceCollectionHistoryEntity
import com.thisisvista.dnssniperx.data.SlipNetExportFormatter
import com.thisisvista.dnssniperx.data.ResolverListParser
import com.thisisvista.dnssniperx.ui.theme.DnsSniperXTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val database = ResolverDatabase.getInstance(applicationContext)
        val dao = database.resolverDao()
        val e2eHistoryDao = database.e2eHistoryDao()
        setContent { DnsSniperXTheme { DnsSniperXApp(dao, e2eHistoryDao) } }
    }
}

@Composable
private fun DnsSniperXApp(dao: ResolverDao, e2eHistoryDao: com.thisisvista.dnssniperx.data.E2eHistoryDao) {
    var resolverRefreshToken by androidx.compose.runtime.remember { mutableStateOf(0) }
    LaunchedEffect(dao) { dao.observeResolverInvalidation().debounce(750).collect { resolverRefreshToken++ } }
    val count by dao.observeCount().collectAsState(initial = 0)
    val recentHistory by dao.observeRecentHistory().collectAsState(initial = emptyList())
    val recentDohHistory by dao.observeRecentDohHistory().collectAsState(initial = emptyList())
    val recentDotHistory by dao.observeRecentDotHistory().collectAsState(initial = emptyList())
    val recentSourceHistory by dao.observeRecentSourceCollectionHistory().collectAsState(initial = emptyList())
    var importText by rememberSaveable { mutableStateOf("") }
    var pendingExportMode by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("Your DNS library is stored on this device.") }
    var isCollecting by rememberSaveable { mutableStateOf(false) }
    var selectedSourceIds by rememberSaveable { mutableStateOf(ResolverCollector.sources.map { it.id }) }
    var isImporting by rememberSaveable { mutableStateOf(false) }
    var importProgress by rememberSaveable { mutableStateOf("") }
    var importedFileName by rememberSaveable { mutableStateOf("") }
    var importSummary by rememberSaveable { mutableStateOf("") }
    var isScanning by rememberSaveable { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf("HOME") }
    var scanProgress by rememberSaveable { mutableStateOf("") }
    var isDohTesting by rememberSaveable { mutableStateOf(false) }
    var dohResults by androidx.compose.runtime.remember { mutableStateOf(emptyList<DohProbeResult>()) }
    var isDotTesting by rememberSaveable { mutableStateOf(false) }
    var dotResults by androidx.compose.runtime.remember { mutableStateOf(emptyList<DotProbeResult>()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // The scan is owned by a foreground service, not by this Compose screen.
    // Poll its persisted state so returning from another app restores live progress.
    LaunchedEffect(context) {
        val scanState = context.getSharedPreferences(
            com.thisisvista.dnssniperx.data.DnsScanForegroundService.PREFERENCES,
            Context.MODE_PRIVATE
        )
        while (true) {
            val running = scanState.getBoolean("running", false)
            val progress = scanState.getString("progress", "") ?: ""
            val status = scanState.getString("message", "") ?: ""
            if (isScanning != running) isScanning = running
            if (scanProgress != progress) scanProgress = progress
            if (status.isNotBlank() && message != status) message = status
            delay(600)
        }
    }
    val providers by dao.observeProviders().collectAsState(initial = emptyList())
    val networkMonitor = androidx.compose.runtime.remember(context) { ActiveNetworkMonitor(context) }
    val activeNetwork by networkMonitor.state.collectAsState()
    SideEffect { networkMonitor.updateProviders(providers) }
    DisposableEffect(networkMonitor) {
        networkMonitor.start()
        onDispose { networkMonitor.stop() }
    }
    val txtExportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                            val slipNet = pendingExportMode == "SLIPNET"
                            val logs = pendingExportMode == "LOGS"
                            if (logs) {
                                writer.appendLine("# DNS SniperX diagnostic logs")
                                writer.appendLine("# Sensitive values are redacted; logs are stored locally.")
                            } else if (!slipNet) {
                                writer.appendLine("# DNS SniperX - working DNS resolvers")
                                writer.appendLine("# Exported: ${java.time.Instant.now()}")
                                writer.appendLine("# Included when UDP or TCP returned a valid DNS response.")
                                writer.appendLine("# Sorted by fastest successful transport latency.")
                            }
                            var offset = 0
                            var exported = 0
                            var skipped = 0
                            if (slipNet) {
                                val workingCount = dao.countWorkingExportRows()
                                val compatibleCount = dao.countSlipNetCompatibleCandidates()
                                skipped = workingCount - compatibleCount
                            }
                            while (true) {
                                if (logs) {
                                    val batch = dao.getAppLogExportBatch(limit = 500, offset = offset)
                                    if (batch.isEmpty()) break
                                    offset += batch.size
                                    for (entry in batch) {
                                        writer.appendLine("${entry.timestamp} | ${entry.level} | ${entry.module} | ${entry.message} | stage=${entry.stage ?: "-"} | id=${entry.identifier ?: "-"} | network=${entry.networkType} | app=${entry.appVersion}")
                                        entry.details?.takeIf { it.isNotBlank() }?.lineSequence()?.forEach { writer.appendLine("  $it") }
                                        exported++
                                    }
                                } else {
                                    val batch = if (slipNet) dao.getSlipNetExportBatch(limit = 500, offset = offset) else dao.getWorkingExportBatch(limit = 500, offset = offset)
                                    if (batch.isEmpty()) break
                                    offset += batch.size
                                    for (resolver in batch) {
                                        if (slipNet) writer.appendLine(resolver.address) else writer.appendLine(formatResolverEndpoint(resolver.address, resolver.port, includeDefaultPort = false))
                                        exported++
                                    }
                                }
                            }
                            if (exported == 0) throw IllegalStateException(when {
                                logs -> "No diagnostic logs are available to export."
                                slipNet -> "No working IPv4 DNS entries on port 53 are available for SlipNet export."
                                else -> "No working DNS entries are available to export yet. Run a scan first."
                            })
                            message = when {
                                logs -> "Exported $exported diagnostic log entries."
                                slipNet -> "Exported $exported IPv4 resolvers for SlipNet; skipped $skipped incompatible or duplicate entries."
                                else -> "Exported $exported working DNS entries as TXT."
                            }
                        } ?: error("Could not create the selected file.")
                    }
                } catch (exception: Exception) {
                    AppLogger.record(dao, context, "ERROR", "export", "TXT export failed", exception, stage = "write")
                    message = "TXT export failed: ${exception.message ?: "Could not write file"}"
                }
            }
        }
    }
    val txtFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isImporting = true
                importedFileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Selected TXT file"
                importSummary = ""
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
                    val skipped = (validCount - inserted).coerceAtLeast(0)
                    importSummary = "Parsed $validCount · Added $inserted · Duplicates skipped $skipped"
                    message = when {
                        validCount == 0 -> "No valid DNS entries found in the TXT file."
                        inserted == 0 -> "The file contained $validCount valid entries, but all were already saved."
                        skipped > 0 -> "Imported $inserted new DNS entries from TXT; skipped $skipped duplicates."
                        else -> "Imported $inserted DNS entries from TXT successfully."
                    }
                } catch (cancelled: CancellationException) {
                    importSummary = "Import cancelled. Completed batches remain saved in your library."
                    message = "TXT import cancelled. Entries saved from completed batches remain in your library."
                    throw cancelled
                } catch (exception: Exception) {
                    importSummary = "Import stopped: ${exception.message ?: "Could not read file"}. Completed batches remain saved."
                    AppLogger.record(dao, context, "ERROR", "import", "TXT import failed", exception, stage = "read-and-parse")
                    message = "TXT import failed: ${exception.message ?: "Could not read file"}. Entries saved from completed batches remain in your library."
                } finally {
                    // Refresh the saved-resolver page even after a partial/cancelled
                    // import so every committed batch is visible immediately.
                    resolverRefreshToken++
                    isImporting = false
                    importProgress = ""
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("DNS SniperX", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("NETWORK INTELLIGENCE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    Card(
                        shape = androidx.compose.foundation.shape.CircleShape,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Text("DNS", modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text("Discover · Test · Verify", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = selectedTab != "SETTINGS", onClick = { selectedTab = "HOME" }, icon = { Text("⌂") }, label = { Text("Home") })
                NavigationBarItem(selected = selectedTab == "SETTINGS", onClick = { selectedTab = "SETTINGS" }, icon = { Text("⚙") }, label = { Text("Settings") })
            }
        }
    ) { innerPadding ->
        HomeContent(
            contentPadding = innerPadding,
            selectedTab = selectedTab,
            onSelectedTabChange = { selectedTab = it },
            count = count,
            dao = dao,
            e2eHistoryDao = e2eHistoryDao,
            activeNetwork = activeNetwork,
            resolverRefreshToken = resolverRefreshToken,
            history = recentHistory,
            dohHistory = recentDohHistory,
            dotHistory = recentDotHistory,
            sourceHistory = recentSourceHistory,
            importText = importText,
            onImportTextChange = { importText = it },
            message = message,
            isCollecting = isCollecting,
            selectedSourceIds = selectedSourceIds,
            onSourceSelectionChange = { selectedSourceIds = it },
            onResetCollectionProgress = {
                if (!isCollecting && !isImporting && !isScanning && !isDohTesting && !isDotTesting) {
                    scope.launch {
                        try {
                            ProviderDiscoveryCoordinator(dao).resetAll()
                            message = "Collection progress reset. Saved DNS entries, scan results, and history were preserved."
                        } catch (exception: Exception) {
                            message = "Could not reset collection progress: ${exception.message ?: "Unknown error"}"
                        }
                    }
                }
            },
            isImporting = isImporting,
            importProgress = importProgress,
            importedFileName = importedFileName,
            importSummary = importSummary,
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
                        val snapshot = activeNetwork
                        val addresses = snapshot.dnsServers
                        if (!snapshot.isConnected) {
                            message = "No active default network is currently available."
                        } else if (addresses.isEmpty()) {
                            message = "Connected through ${snapshot.transport}; Android has not exposed DNS servers yet. Try again in a moment."
                        } else {
                            val operator = snapshot.operatorName ?: "unknown"
                            val providerId = snapshot.providerId ?: "unmatched"
                            val sourceLabel = "current-network (${snapshot.transport}; operator=$operator; providerId=$providerId; user label=$provider)"
                            val entries = ResolverListParser.parse(addresses.joinToString("\\n"), sourceLabel)
                            val inserted = if (entries.isEmpty()) 0 else dao.insertOrMergeSources(entries)
                            addresses.forEach { address ->
                                dao.insertResolverEvidence(
                                    ResolverEvidenceEntity(
                                        address = address,
                                        port = 53,
                                        sourceId = "current-network",
                                        providerId = snapshot.providerId,
                                        collectedAt = System.currentTimeMillis(),
                                        evidenceType = if (snapshot.operatorName != null) EvidenceType.SIM_OPERATOR_MATCH else EvidenceType.ACTIVE_NETWORK,
                                        ownershipStatus = OwnershipStatus.UNVERIFIED,
                                        evidenceDetails = "transport=${snapshot.transport}; operator=${snapshot.operatorName ?: "unavailable"}; providerMatch=${snapshot.providerName ?: "none"}; userLabel=$provider"
                                    )
                                )
                            }
                            val activeName = snapshot.providerName ?: snapshot.operatorName ?: snapshot.transport.name
                            message = "Detected ${addresses.size} DNS server(s) on $activeName; saved $inserted new entries. ISP ownership remains unverified."
                        }
                    } catch (exception: Exception) {
                        AppLogger.record(dao, context, "WARN", "network-monitor", "Could not collect active network DNS", exception, stage = "collect")
                        message = "Could not read current network DNS: ${exception.message ?: "Unknown error"}"
                    }
                }
            },
            onCollect = { sourceIds ->
                if (!isCollecting && !isImporting) {
                    scope.launch {
                        isCollecting = true
                        message = "Streaming public resolver lists in small batches…"
                        try {
                            val summary = ResolverCollector.collectAndStore(
                                sourceIds = sourceIds,
                                onBatch = { batch -> dao.insertOrMergeSources(batch) },
                                onProgress = { sourceName, found, inserted ->
                                    message = "$sourceName: received $found candidates; saved $inserted new entries…"
                                }
                            )
                            summary.results.filter { it.error != null }.forEach { failed ->
                                AppLogger.record(dao, context, "WARN", "source-collector", "Public DNS source fetch failed: ${failed.source}", stage = "download", identifier = failed.sourceId)
                            }
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
                            // Keep the Fetch action responsive: provider-range discovery is
                            // supplemental and must never delay re-enabling the main controls.
                            scope.launch(Dispatchers.IO) {
                                val discoveryReport = try {
                                    val discovery = ProviderDiscoveryCoordinator(dao).discoverNextBatch(
                                        providerIds = ProviderPrefixDiscovery.asnSeeds.mapTo(linkedSetOf()) { it.providerId },
                                        batchSize = IranPrefixDiscovery.DEFAULT_BATCH_SIZE
                                    )
                                    "Provider discovery: saved ${discovery.newlySaved} candidates; ${discovery.nextOffset}/${discovery.totalCandidates} sampled." +
                                        if (discovery.complete) " Discovery complete." else " More candidates can be discovered on a later Fetch."
                                } catch (exception: Exception) {
                                    AppLogger.record(
                                        dao, context, "WARN", "provider-discovery",
                                        "Provider ASN candidate discovery failed", exception,
                                        stage = "discover"
                                    )
                                    "Provider discovery postponed: ${exception.message?.take(120) ?: "temporarily unavailable"}."
                                }
                                withContext(Dispatchers.Main) {
                                    message = message + "\\n" + discoveryReport
                                }
                            }
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
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (exception: Exception) {
                            AppLogger.record(dao, context, "ERROR", "source-collector", "Public DNS collection failed", exception, stage = "collect")
                            message = "Collection failed: ${exception.message ?: "Unknown error"}"
                        } finally {
                            isCollecting = false
                        }
                    }
                }
            },
            onScan = { retryUnsuccessful, sourceFilter ->
                val scanIntent = Intent(context, com.thisisvista.dnssniperx.data.DnsScanForegroundService::class.java)
                try {
                    if (isScanning) {
                        scanIntent.action = com.thisisvista.dnssniperx.data.DnsScanForegroundService.ACTION_STOP
                        context.startService(scanIntent)
                        message = "Stopping DNS scan…"
                    } else {
                        scanIntent.action = com.thisisvista.dnssniperx.data.DnsScanForegroundService.ACTION_START
                        scanIntent.putExtra(com.thisisvista.dnssniperx.data.DnsScanForegroundService.EXTRA_RETRY_UNSUCCESSFUL, retryUnsuccessful)
                        scanIntent.putExtra(com.thisisvista.dnssniperx.data.DnsScanForegroundService.EXTRA_SOURCE_FILTER, sourceFilter)
                        message = "Starting scan…"
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(scanIntent)
                        } else {
                            context.startService(scanIntent)
                        }
                    }
                } catch (exception: Exception) {
                    message = "Could not start DNS scan: ${exception.message ?: exception.javaClass.simpleName}"
                    AppLogger.record(
                        dao, context, "ERROR", "scan-service",
                        "Could not start DNS scan service", exception,
                        stage = "start"
                    )
                }
            },
            onImportFile = { txtFilePicker.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) },
            onExportHealthy = {
                pendingExportMode = "HEALTHY"
                txtExportPicker.launch("dns-sniperx-working.txt")
            },
            onExportSlipNet = {
                pendingExportMode = "SLIPNET"
                txtExportPicker.launch("dns-sniperx-slipnet-ipv4.txt")
            },
            onExportLogs = {
                pendingExportMode = "LOGS"
                txtExportPicker.launch("dns-sniperx-logs.txt")
            },
            onClearLogs = { scope.launch { dao.clearAppLogs(); message = "Diagnostic logs cleared." } },
            onImport = {
                if (!isImporting && !isCollecting && !isScanning) {
                    isImporting = true
                    scope.launch {
                        try {
                            val parsed = ResolverListParser.parse(importText)
                            val inserted = if (parsed.isEmpty()) 0 else dao.insertOrMergeSources(parsed)
                            val skipped = parsed.size - inserted
                            message = when {
                                parsed.isEmpty() -> "No valid DNS entries found. Check the format."
                                inserted == 0 -> "No new entries. These DNS addresses are already saved."
                                skipped > 0 -> "Saved $inserted new DNS entries; skipped $skipped duplicates."
                                else -> "Saved $inserted DNS entries successfully."
                            }
                            if (inserted > 0) {
                                importText = ""
                                // Room count updates independently; explicitly refresh the
                                // paged resolver query so the new row appears in the library.
                                resolverRefreshToken++
                            }
                        } catch (exception: Exception) {
                            AppLogger.record(dao, context, "ERROR", "import", "Manual DNS import failed", exception, stage = "parse-and-save")
                            message = "DNS import failed: ${exception.message ?: "Unknown error"}"
                        } finally {
                            isImporting = false
                        }
                    }
                }
            },
            onDelete = { id -> scope.launch { dao.deleteById(id) } }
        )
    }
}

@Composable
private fun HomeContent(
    contentPadding: PaddingValues,
    selectedTab: String,
    onSelectedTabChange: (String) -> Unit,
    count: Int,
    dao: ResolverDao,
    e2eHistoryDao: com.thisisvista.dnssniperx.data.E2eHistoryDao,
    activeNetwork: com.thisisvista.dnssniperx.data.ActiveNetworkState,
    resolverRefreshToken: Int,
    history: List<ScanHistoryEntity>,
    dohHistory: List<DohHistoryEntity>,
    dotHistory: List<DotHistoryEntity>,
    sourceHistory: List<SourceCollectionHistoryEntity>,
    importText: String,
    onImportTextChange: (String) -> Unit,
    message: String,
    isCollecting: Boolean,
    selectedSourceIds: List<String>,
    onSourceSelectionChange: (List<String>) -> Unit,
    onResetCollectionProgress: () -> Unit,
    isImporting: Boolean,
    importProgress: String,
    importedFileName: String,
    importSummary: String,
    isScanning: Boolean,
    scanProgress: String,
    isDohTesting: Boolean,
    dohResults: List<DohProbeResult>,
    onTestDoh: () -> Unit,
    isDotTesting: Boolean,
    dotResults: List<DotProbeResult>,
    onTestDot: () -> Unit,
    onCollect: (Set<String>) -> Unit,
    onCollectNetworkDns: (String) -> Unit,
    onScan: (retryUnsuccessful: Boolean, sourceFilter: String) -> Unit,
    onImportFile: () -> Unit,
    onExportHealthy: () -> Unit,
    onExportSlipNet: () -> Unit,
    onExportLogs: () -> Unit,
    onClearLogs: () -> Unit,
    onImport: () -> Unit,
    onDelete: (Long) -> Unit
) {
    var showResetCollectionConfirmation by rememberSaveable { mutableStateOf(false) }
    var collectionStartCount by rememberSaveable { mutableStateOf(count) }
    var hasCollectedInSession by rememberSaveable { mutableStateOf(false) }
    var selectedFilter by rememberSaveable { mutableStateOf("ALL") }
    var selectedScanSource by rememberSaveable { mutableStateOf("ALL") }
    var selectedNetworkType by rememberSaveable { mutableStateOf("ALL") }
    var selectedProviderLabel by rememberSaveable { mutableStateOf("ALL") }
    var fastestFirst by rememberSaveable { mutableStateOf(false) }
    var resolverSearch by rememberSaveable { mutableStateOf("") }
    var resolvers by androidx.compose.runtime.remember { mutableStateOf(emptyList<ResolverEntity>()) }
    var matchingCount by androidx.compose.runtime.remember { mutableStateOf(0) }
    var networkTypes by androidx.compose.runtime.remember { mutableStateOf(emptyList<String>()) }
    var providerLabels by androidx.compose.runtime.remember { mutableStateOf(emptyList<String>()) }
    var healthyCount by androidx.compose.runtime.remember { mutableStateOf(0) }
    var udpPassCount by androidx.compose.runtime.remember { mutableStateOf(0) }
    var tcpPassCount by androidx.compose.runtime.remember { mutableStateOf(0) }
    var untestedCount by androidx.compose.runtime.remember { mutableStateOf(0) }
    var warningCount by androidx.compose.runtime.remember { mutableStateOf(0) }
    var isLoadingResolvers by androidx.compose.runtime.remember { mutableStateOf(false) }
    var manualResolvers by androidx.compose.runtime.remember { mutableStateOf(emptyList<ResolverEntity>()) }
    var manualResolverCount by androidx.compose.runtime.remember { mutableStateOf(0) }
    val pageSize = 100
    val scope = rememberCoroutineScope()

    LaunchedEffect(selectedTab, resolverRefreshToken) {
        if (selectedTab == "COLLECT") {
            manualResolverCount = dao.countManualResolvers()
            manualResolvers = dao.getManualResolverPage(limit = 8, offset = 0)
        }
    }

    LaunchedEffect(selectedTab, selectedFilter, resolverSearch, selectedNetworkType, selectedProviderLabel, fastestFirst, resolverRefreshToken) {
        isLoadingResolvers = true
        resolvers = emptyList()
        try {
            resolvers = dao.getResolverPage(
                statusFilter = selectedFilter,
                searchTerm = resolverSearch.trim(),
                networkType = selectedNetworkType,
                providerLabel = selectedProviderLabel,
                fastestFirst = fastestFirst,
                limit = pageSize,
                offset = 0
            )
            matchingCount = dao.countResolverMatches(
                statusFilter = selectedFilter,
                searchTerm = resolverSearch.trim(),
                networkType = selectedNetworkType,
                providerLabel = selectedProviderLabel
            )
            networkTypes = dao.getNetworkTypeFilters()
            providerLabels = dao.getProviderLabelFilters()
            healthyCount = dao.countHealthyResolvers()
            udpPassCount = dao.countUdpPassResolvers()
            tcpPassCount = dao.countTcpPassResolvers()
            untestedCount = dao.countUntestedResolvers()
            warningCount = dao.countWarningResolvers()
        } finally {
            isLoadingResolvers = false
        }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
    val compactLayout = maxWidth < 360.dp
    val horizontalPadding = when {
        maxWidth >= 700.dp -> 28.dp
        compactLayout -> 12.dp
        else -> 16.dp
    }
    Column(
        modifier = Modifier.fillMaxSize().widthIn(max = 760.dp).align(androidx.compose.ui.Alignment.TopCenter)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = horizontalPadding, vertical = if (compactLayout) 8.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (compactLayout) 9.dp else 12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                listOf("HOME" to "Home", "COLLECT" to "Collect", "E2E" to "E2E", "RESULTS" to "Results").forEach { (key, label) ->
                    FilterChip(
                        selected = selectedTab == key,
                        onClick = { onSelectedTabChange(key) },
                        label = { Text(label) },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        border = null
                    )
                }
            }
        }
        if (selectedTab == "HOME") {
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
        val overviewItems = listOf(
            "Saved" to count,
            "Working" to healthyCount,
            "UDP pass" to udpPassCount,
            "TCP pass" to tcpPassCount,
            "Untested" to untestedCount,
            "Warnings" to warningCount
        )
        val overviewColumns = if (compactLayout) 2 else 3
        overviewItems.chunked(overviewColumns).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowItems.forEach { (label, value) ->
                    Card(
                        modifier = Modifier.weight(1f).padding(bottom = 2.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(
                                horizontal = if (compactLayout) 10.dp else 14.dp,
                                vertical = 12.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                value.toString(),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
                repeat(overviewColumns - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        }

        if (selectedTab == "COLLECT") {
        Text("Discover public DNS lists", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Choose sources, fetch DNS candidates, then scan them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Public DNS sources", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Select at least one source. Regional sources run first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ResolverCollector.sources.forEach { source ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(
                    checked = source.id in selectedSourceIds,
                    onCheckedChange = { checked ->
                        val updated = if (checked) selectedSourceIds + source.id else selectedSourceIds - source.id
                        if (updated.isNotEmpty()) onSourceSelectionChange(updated)
                    },
                    enabled = !isCollecting && !isImporting && !isScanning
                )
                Column(modifier = Modifier.weight(1f).padding(top = 10.dp)) {
                    Text(source.description, style = MaterialTheme.typography.bodyMedium)
                    Text(source.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Button(
            onClick = { collectionStartCount = count; hasCollectedInSession = true; onCollect(selectedSourceIds.toSet()) },
            enabled = !isCollecting && !isImporting && !isScanning && selectedSourceIds.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isCollecting) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                Text("  Fetching · ${maxOf(0, count - collectionStartCount)}")
            } else Text("Fetch public DNS lists")
        }
        if (isCollecting || hasCollectedInSession) {
            Text(
                if (isCollecting) "${maxOf(0, count - collectionStartCount)} new · $count saved"
                else "${maxOf(0, count - collectionStartCount)} collected · $count total saved",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        Button(
            onClick = { onScan(false, selectedScanSource) },
            enabled = count > 0 && !isCollecting && !isImporting,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (isScanning) "Pause scan" else "Scan / Resume") }
        if (scanProgress.isNotBlank()) {
            Text("Progress: $scanProgress", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        OutlinedButton(
            onClick = { showResetCollectionConfirmation = true },
            enabled = !isCollecting && !isImporting && !isScanning && !isDohTesting && !isDotTesting,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Reset collection progress (keep saved DNS)") }
        if (showResetCollectionConfirmation) {
            AlertDialog(
                onDismissRequest = { showResetCollectionConfirmation = false },
                title = { Text("Reset collection progress?") },
                text = { Text("The provider-range discovery cursor and saved discovery snapshot will be cleared so that discovery can start from the beginning. All saved DNS addresses, UDP/TCP scan results, source attribution, and history will remain unchanged. Public list downloads already start from the beginning each time.") },
                confirmButton = {
                    TextButton(onClick = {
                        showResetCollectionConfirmation = false
                        onResetCollectionProgress()
                    }) { Text("Reset progress") }
                },
                dismissButton = {
                    TextButton(onClick = { showResetCollectionConfirmation = false }) { Text("Cancel") }
                }
            )
        }

        }

        if (selectedTab == "E2E") {
        E2eVerificationSection(
            resolverDao = dao,
            historyDao = e2eHistoryDao,
            network = activeNetwork,
            enabled = !isCollecting && !isImporting && !isScanning && !isDohTesting && !isDotTesting
        )

        }

        if (selectedTab == "COLLECT") {
        Text("DNS network scan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Checks saved DNS over UDP and TCP in small batches. Progress is saved so you can resume.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Scan source", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Choose which untested entries to scan.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("ALL" to "All DNS", "MANUAL" to "Manual only", "PUBLIC" to "Public only").forEach { (key, label) ->
                FilterChip(
                    selected = selectedScanSource == key,
                    onClick = { selectedScanSource = key },
                    enabled = !isScanning,
                    label = { Text(label) }
                )
            }
        }
        Button(
            onClick = { onScan(true, selectedScanSource) },
            enabled = count > 0 && !isScanning && !isCollecting && !isImporting,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Retry unsuccessful / warnings")
        }
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)

        }

        if (selectedTab == "COLLECT") {
        Text("Manual DNS workspace", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Choose a TXT file or paste DNS addresses, scan only manual entries, and export results here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("TXT import is streamed in small batches and saved to the local library. Scan results update these saved entries and remain on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onImportFile, enabled = !isScanning && !isCollecting && !isImporting, modifier = Modifier.fillMaxWidth()) {
            Text(if (isImporting) "Importing TXT…" else "Choose TXT file")
        }
        if (importedFileName.isNotBlank()) Text("Selected file: $importedFileName", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (isImporting && importProgress.isNotBlank()) {
            Text(importProgress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
        }
        if (importSummary.isNotBlank()) Text(importSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        OutlinedTextField(
            value = importText,
            onValueChange = onImportTextChange,
            enabled = !isImporting,
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            label = { Text("Or paste DNS addresses") },
            placeholder = { Text("1.1.1.1\n8.8.8.8:53\n[2001:db8::1]:53") }
        )
        Button(onClick = onImport, enabled = importText.isNotBlank() && !isScanning && !isCollecting && !isImporting, modifier = Modifier.fillMaxWidth()) {
            Text("Add pasted DNS to local library")
        }
        Text("Manual scan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("$manualResolverCount manual DNS entries in your library", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(
            onClick = { onScan(false, "MANUAL") },
            enabled = manualResolverCount > 0 && !isCollecting && !isImporting,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (isScanning) "Pause scan" else "Scan / Resume manual DNS") }
        if (scanProgress.isNotBlank()) Text("Progress: $scanProgress", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Text("Export actions include all saved working DNS, not only manual entries.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onExportHealthy, enabled = !isScanning && !isCollecting && !isImporting, modifier = Modifier.fillMaxWidth()) { Text("Export working DNS as TXT") }
        OutlinedButton(onClick = onExportSlipNet, enabled = !isScanning && !isCollecting && !isImporting, modifier = Modifier.fillMaxWidth()) { Text("Export SlipNet-compatible DNS") }
        OutlinedButton(
            onClick = { onScan(true, "MANUAL") },
            enabled = manualResolverCount > 0 && !isScanning && !isCollecting && !isImporting,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Retry manual failures / warnings") }
        Text("Manual results", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (manualResolvers.isEmpty()) {
            Text("No manual DNS entries yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            manualResolvers.forEach { resolver ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(formatResolverEndpoint(resolver.address, resolver.port), fontWeight = FontWeight.SemiBold)
                    Text("UDP: ${friendlyProbeStatus(resolver.udpStatus)} · TCP: ${friendlyProbeStatus(resolver.tcpStatus)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (manualResolverCount > manualResolvers.size) Text("Showing ${manualResolvers.size} of $manualResolverCount manual entries. Full list is in Results.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        }

        if (selectedTab == "RESULTS") {
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
        Text("${matchingCount} matching DNS entries", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (count == 0) {
            Text("No DNS addresses saved yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (matchingCount == 0) {
            Text("No DNS entries match this filter.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            resolvers.forEach { resolver ->
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
            if (isLoadingResolvers) {
                CircularProgressIndicator(modifier = Modifier.height(22.dp), strokeWidth = 2.dp)
            }
            if (resolvers.size < matchingCount) {
                Text("Showing ${resolvers.size} of ${matchingCount} matching entries.", style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = {
                        scope.launch {
                            val nextPage = dao.getResolverPage(
                                statusFilter = selectedFilter,
                                searchTerm = resolverSearch.trim(),
                                networkType = selectedNetworkType,
                                providerLabel = selectedProviderLabel,
                                fastestFirst = fastestFirst,
                                limit = pageSize,
                                offset = resolvers.size
                            )
                            resolvers = resolvers + nextPage
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoadingResolvers
                ) {
                    Text("Show 100 more DNS entries")
                }
            }
        }

        }

        if (selectedTab == "RESULTS") {
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

        }

        if (selectedTab == "SETTINGS") {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            GitHubUpdateSection()
            DiagnosticsLogsSection(dao = dao, onExport = onExportLogs, onClear = onClearLogs)
            PrivacyDisclosureSection()
            Text(
                "DNS PASS only confirms a valid DNS response. It does not prove DNSTT, Slipstream, or internet-tunnel compatibility.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
    }
    }
}


@Composable
private fun GitHubUpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by rememberSaveable { mutableStateOf("Installed version: ${BuildConfig.VERSION_NAME}") }
    var releaseUrl by rememberSaveable { mutableStateOf("") }
    var apkUrl by rememberSaveable { mutableStateOf("") }
    var checking by rememberSaveable { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("App updates", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = {
                checking = true
                status = "Checking GitHub Releases…"
                scope.launch {
                    try {
                        val data = withContext(Dispatchers.IO) {
                            val c = java.net.URL("https://api.github.com/repos/thisisvista/dns-sniper-x/releases/latest").openConnection() as java.net.HttpURLConnection
                            try {
                                c.connectTimeout = 10000
                                c.readTimeout = 10000
                                c.setRequestProperty("User-Agent", "DNS-SniperX-Android")
                                val j = org.json.JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                                val assets = j.optJSONArray("assets")
                                var apk = ""
                                if (assets != null) for (i in 0 until assets.length()) {
                                    val asset = assets.optJSONObject(i) ?: continue
                                    if (asset.optString("name").endsWith(".apk", true)) { apk = asset.optString("browser_download_url"); break }
                                }
                                Triple(j.optString("tag_name"), j.optString("html_url"), apk)
                            } finally { c.disconnect() }
                        }
                        releaseUrl = data.second
                        apkUrl = data.third
                        status = if (data.first.trimStart('v') == BuildConfig.VERSION_NAME.trimStart('v')) "Latest version installed (${data.first})."
                            else "Latest: ${data.first} · Installed: ${BuildConfig.VERSION_NAME}"
                    } catch (e: Exception) {
                        status = "Update check failed. Check your connection and retry."
                    } finally { checking = false }
                }
            }, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
                if (checking) CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp) else Text("Check for updates")
            }
            if (releaseUrl.isNotBlank()) OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl))) }, modifier = Modifier.fillMaxWidth()) { Text("Open GitHub release") }
            if (apkUrl.isNotBlank()) Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))) }, modifier = Modifier.fillMaxWidth()) { Text("Download APK") }
        }
    }
}


@Composable
private fun DiagnosticsLogsSection(dao: ResolverDao, onExport: () -> Unit, onClear: () -> Unit) {
    val logs by dao.observeRecentAppLogs().collectAsState(initial = emptyList())
    var expanded by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("ALL") }
    var search by rememberSaveable { mutableStateOf("") }
    var selectedLogId by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val filtered = logs.filter { entry ->
        (filter == "ALL" || entry.level == filter) &&
            (search.isBlank() || listOfNotNull(entry.module, entry.message, entry.details, entry.stage, entry.identifier).any { it.contains(search, ignoreCase = true) })
    }
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Settings · Logs & diagnostics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("${logs.size} recent entries saved locally (maximum 1,000). Sensitive values are redacted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) { Text(if (expanded) "Hide diagnostic logs" else "Open diagnostic logs") }
            if (expanded) {
                OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("Search logs") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("ALL" to "All", "ERROR" to "Errors", "WARN" to "Warnings", "INFO" to "Info").forEach { (value, label) ->
                        FilterChip(selected = filter == value, onClick = { filter = value }, label = { Text(label) })
                    }
                }
                if (filtered.isEmpty()) Text("No matching diagnostic logs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else filtered.take(100).forEach { entry ->
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("${entry.level} · ${entry.module}", fontWeight = FontWeight.SemiBold)
                            Text(entry.message, style = MaterialTheme.typography.bodySmall)
                            Text("${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.timestamp))} · ${entry.networkType} · ${entry.appVersion}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (entry.stage != null || entry.identifier != null) Text("Stage: ${entry.stage ?: "-"} · ID: ${entry.identifier ?: "-"}", style = MaterialTheme.typography.labelSmall)
                            if (!entry.details.isNullOrBlank()) {
                                OutlinedButton(onClick = { selectedLogId = if (selectedLogId == entry.id) null else entry.id }) { Text(if (selectedLogId == entry.id) "Hide details" else "View error details") }
                                if (selectedLogId == entry.id) Text(entry.details, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (filtered.size > 100) Text("Showing latest 100 matching entries. Export includes all retained logs.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onExport, modifier = Modifier.weight(1f)) { Text("Export TXT") }
                    OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.weight(1f)) { Text("Clear logs") }
                }
                if (confirmClear) {
                    Text("Delete all saved diagnostic logs?", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onClear(); confirmClear = false }) { Text("Confirm clear") }
                        OutlinedButton(onClick = { confirmClear = false }) { Text("Cancel") }
                    }
                }
            }
        }
    }
}


@Composable
private fun PrivacyDisclosureSection() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Settings · Privacy & network use", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Resolver data, scan results, histories, and diagnostic logs are stored locally on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text(if (expanded) "Hide privacy details" else "Review privacy & network details")
            }
            if (expanded) {
                Text("When the app uses the network", fontWeight = FontWeight.SemiBold)
                Text(
                    "Network requests are made when you start an action: collecting selected public resolver lists, scanning resolver candidates, or running DoH/DoT provider checks. The app does not run background resolver collection.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("What is sent", fontWeight = FontWeight.SemiBold)
                Text(
                    "Collection downloads public lists from the reviewed source hosts. A DNS scan sends DNS test queries (including example.com and, for extended diagnostics, randomized .invalid names) to the resolvers being tested. DoH and DoT checks contact their configured test providers. Android network information is read to label the active connection and its exposed DNS servers.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Local storage and sharing", fontWeight = FontWeight.SemiBold)
                Text(
                    "Resolver entries, scan outcomes, collection and provider histories, and diagnostic logs are kept in the app's local database. The app does not automatically upload this database or its logs. TXT exports happen only when you request an export and choose a destination. Review exported logs before sharing them, even though known secret-like values and URL query strings are redacted.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Important limitations", fontWeight = FontWeight.SemiBold)
                Text(
                    "Imported resolver lists are parsed and stored locally; they are contacted only if you later run a scan. A successful DNS response does not establish privacy, censorship circumvention, or tunnel connectivity.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
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
