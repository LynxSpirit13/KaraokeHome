package com.karaokehome.app

import java.io.File
import java.nio.charset.Charset

/**
 * Reads timed lyric/text meta-events from a Standard MIDI File (including .KAR).
 * Timings remain in MIDI ticks so they can be compared directly with MidiFilePlayer.currentTick;
 * this avoids converting through a single assumed tempo and drifting on songs with tempo changes.
 */
internal object MidiKaraokeLyrics {
    data class Segment(val text: String, val tick: Long)
    data class Line(val segments: List<Segment>) {
        val text: String get() = segments.joinToString("") { it.text }.trim()
    }

    private data class Event(val tick: Long, val text: String)
    private data class TrackLyrics(
        val lyricEvents: MutableList<Event> = mutableListOf(),
        val textEvents: MutableList<Event> = mutableListOf()
    )

    fun parse(file: File): List<Line> {
        return try {
            val bytes = file.readBytes()
            if (bytes.size < 14 || String(bytes, 0, 4, Charsets.US_ASCII) != "MThd") return emptyList()
            var pos = 8
            val headerLength = readU32(bytes, 4).coerceAtMost((bytes.size - 8).toLong()).toInt()
            val headerEnd = 8 + headerLength
            if (headerEnd > bytes.size || headerLength < 6) return emptyList()
            val division = readU16(bytes, 12)
            if (division == 0 || (division and 0x8000) != 0) return emptyList()
            pos = headerEnd
            val tracks = mutableListOf<TrackLyrics>()
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4, Charsets.US_ASCII)
                val length = readU32(bytes, pos + 4).coerceAtMost((bytes.size - pos - 8).toLong()).toInt()
                val start = pos + 8
                val end = start + length
                if (end > bytes.size) break
                if (id == "MTrk") tracks.add(parseTrack(bytes, start, end))
                pos = end
            }
            val lyricTrack = tracks.maxByOrNull { it.lyricEvents.size }
            val chosen = if (lyricTrack != null && lyricTrack.lyricEvents.isNotEmpty()) lyricTrack.lyricEvents
                         else tracks.maxByOrNull { it.textEvents.size }?.textEvents.orEmpty()
            if (chosen.isEmpty()) return emptyList()
            buildLines(chosen.sortedBy { it.tick })
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseTrack(data: ByteArray, start: Int, end: Int): TrackLyrics {
        val result = TrackLyrics()
        var p = start
        var tick = 0L
        var runningStatus = -1
        while (p < end) {
            val delta = readVlq(data, p, end)
            if (delta.second <= 0) break
            tick += delta.first
            p += delta.second
            if (p >= end) break
            var status = data[p].toInt() and 0xff
            if (status < 0x80) {
                if (runningStatus < 0) break
                status = runningStatus
            } else {
                p++
                if (status in 0x80..0xEF) runningStatus = status
            }
            when (status) {
                0xFF -> {
                    if (p >= end) break
                    val type = data[p++].toInt() and 0xff
                    val len = readVlq(data, p, end)
                    if (len.second <= 0) break
                    p += len.second
                    val count = len.first.toInt().coerceAtMost(end - p)
                    if (count < 0) break
                    if ((type == 0x05 || type == 0x01) && count > 0) {
                        val raw = decodeText(data, p, count)
                        val event = Event(tick, raw)
                        if (type == 0x05) result.lyricEvents.add(event) else result.textEvents.add(event)
                    }
                    p += count
                    if (type == 0x2F) break
                }
                0xF0, 0xF7 -> {
                    val len = readVlq(data, p, end)
                    if (len.second <= 0) break
                    p += len.second + len.first.toInt().coerceAtMost(end - p - len.second)
                }
                else -> {
                    val kind = status and 0xF0
                    val dataBytes = if (kind == 0xC0 || kind == 0xD0) 1 else 2
                    if ((data[p.coerceAtMost(end - 1)].toInt() and 0x80) != 0 && p < end) break
                    p = (p + dataBytes).coerceAtMost(end)
                }
            }
        }
        return result
    }

    private fun buildLines(events: List<Event>): List<Line> {
        val lines = mutableListOf<Line>()
        var current = mutableListOf<Segment>()
        fun finishLine() {
            if (current.any { it.text.isNotBlank() }) lines.add(Line(current.toList()))
            current = mutableListOf()
        }
        for (event in events) {
            val text = event.text.replace("\u0000", "")
            val piece = StringBuilder()
            fun flush() {
                if (piece.isNotEmpty()) {
                    current.add(Segment(piece.toString(), event.tick))
                    piece.setLength(0)
                }
            }
            for (ch in text) {
                if (ch == '\\' || ch == '/' || ch == '\n' || ch == '\r') {
                    flush()
                    finishLine()
                } else if (ch != '\u0000') {
                    piece.append(ch)
                }
            }
            flush()
        }
        finishLine()
        return lines.filter { it.text.isNotBlank() }
    }

    private fun decodeText(data: ByteArray, offset: Int, length: Int): String {
        val slice = data.copyOfRange(offset, offset + length)
        val utf8 = String(slice, Charsets.UTF_8)
        return if (utf8.contains('\uFFFD')) String(slice, Charset.forName("windows-1252")) else utf8
    }

    private fun readVlq(data: ByteArray, offset: Int, end: Int): Pair<Long, Int> {
        var value = 0L
        var p = offset
        var count = 0
        while (p < end && count < 4) {
            val b = data[p++].toInt() and 0xff
            value = (value shl 7) or (b and 0x7f).toLong()
            count++
            if ((b and 0x80) == 0) return value to count
        }
        return 0L to 0
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    private fun readU32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xff) shl 24) or
        ((data[offset + 1].toLong() and 0xff) shl 16) or
        ((data[offset + 2].toLong() and 0xff) shl 8) or
        (data[offset + 3].toLong() and 0xff)
}
