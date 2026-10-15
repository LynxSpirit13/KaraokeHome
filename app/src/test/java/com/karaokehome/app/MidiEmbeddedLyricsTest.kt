package com.karaokehome.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MidiEmbeddedLyricsTest {
    @Test
    fun extractsEmbeddedLyricsAndConvertsTicksToMilliseconds() {
        // Format 0, one track, 480 ticks per quarter note.
        val header = byteArrayOf(
            0x4D, 0x54, 0x68, 0x64, 0x00, 0x00, 0x00, 0x06,
            0x00, 0x00, 0x00, 0x01, 0x01, 0xE0.toByte()
        )
        val track = byteArrayOf(
            0x00, 0xFF.toByte(), 0x51, 0x03, 0x07, 0xA1.toByte(), 0x20, // 500,000 us/quarter
            0x00, 0xFF.toByte(), 0x05, 0x05, 0x48, 0x65, 0x6C, 0x6C, 0x6F, // Hello at 0 ms
            0x83.toByte(), 0x60, 0xFF.toByte(), 0x05, 0x05, 0x77, 0x6F, 0x72, 0x6C, 0x64, // world at 500 ms
            0x00, 0xFF.toByte(), 0x2F, 0x00
        )
        val trackHeader = byteArrayOf(
            0x4D, 0x54, 0x72, 0x6B,
            0x00, 0x00, 0x00, track.size.toByte()
        )
        val file = File.createTempFile("karaoke-lyrics-", ".mid")
        try {
            file.writeBytes(header + trackHeader + track)
            val lyrics = MidiEmbeddedLyrics.extractTimed(file)
            assertEquals(listOf("Hello", "world"), lyrics.map { it.text })
            assertEquals(0L, lyrics[0].timeMs)
            assertEquals(500L, lyrics[1].timeMs)
        } finally {
            file.delete()
        }
    }

    @Test
    fun returnsNoTimedLyricsForMidiWithoutLyricEvents() {
        val header = byteArrayOf(
            0x4D, 0x54, 0x68, 0x64, 0x00, 0x00, 0x00, 0x06,
            0x00, 0x00, 0x00, 0x01, 0x01, 0xE0.toByte()
        )
        val track = byteArrayOf(0x00, 0xFF.toByte(), 0x2F, 0x00)
        val trackHeader = byteArrayOf(
            0x4D, 0x54, 0x72, 0x6B,
            0x00, 0x00, 0x00, track.size.toByte()
        )
        val file = File.createTempFile("karaoke-no-lyrics-", ".mid")
        try {
            file.writeBytes(header + trackHeader + track)
            assertTrue(MidiEmbeddedLyrics.extractTimed(file).isEmpty())
        } finally {
            file.delete()
        }
    }
}
