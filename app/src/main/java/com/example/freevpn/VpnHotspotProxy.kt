package com.example.freevpn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI

class VpnHotspotProxy(private val port: Int = 8080) {

    private var serverSocket: ServerSocket? = null
    private var proxyScope: CoroutineScope? = null
    private var acceptJob: Job? = null

    val isRunning: Boolean
        get() = serverSocket?.isClosed == false

    fun start(onError: (String) -> Unit) {
        if (isRunning) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        proxyScope = scope

        acceptJob = scope.launch {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress("0.0.0.0", port))
                serverSocket = server

                while (isActive && !server.isClosed) {
                    val client = server.accept()
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                if (isActive) {
                    onError("Hotspot proxy error: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        } finally {
            serverSocket = null
            acceptJob?.cancel()
            proxyScope?.cancel()
            proxyScope = null
        }
    }

    private suspend fun handleClient(clientSocket: Socket) {
        clientSocket.use { client ->
            try {
                client.soTimeout = 30_000
                val clientIn = client.getInputStream()
                val clientOut = client.getOutputStream()

                val requestLine = readLineRaw(clientIn) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 3) return

                val method = parts[0].uppercase()
                val target = parts[1]
                val version = parts[2]

                val headers = mutableListOf<String>()
                while (true) {
                    val line = readLineRaw(clientIn) ?: break
                    if (line.isEmpty()) break
                    headers.add(line)
                }

                if (method == "CONNECT") {
                    val hostPort = target.split(":")
                    val host = hostPort[0]
                    val targetPort = hostPort.getOrNull(1)?.toIntOrNull() ?: 443

                    Socket().use { remote ->
                        remote.connect(InetSocketAddress(host, targetPort), 10_000)
                        clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                        clientOut.flush()
                        relayBidirectional(clientIn, clientOut, remote.getInputStream(), remote.getOutputStream())
                    }
                } else {
                    val uri = URI(target)
                    val host = uri.host ?: return
                    val targetPort = if (uri.port != -1) uri.port else 80
                    val path = (uri.rawPath ?: "/").ifEmpty { "/" } +
                        (uri.rawQuery?.let { "?$it" } ?: "")

                    Socket().use { remote ->
                        remote.connect(InetSocketAddress(host, targetPort), 10_000)
                        val remoteOut = remote.getOutputStream()

                        val sb = StringBuilder()
                        sb.append("$method $path $version\r\n")
                        for (h in headers) {
                            if (!h.startsWith("Proxy-Connection", ignoreCase = true)) {
                                sb.append("$h\r\n")
                            }
                        }
                        sb.append("Connection: close\r\n\r\n")
                        remoteOut.write(sb.toString().toByteArray())
                        remoteOut.flush()

                        relayBidirectional(clientIn, clientOut, remote.getInputStream(), remoteOut)
                    }
                }
            } catch (_: Exception) {
                // Client closed or unreachable host
            }
        }
    }

    private suspend fun relayBidirectional(
        clientIn: InputStream,
        clientOut: OutputStream,
        remoteIn: InputStream,
        remoteOut: OutputStream
    ) {
        val scope = CoroutineScope(Dispatchers.IO)
        val up = scope.launch {
            try {
                clientIn.copyTo(remoteOut)
                remoteOut.flush()
            } catch (_: Exception) {
            }
        }
        val down = scope.launch {
            try {
                remoteIn.copyTo(clientOut)
                clientOut.flush()
            } catch (_: Exception) {
            }
        }
        up.join()
        down.cancel()
    }

    private fun readLineRaw(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (buffer.size() == 0) null else buffer.toString("UTF-8")
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) buffer.write(b)
        }
        return buffer.toString("UTF-8")
    }

    companion object {
        fun detectLocalHotspotIp(): String {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                val candidates = mutableListOf<String>()
                for (iface in interfaces) {
                    if (!iface.isUp || iface.isLoopback || iface.name.startsWith("tun")) continue
                    for (addr in iface.inetAddresses) {
                        if (addr is Inet4Address && addr.isSiteLocalAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (ip.startsWith("192.168.43.") || ip.startsWith("192.168.")) {
                                return ip
                            }
                            candidates.add(ip)
                        }
                    }
                }
                candidates.firstOrNull() ?: "192.168.43.1"
            } catch (_: Exception) {
                "192.168.43.1"
            }
        }
    }
}

