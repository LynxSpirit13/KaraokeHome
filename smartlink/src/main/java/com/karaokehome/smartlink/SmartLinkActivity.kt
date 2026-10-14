package com.karaokehome.smartlink

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Standalone phone remote for KaraokeHome TV.
 * The TV owns the song files and playback; this app only sends commands and reads status.
 */
class SmartLinkActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val white = Color.rgb(246, 246, 250)
    private val muted = Color.rgb(164, 165, 177)
    private val bg = Color.rgb(9, 9, 14)
    private val panel = Color.rgb(23, 23, 31)
    private val accent = Color.rgb(226, 25, 48)
    private lateinit var host: EditText
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var library: LinearLayout
    private lateinit var lyrics: TextView
    private lateinit var activeTitle: TextView
    private lateinit var queueInfo: TextView
    private var allSongs = JSONArray()
    private var connected = false
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        discoverTv()
        pollStatus()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 16)
            setBackgroundColor(bg)
        }
        root.addView(TextView(this).apply {
            text = "KaraokeHome Smart Link"
            textSize = 24f
            setTextColor(white)
            setPadding(0, 0, 0, 4)
        })
        root.addView(TextView(this).apply {
            text = "Connect to the TV player on the same Wi-Fi network"
            textSize = 13f
            setTextColor(muted)
        })
        val connectionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        host = EditText(this).apply {
            hint = "TV IP address"
            setSingleLine(true)
            textSize = 16f
            setTextColor(white)
            setHintTextColor(muted)
            setPadding(12, 0, 12, 0)
            setBackgroundColor(panel)
        }
        connectionRow.addView(host, LinearLayout.LayoutParams(0, 50, 1f))
        connectionRow.addView(actionButton("Connect") { connectAndLoad() })
        connectionRow.addView(actionButton("Find TV") { discoverTv() })
        root.addView(connectionRow, LinearLayout.LayoutParams(-1, 54).apply { topMargin = 10 })

        status = TextView(this).apply {
            text = "Looking for KaraokeHome TV…"
            textSize = 13f
            setTextColor(muted)
            setPadding(0, 8, 0, 8)
        }
        root.addView(status)
        activeTitle = TextView(this).apply {
            text = "Not connected"
            textSize = 18f
            setTextColor(white)
            setPadding(0, 6, 0, 2)
        }
        root.addView(activeTitle)
        queueInfo = TextView(this).apply { text = "Queue: —"; textSize = 13f; setTextColor(muted) }
        root.addView(queueInfo)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(actionButton("Pause") { sendCommand("pause") })
        controls.addView(actionButton("Resume") { sendCommand("resume") })
        controls.addView(actionButton("Stop") { sendCommand("stop") })
        controls.addView(actionButton("Queue") { sendCommand("queue") })
        root.addView(controls)

        root.addView(TextView(this).apply {
            text = "LYRICS FROM TV"
            textSize = 12f
            setTextColor(accent)
            setPadding(0, 12, 0, 4)
        })
        lyrics = TextView(this).apply {
            text = "Connect to a TV to receive the current song's lyrics."
            textSize = 18f
            setTextColor(white)
            gravity = Gravity.CENTER
            setPadding(12, 14, 12, 14)
            setBackgroundColor(panel)
            minHeight = 100
        }
        root.addView(lyrics, LinearLayout.LayoutParams(-1, 120))

        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        search = EditText(this).apply {
            hint = "Search TV song library"
            setSingleLine(true)
            textSize = 16f
            setTextColor(white)
            setHintTextColor(muted)
            setPadding(12, 0, 12, 0)
            setBackgroundColor(panel)
        }
        searchRow.addView(search, LinearLayout.LayoutParams(0, 50, 1f))
        searchRow.addView(actionButton("Search") { renderLibrary() })
        searchRow.addView(actionButton("Reload") { loadLibrary() })
        root.addView(searchRow, LinearLayout.LayoutParams(-1, 54).apply { topMargin = 12 })

        library = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isFillViewport = false; addView(library) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun actionButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setTextColor(white)
            setBackgroundColor(accent)
            setOnClickListener { action() }
        }

    private fun discoverTv() {
        status.text = "Searching local network for KaraokeHome TV…"
        io.execute {
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 1800
                    val request = "KARAOKEHOMEDISCOVER".toByteArray()
                    val broadcast = DatagramPacket(request, request.size, InetAddress.getByName("255.255.255.255"), 43822)
                    socket.send(broadcast)
                    val bytes = ByteArray(128)
                    val reply = DatagramPacket(bytes, bytes.size)
                    socket.receive(reply)
                    val response = String(reply.data, reply.offset, reply.length).trim()
                    if (response.startsWith("KARAOKEHOMEREPLY:")) {
                        main.post {
                            host.setText(reply.address.hostAddress ?: "")
                            status.text = "Found TV at ${reply.address.hostAddress}. Connecting…"
                            connectAndLoad()
                        }
                        return@execute
                    }
                }
                main.post { status.text = "TV not found automatically. Enter its IP address and tap Connect." }
            } catch (_: Exception) {
                main.post { status.text = "TV not found automatically. Enter its IP address and tap Connect." }
            }
        }
    }

    private fun connectAndLoad() {
        val ip = host.text.toString().trim()
        if (ip.isBlank()) {
            status.text = "Enter the TV's IP address or use Find TV."
            return
        }
        io.execute {
            val response = request(ip, JSONObject().put("action", "library"))
            main.post {
                if (response?.optBoolean("ok") == true) {
                    connected = true
                    allSongs = response.optJSONArray("songs") ?: JSONArray()
                    status.text = "Connected to TV at $ip"
                    updateStatus(response)
                    renderLibrary()
                } else {
                    connected = false
                    status.text = "Could not connect: ${response?.optString("error") ?: "check the IP and Wi-Fi"}"
                }
            }
        }
    }

    private fun loadLibrary() {
        val ip = host.text.toString().trim()
        if (ip.isBlank()) { discoverTv(); return }
        io.execute {
            val response = request(ip, JSONObject().put("action", "library"))
            main.post {
                if (response?.optBoolean("ok") == true) {
                    connected = true
                    allSongs = response.optJSONArray("songs") ?: JSONArray()
                    updateStatus(response)
                    renderLibrary()
                    status.text = "Library loaded: ${allSongs.length()} songs"
                } else status.text = "Library request failed: ${response?.optString("error") ?: "TV unavailable"}"
            }
        }
    }

    private fun renderLibrary() {
        if (!connected) {
            status.text = "Connect to the TV before searching its library."
            return
        }
        val q = search.text.toString().trim().lowercase()
        library.removeAllViews()
        var shown = 0
        for (i in 0 until allSongs.length()) {
            val song = allSongs.optJSONObject(i) ?: continue
            val title = song.optString("title")
            val number = song.optString("number")
            val type = song.optString("type")
            if (q.isNotEmpty() && !title.lowercase().contains(q) && !number.contains(q)) continue
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(10, 8, 10, 8)
                setBackgroundColor(if (shown % 2 == 0) panel else bg)
            }
            row.addView(TextView(this).apply {
                text = "$number  $title"
                textSize = 16f
                setTextColor(white)
            })
            row.addView(TextView(this).apply {
                text = type.uppercase()
                textSize = 11f
                setTextColor(muted)
            })
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val index = song.optInt("index", i)
            buttons.addView(actionButton("Play now") { sendCommand("play", index) })
            buttons.addView(actionButton("Reserve") { sendCommand("reserve", index) })
            row.addView(buttons)
            library.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            shown++
        }
        if (shown == 0) library.addView(TextView(this).apply {
            text = "No matching songs."
            setTextColor(muted)
            setPadding(10, 20, 10, 20)
        })
        status.text = "Showing $shown of ${allSongs.length()} TV songs"
    }

    private fun sendCommand(action: String, index: Int? = null) {
        val ip = host.text.toString().trim()
        if (ip.isBlank()) { status.text = "Connect to the TV first."; return }
        val body = JSONObject().put("action", action)
        if (index != null) body.put("index", index)
        io.execute {
            val response = request(ip, body)
            main.post {
                if (response?.optBoolean("ok") == true) {
                    connected = true
                    updateStatus(response)
                    if (action == "queue") {
                        val queue = response.optJSONArray("queue") ?: JSONArray()
                        val lines = (0 until queue.length()).mapNotNull { queue.optJSONObject(it)?.let { item -> "${item.optString("number")}  ${item.optString("title")}" } }
                        status.text = if (lines.isEmpty()) "Queue is empty." else "Queue:\n" + lines.joinToString("\n")
                    } else status.text = response.optString("status", "Command sent")
                    if (action == "stop") loadLibrary()
                    if (action == "reserve" || action == "play") pollOnce()
                } else {
                    connected = false
                    status.text = "TV request failed: ${response?.optString("error") ?: "connection lost"}"
                }
            }
        }
    }

    private fun pollStatus() {
        if (destroyed) return
        pollOnce()
        main.postDelayed({ pollStatus() }, 1100)
    }

    private fun pollOnce() {
        val ip = host.text.toString().trim()
        if (ip.isBlank()) return
        io.execute {
            val response = request(ip, JSONObject().put("action", "status"))
            if (response != null && response.optBoolean("ok")) main.post { updateStatus(response) }
        }
    }

    private fun updateStatus(response: JSONObject) {
        val title = response.optString("activeTitle", "")
        activeTitle.text = if (title.isBlank()) "Nothing playing" else "Now playing: $title"
        queueInfo.text = "Queue: ${response.optInt("queueCount", 0)} song(s)"
        val currentLyrics = response.optString("lyrics", "")
        if (currentLyrics.isNotBlank()) lyrics.text = currentLyrics
        else if (title.isBlank()) lyrics.text = "Connect to a TV and start a song to receive lyrics."
    }

    private fun request(ip: String, body: JSONObject): JSONObject? {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, 43821), 1800)
                socket.soTimeout = 2500
                val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                writer.println(body.toString())
                val line = reader.readLine() ?: return null
                JSONObject(line)
            }
        } catch (_: Exception) { null }
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }
}
