package com.thisisvista.dnssniperx.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

/** Local, bounded diagnostics. Never persist credentials or secret-bearing URIs. */
object AppLogger {
    suspend fun record(
        dao: ResolverDao,
        context: Context,
        level: String,
        module: String,
        message: String,
        error: Throwable? = null,
        stage: String? = null,
        identifier: String? = null
    ) {
        runCatching {
            val appVersion = runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            }.getOrDefault("unknown")
            val networkType = runCatching {
                val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities)
                when {
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile"
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
                    else -> "Other or offline"
                }
            }.getOrDefault("Unknown")
            val details = error?.let { sanitize(Log.getStackTraceString(it)).take(MAX_DETAILS_CHARS) }
            dao.addAppLog(
                AppLogEntity(
                    level = level.uppercase().take(12),
                    module = sanitize(module).take(64),
                    message = sanitize(message).take(500),
                    details = details,
                    stage = stage?.let { sanitize(it).take(80) },
                    identifier = identifier?.let { sanitize(it).take(180) },
                    networkType = networkType,
                    appVersion = sanitize(appVersion).take(40)
                )
            )
        }
    }

    internal fun sanitize(value: String): String {
        var safe = value
        safe = SECRET_ASSIGNMENT.replace(safe) { match -> "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]" }
        safe = USER_INFO.replace(safe) { match -> "${match.groupValues[1]}[REDACTED]@" }
        safe = URL_QUERY.replace(safe) { match -> "${match.groupValues[1]}?[REDACTED]" }
        return safe
    }

    private const val MAX_DETAILS_CHARS = 4_000
    private val SECRET_ASSIGNMENT = Regex("(?i)(password|passwd|token|secret|private[_-]?key|authorization|api[_-]?key)(\\s*[:=]\\s*)([^\\s,;]+)")
    private val USER_INFO = Regex("(https?://)[^/@\\s:]+:[^/@\\s]+@")
    private val URL_QUERY = Regex("(https?://[^\\s?]+)\\?[^\\s]+")
}
