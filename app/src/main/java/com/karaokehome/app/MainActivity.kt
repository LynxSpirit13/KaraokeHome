package com.karaokehome.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import dev.kotlinds.fluidsynthkmp.MidiFilePlayer
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File
import java.util.zip.ZipInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

class MainActivity : Activity() {
    private data class Song(val number: String, val title: String, val uri: Uri, val type: String, val zipEntry: String? = null)
    private val songs = mutableListOf<Song>()
    private val queue = mutableListOf<Song>()
    private var activeSong: Song? = null
    private var midiPlayer: MidiFilePlayer? = null
    private var midiPaused = false
    private var midiUri: Uri? = null
    private var videoUri: Uri? = null
    private var filter = "ALL"
    private var query = ""
    private var numberEntry = ""
    private var numberDisplay: TextView? = null
    private var numberScreenActive = false
    private var activeLyrics: String = ""
    private var timedLyrics: List<MidiEmbeddedLyrics.TimedLyric> = emptyList()
    private var lyricsPlaybackView: TextView? = null
    private var lyricStartedAt: Long = 0L
    private val lyricHandler = Handler(Looper.getMainLooper())
    private val lyricHighlighter = object : Runnable {
        override fun run() {
            if (midiPlayer == null || timedLyrics.isEmpty()) return
            renderTimedLyrics()
            lyricHandler.postDelayed(this, 80L)
        }
    }
    private var bgvPlayer: ExoPlayer? = null
    private var bgvTextureView: TextureView? = null
    private var bgvAspectContainer: androidx.media3.ui.AspectRatioFrameLayout? = null
    private var tvNumberDisplay: TextView? = null
    private var mainNavigation: View? = null
    private var shellHeader: View? = null
    private var shellAccentLine: View? = null
    private lateinit var content: LinearLayout
    private lateinit var headerStatus: TextView
    private var exoPlayer: ExoPlayer? = null
    private var videoTextureView: TextureView? = null
    private var videoAspectContainer: androidx.media3.ui.AspectRatioFrameLayout? = null
    private var remoteServer: LanRemoteServer? = null
    private val bg = Color.rgb(8, 8, 12)
    private val panel = Color.rgb(19, 19, 25)
    private val red = Color.rgb(224, 18, 39)
    private val bright = Color.rgb(255, 45, 60)
    private val white = Color.rgb(246, 246, 250)
    private val muted = Color.rgb(164, 165, 177)
    private val divider = Color.rgb(55, 35, 42)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val deviceMode = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        val isTelevision = deviceMode == Configuration.UI_MODE_TYPE_TELEVISION
        if (isTelevision) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildShell()
        loadSavedLibrary()
        // The player remains a player on phones too. Smart Link is a separate installable app.
        if (isTelevision) {
            remoteServer = LanRemoteServer(::handleRemoteRequest).also { it.start() }
        }
        showHome()
    }

    /** Handle phone commands on the UI thread because the song catalog and players are UI-owned. */
    private fun handleRemoteRequest(request: JSONObject): JSONObject {
        var response = JSONObject().put("ok", false).put("error", "TV did not respond")
        val latch = CountDownLatch(1)
        runOnUiThread {
            try {
                when (request.optString("action")) {
                    "library" -> {
                        val list = JSONArray()
                        songs.forEachIndexed { index, song ->
                            list.put(JSONObject().put("index", index).put("number", song.number)
                                .put("title", song.title).put("type", song.type))
                        }
                        response = JSONObject().put("ok", true).put("songs", list)
                            .put("queueCount", queue.size).put("status", headerStatus.text.toString())
                            .put("activeTitle", activeSong?.title ?: "")
                            .put("lyrics", activeLyrics)
                    }
                    "reserve", "play" -> {
                        val index = request.optInt("index", -1)
                        val song = songs.getOrNull(index)
                        if (song == null) {
                            response = JSONObject().put("ok", false).put("error", "Song not found. Reload the TV library.")
                        } else {
                            if (request.optString("action") == "reserve") {
                                if (queue.none { sameSong(it, song) }) queue.add(song)
                                updateHeader()
                                response = remoteStatus("Reserved: " + song.title)
                            } else {
                                activeSong = song
                                playSong(song)
                                response = remoteStatus("Playing: " + song.title)
                            }
                        }
                    }
                    "pause" -> {
                        pausePlayback()
                        response = remoteStatus("Paused")
                    }
                    "resume" -> {
                        resumePlayback()
                        response = remoteStatus("Playback resumed")
                    }
                    "play_pause" -> {
                        togglePlayback()
                        response = remoteStatus("Playback toggled")
                    }
                    "previous" -> {
                        playPreviousSong()
                        response = remoteStatus("Previous song")
                    }
                    "next" -> {
                        playNextSong()
                        response = remoteStatus("Next song")
                    }
                    "stop" -> {
                        stopPlayback()
                        activeSong = null
                        showLibrary()
                        response = remoteStatus("Playback stopped")
                    }
                    "status" -> response = remoteStatus("Connected")
                    "queue" -> {
                        val list = JSONArray()
                        queue.forEach { song -> list.put(JSONObject().put("number", song.number).put("title", song.title).put("type", song.type)) }
                        response = remoteStatus("Queue updated").put("queue", list)
                    }
                    else -> response = JSONObject().put("ok", false).put("error", "Unknown remote command")
                }
            } catch (e: Exception) {
                response = JSONObject().put("ok", false).put("error", e.message ?: "Command failed")
            } finally { latch.countDown() }
        }
        latch.await(4, TimeUnit.SECONDS)
        return response
    }

    private fun remoteStatus(message: String) = JSONObject().put("ok", true)
        .put("status", message).put("queueCount", queue.size)
        .put("activeTitle", activeSong?.title ?: "")
        .put("lyrics", activeLyrics)

    @Deprecated("Android back navigation")
    override fun onBackPressed() {
        if (exoPlayer != null || midiPlayer != null || bgvPlayer != null) {
            stopPlayback()
            showLibrary()
        } else if (::content.isInitialized) {
            showHome()
        } else {
            super.onBackPressed()
        }
    }

    // Android TV remotes and compatible media remotes send standard Android key codes.
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            android.view.KeyEvent.KEYCODE_HEADSETHOOK -> { togglePlayback(); return true }
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> { resumePlayback(); return true }
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> { pausePlayback(); return true }
            android.view.KeyEvent.KEYCODE_MEDIA_STOP -> { stopPlayback(); showLibrary(); return true }
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> { playNextSong(); return true }
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { playPreviousSong(); return true }
            in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> {
                val digit = (keyCode - android.view.KeyEvent.KEYCODE_0).toString()
                numberEntry = (numberEntry + digit).take(7)
                if (isTelevisionUi()) {
                    if (exoPlayer == null && midiPlayer == null && tvNumberDisplay == null) showNumberPad()
                    tvNumberDisplay?.text = numberEntry.padStart(7, '0').takeLast(7)
                    headerStatus.text = "TRACK  " + numberEntry.padStart(7, '0')
                    Toast.makeText(this, "TRACK  " + numberEntry.padStart(7, '0'), Toast.LENGTH_SHORT).show()
                } else if (numberScreenActive) updateNumberDisplay() else showNumberPad()
                return true
            }
            android.view.KeyEvent.KEYCODE_PROG_RED -> {
                showLibrary()
                content.post {
                    val search = content.findViewWithTag<EditText>("songSearch")
                    search?.requestFocus()
                    (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                        ?.showSoftInput(search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
                return true
            }
            android.view.KeyEvent.KEYCODE_PROG_GREEN -> {
                if (isTelevisionUi() && numberEntry.isNotEmpty()) {
                    reserveEnteredNumber()
                    numberEntry = ""
                    tvNumberDisplay?.text = "0000000"
                    headerStatus.text = "RESERVED • QUEUE " + queue.size
                } else showQueue()
                return true
            }
            android.view.KeyEvent.KEYCODE_PROG_YELLOW -> {
                showBgvOptions()
                return true
            }
            android.view.KeyEvent.KEYCODE_PROG_BLUE -> {
                chooseSoundFont()
                return true
            }
            android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER, android.view.KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (numberEntry.isNotEmpty()) {
                    if (isTelevisionUi() && (exoPlayer?.isPlaying == true || midiPlayer != null)) {
                        reserveEnteredNumber()
                        numberEntry = ""
                        headerStatus.text = "RESERVED • QUEUE " + queue.size
                    } else {
                        playEnteredNumber()
                        numberEntry = ""
                        tvNumberDisplay?.text = "0000000"
                    }
                    return true
                }
            }
            android.view.KeyEvent.KEYCODE_DEL, android.view.KeyEvent.KEYCODE_FORWARD_DEL -> {
                if (numberEntry.isNotEmpty()) {
                    numberEntry = numberEntry.dropLast(1)
                    if (isTelevisionUi()) {
                        if (tvNumberDisplay == null && exoPlayer == null && midiPlayer == null) showNumberPad()
                        tvNumberDisplay?.text = numberEntry.padStart(7, '0').takeLast(7)
                    } else if (numberScreenActive) updateNumberDisplay() else showNumberPad()
                    return true
                }
            }
            android.view.KeyEvent.KEYCODE_ESCAPE, android.view.KeyEvent.KEYCODE_BACK -> {
                if (numberEntry.isNotEmpty()) {
                    numberEntry = ""
                    if (isTelevisionUi()) {
                        tvNumberDisplay?.text = "0000000"
                        if (tvNumberDisplay == null && exoPlayer == null && midiPlayer == null) showNumberPad()
                    } else if (numberScreenActive) updateNumberDisplay() else showNumberPad()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showBgvOptions() {
        AlertDialog.Builder(this)
            .setTitle("Background video (BGV)")
            .setMessage("Choose an MP4 to loop silently behind MIDI/KAR playback. BGV audio is muted so the MIDI soundtrack remains audible.")
            .setPositiveButton("CHOOSE VIDEO") { _, _ ->
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "video/*"
                }, REQUEST_PICK_BGV)
            }
            .setNeutralButton("DISABLE BGV") { _, _ ->
                getSharedPreferences("karaokehome", MODE_PRIVATE).edit().remove("bgv_uri").apply()
                message("Background video disabled.")
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun chooseSoundFont() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "audio/x-soundfont", "audio/sf2"))
        }, REQUEST_PICK_SOUNDFONT)
    }


    private fun buildShell() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12)); setBackgroundColor(Color.rgb(17, 5, 8))
        }
        header.addView(TextView(this).apply {
            text = "♫"; textSize = 27f; gravity = Gravity.CENTER; setTextColor(white)
            setBackgroundColor(red); setPadding(dp(8), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        val branding = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        branding.addView(label("KaraokeHome", 22f, true, white))
        branding.addView(label("YOUR HOME KARAOKE SYSTEM  •  MP4 / MIDI / KAR", 9f, true, bright))
        header.addView(branding, LinearLayout.LayoutParams(0, -2, 1f))
        headerStatus = label("READY", 10f, true, bright)
        header.addView(headerStatus)
        shellHeader = header
        root.addView(header)
        shellAccentLine = View(this).apply { setBackgroundColor(red) }
        root.addView(shellAccentLine, LinearLayout.LayoutParams(-1, dp(2)))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        mainNavigation = nav
        val isTelevision = (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
        if (isTelevision) {
            nav.addView(navButton("KARAOKE") { showHome() }, weight())
            nav.addView(navButton("SETTINGS") { showSettings() }, weight())
        } else {
            nav.addView(navButton("SONGS") { showLibrary() }, weight())
            nav.addView(navButton("TRACK #") { showNumberPad() }, weight())
            nav.addView(navButton("QUEUE ${queue.size}") { showQueue() }, weight())
            nav.addView(navButton("SETTINGS") { showSettings() }, weight())
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(16)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    /**
     * Restore the last indexed catalog from app-private storage. Opening the app must not
     * enumerate thousands of files (or reopen every ZIP) on each launch.
     */
    private fun loadSavedLibrary() {
        val saved = getSharedPreferences("karaokehome", MODE_PRIVATE)
            .getString("home_tree_uri", null) ?: return
        val tree = Uri.parse(saved)
        val permissionHeld = contentResolver.persistedUriPermissions.any {
            it.uri == tree && it.isReadPermission
        }
        if (!permissionHeld) {
            songs.clear()
            return
        }
        if (loadCachedCatalog()) return
        // First launch after upgrading from a version without a catalog cache.
        try {
            songs.clear()
            scanTree(tree, DocumentsContract.getTreeDocumentId(tree), 0)
            assignTrackNumbers()
            saveCatalog()
        } catch (_: Exception) {
            songs.clear()
        }
    }

    private fun assignTrackNumbers() {
        songs.sortBy { it.title.lowercase() }
        for (i in songs.indices) songs[i] = songs[i].copy(number = "%06d".format(i + 1))
    }

    private fun loadCachedCatalog(): Boolean {
        val indexFile = File(filesDir, "library-index.json")
        if (!indexFile.isFile) return false
        return try {
            val root = JSONObject(indexFile.readText(Charsets.UTF_8))
            val items = root.optJSONArray("songs") ?: return false
            val restored = mutableListOf<Song>()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val number = item.optString("number")
                val title = item.optString("title")
                val uriText = item.optString("uri")
                val type = item.optString("type")
                if (number.isBlank() || title.isBlank() || uriText.isBlank() ||
                    type !in setOf("MP4", "MIDI", "KAR")) continue
                restored.add(Song(number, title, Uri.parse(uriText), type,
                    item.optString("zipEntry").takeIf { it.isNotBlank() }))
            }
            songs.clear()
            songs.addAll(restored)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun saveCatalog() {
        try {
            val items = JSONArray()
            songs.forEach { song ->
                items.put(JSONObject()
                    .put("number", song.number)
                    .put("title", song.title)
                    .put("uri", song.uri.toString())
                    .put("type", song.type)
                    .put("zipEntry", song.zipEntry ?: ""))
            }
            val root = JSONObject()
                .put("version", 1)
                .put("savedAt", System.currentTimeMillis())
                .put("songs", items)
            val target = File(filesDir, "library-index.json")
            val temp = File(filesDir, "library-index.json.tmp")
            temp.writeText(root.toString(), Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                target.writeText(root.toString(), Charsets.UTF_8)
                temp.delete()
            }
        } catch (_: Exception) {
            // A failed cache write must not interrupt scanning or playback.
        }
    }

    private fun showHome() {
        numberScreenActive = false
        restoreShellChrome()
        content.removeAllViews()
        content.gravity = Gravity.CENTER
        content.setPadding(dp(28), dp(18), dp(28), dp(22))

        content.addView(TextView(this).apply {
            text = "KaraokeHome"
            textSize = 34f
            setTextColor(white)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, lp(-1, -2, 0, 6))
        content.addView(TextView(this).apply {
            text = "CHOOSE WHAT YOU WANT TO DO"
            textSize = 12f
            setTextColor(muted)
            gravity = Gravity.CENTER
        }, lp(-1, -2, 0, 24))

        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun menuTile(title: String, symbol: String, color: Int, action: () -> Unit): View {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(20), dp(20), dp(20))
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    intArrayOf(color, blend(color, Color.WHITE, 0.18f))
                ).apply { cornerRadius = dp(22).toFloat(); setStroke(dp(2), Color.argb(180, 235, 190, 225)) }
                isFocusable = true
                isClickable = true
                contentDescription = title
                setOnClickListener { action() }
                setOnFocusChangeListener { view, focused ->
                    (view.background as? android.graphics.drawable.GradientDrawable)?.setStroke(
                        dp(if (focused) 4 else 2),
                        if (focused) Color.WHITE else Color.argb(180, 235, 190, 225)
                    )
                    view.scaleX = if (focused) 1.025f else 1f
                    view.scaleY = if (focused) 1.025f else 1f
                }
                addView(TextView(this@MainActivity).apply {
                    text = symbol; textSize = 48f; setTextColor(white); gravity = Gravity.CENTER; isFocusable = false
                })
                addView(TextView(this@MainActivity).apply {
                    text = title; textSize = 22f; setTextColor(white); gravity = Gravity.CENTER
                    setTypeface(typeface, android.graphics.Typeface.BOLD); isFocusable = false
                })
            }.also { it.tag = "homeMenu:$title" }
        }
        menu.addView(menuTile("KARAOKE", "♫", Color.rgb(155, 25, 90)) {
            if (isTelevisionUi()) showNumberPad() else showLibrary()
        },
            LinearLayout.LayoutParams(0, dp(190), 1f).apply { marginEnd = dp(10) })
        menu.addView(menuTile("SETTINGS", "⚙", Color.rgb(48, 50, 66)) { showSettings() },
            LinearLayout.LayoutParams(0, dp(190), 1f).apply { marginStart = dp(10) })
        content.addView(menu, LinearLayout.LayoutParams(-1, -2))
        content.post { content.findViewWithTag<View>("homeMenu:KARAOKE")?.requestFocus() }
    }

    private fun blend(a: Int, b: Int, fraction: Float): Int {
        val inv = 1f - fraction
        return Color.rgb((Color.red(a) * inv + Color.red(b) * fraction).toInt(),
            (Color.green(a) * inv + Color.green(b) * fraction).toInt(),
            (Color.blue(a) * inv + Color.blue(b) * fraction).toInt())
    }

    private fun showRankings() {
        numberScreenActive = false
        restoreShellChrome()
        content.removeAllViews()
        content.addView(label("RANKINGS", 24f, true, white), lp(-1, -2, 8, 12))
        content.addView(label("Ranking statistics are not available yet. Start with the song library and reservation queue.", 16f, false, muted), lp(-1, -2, 0, 12))
        content.addView(actionButton("BACK HOME", red) { showHome() }, lp(-1, 48, 8))
    }

    private fun showLibrary() {
        numberScreenActive = false
        restoreShellChrome()
        content.minimumHeight = 0
        content.setPadding(dp(12), dp(4), dp(12), dp(16))
        content.removeAllViews()
        val hero = card()
        hero.addView(label("YOUR HOME KARAOKE SYSTEM", 10f, true, bright))
        hero.addView(label("Pick a song. Grab the mic. Sing together.", 18f, true, white), lp(-1, -2, 8))
        hero.addView(label("One library for your video and classic karaoke files.", 12f, false, muted), lp(-1, -2, 5))
        hero.addView(actionButton("＋  SCAN / RESCAN LIBRARY FOLDER", red) { scanLibraryOrChooseFolder() }.apply { tag = "scanLibraryButton" }, lp(-1, 50, 12))
        content.addView(hero, lp(-1, -2, 0, 10))

        val search = EditText(this).apply {
            hint = "Search song title, artist, or track number…"; setHintTextColor(muted); setTextColor(white); textSize = 16f
            setSingleLine(true); setPadding(dp(12), 0, dp(12), 0); setBackgroundColor(panel)
            setText(query)
            tag = "songSearch"
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s?.toString() ?: ""; renderSongRows() }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        content.addView(search, lp(-1, 54, 0, 8))
        content.addView(actionButton("123  DIGITAL SONG-NUMBER KEYPAD", Color.rgb(40, 45, 75)) { showNumberPad() }, lp(-1, 48, 0, 10))
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("ALL", "MP4", "MIDI", "KAR").forEach { f ->
            filters.addView(actionButton(f, if (filter == f) red else panel) { filter = f; showLibrary() }, weight().apply { rightMargin = dp(4) })
        }
        content.addView(filters, lp(-1, 38, 0, 8))
        val listCard = card()
        val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(8)); setBackgroundColor(Color.rgb(34, 11, 16)) }
        columns.addView(label("TRACK", 10f, true, muted), LinearLayout.LayoutParams(dp(60), -2))
        columns.addView(label("SONG TITLE", 10f, true, muted), LinearLayout.LayoutParams(0, -2, 1f))
        columns.addView(label("TYPE", 10f, true, muted), LinearLayout.LayoutParams(dp(42), -2))
        listCard.addView(columns)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rows.tag = "songRows"
        listCard.addView(rows)
        content.addView(listCard)
        content.addView(label("Tap a song to play • Long-press to reserve", 11f, false, muted), lp(-1, -2, 8))
        renderSongRows()
        content.post { content.findViewWithTag<View>("scanLibraryButton")?.requestFocus() }
    }

    private fun renderSongRows() {
        val rows = content.findViewWithTag<LinearLayout>("songRows") ?: return
        rows.removeAllViews()
        val filtered = songs.filter { (filter == "ALL" || it.type == filter) && (query.isBlank() || it.title.contains(query, true) || it.number.contains(query)) }
        filtered.forEachIndexed { i, song ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(11), dp(8), dp(11)); setBackgroundColor(if (i % 2 == 0) panel else Color.rgb(14, 14, 19))
                isFocusable = true; isClickable = true
                setOnFocusChangeListener { view, focused ->
                    view.setBackgroundColor(if (focused) Color.rgb(75, 25, 38) else if (i % 2 == 0) panel else Color.rgb(14, 14, 19))
                }
                setOnClickListener { activeSong = song; playSong(song) }
                setOnLongClickListener { if (queue.none { sameSong(it, song) }) queue.add(song); updateHeader(); Toast.makeText(this@MainActivity, "Reserved: ${song.title}", Toast.LENGTH_SHORT).show(); true }
            }
            row.addView(label(song.number, 13f, true, bright), LinearLayout.LayoutParams(dp(66), -2))
            row.addView(label(song.title, 16f, false, white), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label(song.type, 12f, true, if (song.type == "MP4") Color.rgb(80, 190, 255) else Color.rgb(80, 220, 165)), LinearLayout.LayoutParams(dp(46), -2))
            rows.addView(row)
            rows.addView(View(this).apply { setBackgroundColor(divider) }, LinearLayout.LayoutParams(-1, dp(1)))
        }
        if (filtered.isEmpty()) rows.addView(label(if (songs.isEmpty()) "No songs yet. Scan the folder containing your MP4, MIDI, and KAR files." else "No matching songs.", 14f, false, muted).apply {
            gravity = Gravity.CENTER; setPadding(dp(18), dp(28), dp(18), dp(28))
        })
    }

    private fun chooseHomeFolderLocation() {
        try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_HOME_FOLDER) }
        catch (_: Exception) { message("Folder picker is unavailable on this device.") }
    }

    private fun createDirectory(parent: Uri, name: String): Uri {
        // ACTION_OPEN_DOCUMENT_TREE returns a tree URI, not a document URI.
        // Resolve its root ID with getTreeDocumentId before querying or creating children.
        val parentId = if (DocumentsContract.isTreeUri(parent)) {
            DocumentsContract.getTreeDocumentId(parent)
        } else {
            DocumentsContract.getDocumentId(parent)
        }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, parentId)
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )
            while (cursor?.moveToNext() == true) {
                val id = cursor.getString(0) ?: continue
                val display = cursor.getString(1) ?: continue
                val mime = cursor.getString(2) ?: ""
                if (display.equals(name, true) && mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    return DocumentsContract.buildDocumentUriUsingTree(parent, id)
                }
            }
        } finally {
            cursor?.close()
        }
        val parentDocument = DocumentsContract.buildDocumentUriUsingTree(parent, parentId)
        return DocumentsContract.createDocument(
            contentResolver,
            parentDocument,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name
        ) ?: throw IllegalStateException("Android could not create $name")
    }

    private fun chooseFolder() {
        try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_FOLDER) }
        catch (_: Exception) { message("Folder picker is unavailable on this device.") }
    }

    @Deprecated("Document tree callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        if (requestCode == REQUEST_HOME_FOLDER || requestCode == REQUEST_FOLDER) {
            // Use the existing SongHub folder directly. No duplicate KaraokeHome folder
            // or copied media is created, so both apps can share the same song files.
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            getSharedPreferences("karaokehome", MODE_PRIVATE).edit().putString("home_tree_uri", uri.toString()).apply()
            songs.clear()
            try {
                scanTree(uri, DocumentsContract.getTreeDocumentId(uri), 0)
                assignTrackNumbers()
                saveCatalog()
            } catch (e: Exception) {
                songs.clear()
                message("Could not scan the selected folder: ${e.message ?: "access failed"}")
            }
            query = ""
            showLibrary()
            val count = songs.size
            Toast.makeText(this, "Indexed $count karaoke files from selected folder", Toast.LENGTH_LONG).show()
            if (count == 0) message("No MP4, MIDI, or KAR files found. Choose the actual SongHub song folder, not its parent or a folder containing only app data.")
        } else if (requestCode == REQUEST_PICK_VIDEO) {
            videoUri = uri
            activeSong = Song("000000", uri.lastPathSegment?.substringAfterLast('/') ?: "Video", uri, "MP4")
            playSong(activeSong!!)
        } else if (requestCode == REQUEST_PICK_MIDI) {
            midiUri = uri
            activeSong = Song("000000", uri.lastPathSegment?.substringAfterLast('/') ?: "MIDI", uri, "MIDI")
            playSong(activeSong!!)
        } else if (requestCode == REQUEST_PICK_SOUNDFONT) {
            try {
                val target = File(filesDir, "custom-soundfont.sf2")
                contentResolver.openInputStream(uri).use { input -> requireNotNull(input) { "SoundFont could not be read" }; target.outputStream().use { output -> input.copyTo(output) } }
                getSharedPreferences("karaokehome", MODE_PRIVATE).edit().putString("custom_soundfont_path", target.absolutePath).apply()
                message("SoundFont selected. It will be used for the next MIDI/KAR playback.")
            } catch (e: Exception) { message("Could not load SoundFont: ${e.message ?: "invalid file"}") }
        } else if (requestCode == REQUEST_PICK_BGV) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            getSharedPreferences("karaokehome", MODE_PRIVATE).edit().putString("bgv_uri", uri.toString()).apply()
            if (numberScreenActive || isTelevisionUi()) showNumberPad()
            else message("BGV saved. Open Enter Karaoke to preview it.")
        }
    }

    private fun scanTree(tree: Uri, parentId: String, depth: Int) {
        if (depth > 12 || songs.size >= 15000) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)
            while (cursor?.moveToNext() == true && songs.size < 15000) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (isBgvFolder(name)) scanBgvTree(tree, id, depth + 1)
                    else scanTree(tree, id, depth + 1)
                    continue
                }
                val ext = name.substringAfterLast('.', "").lowercase()
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                when (ext) {
                    "mp4", "m4v" -> songs.add(Song("", name.substringBeforeLast('.', name), uri, "MP4"))
                    "mid", "midi" -> songs.add(Song("", name.substringBeforeLast('.', name), uri, "MIDI"))
                    "kar" -> songs.add(Song("", name.substringBeforeLast('.', name), uri, "KAR"))
                    "zip" -> scanZipArchive(uri, name)
                }
            }
        } catch (_: Exception) {
            // Some shared-storage providers deny individual folders. Continue indexing the rest.
        } finally { cursor?.close() }
    }

    private fun isBgvFolder(name: String): Boolean {
        val normalized = name.trim().lowercase().replace(Regex("[ _-]+"), "")
        return normalized in setOf("bgv", "bgvs", "backgroundvideo", "backgroundvideos", "background")
    }

    private fun scanBgvTree(tree: Uri, parentId: String, depth: Int) {
        if (depth > 12) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)
            while (cursor?.moveToNext() == true) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    scanBgvTree(tree, id, depth + 1)
                } else if (mime.startsWith("video/") || name.substringAfterLast('.', "").lowercase() in setOf("mp4", "m4v", "webm", "mkv", "3gp")) {
                    val prefs = getSharedPreferences("karaokehome", MODE_PRIVATE)
                    if (prefs.getString("bgv_uri", null).isNullOrBlank()) {
                        prefs.edit().putString("bgv_uri", DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()).apply()
                    }
                }
            }
        } catch (_: Exception) {
        } finally { cursor?.close() }
    }

    private fun scanZipArchive(uri: Uri, archiveName: String) {
        try {
            contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null && songs.size < 15000) {
                        if (!entry.isDirectory) {
                            val entryName = entry.name
                            val leaf = entryName.substringAfterLast('/')
                            val ext = leaf.substringAfterLast('.', "").lowercase()
                            val type = when (ext) { "mid", "midi" -> "MIDI"; "kar" -> "KAR"; else -> null }
                            if (type != null && leaf.isNotBlank()) {
                                val title = leaf.substringBeforeLast('.', leaf).ifBlank { archiveName.substringBeforeLast('.', archiveName) }
                                songs.add(Song("", title, uri, type, entryName))
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore malformed or unsupported archives rather than failing the whole library scan.
        }
    }

    private fun materializeMidi(song: Song, destination: File) {
        if (song.zipEntry == null) {
            contentResolver.openInputStream(song.uri).use { input ->
                requireNotNull(input) { "File could not be read" }
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        var found = false
        contentResolver.openInputStream(song.uri).use { raw ->
            requireNotNull(raw) { "ZIP archive could not be read" }
            ZipInputStream(raw.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name == song.zipEntry) {
                        destination.outputStream().use { output -> zip.copyTo(output) }
                        found = true
                        break
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        require(found) { "The selected MIDI/KAR entry was not found inside its ZIP archive." }
    }

    private fun sameSong(a: Song, b: Song): Boolean = a.uri == b.uri && a.zipEntry == b.zipEntry

    private fun isTelevisionUi(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION

    private fun showNumberPad() {
        if (isTelevisionUi()) {
            showTelevisionKaraokeIdle()
            return
        }
        numberScreenActive = true
        restoreShellChrome()
        enterKaraokeFullscreen()
        shellHeader?.visibility = View.GONE
        shellAccentLine?.visibility = View.GONE
        mainNavigation?.visibility = View.GONE
        content.removeAllViews()
        content.setPadding(0, 0, 0, 0)
        val stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val bgvUri = getSharedPreferences("karaokehome", MODE_PRIVATE).getString("bgv_uri", null)?.let { Uri.parse(it) }
        if (bgvUri != null) startBgv(stage, bgvUri)
        val shade = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.argb(if (bgvUri != null) 125 else 255, 4, 5, 24))
        }
        shade.addView(label("Select a Song", 25f, true, Color.rgb(255, 220, 0)).apply { gravity = Gravity.CENTER }, lp(-1, -2, 0, 4))
        numberDisplay = label(numberEntry.padStart(7, '0').takeLast(7), 38f, true, Color.rgb(255, 225, 0)).apply {
            gravity = Gravity.CENTER; setPadding(dp(18), dp(10), dp(18), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(12, 12, 18)); cornerRadius = dp(4).toFloat()
                setStroke(dp(2), Color.rgb(230, 190, 0))
            }
        }
        shade.addView(numberDisplay, lp(-1, -2, 0, 14))
        val phoneLayout = !isTelevisionUi()
        if (phoneLayout) {
            val entry = EditText(this).apply {
                hint = "Type song number"
                setHintTextColor(muted)
                setTextColor(white)
                textSize = 18f
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
                setSingleLine(true)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackgroundColor(Color.rgb(32, 28, 48))
                setText(numberEntry)
                setSelection(text.length)
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        numberEntry = s?.toString()?.filter { it.isDigit() }?.take(7) ?: ""
                        updateNumberDisplay()
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                        actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                        playEnteredNumber(); true
                    } else false
                }
            }
            shade.addView(entry, lp(-1, 52, 0, 10))
            val keypad = GridLayout(this).apply { columnCount = 3; rowCount = 4 }
            val keys = listOf("1","2","3","4","5","6","7","8","9","CLEAR","0","⌫")
            keys.forEach { key ->
                val button = actionButton(key, panel) {
                    when (key) {
                        "⌫" -> numberEntry = numberEntry.dropLast(1)
                        "CLEAR" -> numberEntry = ""
                        else -> if (numberEntry.length < 7) numberEntry += key
                    }
                    if (entry.text.toString() != numberEntry) {
                        entry.setText(numberEntry)
                        entry.setSelection(entry.text.length)
                    }
                    updateNumberDisplay()
                }.apply {
                    textSize = 20f
                    setTextColor(if (key == "CLEAR") Color.WHITE else Color.rgb(255, 225, 0))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(if (key == "CLEAR") Color.rgb(130, 22, 28) else Color.rgb(31, 33, 42))
                        cornerRadius = dp(5).toFloat()
                        setStroke(dp(1), Color.rgb(95, 95, 105))
                    }
                }
                val p = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                    width = 0; height = dp(42); setMargins(dp(3), dp(2), dp(3), dp(2))
                }
                keypad.addView(button, p)
            }
            shade.addView(keypad, lp(-1, -2, 0, 8))
            shade.addView(label("Touch a number or use your phone keyboard.", 11f, false, white).apply { gravity = Gravity.CENTER }, lp(-1, -2, 0, 4))
        } else {
            shade.addView(label("ENTER TRACK NUMBER  •  OK TO PLAY  •  GREEN FOR QUEUE", 13f, true, white).apply { gravity = Gravity.CENTER }, lp(-1, -2, 0, 14))
        }
        val transport = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        transport.addView(actionButton("SEARCH", Color.rgb(43, 47, 59)) { stopBgv(); showLibrary() }, weight())
        transport.addView(actionButton("PREV", Color.rgb(43, 47, 59)) { playPreviousSong() }, weight())
        transport.addView(actionButton("PLAY", red) { playEnteredNumber() }, weight())
        transport.addView(actionButton("NEXT", Color.rgb(43, 47, 59)) { playNextSong() }, weight())
        transport.addView(actionButton("STOP", Color.rgb(43, 47, 59)) { stopPlayback(); showLibrary() }, weight())
        shade.addView(transport, lp(-1, 44, 0, 5))

        val reservations = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        reservations.addView(actionButton("RES", Color.rgb(31, 83, 120)) {
            reserveEnteredNumber()
            numberEntry = ""
            updateNumberDisplay()
        }, weight())
        reservations.addView(actionButton("RSV LIST", Color.rgb(31, 83, 120)) { showQueue() }, weight())
        reservations.addView(actionButton("1st RSV", Color.rgb(31, 83, 120)) {
            if (queue.isEmpty()) message("Reservation list is empty.")
            else { val next = queue.removeAt(0); updateHeader(); playSong(next) }
        }, weight())
        reservations.addView(actionButton("CANCEL", Color.rgb(100, 28, 35)) {
            stopBgv()
            numberEntry = ""
            showLibrary()
        }, weight())
        shade.addView(reservations, lp(-1, 44, 0, 5))

        val toneControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        toneControls.addView(actionButton("KEY −", Color.rgb(43, 47, 59)) { adjustKaraokeSetting("key", -1) }, weight())
        toneControls.addView(actionButton("KEY +", Color.rgb(43, 47, 59)) { adjustKaraokeSetting("key", 1) }, weight())
        toneControls.addView(actionButton("TEMPO −", Color.rgb(43, 47, 59)) { adjustKaraokeSetting("tempo", -1) }, weight())
        toneControls.addView(actionButton("TEMPO +", Color.rgb(43, 47, 59)) { adjustKaraokeSetting("tempo", 1) }, weight())
        toneControls.addView(actionButton("MELODY", Color.rgb(43, 47, 59)) { toggleMelody() }, weight())
        shade.addView(toneControls, lp(-1, 40, 0, 5))
        shade.addView(actionButton("BACK TO HOME", Color.rgb(35, 30, 65)) { stopBgv(); numberEntry = ""; showHome() }, lp(-1, 40, 5))
        stage.addView(shade, FrameLayout.LayoutParams(-1, -1))
        content.minimumHeight = resources.displayMetrics.heightPixels
        content.addView(stage, LinearLayout.LayoutParams(-1, -1))
    }

    private fun showTelevisionKaraokeIdle() {
        numberScreenActive = false
        restoreShellChrome()
        enterKaraokeFullscreen()
        shellHeader?.visibility = View.GONE
        shellAccentLine?.visibility = View.GONE
        mainNavigation?.visibility = View.GONE
        content.setPadding(0, 0, 0, 0)
        content.minimumHeight = resources.displayMetrics.heightPixels
        content.removeAllViews()

        val stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val bgvUri = getSharedPreferences("karaokehome", MODE_PRIVATE)
            .getString("bgv_uri", null)?.let { Uri.parse(it) }
        if (bgvUri != null) startBgv(stage, bgvUri)

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(20), dp(28), dp(20))
            setBackgroundColor(Color.argb(if (bgvUri != null) 92 else 255, 4, 5, 18))
        }
        overlay.addView(label("ENTER SONG", 24f, true, Color.rgb(255, 220, 0)).apply {
            gravity = Gravity.CENTER
        }, lp(-1, -2, 0, 12))
        tvNumberDisplay = label(numberEntry.padStart(7, '0').takeLast(7), 52f, true, Color.rgb(255, 225, 0)).apply {
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(12), dp(22), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(12, 12, 18))
                cornerRadius = dp(4).toFloat()
                setStroke(dp(2), Color.rgb(230, 190, 0))
            }
        }
        overlay.addView(tvNumberDisplay, lp(-1, -2, 0, 18))
        overlay.addView(label("Use the TV remote number keys • OK to play • GREEN to reserve", 15f, true, white).apply {
            gravity = Gravity.CENTER
        }, lp(-1, -2, 0, 8))
        overlay.addView(label("RED: SEARCH     YELLOW: CHANGE BGV     BLUE: SOUNDFONT", 12f, false, white).apply {
            gravity = Gravity.CENTER
        }, lp(-1, -2))
        stage.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        content.addView(stage, LinearLayout.LayoutParams(-1, -1))
        headerStatus.text = "ENTER SONG  " + numberEntry.padStart(7, '0')
    }

    @Suppress("DEPRECATION")
    private fun enterKaraokeFullscreen() {
        if (isTelevisionUi()) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    private fun adjustKaraokeSetting(name: String, delta: Int) {
        val prefs = getSharedPreferences("karaokehome", MODE_PRIVATE)
        val key = "karaoke_" + name
        val current = prefs.getInt(key, 0)
        val next = if (name == "tempo") (current + delta * 5).coerceIn(-30, 30) else (current + delta).coerceIn(-6, 6)
        prefs.edit().putInt(key, next).apply()
        message(name.uppercase() + ": " + (if (next > 0) "+" else "") + next +
            (if (name == "tempo") "%" else " semitones") +
            ". This setting applies to the next MIDI/KAR song you play.")
    }

    private fun toggleMelody() {
        val prefs = getSharedPreferences("karaokehome", MODE_PRIVATE)
        val enabled = !prefs.getBoolean("karaoke_melody_enabled", true)
        prefs.edit().putBoolean("karaoke_melody_enabled", enabled).apply()
        message(if (enabled) "Melody guide track enabled for the next MIDI/KAR song." else "Melody guide track muted for the next MIDI/KAR song.")
    }

    private fun updateNumberDisplay() {
        numberDisplay?.text = numberEntry.padStart(7, '0').takeLast(7)
    }

    private fun playEnteredNumber() {
        val key = numberEntry.trimStart('0')
        val song = songs.firstOrNull { it.number.trimStart('0') == key || it.number == numberEntry }
        if (song == null) { message("Track number not found. Scan your song library first."); return }
        numberScreenActive = false
        stopBgv()
        activeSong = song
        playSong(song)
    }

    private fun reserveEnteredNumber() {
        val key = numberEntry.trimStart('0')
        val song = songs.firstOrNull { it.number.trimStart('0') == key || it.number == numberEntry }
        if (song == null) { message("Track number not found. Scan your song library first."); return }
        if (queue.none { sameSong(it, song) }) queue.add(song)
        updateHeader()
        Toast.makeText(this, "Reserved: ${song.title}", Toast.LENGTH_SHORT).show()
    }

    private fun startBgv(stage: FrameLayout, uri: Uri) {
        stopBgv()
        try {
            // TextureView stays in the normal view hierarchy so lyric overlays reliably render above it.
            val aspect = androidx.media3.ui.AspectRatioFrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM)
            }
            val texture = TextureView(this).apply { keepScreenOn = true }
            aspect.addView(texture, FrameLayout.LayoutParams(-1, -1))
            val player = ExoPlayer.Builder(this).build()
            player.volume = 0f
            player.repeatMode = Player.REPEAT_MODE_ONE
            bgvTextureView = texture
            bgvAspectContainer = aspect
            bgvPlayer = player
            stage.addView(aspect, 0, FrameLayout.LayoutParams(-1, -1))
            player.setVideoTextureView(texture)
            player.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    val ratio = if (videoSize.height == 0) 0f else
                        videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height.toFloat()
                    if (ratio > 0f) aspect.setAspectRatio(ratio)
                }
                override fun onPlayerError(error: PlaybackException) {
                    headerStatus.text = "BGV UNAVAILABLE"
                }
            })
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.playWhenReady = true
        } catch (_: Exception) {
            stopBgv()
            headerStatus.text = "BGV UNAVAILABLE"
        }
    }

    private fun stopBgv() {
        try { bgvTextureView?.let { texture -> bgvPlayer?.clearVideoTextureView(texture) } } catch (_: Exception) {}
        try { bgvPlayer?.stop() } catch (_: Exception) {}
        try { bgvPlayer?.release() } catch (_: Exception) {}
        bgvPlayer = null
        bgvTextureView = null
        bgvAspectContainer = null
    }

    private fun showQueue() {
        numberScreenActive = false
        restoreShellChrome()
        stopBgv()
        content.removeAllViews()
        content.addView(label("SONG RESERVATION QUEUE", 17f, true, white), lp(-1, -2, 4, 10))
        content.addView(label("${queue.size} reserved song(s)", 12f, false, muted), lp(-1, -2, 0, 10))
        queue.toList().forEachIndexed { index, song ->
            val row = card()
            row.addView(label("${song.number}  •  ${song.title}", 14f, true, white))
            row.addView(label(song.type, 11f, true, bright), lp(-1, -2, 5))
            val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            controls.addView(actionButton("↑", panel) { if (index > 0) { val item = queue.removeAt(index); queue.add(index - 1, item); showQueue() } }, weight())
            controls.addView(actionButton("↓", panel) { if (index < queue.lastIndex) { val item = queue.removeAt(index); queue.add(index + 1, item); showQueue() } }, weight())
            controls.addView(actionButton("PLAY", red) { activeSong = song; playSong(song) }, weight())
            controls.addView(actionButton("REMOVE", panel) { queue.remove(song); showQueue(); updateHeader() }, weight())
            row.addView(controls, lp(-1, 42, 8))
            content.addView(row, lp(-1, -2, 0, 8))
        }
        if (queue.isEmpty()) content.addView(label("Your reservation queue is empty.", 14f, false, muted), lp(-1, -2, 0, 16))
        content.addView(actionButton("CLEAR QUEUE", red) { queue.clear(); showQueue(); updateHeader() }, lp(-1, 46, 8))
        content.addView(actionButton("PLAY NEXT RESERVED SONG", Color.rgb(60, 35, 43)) {
            if (queue.isEmpty()) message("Queue is empty.") else { val next = queue.removeAt(0); showQueue(); activeSong = next; playSong(next); updateHeader() }
        }, lp(-1, 46, 8))
    }

    private fun showSettings() {
        numberScreenActive = false
        restoreShellChrome()
        stopBgv()
        content.removeAllViews()
        content.addView(label("SETTINGS", 20f, true, white), lp(-1, -2, 4, 10))
        val c = card()
        c.addView(label("LIBRARY", 11f, true, bright))
        c.addView(label("Select your existing SongHub song folder. KaraokeHome reads the same files without copying them or creating duplicates.", 13f, false, muted), lp(-1, -2, 6))
        c.addView(actionButton("USE EXISTING SONGHUB FOLDER", red) { chooseHomeFolderLocation() }, lp(-1, 46, 10))
        c.addView(actionButton("CHOOSE / RESCAN SONGS FOLDER", panel) { chooseFolder() }, lp(-1, 46, 8))
        c.addView(label("Shared library: existing SongHub folder • no duplicate media storage", 12f, false, muted), lp(-1, -2, 8))
        c.addView(actionButton("RESCAN CURRENT FOLDER", red) { rescanCurrentFolder() }, lp(-1, 46, 8))
        c.addView(View(this).apply { setBackgroundColor(divider) }, lp(-1, 1, 14, 14))
        c.addView(label("PHONE REMOTE CONNECTION", 11f, true, bright))
        c.addView(label("Connect your phone's KaraokeHome Smart Link to the same Wi-Fi network. Enter one of these TV IP addresses and use port 43821 if automatic discovery does not work.", 13f, false, muted), lp(-1, -2, 6))
        c.addView(label("TV IP ADDRESS: " + (localIpv4Addresses().ifEmpty { listOf("Not available — connect TV to Wi-Fi/Ethernet") }.joinToString("   •   ")), 17f, true, white), lp(-1, -2, 8))
        c.addView(label("Remote service: TCP 43821  •  Discovery: UDP 43822", 12f, true, bright), lp(-1, -2, 4))
        c.addView(actionButton("REFRESH IP ADDRESS", panel) { showSettings() }, lp(-1, 42, 8))
        c.addView(View(this).apply { setBackgroundColor(divider) }, lp(-1, 1, 14, 14))
        c.addView(label("PLAYBACK", 11f, true, bright))
        c.addView(label("MP4 video uses the device video decoder. MIDI/KAR uses the bundled SoundFont. For external lyrics, place a same-name .LRC or .TXT file beside the MIDI/KAR file. Timed lyric rendering and phone-to-TV pairing still need further work.", 13f, false, muted), lp(-1, -2, 6))
        c.addView(actionButton("PICK MP4 VIDEO", panel) { chooseVideoFile() }, lp(-1, 44, 10))
        c.addView(actionButton("PICK MIDI / KAR FILE", panel) { chooseMidiFile() }, lp(-1, 44, 8))
        c.addView(actionButton("STOP PLAYBACK", panel) { stopPlayback() }, lp(-1, 44, 8))
        content.addView(c)
        content.post { c.getChildAt(2)?.requestFocus() }
    }

    private fun scanLibraryOrChooseFolder() {
        val saved = getSharedPreferences("karaokehome", MODE_PRIVATE).getString("home_tree_uri", null)
        if (saved.isNullOrBlank()) chooseFolder() else rescanCurrentFolder()
    }

    private fun rescanCurrentFolder() {
        val saved = getSharedPreferences("karaokehome", MODE_PRIVATE).getString("home_tree_uri", null)
        if (saved.isNullOrBlank()) {
            message("No library folder selected yet. Choose SCAN LIBRARY FOLDER first.")
            chooseFolder()
            return
        }
        val tree = Uri.parse(saved)
        val permissionHeld = contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }
        if (!permissionHeld) {
            message("Android no longer grants access to the saved folder. Choose the library folder again.")
            chooseFolder()
            return
        }
        val oldSongs = songs.toList()
        songs.clear()
        try {
            scanTree(tree, DocumentsContract.getTreeDocumentId(tree), 0)
            assignTrackNumbers()
            saveCatalog()
            query = ""
            showLibrary()
            Toast.makeText(this, "Rescanned ${songs.size} karaoke files", Toast.LENGTH_LONG).show()
            if (songs.isEmpty()) message("No MP4, MIDI, or KAR files found. Select the folder that contains your songs.")
        } catch (e: Exception) {
            songs.clear()
            songs.addAll(oldSongs)
            message("Rescan failed: ${e.message ?: "could not read folder"}")
        }
    }

    private fun localIpv4Addresses(): List<String> {
        return try {
            val addresses = mutableListOf<String>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                if (!network.isUp || network.isLoopback) continue
                val ips = network.inetAddresses
                while (ips.hasMoreElements()) {
                    val address = ips.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) {
                        addresses.add(address.hostAddress ?: continue)
                    }
                }
            }
            addresses.distinct().sorted()
        } catch (_: Exception) { emptyList() }
    }

    private fun connectionInfoCard(): View {
        val c = card()
        c.addView(label("SMART LINK CONNECTION", 11f, true, bright))
        c.addView(label("TV IP: " + (localIpv4Addresses().firstOrNull() ?: "Connect TV to Wi-Fi or Ethernet"), 18f, true, white), lp(-1, -2, 5))
        c.addView(label("Port 43821  •  Phone and TV must use the same local network", 12f, false, muted), lp(-1, -2, 3))
        c.addView(actionButton("NETWORK / LIBRARY SETTINGS", panel) { showSettings() }, lp(-1, 42, 8))
        return c
    }

    private fun chooseVideoFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "video/*" }, REQUEST_PICK_VIDEO)
    }

    private fun chooseMidiFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/midi", "audio/x-midi", "application/x-midi", "application/octet-stream"))
        }, REQUEST_PICK_MIDI)
    }

    private fun playSong(song: Song) {
        activeSong = song
        midiPaused = false
        if (song.type == "MP4") playVideo(song)
        else playMidiSong(song)
    }

    /** Shared transport actions used by physical TV remotes and Smart Link commands. */
    private fun pausePlayback() {
        try { exoPlayer?.pause() } catch (_: Exception) {}
        if (midiPlayer != null) {
            lyricHandler.removeCallbacks(lyricHighlighter)
            try {
                midiPlayer?.pause()
                midiPaused = true
            } catch (_: Exception) {}
        }
    }

    private fun resumePlayback() {
        if (exoPlayer != null) {
            try { exoPlayer?.play() } catch (_: Exception) {}
            return
        }
        val song = activeSong ?: return
        // The current FluidSynth wrapper exposes pause but no reliable resume/seek API.
        // Restarting the MIDI/KAR file is safer than reporting a false successful resume.
        if (midiPlayer == null || midiPaused) {
            midiPaused = false
            playSong(song)
        }
    }

    private fun togglePlayback() {
        if (exoPlayer != null) {
            if (exoPlayer?.isPlaying == true) pausePlayback() else resumePlayback()
        } else if (midiPlayer != null && !midiPaused) {
            pausePlayback()
        } else {
            resumePlayback()
        }
    }

    private fun playNextSong() {
        val next = if (queue.isNotEmpty()) {
            queue.removeAt(0)
        } else {
            val currentIndex = activeSong?.let { current -> songs.indexOfFirst { sameSong(it, current) } } ?: -1
            if (songs.isEmpty()) null else songs[(currentIndex + 1).mod(songs.size)]
        }
        if (next == null) {
            message("No songs are available. Scan your library first.")
            return
        }
        updateHeader()
        playSong(next)
    }

    private fun playPreviousSong() {
        val currentIndex = activeSong?.let { current -> songs.indexOfFirst { sameSong(it, current) } } ?: -1
        if (songs.isEmpty()) {
            message("No songs are available. Scan your library first.")
            return
        }
        val previous = when {
            currentIndex > 0 -> songs[currentIndex - 1]
            currentIndex == 0 -> songs.last()
            else -> songs.first()
        }
        playSong(previous)
    }

    @Suppress("DEPRECATION")
    private fun playVideo(song: Song) {
        numberScreenActive = false
        tvNumberDisplay = null
        activeSong = song
        activeLyrics = ""
        stopBgv()
        stopMidi()
        safeStopVideo()
        enterKaraokeFullscreen()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        content.minimumHeight = resources.displayMetrics.heightPixels
        // TextureView renders reliably inside the app's scrolling shell on phones and TV.
        // PlayerView's default SurfaceView can be clipped/hidden when nested in a ScrollView.
        val aspectContainer = androidx.media3.ui.AspectRatioFrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT)
        }
        val texture = TextureView(this).apply { keepScreenOn = true }
        videoAspectContainer = aspectContainer
        videoTextureView = texture
        aspectContainer.addView(texture, FrameLayout.LayoutParams(-1, -1))
        root.addView(aspectContainer, FrameLayout.LayoutParams(-1, -1))
        content.removeAllViews()
        shellHeader?.visibility = View.GONE
        shellAccentLine?.visibility = View.GONE
        mainNavigation?.visibility = View.GONE
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        content.addView(root, LinearLayout.LayoutParams(-1, -1))
        headerStatus.text = "PREPARING VIDEO"
        try {
            val player = ExoPlayer.Builder(this).build()
            exoPlayer = player
            player.setVideoTextureView(texture)
            player.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    val ratio = if (videoSize.height == 0) 0f else
                        videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height.toFloat()
                    if (ratio > 0f) aspectContainer.setAspectRatio(ratio)
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> headerStatus.text = "PREPARING VIDEO"
                        Player.STATE_READY -> headerStatus.text = if (player.playWhenReady) "KTV PLAYING" else "VIDEO READY"
                        Player.STATE_ENDED -> {
                            if (queue.isNotEmpty()) {
                                val next = queue.removeAt(0)
                                updateHeader()
                                activeSong = next
                                playSong(next)
                            } else {
                                headerStatus.text = "FINISHED"
                                if (isTelevisionUi()) {
                                    safeStopVideo()
                                    activeSong = null
                                    showNumberPad()
                                }
                            }
                        }
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    headerStatus.text = "VIDEO ERROR"
                    message("This video could not be decoded. Try an MP4 encoded as H.264 video with AAC audio. Details: ${error.errorCodeName}")
                }
            })
            player.setMediaItem(MediaItem.fromUri(song.uri))
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            safeStopVideo()
            showLibrary()
            headerStatus.text = "VIDEO ERROR"
            message("Could not open video: ${e.message ?: "unsupported media"}")
        }
    }

    private fun safeStopVideo() {
        window.decorView.systemUiVisibility = 0
        try { videoTextureView?.let { texture -> exoPlayer?.clearVideoTextureView(texture) } } catch (_: Exception) {}
        try { exoPlayer?.stop() } catch (_: Exception) {}
        try { exoPlayer?.release() } catch (_: Exception) {}
        exoPlayer = null
        videoTextureView = null
        videoAspectContainer = null
        headerStatus.text = "READY"
    }

    private fun loadSiblingLyrics(song: Song): String? {
        return try {
            val tree = song.uri
            val documentId = DocumentsContract.getDocumentId(tree)
            val parentId = documentId.substringBeforeLast('/', "")
            if (parentId.isBlank()) return null
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            var lyricsUri: Uri? = null
            var cursor: Cursor? = null
            try {
                cursor = contentResolver.query(
                    children,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                    null, null, null
                )
                val base = song.title.substringBeforeLast('.', song.title).trim()
                while (cursor?.moveToNext() == true) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2) ?: ""
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (mime != DocumentsContract.Document.MIME_TYPE_DIR &&
                        (ext == "lrc" || ext == "txt") &&
                        name.substringBeforeLast('.', name).equals(base, true)
                    ) {
                        lyricsUri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        break
                    }
                }
            } finally {
                cursor?.close()
            }
            val found = lyricsUri ?: return null
            contentResolver.openInputStream(found)?.bufferedReader()?.use { reader ->
                reader.readText().take(12000).lineSequence()
                    .filter { it.isNotBlank() }
                    .map { line -> line.replace(Regex("^\\\\[[0-9:.]+\\\\]"), "").trim() }
                    .take(8)
                    .joinToString("\\n")
                    .takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun playMidiSong(song: Song) {
        numberScreenActive = false
        tvNumberDisplay = null
        stopBgv()
        safeStopVideo()
        stopMidi()
        enterKaraokeFullscreen()
        activeLyrics = ""
        try {
            val prefs = getSharedPreferences("karaokehome", MODE_PRIVATE)
            val customSfPath = prefs.getString("custom_soundfont_path", null)
            val sf = customSfPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
                ?: File(filesDir, "reyna-se.sf2").also { bundled ->
                    if (!bundled.exists() || bundled.length() == 0L) assets.open("reyna-se.sf2").use { input -> bundled.outputStream().use { output -> input.copyTo(output) } }
                }
            val safeName = song.number.ifBlank { song.title.hashCode().toString() }.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val sourceMidi = File(cacheDir, "song-$safeName-source.mid")
            val midi = File(cacheDir, "song-$safeName-play.mid")
            materializeMidi(song, sourceMidi)
            MidiTransform.apply(
                sourceMidi,
                midi,
                prefs.getInt("karaoke_key", 0),
                prefs.getInt("karaoke_tempo", 0),
                !prefs.getBoolean("karaoke_melody_enabled", true)
            )
            val player = MidiFilePlayer(sf.absolutePath, midi.absolutePath)
            midiPlayer = player

            val stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
            val bgvUri = prefs.getString("bgv_uri", null)?.let { Uri.parse(it) }
            if (bgvUri != null) startBgv(stage, bgvUri)

            val overlay = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(14), dp(18), dp(14))
                setBackgroundColor(Color.argb(if (bgvUri != null) 88 else 255, 5, 6, 16))
            }
            overlay.addView(label("NOW SINGING  •  ${song.type}", 11f, true, bright))
            overlay.addView(label(song.title, 22f, true, white), lp(-1, -2, 8))
            val timedFromMidi = MidiEmbeddedLyrics.extractTimed(midi)
            val embeddedLyrics = timedFromMidi.joinToString("") { it.text }.trim().takeIf { it.isNotBlank() }
            val savedLyrics = getCorrectedLyrics(song)?.takeIf { it.isNotBlank() }
                ?: loadSiblingLyrics(song)?.takeIf { it.isNotBlank() }
                ?: embeddedLyrics
            activeLyrics = savedLyrics.orEmpty()
            timedLyrics = if (!embeddedLyrics.isNullOrBlank() && savedLyrics == embeddedLyrics) timedFromMidi else emptyList()
            val lyricsView = label(savedLyrics?.takeIf { it.isNotBlank() }
                ?: "No embedded lyrics were found in this MIDI/KAR file. You can still add or correct lyrics here.", 
                24f, true, if (savedLyrics.isNullOrBlank()) muted else white).apply {
                typeface = karaokeLyricsTypeface()
                gravity = Gravity.CENTER
                maxLines = 4
                ellipsize = android.text.TextUtils.TruncateAt.END
                setShadowLayer(4f, 1f, 2f, Color.BLACK)
                setLineSpacing(dp(5).toFloat(), 1.05f)
            }
            lyricsPlaybackView = lyricsView
            overlay.addView(lyricsView, lp(-1, -2, 12))
            overlay.addView(actionButton("EDIT / CORRECT LYRICS", Color.rgb(45, 35, 75)) { showLyricsEditor(song) }, lp(-1, 44, 12))
            val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            controls.addView(actionButton("Ⅱ PAUSE", panel) {
                pausePlayback()
            }, weight())
            controls.addView(actionButton("▶ RESTART", red) {
                try { player.stop(); player.close() } catch (_: Exception) {}
                midiPlayer = null
                playMidiSong(song)
            }, weight())
            controls.addView(actionButton("■ STOP", panel) { stopMidi(); stopBgv(); showLibrary() }, weight())
            overlay.addView(controls, lp(-1, 44, 8))
            overlay.addView(actionButton("＋ RESERVE THIS SONG", Color.rgb(45, 20, 27)) {
                if (queue.none { sameSong(it, song) }) queue.add(song)
                updateHeader(); Toast.makeText(this, "Added to queue", Toast.LENGTH_SHORT).show()
            }, lp(-1, 42, 8))
            stage.addView(overlay, FrameLayout.LayoutParams(-1, -1))
            content.removeAllViews()
            shellHeader?.visibility = View.GONE
            shellAccentLine?.visibility = View.GONE
            mainNavigation?.visibility = View.GONE
            content.minimumHeight = resources.displayMetrics.heightPixels
            content.addView(stage, LinearLayout.LayoutParams(-1, -1))
            lyricStartedAt = SystemClock.elapsedRealtime()
            lyricHandler.removeCallbacks(lyricHighlighter)
            if (timedLyrics.isNotEmpty()) lyricHandler.post(lyricHighlighter)
            player.play {
                runOnUiThread {
                    lyricHandler.removeCallbacks(lyricHighlighter)
                    lyricsPlaybackView = null
                    timedLyrics = emptyList()
                    headerStatus.text = "FINISHED"
                    try { player.close() } catch (_: Exception) {}
                    if (midiPlayer === player) midiPlayer = null
                    stopBgv()
                    if (queue.isNotEmpty()) {
                        val next = queue.removeAt(0)
                        updateHeader()
                        activeSong = next
                        playSong(next)
                    } else if (isTelevisionUi()) {
                        activeSong = null
                        showNumberPad()
                    }
                }
            }
            headerStatus.text = if (bgvUri != null) "PLAYING MIDI • BGV" else "PLAYING MIDI"
        } catch (e: Exception) {
            stopBgv()
            message("Could not play this MIDI/KAR file: ${e.message ?: "unsupported file"}")
        }
    }

    // Corrected lyrics are stored separately from the original MIDI/KAR file so the source is never overwritten.
    private fun lyricsPreferenceKey(song: Song): String = "lyrics_override:" + song.uri.toString() + (song.zipEntry?.let { "#zip:" + it } ?: "")

    private fun getCorrectedLyrics(song: Song): String? =
        getSharedPreferences("karaokehome_lyrics", MODE_PRIVATE).getString(lyricsPreferenceKey(song), null)

    private fun showLyricsEditor(song: Song) {
        val editor = EditText(this).apply {
            setText(getCorrectedLyrics(song) ?: "")
            hint = "Paste or type corrected lyrics here…"
            setTextColor(white)
            setHintTextColor(muted)
            textSize = 16f
            gravity = Gravity.TOP or Gravity.START
            minLines = 8
            maxLines = 18
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(panel)
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
            addView(label("These corrections are saved separately; the original MIDI/KAR file is not changed.", 12f, false, muted), lp(-1, -2, 0, 8))
            addView(editor, lp(-1, -2))
        }
        AlertDialog.Builder(this)
            .setTitle("Edit lyrics · ${song.title}")
            .setView(wrapper)
            .setPositiveButton("SAVE") { _, _ ->
                val text = editor.text?.toString()?.trim().orEmpty()
                getSharedPreferences("karaokehome_lyrics", MODE_PRIVATE).edit()
                    .putString(lyricsPreferenceKey(song), text).apply()
                Toast.makeText(this, if (text.isEmpty()) "Lyrics cleared" else "Corrected lyrics saved", Toast.LENGTH_SHORT).show()
                playMidiSong(song)
            }
            .setNeutralButton("CLEAR") { _, _ ->
                getSharedPreferences("karaokehome_lyrics", MODE_PRIVATE).edit().remove(lyricsPreferenceKey(song)).apply()
                Toast.makeText(this, "Saved correction removed", Toast.LENGTH_SHORT).show()
                playMidiSong(song)
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun renderTimedLyrics() {
        val events = timedLyrics
        val view = lyricsPlaybackView ?: return
        if (events.isEmpty()) return
        val elapsed = (SystemClock.elapsedRealtime() - lyricStartedAt).coerceAtLeast(0L)
        // Do not highlight the first syllable during a MIDI intro before its event time.
        val active = events.indexOfLast { it.timeMs <= elapsed }
        val first = if (active < 0) 0 else (active - 5).coerceAtLeast(0)
        val last = if (active < 0) minOf(events.lastIndex, 15) else (active + 10).coerceAtMost(events.lastIndex)
        val rendered = android.text.SpannableStringBuilder()
        for (i in first..last) {
            val chunk = events[i].text
            val begin = rendered.length
            rendered.append(chunk)
            if (i == active && rendered.length > begin) {
                rendered.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(255, 224, 0)),
                    begin, rendered.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                rendered.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    begin, rendered.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                rendered.setSpan(android.text.style.BackgroundColorSpan(Color.argb(105, 255, 210, 0)),
                    begin, rendered.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        view.text = rendered
    }

    private fun stopPlayback() {
        activeLyrics = ""
        stopBgv()
        midiPaused = false
        numberScreenActive = false
        safeStopVideo()
        stopMidi()
        activeSong = null
        headerStatus.text = "READY"
    }

    private fun stopMidi() {
        lyricHandler.removeCallbacks(lyricHighlighter)
        timedLyrics = emptyList()
        lyricsPlaybackView = null
        val player = midiPlayer
        midiPlayer = null
        try { player?.stop() } catch (_: Exception) {}
        try { player?.close() } catch (_: Exception) {}
    }

    private fun updateHeader() { if (::headerStatus.isInitialized) headerStatus.text = "QUEUE ${queue.size}" }

    private fun restoreShellChrome() {
        window.decorView.systemUiVisibility = 0
        if (!isTelevisionUi()) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        tvNumberDisplay = null
        shellHeader?.visibility = View.VISIBLE
        shellAccentLine?.visibility = View.VISIBLE
        mainNavigation?.visibility = View.VISIBLE
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)); setBackgroundColor(panel)
    }
    private fun karaokeLyricsTypeface(): android.graphics.Typeface {
        return try {
            android.graphics.Typeface.createFromAsset(assets, "RadioCanada[wdth,wght].ttf")
        } catch (_: Exception) {
            android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        }
    }

    private fun label(value: String, size: Float, bold: Boolean, color: Int) = TextView(this).apply {
        text = value
        textSize = if (resources.configuration.screenWidthDp < 600) maxOf(size, 12f) else size
        setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun actionButton(value: String, color: Int, action: () -> Unit) = Button(this).apply {
        text = value; textSize = if (resources.configuration.screenWidthDp < 600) 14f else 12f; isAllCaps = false; setTextColor(white); setBackgroundColor(color)
        isFocusable = true; isClickable = true
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setOnFocusChangeListener { view, focused ->
            view.setBackgroundColor(if (focused) Color.rgb(235, 45, 70) else color)
            view.scaleX = if (focused) 1.035f else 1f
            view.scaleY = if (focused) 1.035f else 1f
        }
        setOnClickListener { action() }
    }
    private fun navButton(value: String, action: () -> Unit) = actionButton(value, Color.rgb(35, 13, 19), action)
    private fun lp(w: Int, h: Int, top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
    private fun weight() = LinearLayout.LayoutParams(0, dp(42), 1f)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun message(value: String) { AlertDialog.Builder(this).setMessage(value).setPositiveButton("OK", null).show() }

    override fun onDestroy() { remoteServer?.stop(); remoteServer = null; if (::headerStatus.isInitialized) stopPlayback(); super.onDestroy() }

    companion object {
        private const val REQUEST_FOLDER = 201
        private const val REQUEST_HOME_FOLDER = 204
        private const val REQUEST_PICK_VIDEO = 202
        private const val REQUEST_PICK_MIDI = 203
        private const val REQUEST_PICK_SOUNDFONT = 205
        private const val REQUEST_PICK_BGV = 206
    }
}
