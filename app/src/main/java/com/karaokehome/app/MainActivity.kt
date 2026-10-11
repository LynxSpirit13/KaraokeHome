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
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var selectedSongText: TextView
    private lateinit var queueText: TextView
    private val queue = mutableListOf<String>()
    private var selectedSong: String? = null

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
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(colors.background)
        }

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(content)

        val brand = text("KaraokeHome", 30f, true, colors.text).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        content.addView(brand, marginParams(match, wrap, bottom = 4))

        content.addView(
            text("Your living-room karaoke system", 16f, false, colors.muted),
            marginParams(match, wrap, bottom = 22)
        )

        val playerPanel = panel()
        playerPanel.addView(text("NOW PLAYING", 12f, true, colors.accent))
        playerPanel.addView(
            text("Choose a karaoke video to get started", 20f, true, colors.text),
            marginParams(match, wrap, top = 10, bottom = 8)
        )
        statusText = text("Ready • No song selected", 14f, false, colors.muted)
        playerPanel.addView(statusText)
        playerPanel.addView(
            button("Choose a song", colors.accent, colors.background) {
                chooseSong()
            },
            marginParams(match, wrap, top = 16)
        )
        content.addView(playerPanel, marginParams(match, wrap, bottom = 16))

        val libraryPanel = panel()
        libraryPanel.addView(text("SONG LIBRARY", 12f, true, colors.accent))
        libraryPanel.addView(
            text("Select an MP4, audio, MIDI, or KAR file from your device. Full playback and automatic USB scanning are planned next.", 15f, false, colors.muted),
            marginParams(match, wrap, top = 10, bottom = 12)
        )
        selectedSongText = text("No file selected yet", 14f, false, colors.text)
        libraryPanel.addView(selectedSongText)
        content.addView(libraryPanel, marginParams(match, wrap, bottom = 16))

        val queuePanel = panel()
        queuePanel.addView(text("SINGING QUEUE", 12f, true, colors.accent))
        queueText = text("No songs in the queue yet", 15f, false, colors.muted)
        queuePanel.addView(queueText, marginParams(match, wrap, top = 10, bottom = 12))
        queuePanel.addView(button("Add selected song to queue", Color.rgb(60, 72, 101), colors.text) {
            val song = selectedSong
            if (song == null) {
                message("Choose a song first.")
            } else {
                queue.add(song)
                updateQueue()
                message("Added to the queue.")
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
            text("TV pairing by QR code and local Wi-Fi remote control are planned features. This prototype currently works as a standalone screen.", 15f, false, colors.muted),
            marginParams(match, wrap, top = 10)
        )
        content.addView(remotePanel)

        root.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
        setContentView(root)
    }

    private fun chooseSong() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "video/mp4",
                "audio/*",
                "application/octet-stream",
                "application/x-kar",
                "audio/midi"
            ))
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_PICK_SONG)
        } catch (_: Exception) {
            message("The file picker could not be opened on this device.")
        }
    }

    @Deprecated("Uses the compatible document picker callback for broad Android device support")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_SONG && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some document providers do not offer persistable permissions.
            }
            val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "Selected media file"
            selectedSong = name
            selectedSongText.text = name
            statusText.text = "File selected • Playback is not implemented yet"
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

    private fun marginParams(
        width: Int,
        height: Int,
        top: Int = 0,
        bottom: Int = 0
    ) = LinearLayout.LayoutParams(width, height).apply {
        setMargins(0, dp(top), 0, dp(bottom))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_PICK_SONG = 101
        private const val match = ViewGroup.LayoutParams.MATCH_PARENT
        private const val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
