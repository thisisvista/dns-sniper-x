package com.thisisvista.dnssniperx.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lightweight foreground monitor for the app's default network.
 * It registers one callback while the observing screen is composed and unregisters it on disposal.
 */
class ActiveNetworkMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val telephony = appContext.getSystemService(TelephonyManager::class.java)
    private val mutableState = MutableStateFlow(ActiveNetworkState())
    val state: StateFlow<ActiveNetworkState> = mutableState.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var activeNetwork: Network? = null
    private var providers: List<ProviderEntity> = emptyList()

    fun updateProviders(value: List<ProviderEntity>) {
        providers = value
        val current = mutableState.value
        if (current.transport == ActiveNetworkTransport.CELLULAR) {
            mutableState.value = current.withOperator(current.operatorName)
        }
    }

    @Synchronized
    fun start() {
        if (callback != null) return
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                activeNetwork = network
                mutableState.value = ActiveNetworkState(isConnected = true)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (network != activeNetwork) return
                val transport = when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> ActiveNetworkTransport.VPN
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ActiveNetworkTransport.WIFI
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ActiveNetworkTransport.CELLULAR
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ActiveNetworkTransport.ETHERNET
                    else -> ActiveNetworkTransport.OTHER
                }
                val operator = if (transport == ActiveNetworkTransport.CELLULAR) readNetworkOperatorName() else null
                mutableState.value = mutableState.value.copy(
                    isConnected = true,
                    transport = transport,
                    isInternetValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    operatorName = operator,
                    providerId = ProviderNameMatcher.match(operator, providers)?.providerId,
                    providerName = ProviderNameMatcher.match(operator, providers)?.displayName
                )
            }

            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                if (network != activeNetwork) return
                val dns = properties.dnsServers.mapNotNull { it.hostAddress?.substringBefore('%') }.distinct()
                mutableState.value = mutableState.value.copy(dnsServers = dns)
            }

            override fun onLost(network: Network) {
                if (network == activeNetwork) {
                    activeNetwork = null
                    mutableState.value = ActiveNetworkState()
                }
            }
        }
        callback = networkCallback
        try {
            connectivity.registerDefaultNetworkCallback(networkCallback)
        } catch (exception: RuntimeException) {
            callback = null
            mutableState.value = ActiveNetworkState()
        }
    }

    @Synchronized
    fun stop() {
        val registered = callback ?: return
        callback = null
        try {
            connectivity.unregisterNetworkCallback(registered)
        } catch (_: IllegalArgumentException) {
            // Already unregistered by the platform.
        }
        activeNetwork = null
        mutableState.value = ActiveNetworkState()
    }

    private fun readNetworkOperatorName(): String? = try {
        telephony?.networkOperatorName?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: SecurityException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    }

    private fun ActiveNetworkState.withOperator(operator: String?): ActiveNetworkState {
        val provider = ProviderNameMatcher.match(operator, providers)
        return copy(operatorName = operator, providerId = provider?.providerId, providerName = provider?.displayName)
    }
}
