package com.karaokehome.app

import java.io.File

/** Lightweight Standard MIDI File transformer for phone keypad key/tempo/melody controls. */
internal object MidiTransform {
    fun apply(source: File, destination: File, transpose: Int, tempoPercent: Int, muteMelody: Boolean) {
        val bytes = source.readBytes()
        if (bytes.size < 14 || ascii(bytes, 0, 4) != "MThd") {
            destination.writeBytes(bytes)
            return
        }
        val headerLength = readInt(bytes, 4).coerceAtLeast(6)
        var offset = 8 + headerLength
        while (offset + 8 <= bytes.size) {
            val length = readInt(bytes, offset + 4)
            if (length < 0 || offset + 8L + length > bytes.size) break
            if (ascii(bytes, offset, 4) == "MTrk") {
                transformTrack(bytes, offset + 8, offset + 8 + length, transpose.coerceIn(-12, 12),
                    tempoPercent.coerceIn(-30, 30), muteMelody)
            }
            offset += 8 + length
        }
        destination.writeBytes(bytes)
    }

    private fun transformTrack(bytes: ByteArray, start: Int, end: Int, transpose: Int, tempoPercent: Int, muteMelody: Boolean) {
        var p = start
        var runningStatus = 0
        while (p < end) {
            val delta = readVlq(bytes, p, end) ?: break
            p = delta.second
            if (p >= end) break
            var status = bytes[p].toInt() and 0xff
            if (status >= 0x80) {
                p++
                if (status < 0xf0) runningStatus = status
            } else {
                if (runningStatus == 0) break
                status = runningStatus
            }
            when {
                status == 0xff -> {
                    if (p >= end) break
                    val type = bytes[p++].toInt() and 0xff
                    val size = readVlq(bytes, p, end) ?: break
                    p = size.second
                    val count = size.first.coerceAtMost((end - p).coerceAtLeast(0))
                    if (type == 0x51 && count == 3 && tempoPercent != 0) {
                        val original = ((bytes[p].toInt() and 0xff) shl 16) or
                            ((bytes[p + 1].toInt() and 0xff) shl 8) or (bytes[p + 2].toInt() and 0xff)
                        val changed = (original.toDouble() * 100.0 / (100 + tempoPercent)).toInt().coerceIn(1, 0xFFFFFF)
                        bytes[p] = (changed shr 16).toByte()
                        bytes[p + 1] = (changed shr 8).toByte()
                        bytes[p + 2] = changed.toByte()
                    }
                    p += count
                }
                status == 0xf0 || status == 0xf7 -> {
                    val size = readVlq(bytes, p, end) ?: break
                    p = (size.second + size.first).coerceAtMost(end)
                }
                status < 0xf0 -> {
                    val channel = status and 0x0f
                    val kind = status and 0xf0
                    val dataCount = if (kind == 0xc0 || kind == 0xd0) 1 else 2
                    if (p + dataCount > end) break
                    if (kind == 0x80 || kind == 0x90) {
                        if (muteMelody && channel == 0 && kind == 0x90 && (bytes[p + 1].toInt() and 0xff) > 0) {
                            bytes[p + 1] = 0
                        } else if (transpose != 0 && channel != 9) {
                            val pitch = bytes[p].toInt() and 0xff
                            bytes[p] = (pitch + transpose).coerceIn(0, 127).toByte()
                        }
                    }
                    p += dataCount
                }
                else -> break
            }
        }
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
        String(bytes, offset, count, Charsets.US_ASCII)
}
