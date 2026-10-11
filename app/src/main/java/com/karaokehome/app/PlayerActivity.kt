package com.karaokehome.app

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView

class PlayerActivity : Activity() {
    private lateinit var video: VideoView
    private lateinit var overlay: LinearLayout
    private lateinit var countText: TextView
    private lateinit var nextText: TextView
    private var queue = arrayListOf<String>()

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )

        queue = intent.getStringArrayListExtra(EXTRA_QUEUE) ?: arrayListOf()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Karaoke video"
        val uriText = intent.getStringExtra(EXTRA_URI)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = VideoView(this)
        root.addView(video, FrameLayout.LayoutParams(-1, -1))

        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(16), dp(22), dp(16))
            setBackgroundColor(Color.TRANSPARENT)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleText = TextView(this).apply {
            text = "NOW PLAYING  •  " + title
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        top.addView(titleText, LinearLayout.LayoutParams(0, -2, 1f))
        val close = TextView(this).apply {
            text = "✕  EXIT"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(10), dp(8), dp(10))
            setOnClickListener { finish() }
        }
        top.addView(close)
        overlay.addView(top)

        countText = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFF75E0C0.toInt())
            setPadding(0, dp(10), 0, 0)
        }
        overlay.addView(countText)
        nextText = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(0, dp(5), 0, 0)
        }
        overlay.addView(nextText)
        updateQueueDisplay()
        root.addView(overlay, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        setContentView(root)

        if (uriText.isNullOrBlank()) {
            titleText.text = "No video selected"
        } else {
            try {
                video.setVideoURI(Uri.parse(uriText))
                video.setOnPreparedListener { player ->
                    player.isLooping = false
                    video.start()
                }
                video.setOnCompletionListener { titleText.text = "FINISHED  •  " + title }
                video.setOnErrorListener { _, _, _ ->
                    titleText.text = "Unable to play this video"
                    true
                }
            } catch (_: Exception) {
                titleText.text = "Unable to open this video"
            }
        }
        video.setOnClickListener {
            overlay.visibility = if (overlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }

    private fun updateQueueDisplay() {
        countText.text = if (queue.isEmpty()) "" else "RESERVED: %02d".format(queue.size)
        nextText.text = queue.firstOrNull()?.let { "UP NEXT  •  " + it } ?: ""
        countText.visibility = if (queue.isEmpty()) View.GONE else View.VISIBLE
        nextText.visibility = if (queue.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        if (::video.isInitialized) video.stopPlayback()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_URI = "com.karaokehome.app.PLAYER_URI"
        const val EXTRA_TITLE = "com.karaokehome.app.PLAYER_TITLE"
        const val EXTRA_QUEUE = "com.karaokehome.app.PLAYER_QUEUE"
    }
}
