package com.thisisvista.dnssniperx.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.random.Random

data class ProbeResult(val status: String, val latencyMs: Long? = null, val detail: String? = null)
data class ResolverScanResult(val resolverId: Long, val udp: ProbeResult, val tcp: ProbeResult)

object DnsProbe {
    private const val TIMEOUT_MS = 1_800
    private const val MAX_PARALLEL_RESOLVERS = 8
    private const val MAX_BATCH_SIZE = 100
    private const val TEST_NAME = "example.com"

    suspend fun scan(resolvers: List<ResolverEntity>): List<ResolverScanResult> =
        withContext(Dispatchers.IO) {
            val semaphore = Semaphore(MAX_PARALLEL_RESOLVERS)
            coroutineScope {
                resolvers.take(MAX_BATCH_SIZE).map { resolver ->
                    async {
                        semaphore.withPermit {
                            ResolverScanResult(
                                resolver.id,
                                probe(resolver, useTcp = false),
                                probe(resolver, useTcp = true)
                            )
                        }
                    }
                }.awaitAll()
            }
        }

    private fun probe(resolver: ResolverEntity, useTcp: Boolean): ProbeResult {
        val started = System.nanoTime()
        return try {
            val address = InetAddress.getByName(resolver.address)
            val query = DnsPacket.buildQuery(TEST_NAME, Random.nextInt(0, 65_536))
            val response = if (useTcp) tcpQuery(address, resolver.port, query)
                else udpQuery(address, resolver.port, query)
            DnsPacket.validateResponse(query, response)
            ProbeResult("PASS", (System.nanoTime() - started) / 1_000_000)
        } catch (_: SocketTimeoutException) {
            ProbeResult("TIMEOUT", detail = "No DNS response before timeout")
        } catch (exception: Exception) {
            ProbeResult("FAIL", detail = exception.message?.take(120) ?: "Network error")
        }
    }

    private fun udpQuery(address: InetAddress, port: Int, query: ByteArray): ByteArray {
        DatagramSocket().use { socket ->
            socket.soTimeout = TIMEOUT_MS
            socket.send(DatagramPacket(query, query.size, InetSocketAddress(address, port)))
            val packet = DatagramPacket(ByteArray(4096), 4096)
            socket.receive(packet)
            return packet.data.copyOf(packet.length)
        }
    }

    private fun tcpQuery(address: InetAddress, port: Int, query: ByteArray): ByteArray {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, port), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            DataOutputStream(socket.getOutputStream()).apply {
                writeShort(query.size)
                write(query)
                flush()
            }
            val input = DataInputStream(socket.getInputStream())
            val length = input.readUnsignedShort()
            if (length < 12 || length > 4096) error("Invalid DNS response length")
            return ByteArray(length).also { input.readFully(it) }
        }
    }
}
