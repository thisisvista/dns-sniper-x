package com.thisisvista.dnssniperx

import android.os.Bundle
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.LinkProperties
import android.net.NetworkCapabilities
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.thisisvista.dnssniperx.data.ResolverCollector
import com.thisisvista.dnssniperx.data.ResolverDao
import com.thisisvista.dnssniperx.data.ResolverDatabase
import com.thisisvista.dnssniperx.data.ResolverEntity
import com.thisisvista.dnssniperx.data.ResolverListParser
import com.thisisvista.dnssniperx.ui.theme.DnsSniperXTheme
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
    var importText by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("Your DNS library is stored on this device.") }
    var isCollecting by rememberSaveable { mutableStateOf(false) }
    var isScanning by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

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
            importText = importText,
            onImportTextChange = { importText = it },
            message = message,
            isCollecting = isCollecting,
            isScanning = isScanning,
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
                            val inserted = if (entries.isEmpty()) 0 else dao.insertIgnore(entries).count { it != -1L }
                            message = "Detected ${addresses.size} DNS server(s) on $networkType; saved $inserted new DNS entries with label: $provider. ISP ownership is not verified."
                        }
                    } catch (exception: Exception) {
                        message = "Could not read current network DNS: ${exception.message ?: "Unknown error"}"
                    }
                }
            },
            onCollect = {
                if (!isCollecting) {
                    scope.launch {
                        isCollecting = true
                        message = "Downloading public resolver lists…"
                        try {
                            val (summary, entries) = ResolverCollector.downloadAndParse()
                            val inserted = if (entries.isEmpty()) 0 else dao.insertIgnore(entries).count { it != -1L }
                            val failures = summary.results.filter { it.error != null }
                            val sourceReport = summary.results.joinToString { result ->
                                if (result.error == null) "${result.source}: ${result.found}" else "${result.source}: unavailable"
                            }
                            message = buildString {
                                append("Found ${summary.found} candidates; saved ${inserted} new DNS entries.")
                                if (failures.isNotEmpty()) append(" ${failures.size} source(s) could not be reached.")
                                append("\n").append(sourceReport)
                                if (summary.found > inserted) append("\nExisting duplicates or repeated entries were skipped.")
                            }
                        } catch (exception: Exception) {
                            message = "Collection failed: ${exception.message ?: "Unknown error"}"
                        } finally {
                            isCollecting = false
                        }
                    }
                }
            },
            onScan = {
                if (!isScanning && resolvers.isNotEmpty()) {
                    scope.launch {
                        isScanning = true
                        message = "Testing up to 100 saved DNS entries over UDP and TCP…"
                        try {
                            val results = DnsProbe.scan(resolvers)
                            results.forEach { result ->
                                val best = listOfNotNull(result.udp.latencyMs, result.tcp.latencyMs).minOrNull()
                                val overall = when {
                                    result.udp.status == "PASS" || result.tcp.status == "PASS" -> "DNS_PASS"
                                    result.udp.status == "TIMEOUT" || result.tcp.status == "TIMEOUT" -> "TIMEOUT"
                                    else -> "FAILED"
                                }
                                val notes = listOfNotNull(
                                    result.udp.detail?.let { "UDP: $it" },
                                    result.tcp.detail?.let { "TCP: $it" }
                                ).joinToString("; ").take(240).ifBlank { null }
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
                            }
                            val udpSummary = summarizeStatuses(results.map { it.udp.status })
                            val tcpSummary = summarizeStatuses(results.map { it.tcp.status })
                            message = "Scan complete: ${results.size} DNS entries.\nUDP — $udpSummary\nTCP — $tcpSummary"
                            if (resolvers.size > 100) message += "\nScan is limited to the first 100 entries per run."
                        } catch (exception: Exception) {
                            message = "Scan failed: ${exception.message ?: "Unknown error"}"
                        } finally {
                            isScanning = false
                        }
                    }
                }
            },
            onImport = {
                val parsed = ResolverListParser.parse(importText)
                scope.launch {
                    val inserted = if (parsed.isEmpty()) 0 else dao.insertIgnore(parsed).count { it != -1L }
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
    importText: String,
    onImportTextChange: (String) -> Unit,
    message: String,
    isCollecting: Boolean,
    isScanning: Boolean,
    onCollect: () -> Unit,
    onCollectNetworkDns: (String) -> Unit,
    onScan: () -> Unit,
    onImport: () -> Unit,
    onDelete: (Long) -> Unit
) {
    var selectedProvider by rememberSaveable { mutableStateOf("Unspecified") }
    var selectedFilter by rememberSaveable { mutableStateOf("ALL") }
    var selectedNetworkType by rememberSaveable { mutableStateOf("ALL") }
    var selectedProviderLabel by rememberSaveable { mutableStateOf("ALL") }
    var fastestFirst by rememberSaveable { mutableStateOf(false) }
    val networkTypes = resolvers.mapNotNull { resolver ->
        resolver.source.takeIf { it.startsWith("current-network (") }
            ?.substringAfter("current-network (")?.substringBefore(";")
    }.distinct().sorted()
    val providerLabels = resolvers.mapNotNull { resolver ->
        resolver.source.takeIf { it.startsWith("current-network (") }
            ?.substringAfter("user label: ")?.substringBefore(")")
    }.distinct().sorted()
    val filteredResolvers = resolvers.filter { resolver ->
        val networkType = resolver.source.takeIf { it.startsWith("current-network (") }
            ?.substringAfter("current-network (")?.substringBefore(";")
        val providerLabel = resolver.source.takeIf { it.startsWith("current-network (") }
            ?.substringAfter("user label: ")?.substringBefore(")")
        val sourceMatches = (selectedNetworkType == "ALL" || networkType == selectedNetworkType) &&
            (selectedProviderLabel == "ALL" || providerLabel == selectedProviderLabel)
        val statusMatches = when (selectedFilter) {
            "WORKING" -> resolver.udpStatus == "PASS" || resolver.tcpStatus == "PASS"
            "UDP" -> resolver.udpStatus == "PASS"
            "TCP" -> resolver.tcpStatus == "PASS"
            "UNTESTED" -> resolver.udpStatus == "NOT_TESTED" && resolver.tcpStatus == "NOT_TESTED"
            else -> true
        }
        sourceMatches && statusMatches
    }.let { list ->
        if (fastestFirst) list.sortedBy { item -> listOfNotNull(item.udpLatencyMs, item.tcpLatencyMs).minOrNull() ?: Long.MAX_VALUE } else list
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
        Button(onClick = { onCollectNetworkDns(selectedProvider) }, enabled = !isCollecting && !isScanning, modifier = Modifier.fillMaxWidth()) {
            Text("Detect and save current network DNS")
        }
        Text(
            "Reads DNS servers configured on the phone’s active Wi-Fi or mobile network. This does not prove which ISP owns them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onCollect, enabled = !isCollecting && !isScanning, modifier = Modifier.fillMaxWidth()) {
            if (isCollecting) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                Text("  Collecting…")
            } else Text("Fetch public DNS lists")
        }

        Text("DNS network scan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Sends a small standard DNS query to each saved address over UDP and TCP. Up to 100 entries are tested per run, with limited parallel connections.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Result guide: PASS = valid answer; DNS error = server replied with an error (for example, blocked or nonexistent domain); No answer = valid reply without an address; Truncated = reply was cut short; Timeout = no reply in time; Failed = network or protocol problem.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onScan, enabled = count > 0 && !isScanning && !isCollecting, modifier = Modifier.fillMaxWidth()) {
            if (isScanning) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                Text("  Scanning…")
            } else Text("Scan saved DNS (UDP + TCP)")
        }
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)

        Text("Import DNS addresses manually", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Paste one address per line. Supported: IP, IP:port, hostname, or [IPv6]:port. Port 53 is used when omitted.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = importText,
            onValueChange = onImportTextChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            label = { Text("DNS addresses") },
            placeholder = { Text("1.1.1.1\n8.8.8.8:53\n[2001:db8::1]:53") }
        )
        Button(onClick = onImport, enabled = importText.isNotBlank() && !isScanning && !isCollecting, modifier = Modifier.fillMaxWidth()) {
            Text("Import to local library")
        }

        Text("Saved resolvers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ALL" to "All", "WORKING" to "Working", "UDP" to "UDP", "TCP" to "TCP", "UNTESTED" to "Untested").forEach { (key, label) ->
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
            filteredResolvers.take(100).forEach { resolver ->
                val sourceDescription = if (resolver.source.startsWith("current-network (")) {
                    val networkType = resolver.source.substringAfter("current-network (").substringBefore(";")
                    val providerLabel = resolver.source.substringAfter("user label: ", "Unspecified").substringBefore(")")
                    "Network: $networkType • Provider label: $providerLabel"
                } else {
                    "Source: ${resolver.source}"
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("${resolver.address}:${resolver.port}", fontWeight = FontWeight.SemiBold)
                        Text(
                            "UDP: ${friendlyProbeStatus(resolver.udpStatus)}${resolver.udpLatencyMs?.let { " • ${it} ms" } ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "TCP: ${friendlyProbeStatus(resolver.tcpStatus)}${resolver.tcpLatencyMs?.let { " • ${it} ms" } ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(sourceDescription, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        resolver.note?.takeIf { it.isNotBlank() }?.let { detail ->
                            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = { onDelete(resolver.id) }) { Text("Remove") }
                    }
                }
            }
            if (filteredResolvers.size > 100) Text("Showing the first 100 matching entries.", style = MaterialTheme.typography.bodySmall)
        }

        Text(
            "DNS PASS only confirms a valid DNS response. It does not prove DNSTT, Slipstream, or internet-tunnel compatibility.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }
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
