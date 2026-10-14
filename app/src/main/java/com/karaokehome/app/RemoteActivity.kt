package com.karaokehome.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/** Phone-only remote UI. The TV remains responsible for media playback and the song library. */
class RemoteActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val white = Color.rgb(245, 245, 250)
    private val muted = Color.rgb(165, 166, 178)
    private val bg = Color.rgb(9, 9, 14)
    private val panel = Color.rgb(24, 24, 32)
    private val accent = Color.rgb(225, 25, 48)
    private lateinit var host: EditText
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private lateinit var queueText: TextView
    private lateinit var lyrics: TextView
    private var songs = JSONArray()
    private var currentQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        discoverTv()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(12))
            setBackgroundColor(bg)
        }
        root.addView(TextView(this).apply {
            text = "♫  KaraokeHome Remote"; textSize = 25f; setTextColor(white)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Connect to the TV on the same Wi-Fi network. Internet is not required."
            textSize = 13f; setTextColor(muted); setPadding(0, dp(5), 0, dp(12))
        })
        val connectRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        host = EditText(this).apply {
            hint = "TV IP address"; setSingleLine(true); setTextColor(white); setHintTextColor(muted)
            setPadding(dp(10), 0, dp(10), 0); setBackgroundColor(panel)
        }
        connectRow.addView(host, LinearLayout.LayoutParams(0, dp(48), 1f))
        connectRow.addView(button("FIND TV", accent) { discoverTv() }, LinearLayout.LayoutParams(dp(100), dp(48)).apply { leftMargin = dp(6) })
        root.addView(connectRow)
        root.addView(button("CONNECT / LOAD SONGS", accent) { loadSongs() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        status = TextView(this).apply { text = "Searching for KaraokeHome TV…"; textSize = 13f; setTextColor(muted); setPadding(0, dp(8), 0, dp(8)) }
        root.addView(status)
        search = EditText(this).apply {
            hint = "Search songs or track number…"; setSingleLine(true); setTextColor(white); setHintTextColor(muted)
            setPadding(dp(10), 0, dp(10), 0); setBackgroundColor(panel)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { currentQuery = s?.toString().orEmpty(); renderSongs() }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(8) })
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(button("Ⅱ PAUSE", panel) { command("pause") }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(4) })
        controls.addView(button("▶ RESUME", panel) { command("resume") }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(4) })
        controls.addView(button("■ STOP", panel) { command("stop") }, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(controls)
        root.addView(button("VIEW RESERVED QUEUE", panel) { command("queue") }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(5) })
        queueText = TextView(this).apply { text = "Queue: not connected"; textSize = 14f; setTextColor(white); setPadding(0, dp(12), 0, dp(6)) }
        root.addView(queueText)
        lyrics = TextView(this).apply { text = "Lyrics will appear here when available."; textSize = 16f; setTextColor(white); setPadding(dp(10), dp(10), dp(10), dp(10)); setBackgroundColor(panel) }
        root.addView(lyrics, LinearLayout.LayoutParams(-1, dp(110)))
        val scroll = ScrollView(this)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(results)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun discoverTv() {
        status.text = "Searching for KaraokeHome TV on this Wi-Fi…"
        io.execute {
            var found: String? = null
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 1800
                    val data = LanRemoteServer.DISCOVER_TOKEN.toByteArray()
                    val packet = DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), LanRemoteServer.DISCOVERY_PORT)
                    socket.send(packet)
                    val buffer = ByteArray(128)
                    val response = DatagramPacket(buffer, buffer.size)
                    socket.receive(response)
                    if (String(response.data, response.offset, response.length).startsWith("KARAOKEHOMEREPLY")) found = response.address.hostAddress
                }
            } catch (_: Exception) {}
            runOnUiThread {
                val address = found
                if (address != null) {
                    host.setText(address)
                    status.text = "Found TV at $address. Tap CONNECT / LOAD SONGS."
                } else status.text = "TV not found automatically. Enter its local IP address and connect."
            }
        }
    }

    private fun loadSongs() {
        val ip = host.text.toString().trim()
        if (ip.isBlank()) { status.text = "Enter the TV's local IP address first."; return }
        status.text = "Connecting…"
        io.execute {
            val response = request(ip, JSONObject().put("action", "library"))
            runOnUiThread {
                if (response.optBoolean("ok")) {
                    songs = response.optJSONArray("songs") ?: JSONArray()
                    status.text = "Connected to TV · ${songs.length()} songs"
                    queueText.text = "Queue: ${response.optInt("queueCount")} reserved song(s)"
                    renderSongs()
                    refreshStatus()
                } else status.text = response.optString("error", "Could not connect. Check both devices are on the same Wi-Fi.")
            }
        }
    }

    private fun command(action: String, index: Int = -1) {
        val ip = host.text.toString().trim()
        if (ip.isBlank()) { status.text = "Connect to the TV first."; return }
        io.execute {
            val body = JSONObject().put("action", action)
            if (index >= 0) body.put("index", index)
            val response = request(ip, body)
            runOnUiThread {
                if (!response.optBoolean("ok")) status.text = response.optString("error", "Command failed")
                else {
                    status.text = response.optString("status", "Command sent")
                    queueText.text = "Queue: ${response.optInt("queueCount")} reserved song(s)"
                    response.optString("lyrics").takeIf { it.isNotBlank() }?.let { lyrics.text = it }
                    response.optJSONArray("queue")?.let { list ->
                        val lines = (0 until list.length()).mapNotNull { i ->
                            list.optJSONObject(i)?.let { "${it.optString("number")}  ·  ${it.optString("title")}  (${it.optString("type")})" }
                        }
                        android.app.AlertDialog.Builder(this).setTitle("Reserved songs")
                            .setMessage(if (lines.isEmpty()) "The queue is empty." else lines.joinToString("\n"))
                            .setPositiveButton("OK", null).show()
                    }
                }
            }
        }
    }

    private fun refreshStatus() = command("status")

    private fun request(ip: String, body: JSONObject): JSONObject {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, LanRemoteServer.PORT), 2500)
                socket.soTimeout = 3500
                PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true).use { it.println(body.toString()) }
                BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).use { reader ->
                    JSONObject(reader.readLine() ?: return JSONObject().put("ok", false).put("error", "TV returned no response"))
                }
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "TV connection failed")
        }
    }

    private fun renderSongs() {
        if (!::results.isInitialized) return
        results.removeAllViews()
        val q = currentQuery.trim()
        var count = 0
        for (i in 0 until songs.length()) {
            val song = songs.optJSONObject(i) ?: continue
            val title = song.optString("title")
            val number = song.optString("number")
            val type = song.optString("type")
            if (q.isNotBlank() && !title.contains(q, true) && !number.contains(q)) continue
            count++
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10)); setBackgroundColor(if (count % 2 == 0) bg else panel)
            }
            row.addView(TextView(this).apply {
                text = "$number  ·  $title"; textSize = 15f; setTextColor(white)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            row.addView(TextView(this).apply { text = type; textSize = 11f; setTextColor(muted) })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(button("RESERVE", Color.rgb(40, 70, 110)) { command("reserve", i) }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(6) })
            actions.addView(button("PLAY NOW", accent) { command("play", i) }, LinearLayout.LayoutParams(0, dp(40), 1f))
            row.addView(actions)
            results.addView(row)
        }
        if (count == 0) results.addView(TextView(this).apply {
            text = if (songs.length() == 0) "No songs received yet. Connect to the TV and scan its library in TV Settings." else "No matching songs."
            textSize = 14f; setTextColor(muted); setPadding(dp(12), dp(20), dp(12), dp(20))
        })
    }

    private fun button(text: String, color: Int, action: () -> Unit) = Button(this).apply {
        this.text = text; setTextColor(white); setBackgroundColor(color); isAllCaps = false; setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }
}
