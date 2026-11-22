package com.thisisvista.dnssniperx.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mobile.Mobile
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Real DNSTT verification through the pinned gomobile client.
 *
 * The DNSTT server must forward its streams to a SOCKS5-capable upstream.
 * The local Go listener is only a TCP forwarder; SOCKS5 negotiation happens
 * through that stream and is verified before any HTTP request is sent.
 */
class DnsttMobileE2eEngine : SlipNetE2eEngine {

    override suspend fun verify(
        request: SlipNetE2eRequest,
        onPhase: (String) -> Unit
    ): SlipNetE2eResult = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        var phase = E2ePhase.NOT_STARTED
        var client: mobile.DnsttClient? = null
        var result: SlipNetE2eResult

        val errors = request.validate()
        if (errors.isNotEmpty()) {
            return@withContext SlipNetE2eResult(
                status = if (request.mode == E2eVerificationMode.FULL) E2eStatus.FULL_FAIL else E2eStatus.HANDSHAKE_FAIL,
                phase = phase,
                elapsedMs = elapsedMs(startedAt),
                errorMessage = errors.joinToString("; ")
            )
        }
        if (request.profile.protocol != TunnelProtocol.DNSTT) {
            return@withContext SlipNetE2eResult.notConfigured("This engine supports DNSTT profiles only")
        }

        try {
            onPhase("Preparing DNSTT client")
            phase = E2ePhase.TUNNEL_SETUP

            val resolver = resolverEndpoint(request.resolverAddress, request.resolverPort)
            val localPort = reserveLoopbackPort()
            val listenAddress = "127.0.0.1:$localPort"
            val publicKey = requireNotNull(request.profile.dnsttServerPublicKey)

            client = Mobile.newClient(
                resolver,
                request.profile.serverHost,
                publicKey,
                listenAddress
            )

            onPhase("Establishing encrypted DNSTT handshake")
            phase = E2ePhase.HANDSHAKE
            client.start()
            check(client.isRunning) { "DNSTT client did not enter the running state" }

            if (request.mode == E2eVerificationMode.HANDSHAKE) {
                result = SlipNetE2eResult(
                    status = E2eStatus.HANDSHAKE_PASS,
                    phase = E2ePhase.COMPLETED,
                    elapsedMs = elapsedMs(startedAt)
                )
            } else {
                onPhase("Verifying SOCKS5 forwarding and HTTP traffic")
                phase = E2ePhase.APPLICATION_REQUEST
                val statusCode = verifyHttpThroughSocks5(
                    proxyPort = localPort,
                    verificationUrl = request.verificationUrl,
                    timeoutMs = request.timeoutMs
                )
                result = SlipNetE2eResult(
                    status = if (statusCode in 200..399) E2eStatus.FULL_PASS else E2eStatus.FULL_FAIL,
                    phase = E2ePhase.COMPLETED,
                    elapsedMs = elapsedMs(startedAt),
                    errorMessage = if (statusCode in 200..399) null else "Verification endpoint returned HTTP $statusCode",
                    httpStatusCode = statusCode
                )
            }
        } catch (e: Exception) {
            val status = if (request.mode == E2eVerificationMode.FULL) E2eStatus.FULL_FAIL else E2eStatus.HANDSHAKE_FAIL
            result = SlipNetE2eResult(
                status = status,
                phase = phase,
                elapsedMs = elapsedMs(startedAt),
                errorMessage = e.message ?: e.javaClass.simpleName
            )
        } finally {
            try {
                client?.stop()
            } catch (_: Exception) {
                // Preserve the verification result; cleanup is best-effort.
            }
        }

