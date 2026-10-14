package com.karaokehome.app

import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

internal object MidiEmbeddedLyrics {
    data class TimedLyric(val timeMs: Long, val text: String)
    private data class RawText(val tick: Long, val text: String, val isLyric: Boolean)
    private data class Tempo(val tick: Long, val microsPerQuarter: Int)

    fun extract(file: File): String? = extractTimed(file).takeIf { it.isNotEmpty() }
        ?.joinToString("") { it.text }?.trim()?.takeIf { it.isNotBlank() }

    /** Reads MIDI lyric events and converts their MIDI ticks to milliseconds using tempo meta-events. */
    fun extractTimed(file: File): List<TimedLyric> {
        return try {
            val bytes = file.readBytes()
            if (bytes.size < 14 || ascii(bytes, 0, 4) != "MThd") return emptyList()
            val headerLength = readInt(bytes, 4).coerceAtLeast(6)
            val division = readU16(bytes, 12)
            if (division == 0 || division and 0x8000 != 0) return emptyList()
            var offset = 8 + headerLength
            val lyrics = mutableListOf<RawText>()
            val texts = mutableListOf<RawText>()
            val tempos = mutableListOf(Tempo(0L, 500000))
            while (offset + 8 <= bytes.size) {
                val chunk = ascii(bytes, offset, 4)
                val length = readInt(bytes, offset + 4)
                if (length < 0 || offset + 8L + length > bytes.size) break
                if (chunk == "MTrk") parseTrack(bytes, offset + 8, offset + 8 + length, lyrics, texts, tempos)
                offset += 8 + length
            }
            val selected = if (lyrics.isNotEmpty()) lyrics else texts
            if (selected.isEmpty()) return emptyList()
            val orderedTempos = tempos.distinctBy { it.tick to it.microsPerQuarter }.sortedBy { it.tick }
            selected.sortedBy { it.tick }.map { raw ->
                TimedLyric(ticksToMillis(raw.tick, division, orderedTempos), cleanPiece(raw.text))
            }.filter { it.text.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseTrack(
        bytes: ByteArray, start: Int, end: Int,
        lyrics: MutableList<RawText>, texts: MutableList<RawText>, tempos: MutableList<Tempo>
    ) {
        var p = start
        var tick = 0L
        var runningStatus = 0
        while (p < end) {
            val delta = readVlq(bytes, p, end) ?: break
            tick += delta.first
            p = delta.second
            if (p >= end) break
            var status = bytes[p].toInt() and 0xff
            if (status < 0x80) {
                if (runningStatus == 0) break
                status = runningStatus
            } else {
                p++
                if (status < 0xf0) runningStatus = status
            }
            when {
                status == 0xff -> {
                    if (p >= end) break
                    val type = bytes[p++].toInt() and 0xff
                    val size = readVlq(bytes, p, end) ?: break
                    p = size.second
                    val count = size.first.coerceAtMost((end - p).coerceAtLeast(0))
                    if (type == 0x05 || type == 0x01) {
                        val value = decode(bytes.copyOfRange(p, p + count)).trim()
                        if (value.isNotEmpty() && !value.startsWith("@")) {
                            (if (type == 0x05) lyrics else texts).add(RawText(tick, value, type == 0x05))
                        }
                    } else if (type == 0x51 && count == 3) {
                        val micros = ((bytes[p].toInt() and 0xff) shl 16) or
                            ((bytes[p + 1].toInt() and 0xff) shl 8) or
                            (bytes[p + 2].toInt() and 0xff)
                        if (micros > 0) tempos.add(Tempo(tick, micros))
                    }
                    p += count
                }
                status == 0xf0 || status == 0xf7 -> {
                    val size = readVlq(bytes, p, end) ?: break
                    p = (size.second + size.first).coerceAtMost(end)
                }
                status < 0xf0 -> {
                    val kind = status and 0xf0
                    val dataBytes = if (kind == 0xc0 || kind == 0xd0) 1 else 2
                    if (p < end && (bytes[p].toInt() and 0xff) >= 0x80) break
                    p = (p + dataBytes).coerceAtMost(end)
                }
                else -> break
            }
        }
    }

    private fun ticksToMillis(targetTick: Long, division: Int, tempos: List<Tempo>): Long {
        var previousTick = 0L
        var microsPerQuarter = 500000
        var totalMicros = 0.0
        for (tempo in tempos) {
            if (tempo.tick > targetTick) break
            if (tempo.tick > previousTick) {
                totalMicros += (tempo.tick - previousTick).toDouble() * microsPerQuarter / division
                previousTick = tempo.tick
            }
            microsPerQuarter = tempo.microsPerQuarter
        }
        if (targetTick > previousTick) {
            totalMicros += (targetTick - previousTick).toDouble() * microsPerQuarter / division
        }
        return (totalMicros / 1000.0).toLong()
    }

    private fun cleanPiece(value: String): String {
        // Slash is a syllable separator; backslash is the karaoke line-break marker.
        return value.replace("\\", "\n")
            .replace("/", "")
            .replace(Regex("[\u0001-\u0008\u000B\u000C\u000E-\u001F]"), "")
            .replace(Regex("[ \t]+"), " ")
    }

    private fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, StandardCharsets.UTF_8)
        return if (!utf8.contains('\uFFFD')) utf8 else String(bytes, Charset.forName("windows-1252"))
    }

    private fun readVlq(bytes: ByteArray, start: Int, end: Int): Pair<Int, Int>? {
        var p = start
        var value = 0
        var count = 0
        while (p < end && count < 4) {
            val b = bytes[p++].toInt() and 0xff
            value = (value shl 7) or (b and 0x7f)
            count++
            if (b and 0x80 == 0) return value to p
        }
        return null
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return -1
        return ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun ascii(bytes: ByteArray, offset: Int, count: Int): String =
        String(bytes, offset, count, StandardCharsets.US_ASCII)
}
