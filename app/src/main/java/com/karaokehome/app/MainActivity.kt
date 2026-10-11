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

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var selectedSongText: TextView
    private lateinit var queueText: TextView
    private lateinit var videoView: VideoView
    private val queue = mutableListOf<String>()
    private var selectedSong: String? = null
    private var selectedUri: Uri? = null

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
        content.addView(playerPanel, marginParams(match, wrap, bottom = 16))

        val libraryPanel = panel()
        libraryPanel.addView(text("SELECTED FILE", 12f, true, colors.accent))
        selectedSongText = text("No file selected yet", 15f, false, colors.text)
        libraryPanel.addView(selectedSongText, marginParams(match, wrap, top = 10))
        libraryPanel.addView(
            text("This first playback update supports common video formats such as MP4 when your Android device supports their codecs. KAR/MIDI playback and automatic USB scanning are not available yet.", 13f, false, colors.muted),
            marginParams(match, wrap, top = 10)
        )
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

    @Deprecated("Uses the compatible document picker callback for broad Android device support")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_SONG || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_PICK_SONG = 101
        private const val match = ViewGroup.LayoutParams.MATCH_PARENT
        private const val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}