        result
    }

    private fun resolverEndpoint(address: String, port: Int): String {
        require(port in 1..65535) { "Invalid resolver port" }
        require(address.isNotBlank() && address.none(Char::isWhitespace)) { "Invalid resolver address" }
        // Candidate resolvers are IP literals. Reject hostnames before calling
        // InetAddress so Android's configured DNS cannot resolve the candidate.
        val ipv4Parts = address.split('.')
        val isIpv4 = ipv4Parts.size == 4 && ipv4Parts.all { part ->
            part.isNotEmpty() && part.all(Char::isDigit) &&
                (part.toIntOrNull()?.let { it in 0..255 } == true)
        }
        val isIpv6 = address.contains(':') &&
            address.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
        require(isIpv4 || isIpv6) { "Resolver must be an IPv4 or IPv6 literal" }
        val parsed = InetAddress.getByName(address)
        require(parsed.hostAddress != null) { "Invalid resolver IP address" }
        val host = if (isIpv6) "[$address]" else address
        return "$host:$port"
    }

    private fun reserveLoopbackPort(): Int =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun verifyHttpThroughSocks5(
        proxyPort: Int,
        verificationUrl: String,
        timeoutMs: Long
    ): Int {
        val uri = URI(verificationUrl)
        val host = requireNotNull(uri.host) { "Verification URL has no host" }
        val tls = uri.scheme.equals("https", ignoreCase = true)
        val port = if (uri.port >= 0) uri.port else if (tls) 443 else 80
        val timeout = timeoutMs.coerceIn(1_000L, 120_000L).toInt()

        var rawSocket: Socket? = null
        var activeSocket: Socket? = null
        try {
            val connectedRawSocket = Socket()
            rawSocket = connectedRawSocket
            connectedRawSocket.connect(java.net.InetSocketAddress("127.0.0.1", proxyPort), timeout)
            connectedRawSocket.soTimeout = timeout
            connectedRawSocket.tcpNoDelay = true

            val input = connectedRawSocket.getInputStream()
            val output = connectedRawSocket.getOutputStream()

            // SOCKS5 greeting: version 5, one method, no authentication.
            output.write(byteArrayOf(0x05, 0x01, 0x00))
            output.flush()
            val greeting = ByteArray(2)
            readFully(input, greeting)
            check(greeting[0].toInt() == 5 && greeting[1].toInt() == 0) {
                "SOCKS5 proxy rejected unauthenticated handshake"
            }

            val hostBytes = host.toByteArray(StandardCharsets.UTF_8)
            require(hostBytes.size in 1..255) { "Verification host is too long for SOCKS5" }
            val connectRequest = ByteArray(7 + hostBytes.size)
            connectRequest[0] = 0x05
            connectRequest[1] = 0x01
            connectRequest[2] = 0x00
            connectRequest[3] = 0x03
            connectRequest[4] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, connectRequest, 5, hostBytes.size)
            connectRequest[5 + hostBytes.size] = (port ushr 8).toByte()
            connectRequest[6 + hostBytes.size] = port.toByte()
            output.write(connectRequest)
            output.flush()

            val response = ByteArray(4)
            readFully(input, response)
            check(response[0].toInt() == 5 && response[1].toInt() == 0) {
                "SOCKS5 CONNECT failed (reply=${response[1].toInt() and 0xff})"
            }
            when (response[3].toInt() and 0xff) {
                1 -> skipFully(input, 4)
                3 -> {
                    val length = input.read()
                    check(length >= 0) { "Truncated SOCKS5 domain reply" }
                    skipFully(input, length)
                }
                4 -> skipFully(input, 16)
                else -> error("Invalid SOCKS5 address type")
            }
            skipFully(input, 2) // bound port

            activeSocket = if (tls) {
                (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(connectedRawSocket, host, port, true) as SSLSocket
            } else {
                connectedRawSocket
            }
            activeSocket = activeSocket
            activeSocket.soTimeout = timeout
            if (activeSocket is SSLSocket) activeSocket.startHandshake()

            val path = buildString {
                append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
                if (!uri.rawQuery.isNullOrEmpty()) append("?").append(uri.rawQuery)
            }
            val hostHeader = if ((tls && port != 443) || (!tls && port != 80)) "$host:$port" else host
            val writer = OutputStreamWriter(activeSocket.getOutputStream(), StandardCharsets.ISO_8859_1)
            writer.write("GET $path HTTP/1.1\r\n")
            writer.write("Host: $hostHeader\r\n")
            writer.write("User-Agent: DNS-SniperX-E2E/1\r\n")
            writer.write("Accept: */*\r\n")
            writer.write("Connection: close\r\n\r\n")
            writer.flush()

            val reader = BufferedReader(InputStreamReader(activeSocket.getInputStream(), StandardCharsets.ISO_8859_1))
            val statusLine = reader.readLine() ?: error("No HTTP response received through DNSTT")
            val match = Regex("^HTTP/1\\.[01] ([0-9]{3})(?:\\s|$)").find(statusLine)
                ?: error("Invalid HTTP status line: $statusLine")
            return match.groupValues[1].toInt()
        } finally {
            try { activeSocket?.close() } catch (_: Exception) {}
            if (activeSocket !== rawSocket) {
                try { rawSocket?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun readFully(input: java.io.InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw SocketTimeoutException("Connection closed during SOCKS5 handshake")
            offset += count
        }
    }

    private fun skipFully(input: java.io.InputStream, count: Int) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining.toLong()).toInt()
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (input.read() < 0) throw SocketTimeoutException("Connection closed during SOCKS5 reply")
                remaining--
            }
        }
    }

    private fun elapsedMs(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000L
}
