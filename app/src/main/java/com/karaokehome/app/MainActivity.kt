package com.karaokehome.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import dev.kotlinds.fluidsynthkmp.MidiFilePlayer
import java.io.File

class MainActivity : Activity() {
    private data class Song(val number: String, val title: String, val uri: Uri, val type: String)
    private val songs = mutableListOf<Song>()
    private val queue = mutableListOf<Song>()
    private var activeSong: Song? = null
    private var midiPlayer: MidiFilePlayer? = null
    private var midiUri: Uri? = null
    private var videoUri: Uri? = null
    private var filter = "ALL"
    private var query = ""
    private var numberEntry = ""
    private var numberDisplay: TextView? = null
    private var numberScreenActive = false
    private var bgvView: VideoView? = null
    private lateinit var content: LinearLayout
    private lateinit var headerStatus: TextView
    private lateinit var video: VideoView
    private val bg = Color.rgb(8, 8, 12)
    private val panel = Color.rgb(19, 19, 25)
    private val red = Color.rgb(224, 18, 39)
    private val bright = Color.rgb(255, 45, 60)
    private val white = Color.rgb(246, 246, 250)
    private val muted = Color.rgb(164, 165, 177)
    private val divider = Color.rgb(55, 35, 42)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildShell()
        showHome()
    }

    // Android TV remotes send standard DPAD/numeric/color-button key codes on many devices.
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        when (keyCode) {
            in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> {
                val digit = (keyCode - android.view.KeyEvent.KEYCODE_0).toString()
                numberEntry = (numberEntry + digit).take(7)
                if (numberScreenActive) updateNumberDisplay() else showNumberPad()
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
                showQueue()
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
            android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (numberEntry.isNotEmpty()) {
                    val song = songs.firstOrNull { it.number.trimStart('0') == numberEntry.trimStart('0') || it.number == numberEntry }
                    if (song != null) { activeSong = song; playSong(song); return true }
                    message("Track number not found.")
                    return true
                }
            }
            android.view.KeyEvent.KEYCODE_DEL, android.view.KeyEvent.KEYCODE_FORWARD_DEL -> {
                if (numberEntry.isNotEmpty()) {
                    numberEntry = numberEntry.dropLast(1)
                    if (numberScreenActive) updateNumberDisplay() else showNumberPad()
                    return true
                }
            }
            android.view.KeyEvent.KEYCODE_ESCAPE, android.view.KeyEvent.KEYCODE_BACK -> {
                if (numberEntry.isNotEmpty()) { numberEntry = ""; if (numberScreenActive) updateNumberDisplay() else showNumberPad(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showBgvOptions() {
        AlertDialog.Builder(this)
            .setTitle("Background video (BGV)")
            .setMessage("BGV playback for MIDI/KAR is not implemented yet. You can choose a video file now, but it will not be composited behind MIDI lyrics until the BGV player is added.")
            .setPositiveButton("CHOOSE VIDEO") { _, _ ->
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "video/*"
                }, REQUEST_PICK_BGV)
            }
            .setNeutralButton("DISABLE BGV") { _, _ ->
                getSharedPreferences("karaokehome", MODE_PRIVATE).edit().remove("bgv_uri").apply()
                message("BGV preference cleared. MIDI/KAR background-video playback is not available in this build yet.")
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
        root.addView(header)
        root.addView(View(this).apply { setBackgroundColor(red) }, LinearLayout.LayoutParams(-1, dp(2)))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        nav.addView(navButton("SONGS") { showLibrary() }, weight())
        nav.addView(navButton("TRACK #") { showNumberPad() }, weight())
        nav.addView(navButton("QUEUE ${queue.size}") { showQueue() }, weight())
        nav.addView(navButton("SETTINGS") { showSettings() }, weight())
        root.addView(nav)
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(16)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun showHome() {
        numberScreenActive = false
        content.removeAllViews()
        content.setPadding(dp(24), dp(18), dp(24), dp(20))
        val grid = GridLayout(this).apply { columnCount = 3; rowCount = 3; useDefaultMargins = true }
        fun tile(title: String, symbol: String, color: Int, row: Int, col: Int, span: Int = 1, action: () -> Unit) {
            val b = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(12), dp(18), dp(12))
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    intArrayOf(color, blend(color, Color.WHITE, 0.20f))
                ).apply { cornerRadius = dp(22).toFloat(); setStroke(dp(2), Color.argb(180, 235, 190, 225)) }
                isFocusable = true; isClickable = true; setOnClickListener { action() }
                addView(TextView(this@MainActivity).apply { text = symbol; textSize = 42f; setTextColor(white); gravity = Gravity.CENTER })
                addView(TextView(this@MainActivity).apply { text = title; textSize = 18f; setTextColor(white); gravity = Gravity.CENTER; setTypeface(typeface, android.graphics.Typeface.BOLD) })
            }
            val params = GridLayout.LayoutParams(GridLayout.spec(row, 1f), GridLayout.spec(col, span, 1f)).apply {
                width = 0; height = dp(if (span == 2) 170 else 145); setMargins(dp(5), dp(5), dp(5), dp(5))
            }
            grid.addView(b, params)
        }
        tile("ENTER KARAOKE", "♫", Color.rgb(155, 25, 90), 0, 0, 2) { numberEntry = ""; showNumberPad() }
        tile("MEDIA PLAYER", "▣", Color.rgb(105, 40, 145), 0, 2) { showLibrary() }
        tile("RANKINGS", "🏆", Color.rgb(30, 80, 155), 1, 2) { showRankings() }
        tile("SONG SEARCH", "♫⌕", Color.rgb(160, 55, 40), 2, 0) { showLibrary() }
        tile("ARTIST SEARCH", "♟⌕", Color.rgb(170, 120, 35), 2, 1) { showLibrary() }
        tile("NEW SONGS", "♫", Color.rgb(25, 105, 65), 2, 2) { showLibrary() }
        // Keep the reference's colorful tile layout while offering simple access to settings.
        content.addView(TextView(this).apply {
            text = "KaraokeHome"; textSize = 30f; setTextColor(white); gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(4), 0, dp(10))
        }, lp(-1, -2))
        content.addView(grid, lp(-1, -2))
        val footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        footer.addView(actionButton("⚙ SETTINGS", panel) { showSettings() }, weight())
        footer.addView(actionButton("SONG LIBRARY", panel) { showLibrary() }, weight())
        footer.addView(actionButton("RESERVED SONGS", panel) { showQueue() }, weight())
        content.addView(footer, lp(-1, 48, 8))
        content.setPadding(dp(12), dp(8), dp(12), dp(12))
    }

    private fun blend(a: Int, b: Int, fraction: Float): Int {
        val inv = 1f - fraction
        return Color.rgb((Color.red(a) * inv + Color.red(b) * fraction).toInt(),
            (Color.green(a) * inv + Color.green(b) * fraction).toInt(),
            (Color.blue(a) * inv + Color.blue(b) * fraction).toInt())
    }

    private fun showRankings() {
        numberScreenActive = false
        content.removeAllViews()
        content.addView(label("RANKINGS", 24f, true, white), lp(-1, -2, 8, 12))
        content.addView(label("Ranking statistics are not available yet. Start with the song library and reservation queue.", 16f, false, muted), lp(-1, -2, 0, 12))
        content.addView(actionButton("BACK HOME", red) { showHome() }, lp(-1, 48, 8))
    }

    private fun showLibrary() {
        numberScreenActive = false
        content.setPadding(dp(12), dp(4), dp(12), dp(16))
    private fun renderSongRows() {
        val rows = content.findViewWithTag<LinearLayout>("songRows") ?: return
        rows.removeAllViews()
        val filtered = songs.filter { (filter == "ALL" || it.type == filter) && (query.isBlank() || it.title.contains(query, true) || it.number.contains(query)) }
        filtered.forEachIndexed { i, song ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(11), dp(8), dp(11)); setBackgroundColor(if (i % 2 == 0) panel else Color.rgb(14, 14, 19))
                isFocusable = true; isClickable = true
                setOnClickListener { activeSong = song; playSong(song) }
                setOnLongClickListener { if (queue.none { it.uri == song.uri }) queue.add(song); updateHeader(); Toast.makeText(this@MainActivity, "Reserved: ${song.title}", Toast.LENGTH_SHORT).show(); true }
            }
            row.addView(label(song.number, 11f, true, bright), LinearLayout.LayoutParams(dp(60), -2))
            row.addView(label(song.title, 13f, false, white), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(label(song.type, 10f, true, if (song.type == "MP4") Color.rgb(80, 190, 255) else Color.rgb(80, 220, 165)), LinearLayout.LayoutParams(dp(42), -2))
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
            scanTree(uri, DocumentsContract.getTreeDocumentId(uri), 0)
            songs.sortBy { it.title.lowercase() }
            for (i in songs.indices) songs[i] = songs[i].copy(number = "%06d".format(i + 1))
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
            getSharedPreferences("karaokehome", MODE_PRIVATE).edit().putString("bgv_uri", uri.toString()).apply()
            message("BGV file saved as a preference. Actual MIDI/KAR background-video playback is not implemented yet.")
        }
    }

    private fun scanTree(tree: Uri, parentId: String, depth: Int) {
        if (depth > 9 || songs.size >= 15000) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)
            while (cursor?.moveToNext() == true) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) scanTree(tree, id, depth + 1)
                else {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val type = when (ext) { "mp4", "m4v" -> "MP4"; "mid", "midi" -> "MIDI"; "kar" -> "KAR"; else -> null }
                    if (type != null) songs.add(Song("", name.substringBeforeLast('.', name), DocumentsContract.buildDocumentUriUsingTree(tree, id), type))
                }
            }
        } catch (_: Exception) {
        } finally { cursor?.close() }
    }

    private fun showNumberPad() {
        numberScreenActive = true
        content.removeAllViews()
        content.setPadding(0, 0, 0, 0)
        val stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val bgvUri = getSharedPreferences("karaokehome", MODE_PRIVATE).getString("bgv_uri", null)?.let { Uri.parse(it) }
        if (bgvUri != null) {
            val bgv = VideoView(this).apply { setBackgroundColor(Color.BLACK); keepScreenOn = true }
            bgvView = bgv
            stage.addView(bgv, FrameLayout.LayoutParams(-1, -1))
            bgv.setOnPreparedListener { mp -> mp.isLooping = true; bgv.start(); headerStatus.text = "BGV PLAYING" }
            bgv.setOnErrorListener { _, _, _ -> headerStatus.text = "BGV UNAVAILABLE"; true }
            try { bgv.setVideoURI(bgvUri) } catch (_: Exception) { headerStatus.text = "BGV UNAVAILABLE" }
        }
        val shade = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(24))
            setBackgroundColor(Color.argb(if (bgvUri != null) 125 else 255, 4, 5, 24))
        }
        shade.addView(label("ENTER SONG", 30f, true, white).apply { gravity = Gravity.CENTER }, lp(-1, -2, 0, 14))
        numberDisplay = label(numberEntry.padEnd(7, '0').take(7), 46f, true, white).apply {
            gravity = Gravity.CENTER; setPadding(dp(18), dp(10), dp(18), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(190, 15, 13, 40)); cornerRadius = dp(12).toFloat()
                setStroke(dp(2), Color.rgb(220, 100, 175))
            }
        }
        shade.addView(numberDisplay, lp(-1, -2, 0, 14))
        shade.addView(label("ENTER TRACK NUMBER  •  OK TO PLAY  •  GREEN FOR QUEUE", 13f, true, white).apply { gravity = Gravity.CENTER }, lp(-1, -2, 0, 14))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        buttons.addView(actionButton("⌫", panel) { if (numberEntry.isNotEmpty()) numberEntry = numberEntry.dropLast(1); updateNumberDisplay() }, weight())
        buttons.addView(actionButton("CLEAR", panel) { numberEntry = ""; updateNumberDisplay() }, weight())
        buttons.addView(actionButton("PLAY", red) { playEnteredNumber() }, weight())
        buttons.addView(actionButton("RESERVE", Color.rgb(40, 90, 150)) { reserveEnteredNumber() }, weight())
        shade.addView(buttons, lp(-1, 50))
        shade.addView(actionButton("BACK TO HOME", Color.rgb(35, 30, 65)) { stopBgv(); numberEntry = ""; showHome() }, lp(-1, 44, 10))
        stage.addView(shade, FrameLayout.LayoutParams(-1, -1))
        content.addView(stage, LinearLayout.LayoutParams(-1, -1))
    }

    private fun updateNumberDisplay() {
        numberDisplay?.text = numberEntry.padEnd(7, '0').take(7)
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
        if (queue.none { it.uri == song.uri }) queue.add(song)
        updateHeader()
        Toast.makeText(this, "Reserved: ${song.title}", Toast.LENGTH_SHORT).show()
    }

    private fun stopBgv() {
        try { bgvView?.stopPlayback() } catch (_: Exception) {}
        bgvView = null
    }

    private fun showQueue() {
        numberScreenActive = false
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
        stopBgv()
        content.removeAllViews()
        content.addView(label("SETTINGS", 20f, true, white), lp(-1, -2, 4, 10))
        val c = card()
        c.addView(label("LIBRARY", 11f, true, bright))
        c.addView(label("Select your existing SongHub song folder. KaraokeHome reads the same files without copying them or creating duplicates.", 13f, false, muted), lp(-1, -2, 6))
        c.addView(actionButton("USE EXISTING SONGHUB FOLDER", red) { chooseHomeFolderLocation() }, lp(-1, 46, 10))
        c.addView(actionButton("CHOOSE / RESCAN SONGS FOLDER", panel) { chooseFolder() }, lp(-1, 46, 8))
        c.addView(label("Shared library: existing SongHub folder • no duplicate media storage", 12f, false, muted), lp(-1, -2, 8))
        c.addView(View(this).apply { setBackgroundColor(divider) }, lp(-1, 1, 14, 14))
        c.addView(label("PLAYBACK", 11f, true, bright))
        c.addView(label("MP4 video: Android-supported video/audio codecs. MIDI/KAR: bundled SoundFont playback. Lyrics, scoring, microphone mixing, separate BGV overlays, and network phone-to-TV pairing are not implemented yet.", 13f, false, muted), lp(-1, -2, 6))
        c.addView(actionButton("PICK MP4 VIDEO", panel) { chooseVideoFile() }, lp(-1, 44, 10))
        c.addView(actionButton("PICK MIDI / KAR FILE", panel) { chooseMidiFile() }, lp(-1, 44, 8))
        c.addView(actionButton("STOP PLAYBACK", panel) { stopPlayback() }, lp(-1, 44, 8))
        content.addView(c)
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
        if (song.type == "MP4") playVideo(song)
        else playMidiSong(song)
    }

    private fun playVideo(song: Song) {
        numberScreenActive = false
        stopBgv()
        stopMidi()
        try {
            if (videoInitialized()) {
                try { video.stopPlayback() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(label("KTV MODE  •  TRACK ${song.number}", 12f, true, bright), lp(-1, -2, 0, 4))
        root.addView(label(song.title, 18f, true, white), lp(-1, -2, 0, 6))
        val stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = VideoView(this).apply { setBackgroundColor(Color.BLACK); keepScreenOn = true }
        stage.addView(video, FrameLayout.LayoutParams(-1, -1))
        val lyricOverlay = TextView(this).apply {
            text = "KARAOKE MODE\\n\\nIf lyrics are available, place a matching .lrc or .txt file beside this video."
            textSize = 19f
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setTextColor(Color.WHITE)
            setShadowLayer(5f, 2f, 2f, Color.BLACK)
            setBackgroundColor(Color.argb(100, 0, 0, 0))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        lyricOverlay.text = loadSiblingLyrics(song)
            ?: "NO LYRICS FOUND\\nAdd a matching .lrc or .txt file beside this song."
        stage.addView(lyricOverlay, FrameLayout.LayoutParams(-1, dp(90), Gravity.BOTTOM))
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(actionButton("LIBRARY", panel) { safeStopVideo(); showLibrary() }, weight())
        controls.addView(actionButton("PAUSE", panel) { try { if (video.isPlaying) video.pause() } catch (_: Exception) {} }, weight())
        controls.addView(actionButton("PLAY", red) { try { video.start() } catch (_: Exception) { message("Video could not start. Try selecting the file again.") } }, weight())
        controls.addView(actionButton("STOP", panel) { safeStopVideo(); showLibrary() }, weight())
        root.addView(controls, lp(-1, 46, 6))
        root.addView(actionButton("RESERVE THIS SONG", Color.rgb(45, 20, 27)) {
            if (queue.none { it.uri == song.uri }) queue.add(song)
            updateHeader()
            Toast.makeText(this, "Added to queue", Toast.LENGTH_SHORT).show()
        }, lp(-1, 42, 6))
        content.removeAllViews()
        content.addView(root, LinearLayout.LayoutParams(-1, -1))
        headerStatus.text = "PREPARING VIDEO"
        video.setOnPreparedListener { mp ->
            try {
                mp.isLooping = false
                video.start()
                headerStatus.text = "KTV PLAYING"
            } catch (e: Exception) {
                headerStatus.text = "VIDEO ERROR"
                message("Video could not start: ${e.message ?: "unsupported media"}")
            }
        }
        video.setOnCompletionListener { headerStatus.text = "FINISHED" }
        video.setOnErrorListener { _, what, extra ->
            headerStatus.text = "VIDEO ERROR"
            message("Android could not decode this MP4 (error $what/$extra). Try an H.264 video with AAC audio. If the app closes entirely, send me the phone model and Android version.")
            true
        }
        try {
            video.setVideoURI(song.uri)
        } catch (e: Exception) {
            headerStatus.text = "VIDEO ERROR"
            message("Could not open video: ${e.message ?: "unknown error"}")
        }
    }

    private fun videoInitialized(): Boolean = ::video.isInitialized

    private fun safeStopVideo() {
        try { if (::video.isInitialized) video.stopPlayback() } catch (_: Exception) {}
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
        stopBgv()
        stopMidi()
        try {
            val prefs = getSharedPreferences("karaokehome", MODE_PRIVATE)
            val customSfPath = prefs.getString("custom_soundfont_path", null)
            val sf = customSfPath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
                ?: File(filesDir, "reyna-se.sf2").also { bundled ->
                    if (!bundled.exists() || bundled.length() == 0L) assets.open("reyna-se.sf2").use { input -> bundled.outputStream().use { output -> input.copyTo(output) } }
                }
            val midi = File(cacheDir, "song-${song.number}.mid")
            contentResolver.openInputStream(song.uri).use { input ->
                requireNotNull(input) { "File could not be read" }
                midi.outputStream().use { output -> input.copyTo(output) }
            }
            val player = MidiFilePlayer(sf.absolutePath, midi.absolutePath)
            midiPlayer = player
            content.removeAllViews()
            val c = card()
            c.addView(label("NOW PLAYING  •  ${song.type}", 11f, true, bright))
            c.addView(label(song.title, 20f, true, white), lp(-1, -2, 8))
            val savedLyrics = getCorrectedLyrics(song)
            c.addView(label(savedLyrics?.takeIf { it.isNotBlank() } ?: "MIDI instrument playback via bundled SoundFont. Embedded KAR lyric extraction is not available yet; you can save corrected lyrics for this song below.", 13f, false, if (savedLyrics.isNullOrBlank()) muted else white), lp(-1, -2, 8))
            c.addView(actionButton("EDIT / CORRECT LYRICS", Color.rgb(45, 35, 75)) { showLyricsEditor(song) }, lp(-1, 46, 10))
            c.addView(actionButton("Ⅱ PAUSE", panel) { try { player.pause() } catch (_: Exception) {} }, lp(-1, 46, 8))
            c.addView(actionButton("▶ RESTART / PLAY", red) {
                try { player.stop(); player.close() } catch (_: Exception) {}
                midiPlayer = null; playMidiSong(song)
            }, lp(-1, 46, 8))
            c.addView(actionButton("■ STOP", panel) { stopMidi(); showLibrary() }, lp(-1, 46, 8))
            c.addView(actionButton("＋ RESERVE THIS SONG", Color.rgb(45, 20, 27)) {
                if (queue.none { it.uri == song.uri }) queue.add(song)
                updateHeader(); Toast.makeText(this, "Added to queue", Toast.LENGTH_SHORT).show()
            }, lp(-1, 46, 8))
            content.addView(c)
            player.play { runOnUiThread { headerStatus.text = "FINISHED"; try { player.close() } catch (_: Exception) {}; if (midiPlayer === player) midiPlayer = null } }
            headerStatus.text = "PLAYING MIDI"
        } catch (e: Exception) { message("Could not play this MIDI/KAR file: ${e.message ?: "unsupported file"}") }
    }

    // Corrected lyrics are stored separately from the original MIDI/KAR file so the source is never overwritten.
    private fun lyricsPreferenceKey(song: Song): String = "lyrics_override:" + song.uri.toString()

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

    private fun stopPlayback() {
        stopBgv()
        numberScreenActive = false
        try { if (::video.isInitialized) video.stopPlayback() } catch (_: Exception) {}
        stopMidi()
        headerStatus.text = "READY"
    }

    private fun stopMidi() {
        val player = midiPlayer
        midiPlayer = null
        try { player?.stop() } catch (_: Exception) {}
        try { player?.close() } catch (_: Exception) {}
    }

    private fun updateHeader() { if (::headerStatus.isInitialized) headerStatus.text = "QUEUE ${queue.size}" }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)); setBackgroundColor(panel)
    }
    private fun label(value: String, size: Float, bold: Boolean, color: Int) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun actionButton(value: String, color: Int, action: () -> Unit) = Button(this).apply {
        text = value; textSize = 12f; isAllCaps = false; setTextColor(white); setBackgroundColor(color)
        isFocusable = true; setOnClickListener { action() }
    }
    private fun navButton(value: String, action: () -> Unit) = actionButton(value, Color.rgb(35, 13, 19), action)
    private fun lp(w: Int, h: Int, top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
    private fun weight() = LinearLayout.LayoutParams(0, dp(42), 1f)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun message(value: String) { AlertDialog.Builder(this).setMessage(value).setPositiveButton("OK", null).show() }

    override fun onDestroy() { stopPlayback(); super.onDestroy() }

    companion object {
        private const val REQUEST_FOLDER = 201
        private const val REQUEST_HOME_FOLDER = 204
        private const val REQUEST_PICK_VIDEO = 202
        private const val REQUEST_PICK_MIDI = 203
        private const val REQUEST_PICK_SOUNDFONT = 205
        private const val REQUEST_PICK_BGV = 206
    }
}
