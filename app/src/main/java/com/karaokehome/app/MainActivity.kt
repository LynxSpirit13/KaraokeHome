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
        showLibrary()
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

    private fun showLibrary() {
        content.removeAllViews()
        val hero = card()
        hero.addView(label("YOUR HOME KARAOKE SYSTEM", 10f, true, bright))
        hero.addView(label("Pick a song. Grab the mic. Sing together.", 18f, true, white), lp(-1, -2, 8))
        hero.addView(label("One library for your video and classic karaoke files.", 12f, false, muted), lp(-1, -2, 5))
        hero.addView(actionButton("＋  SCAN LIBRARY FOLDER", red) { chooseFolder() }, lp(-1, 46, 12))
        content.addView(hero, lp(-1, -2, 0, 10))

        val search = EditText(this).apply {
            hint = "Search songs or artists…"; setHintTextColor(muted); setTextColor(white); textSize = 14f
            setSingleLine(true); setPadding(dp(12), 0, dp(12), 0); setBackgroundColor(panel)
            setText(query)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s?.toString() ?: ""; renderSongRows() }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        content.addView(search, lp(-1, 46, 0, 8))
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
        content.removeAllViews()
        content.addView(label("KARAOKE MODE", 12f, true, bright), lp(-1, -2, 4, 8))
        content.addView(label("Enter a six-digit track number", 15f, false, muted), lp(-1, -2, 0, 12))
        val numberPanel = card()
        val display = label(numberEntry.ifEmpty { "______" }, 34f, true, white).apply { gravity = Gravity.CENTER; setBackgroundColor(Color.rgb(40, 8, 15)); setPadding(dp(8), dp(16), dp(8), dp(16)) }
        numberPanel.addView(display, lp(-1, -2))
        val keys = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, 0) }
        val keypad = listOf(listOf("1","2","3"), listOf("4","5","6"), listOf("7","8","9"), listOf("CLR","0","⌫"))
        keypad.forEach { group ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            group.forEach { key -> r.addView(actionButton(key, panel) {
                numberEntry = when (key) { "CLR" -> ""; "⌫" -> numberEntry.dropLast(1); else -> if (numberEntry.length < 6) numberEntry + key else numberEntry }
                display.text = numberEntry.ifEmpty { "______" }
            }, weight().apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }) }
            keys.addView(r)
        }
        numberPanel.addView(keys)
        numberPanel.addView(actionButton("OK  •  PLAY TRACK", red) {
            val song = songs.firstOrNull { it.number == numberEntry }
            if (song == null) message("Track number not found. Scan your library or enter a number shown in the song list.")
            else { activeSong = song; playSong(song) }
        }, lp(-1, 48, 8))
        numberPanel.addView(actionButton("RESERVE TRACK", Color.rgb(55, 35, 42)) {
            val song = songs.firstOrNull { it.number == numberEntry }
            if (song == null) message("Track number not found.") else {
                if (queue.none { it.uri == song.uri }) queue.add(song)
                updateHeader(); message("Added to reservation queue.")
            }
        }, lp(-1, 44, 8))
        content.addView(numberPanel)
    }

    private fun showQueue() {
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
        stopMidi()
        try {
            val sf = File(filesDir, "reyna-se.sf2")
            if (!sf.exists() || sf.length() == 0L) assets.open("reyna-se.sf2").use { input -> sf.outputStream().use { output -> input.copyTo(output) } }
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
    }
}
