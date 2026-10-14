package com.karaokehome.app

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Local-network control endpoint for the TV. No cloud service or internet connection is used.
 * Each TCP connection carries one JSON request and receives one JSON response.
 */
class LanRemoteServer(
    private val handler: (JSONObject) -> JSONObject,
    private val onStarted: (Boolean) -> Unit = {}
) {
    @Volatile private var running = false
    private var tcp: ServerSocket? = null
    private var discovery: DatagramSocket? = null

    fun start() {
        if (running) return
        running = true
        thread(name = "KaraokeHome-TCP", isDaemon = true) {
            try {
                val socket = ServerSocket(PORT)
                tcp = socket
                onStarted(true)
                while (running) {
                    val client = try { socket.accept() } catch (_: SocketException) { break }
                    thread(name = "KaraokeHome-client", isDaemon = true) { handleClient(client) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Remote TCP server unavailable", e)
                onStarted(false)
            } finally {
                try { tcp?.close() } catch (_: Exception) {}
                tcp = null
            }
        }
        thread(name = "KaraokeHome-discovery", isDaemon = true) {
            try {
                val socket = DatagramSocket(DISCOVERY_PORT, InetAddress.getByName("0.0.0.0"))
                discovery = socket
                socket.broadcast = true
                val buffer = ByteArray(128)
                while (running) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val request = String(packet.data, packet.offset, packet.length).trim()
                    if (request == DISCOVER_TOKEN) {
                        val reply = "KARAOKEHOMEREPLY:$PORT".toByteArray()
                        socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                    }
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "LAN discovery unavailable", e)
            } finally {
                try { discovery?.close() } catch (_: Exception) {}
                discovery = null
            }
        }
    }

    fun stop() {
        running = false
        try { tcp?.close() } catch (_: Exception) {}
        try { discovery?.close() } catch (_: Exception) {}
        tcp = null
        discovery = null
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 5000
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = PrintWriter(socket.getOutputStream(), true, Charsets.UTF_8)
                val line = reader.readLine() ?: return
                val request = JSONObject(line)
                val latch = CountDownLatch(1)
                var response = JSONObject().put("ok", false).put("error", "TV did not respond in time")
                // The supplied handler is expected to marshal UI work onto the main thread itself.
                val thread = Thread {
                    try { response = handler(request) } catch (e: Exception) {
                        response = JSONObject().put("ok", false).put("error", e.message ?: "Request failed")
                    } finally { latch.countDown() }
                }
                thread.start()
                latch.await(5, TimeUnit.SECONDS)
                writer.println(response.toString())
            } catch (e: Exception) {
                Log.w(TAG, "Remote request failed", e)
                try { PrintWriter(socket.getOutputStream(), true).println(JSONObject().put("ok", false).put("error", "Request failed").toString()) } catch (_: Exception) {}
            }
        }
    }

    companion object {
        const val PORT = 43821
        const val DISCOVERY_PORT = 43822
        const val DISCOVER_TOKEN = "KARAOKEHOMEDISCOVER"
        const val TAG = "KaraokeHomeRemote"
    }
}
