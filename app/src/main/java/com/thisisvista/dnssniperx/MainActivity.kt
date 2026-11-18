package com.thisisvista.dnssniperx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
    val scope = rememberCoroutineScope()

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
    onCollect: () -> Unit,
    onImport: () -> Unit,
    onDelete: (Long) -> Unit
) {
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
        Button(onClick = onCollect, enabled = !isCollecting, modifier = Modifier.fillMaxWidth()) {
            if (isCollecting) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                Text("  Collecting…")
            } else {
                Text("Fetch public DNS lists")
            }
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
        Button(onClick = onImport, enabled = importText.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Import to local library")
        }

        Text("Saved resolvers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (resolvers.isEmpty()) {
            Text("No DNS addresses saved yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            resolvers.take(100).forEach { resolver ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("${resolver.address}:${resolver.port}", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Status: Not tested • Source: ${resolver.source}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = { onDelete(resolver.id) }) { Text("Remove") }
                    }
                }
            }
            if (count > 100) Text("Showing the latest 100 entries.", style = MaterialTheme.typography.bodySmall)
        }

        Text(
            "Public list collection and local storage are active. Network scanning and tunnel compatibility tests are not implemented yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }
}
