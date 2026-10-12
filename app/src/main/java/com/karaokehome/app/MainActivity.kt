package com.karaokehome.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import java.io.File
import dev.kotlinds.fluidsynthkmp.MidiFilePlayer

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var selectedSongText: TextView
    private lateinit var queueText: TextView
    private lateinit var videoView: VideoView
    private val queue = mutableListOf<String>()
    private var selectedSong: String? = null
    private var selectedUri: Uri? = null
    private var midiPlayer: MidiFilePlayer? = null
    private var selectedMidiName: String? = null
    private var midiUri: Uri? = null

    private val colors = object {
        val background = Color.rgb(17, 20, 32)
        val panel = Color.rgb(29, 35, 53)
        val accent = Color.rgb(83, 190, 164)
        val text = Color.rgb(246, 248, 252)
        val muted = Color.rgb(177, 187, 205)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(colors.background)
        }

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(content)

        content.addView(
            text("KaraokeHome", 30f, true, colors.text),
            marginParams(match, wrap, bottom = 4)
        )
        content.addView(
            text("Your living-room karaoke system", 16f, false, colors.muted),
            marginParams(match, wrap, bottom = 18)
        )

        val playerPanel = panel()
        playerPanel.addView(text("NOW PLAYING", 12f, true, colors.accent))
        videoView = VideoView(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        playerPanel.addView(
            videoView,
            LinearLayout.LayoutParams(match, dp(220)).apply {
                topMargin = dp(10)
                bottomMargin = dp(12)
            }
        )
        playerPanel.addView(
            text("Choose an MP4 karaoke video. Playback starts here.", 15f, false, colors.muted),
            marginParams(match, wrap, bottom = 10)
        )
        statusText = text("Ready • No video playing", 14f, false, colors.muted)
        playerPanel.addView(statusText)
        playerPanel.addView(
            button("Choose MP4 video", colors.accent, colors.background) {
                chooseSong()
            },
            marginParams(match, wrap, top = 14)
        )
        playerPanel.addView(
            button("Stop playback", Color.rgb(60, 72, 101), colors.text) {
                videoView.stopPlayback()
                statusText.text = "Playback stopped"
            },
            marginParams(match, wrap, top = 8)
        )

        playerPanel.addView(
            button("Open full-screen TV player", Color.rgb(93, 87, 210), Color.WHITE) {
                val uri = selectedUri
                if (uri == null) {
                    message("Choose an MP4 video first.")
                } else {
                    startActivity(Intent(this, PlayerActivity::class.java).apply {
                        putExtra(PlayerActivity.EXTRA_URI, uri.toString())
                        putExtra(PlayerActivity.EXTRA_TITLE, selectedSong ?: "Karaoke video")
                        putStringArrayListExtra(PlayerActivity.EXTRA_QUEUE, ArrayList(queue))
                    })
                }
            },
            marginParams(match, wrap, top = 8)
        )
        content.addView(playerPanel, marginParams(match, wrap, bottom = 16))

        val libraryPanel = panel()
        libraryPanel.addView(text("SELECTED FILE", 12f, true, colors.accent))
        selectedSongText = text("No file selected yet", 15f, false, colors.text)
        libraryPanel.addView(selectedSongText, marginParams(match, wrap, top = 10))
        libraryPanel.addView(
            text("MP4 video playback is available. MIDI/KAR files can be played through the bundled SoundFont; lyrics display and automatic USB scanning are still under development.", 13f, false, colors.muted),
            marginParams(match, wrap, top = 10)
        )
        libraryPanel.addView(button("Choose MIDI / KAR file", colors.accent, colors.background) {
            chooseMidi()
        }, marginParams(match, wrap, top = 12))
        libraryPanel.addView(button("Play / Resume MIDI", Color.rgb(93, 87, 210), Color.WHITE) {
            playMidi()
        }, marginParams(match, wrap, top = 8))
        libraryPanel.addView(button("Pause MIDI", Color.rgb(60, 72, 101), colors.text) {
            midiPlayer?.pause()
            statusText.text = "MIDI paused"
        }, marginParams(match, wrap, top = 8))
        libraryPanel.addView(button("Stop MIDI", Color.rgb(60, 72, 101), colors.text) {
            midiPlayer?.stop()
            statusText.text = "MIDI stopped"
        }, marginParams(match, wrap, top = 8))
        content.addView(libraryPanel, marginParams(match, wrap, bottom = 16))

        val queuePanel = panel()
        queuePanel.addView(text("SINGING QUEUE", 12f, true, colors.accent))
        queueText = text("No songs in the queue yet", 15f, false, colors.muted)
        queuePanel.addView(queueText, marginParams(match, wrap, top = 10, bottom = 12))
        queuePanel.addView(button("Add selected file to queue", Color.rgb(60, 72, 101), colors.text) {
            val song = selectedSong
            if (song == null) {
                message("Choose a file first.")
            } else {
                queue.add(song)
                updateQueue()
                message("Added to the queue. Queue playback is not automatic yet.")
            }
        })
        queuePanel.addView(button("Clear queue", Color.rgb(60, 72, 101), colors.text) {
            queue.clear()
            updateQueue()
        }, marginParams(match, wrap, top = 8))
        content.addView(queuePanel, marginParams(match, wrap, bottom = 16))

        val remotePanel = panel()
        remotePanel.addView(text("PHONE REMOTE", 12f, true, colors.accent))
        remotePanel.addView(
            text("TV pairing, QR code, shared song library and local Wi-Fi remote control are still under development.", 14f, false, colors.muted),
            marginParams(match, wrap, top = 10)
        )
        content.addView(remotePanel)

        root.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
        setContentView(root)
    }

    private fun chooseSong() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        try {
            startActivityForResult(intent, REQUEST_PICK_SONG)
        } catch (_: Exception) {
            message("The file picker could not be opened on this device.")
        }
    }

    private fun chooseMidi() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/midi", "audio/x-midi", "application/x-midi", "application/octet-stream"))
        }
        try {
            startActivityForResult(intent, REQUEST_PICK_MIDI)
        } catch (_: Exception) {
            message("The file picker could not be opened on this device.")
        }
    }

    private fun playMidi() {
        val existingPlayer = midiPlayer
        if (existingPlayer != null) {
            try {
                if (existingPlayer.isPlaying) {
                    statusText.text = "MIDI is already playing"
                    return
                }
                statusText.text = "Resuming MIDI"
                existingPlayer.play {
                    runOnUiThread {
                        statusText.text = "Finished MIDI"
                        try { existingPlayer.close() } catch (_: Exception) {}
                        if (midiPlayer === existingPlayer) midiPlayer = null
                    }
                }
                return
            } catch (e: Exception) {
                try { existingPlayer.close() } catch (_: Exception) {}
                if (midiPlayer === existingPlayer) midiPlayer = null
                message("Couldn't resume MIDI, so the player was reset. Tap Play / Resume again. " + (e.message ?: ""))
                return
            }
        }

        val uri = midiUri
        if (uri == null) {
            message("Choose a MIDI (.mid) or karaoke (.kar) file first.")
            return
        }
        try {
            val soundFont = copySoundFont()
            val midiFile = File(filesDir, "selected-song.mid")
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Could not read the selected MIDI file." }
                midiFile.outputStream().use { output -> input.copyTo(output) }
            }
            val player = MidiFilePlayer(soundFont, midiFile.absolutePath)
            midiPlayer = player
            statusText.text = "Playing MIDI"
            player.play {
                runOnUiThread {
                    statusText.text = "Finished MIDI"
                    try { player.close() } catch (_: Exception) {}
                    if (midiPlayer === player) midiPlayer = null
                }
            }
        } catch (e: Exception) {
            statusText.text = "Could not play MIDI"
            message("Couldn't start MIDI playback. Please try a standard .mid or .kar file. " + (e.message ?: ""))
        }
    }

    private fun copySoundFont(): String {
        val target = File(filesDir, "reyna-se.sf2")
        if (!target.exists() || target.length() == 0L) {
            assets.open("reyna-se.sf2").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return target.absolutePath
    }

    @Deprecated("Uses the compatible document picker callback for broad Android device support")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        if (requestCode == REQUEST_PICK_MIDI) {
            midiUri = uri
            selectedMidiName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Selected MIDI/KAR"
            selectedSongText.text = selectedMidiName
            statusText.text = "Ready • " + (selectedMidiName ?: "MIDI")
            return
        }
        if (requestCode != REQUEST_PICK_SONG) return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some document providers do not offer persistable permissions.
        }
        selectedUri = uri
        selectedSong = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "Selected video"
        selectedSongText.text = selectedSong
        playSelectedVideo(uri)
    }

    private fun playSelectedVideo(uri: Uri) {
        statusText.text = "Loading video…"
        try {
            videoView.stopPlayback()
            videoView.setVideoURI(uri)
            val controller = MediaController(this)
            controller.setAnchorView(videoView)
            videoView.setMediaController(controller)
            videoView.setOnPreparedListener { mediaPlayer ->
                mediaPlayer.isLooping = false
                videoView.start()
                statusText.text = "Playing • ${selectedSong ?: "Video"}"
            }
            videoView.setOnCompletionListener {
                statusText.text = "Finished • ${selectedSong ?: "Video"}"
            }
            videoView.setOnErrorListener { _, what, extra ->
                statusText.text = "Could not play this video"
                message("Android couldn't play this file (error $what/$extra). Try a standard MP4 using H.264 video and AAC audio.")
                true
            }
        } catch (_: Exception) {
            statusText.text = "Could not open this video"
            message("Couldn't open that file. Please try another MP4 video.")
        }
    }

    private fun updateQueue() {
        queueText.text = if (queue.isEmpty()) {
            "No songs in the queue yet"
        } else {
            queue.mapIndexed { index, song -> "${index + 1}. $song" }.joinToString("\n")
        }
    }

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setBackgroundColor(colors.panel)
    }

    private fun text(value: String, size: Float, bold: Boolean, color: Int) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun button(label: String, background: Int, foreground: Int, action: () -> Unit) =
        Button(this).apply {
            text = label
            setTextColor(foreground)
            setBackgroundColor(background)
            isAllCaps = false
            textSize = 15f
            setOnClickListener { action() }
        }

    private fun message(value: String) {
        AlertDialog.Builder(this)
            .setMessage(value)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun marginParams(width: Int, height: Int, top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(width, height).apply {
            setMargins(0, dp(top), 0, dp(bottom))
        }

    override fun onDestroy() {
        try { midiPlayer?.stop() } catch (_: Exception) {}
        try { midiPlayer?.close() } catch (_: Exception) {}
        midiPlayer = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_PICK_SONG = 101
        private const val REQUEST_PICK_MIDI = 102
        private const val match = ViewGroup.LayoutParams.MATCH_PARENT
        private const val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}