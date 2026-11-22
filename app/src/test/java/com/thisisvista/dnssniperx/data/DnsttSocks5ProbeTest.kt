package com.thisisvista.dnssniperx.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.Test
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class DnsttSocks5ProbeTest {

    @Test
    fun socks5ConnectCarriesHttpRequestAndReturnsExpectedStatus() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val serverFailure = AtomicReference<Throwable?>(null)
        val worker = thread(isDaemon = true) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = DataInputStream(socket.getInputStream())
                    val output = socket.getOutputStream()

                    val greeting = ByteArray(3)
                    input.readFully(greeting)
                    assertEquals(listOf(5, 1, 0), greeting.map { it.toInt() and 0xff })
                    output.write(byteArrayOf(5, 0))
                    output.flush()

                    val requestHeader = ByteArray(5)
                    input.readFully(requestHeader)
                    assertEquals(5, requestHeader[0].toInt())
                    assertEquals(1, requestHeader[1].toInt())
                    assertEquals(3, requestHeader[3].toInt())
                    val hostLength = requestHeader[4].toInt() and 0xff
                    val hostAndPort = ByteArray(hostLength + 2)
                    input.readFully(hostAndPort)
                    val requestedHost = String(hostAndPort, 0, hostLength, StandardCharsets.UTF_8)
                    assertEquals("example.test", requestedHost)
                    val requestedPort = ((hostAndPort[hostLength].toInt() and 0xff) shl 8) or
                        (hostAndPort[hostLength + 1].toInt() and 0xff)
                    assertEquals(80, requestedPort)

                    // SOCKS5 success with an IPv4 bound address and port.
                    output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80))
                    output.flush()

                    val reader = BufferedReader(InputStreamReader(input, StandardCharsets.ISO_8859_1))
                    val requestLine = reader.readLine()
                    assertEquals("GET /probe HTTP/1.1", requestLine)
                    while (reader.readLine()?.isNotEmpty() == true) {
                        // Consume request headers.
                    }
                    output.write(
                        "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .toByteArray(StandardCharsets.ISO_8859_1)
                    )
                    output.flush()
                }
            } catch (error: Throwable) {
                serverFailure.set(error)
            } finally {
                server.close()
            }
        }

        try {
            val status = DnsttMobileE2eEngine().verifyHttpThroughSocks5(
                proxyPort = server.localPort,
                verificationUrl = "http://example.test/probe",
                timeoutMs = 5_000
            )
            worker.join(5_000)
            assertEquals(204, status)
            assertNull(serverFailure.get())
        } finally {
            server.close()
            worker.join(5_000)
        }
    }

    @Test
    fun closingActiveSocketInterruptsBlockedSocks5GreetingRead() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 3_000
        val publishedSocket = AtomicReference<Socket?>(null)
        val socketPublished = CountDownLatch(1)
        val probeFailure = AtomicReference<Throwable?>(null)
        val cancellationJob = Job()
        val cancellationHandle = closeSocketOnJobCancellation(cancellationJob, publishedSocket)
        val worker = thread(isDaemon = true) {
            try {
                DnsttMobileE2eEngine().verifyHttpThroughSocks5(
                    proxyPort = server.localPort,
                    verificationUrl = "http://example.test/probe",
                    timeoutMs = 30_000,
                    onSocket = {
                        publishedSocket.set(it)
                        socketPublished.countDown()
                    }
                )
            } catch (error: Throwable) {
                probeFailure.set(error)
            }
        }
        var acceptedSocket: Socket? = null

        try {
            assertTrue("Probe did not publish its socket", socketPublished.await(2, TimeUnit.SECONDS))
            acceptedSocket = server.accept()
            acceptedSocket.soTimeout = 2_000

            // Wait until the probe sends its greeting; the server deliberately never replies.
            val greeting = ByteArray(3)
            DataInputStream(acceptedSocket.getInputStream()).readFully(greeting)
            assertEquals(listOf(5, 1, 0), greeting.map { it.toInt() and 0xff })

            cancellationJob.cancel(CancellationException("Test cancellation"))

            worker.join(1_500)
            assertFalse("Probe remained blocked after its socket was closed", worker.isAlive)
            val failure = probeFailure.get()
            assertNotNull("Closing the socket should fail the blocked probe", failure)
            assertTrue("Expected an I/O exception, got $failure", failure is IOException)
        } finally {
            cancellationJob.cancel()
            cancellationHandle.dispose()
            publishedSocket.get()?.close()
            acceptedSocket?.close()
            server.close()
            worker.join(2_000)
        }
    }
}
