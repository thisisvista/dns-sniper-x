package com.thisisvista.dnssniperx

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.thisisvista.dnssniperx.data.AndroidKeystoreTunnelSecretStore
import com.thisisvista.dnssniperx.data.ResolverDao
import com.thisisvista.dnssniperx.data.TunnelProfile
import com.thisisvista.dnssniperx.data.TunnelProfileRepository
import com.thisisvista.dnssniperx.data.TunnelProtocol
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Restores the DNSTT server-profile editor. This stores profile metadata only;
 * it deliberately does not claim to start a tunnel until a real native/Go
 * DNSTT adapter is packaged and validated.
 */
@Composable
internal fun DnsttProfileSection(dao: ResolverDao) {
    val context = LocalContext.current
    val repository = remember(dao, context) {
        TunnelProfileRepository(dao, AndroidKeystoreTunnelSecretStore(context))
    }
    val profiles by repository.observeProfiles().collectAsState(initial = emptyList())
    val existing = profiles.firstOrNull { it.protocol == TunnelProtocol.DNSTT }
    val scope = rememberCoroutineScope()

    var profileName by rememberSaveable { mutableStateOf("My DNSTT server") }
    var serverHost by rememberSaveable { mutableStateOf("dns.ilovenella.dpdns.org") }
    var serverPort by rememberSaveable { mutableStateOf("53") }
    var publicKey by rememberSaveable {
        mutableStateOf("4eacd13c0de06b3c61fc35102ca3de9e34ea761ed23f4d48bd427ae40b7ef73c")
    }
    var loadedProfileId by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("Enter your DNSTT server details and save the profile.") }
    var saving by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(existing?.id) {
        if (existing != null && loadedProfileId != existing.id) {
            profileName = existing.name
            serverHost = existing.serverHost
            serverPort = existing.serverPort.toString()
            publicKey = existing.dnsttServerPublicKey.orEmpty()
            loadedProfileId = existing.id
            message = "Saved DNSTT profile loaded."
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
            Text("Settings · DNSTT server profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Save the domain and server public key once. These settings are kept locally on this device and reused for future E2E tests.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = profileName,
                onValueChange = { profileName = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Profile name") }
            )
            OutlinedTextField(
                value = serverHost,
                onValueChange = { serverHost = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("DNSTT domain") },
                placeholder = { Text("dns.example.com") }
            )
            OutlinedTextField(
                value = serverPort,
                onValueChange = { value -> if (value.all(Char::isDigit) && value.length <= 5) serverPort = value },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Server port") }
            )
            OutlinedTextField(
                value = publicKey,
                onValueChange = { publicKey = it.filterNot(Char::isWhitespace) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("DNSTT server Public Key (64 hex characters)") },
                supportingText = { Text("${publicKey.length}/64 characters") }
            )
            Button(
                onClick = {
                    val port = serverPort.toIntOrNull()
                    val profile = TunnelProfile(
                        id = existing?.id ?: UUID.randomUUID().toString(),
                        name = profileName,
                        protocol = TunnelProtocol.DNSTT,
                        serverHost = serverHost,
                        serverPort = port ?: 0,
                        dnsttServerPublicKey = publicKey
                    )
                    val errors = profile.validate()
                    if (errors.isNotEmpty()) {
                        message = errors.joinToString(". ")
                    } else {
                        scope.launch {
                            saving = true
                            try {
                                repository.saveProfile(profile)
                                loadedProfileId = profile.id
                                message = "DNSTT profile saved on this device. E2E engine integration is still pending."
                            } catch (error: Exception) {
                                message = "Could not save profile: ${error.message ?: "unknown error"}"
                            } finally {
                                saving = false
                            }
                        }
                    }
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (saving) "Saving…" else if (existing == null) "Save DNSTT profile" else "Update DNSTT profile")
            }
            if (existing != null) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            try {
                                repository.deleteProfile(existing.id)
                                loadedProfileId = ""
                                profileName = "My DNSTT server"
                                serverHost = ""
                                serverPort = "53"
                                publicKey = ""
                                message = "DNSTT profile deleted."
                            } catch (error: Exception) {
                                message = "Could not delete profile: ${error.message ?: "unknown error"}"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Delete saved profile") }
            }
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Text(
                "E2E testing is not enabled yet: this screen saves the configuration, but SniperX must still package and validate the real DNSTT client before it can report FULL_PASS.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
