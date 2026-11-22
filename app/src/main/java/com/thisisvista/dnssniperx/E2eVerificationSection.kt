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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.thisisvista.dnssniperx.data.ActiveNetworkState
import com.thisisvista.dnssniperx.data.DnsttMobileE2eEngine
import com.thisisvista.dnssniperx.data.E2eHistoryDao
import com.thisisvista.dnssniperx.data.E2eVerificationCoordinator
import com.thisisvista.dnssniperx.data.E2eVerificationMode
import com.thisisvista.dnssniperx.data.ResolverDao
import com.thisisvista.dnssniperx.data.SlipNetE2eEngine
import com.thisisvista.dnssniperx.data.TunnelProfile
import com.thisisvista.dnssniperx.data.TunnelProfileMapper
import com.thisisvista.dnssniperx.data.TunnelProtocol
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()
    val profiles by resolverDao.observeTunnelProfiles().collectAsState(initial = emptyList())
    val attemptsFlow = remember(resolverIp) {
        if (resolverIp.isBlank()) flowOf(emptyList())
        else historyDao.observeResolverAttempts(resolverIp.trim(), 53, 10)
    }
    val attempts by attemptsFlow.collectAsState(initial = emptyList())

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
                    scope.launch {
                        running = true
                        status = "Running"
                        detail = "Preparing DNSTT E2E verification…"
                        elapsed = null
                        try {
                            val profile = TunnelProfile(
                                id = "dnstt-e2e-manual",
                                name = "DNSTT E2E test profile",
                                protocol = TunnelProtocol.DNSTT,
                                serverHost = tunnelDomain.trim(),
                                // The generic profile requires a port; DNSTT's resolver port is stored here.
                                // The mobile binding itself uses the resolver endpoint passed to verify().
                                serverPort = 53,
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
                                resolverPort = 53,
                                profile = profile,
                                mode = E2eVerificationMode.FULL,
                                network = network,
                                timeoutMs = 25_000,
                                verificationUrl = "http://www.gstatic.com/generate_204",
                                expectedHttpStatusCode = 204,
                                onPhase = { phase -> detail = phase }
                            )
                            status = result.status.name
                            detail = result.errorMessage ?: "HTTP ${result.httpStatusCode ?: "-"} received through the tunnel."
                            elapsed = result.elapsedMs
                        } catch (failure: Exception) {
                            status = "NOT_TESTED"
                            detail = failure.message ?: failure.javaClass.simpleName
                        } finally {
                            running = false
                        }
                    }
                },
                enabled = enabled && !running && resolverIp.isNotBlank() && tunnelDomain.isNotBlank() && publicKey.length == 64,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (running) {
                    CircularProgressIndicator()
                    Text("  Testing DNSTT tunnel…")
                } else {
                    Text("Save profile and run real E2E test")
                }
            }
            Text("Network: ${network.transport.name} · ${network.providerName ?: network.operatorName ?: "provider unknown"}", style = MaterialTheme.typography.labelSmall)
            Text("Result: $status${elapsed?.let { " · ${it} ms" } ?: ""}", fontWeight = FontWeight.SemiBold)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
