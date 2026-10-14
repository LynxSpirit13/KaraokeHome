package com.karaokehome.app

import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Reads lyric/text meta events directly from a Standard MIDI File.
 * Karaoke files commonly store syllables in FF 05 lyric events; some use FF 01 text events.
 * This is a display fallback, independent of the synthesizer's audio playback API.
 */
internal object MidiEmbeddedLyrics {
    fun extract(file: File): String? {
        return try {
            val bytes = file.readBytes()
            if (bytes.size < 14 || ascii(bytes, 0, 4) != "MThd") return null
            val headerLength = readInt(bytes, 4).coerceAtLeast(6)
            var offset = 8 + headerLength
            val lyricEvents = mutableListOf<Pair<Long, String>>()
            val textEvents = mutableListOf<Pair<Long, String>>()
            while (offset + 8 <= bytes.size) {
                val chunk = ascii(bytes, offset, 4)
                val length = readInt(bytes, offset + 4)
                if (length < 0 || offset + 8L + length > bytes.size) break
                if (chunk == "MTrk") {
                    parseTrack(bytes, offset + 8, offset + 8 + length, lyricEvents, textEvents)
                }
                offset += 8 + length
            }
            val chosen = if (lyricEvents.isNotEmpty()) lyricEvents else textEvents
            val rendered = chosen.sortedBy { it.first }.joinToString("") { it.second }
                .replace("\u0000", "")
                .replace("\\r\\n", "\\n")
                .replace("\\r", "\\n")
                .replace(Regex("[\\t ]+"), " ")
                .replace(Regex(" *\\n *"), "\\n")
                .replace(Regex("\\n{3,}"), "\\n\\n")
                .trim()
                .takeIf { it.isNotBlank() }
            rendered?.let { cleanKaraokeText(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseTrack(
        bytes: ByteArray,
        start: Int,
        end: Int,
        lyrics: MutableList<Pair<Long, String>>,
        texts: MutableList<Pair<Long, String>>
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
                        val raw = bytes.copyOfRange(p, p + count)
                        val value = decode(raw).trim()
                        if (value.isNotEmpty() && !value.startsWith("@")) {
                            val target = if (type == 0x05) lyrics else texts
                            target.add(tick to value)
                        }
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
                    if ((bytes.getOrNull(p)?.toInt()?.and(0xff) ?: 0) >= 0x80 && p < end) break
                    p = (p + dataBytes).coerceAtMost(end)
                }
                else -> break
            }
        }
    }

    private fun cleanKaraokeText(value: String): String {
        val cleaned = value
            // In common MIDI karaoke files, backslash marks a lyric line break and slash
            // separates syllables. Remove the separator rather than displaying it.
            .replace("\\\\", "\\n")
            .replace("/", "")
            .replace(Regex("[\\u0001-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")
            .replace(Regex(" +"), " ")
            .replace(Regex(" *\\n *"), "\\n")
            .replace(Regex("\\n{3,}"), "\\n\\n")
            .trim()
        return cleaned.takeIf { it.isNotBlank() } ?: value.trim()
    }

    private fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, StandardCharsets.UTF_8)
        if (!utf8.contains('\\uFFFD')) return utf8
        return String(bytes, Charset.forName("windows-1252"))
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

    private fun ascii(bytes: ByteArray, offset: Int, count: Int): String =
        String(bytes, offset, count, StandardCharsets.US_ASCII)
}
