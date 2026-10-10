package com.wtifs.photos

import java.io.File
import java.io.RandomAccessFile

/** Find the main JPEG's end, skipping metadata, stuffed scan bytes and restart markers. */
internal fun jpegEnd(file: File): Long = file.inputStream().buffered().use { input ->
    var offset = 0L
    fun next(): Int {
        val value = input.read()
        require(value >= 0) { "Incomplete JPEG. Original was not changed." }
        offset++
        return value
    }
    require(next() == 0xff && next() == 0xd8) { "This file is not a JPEG." }
    var inScan = false
    while (true) {
        var prefix = next()
        if (inScan) {
            while (prefix != 0xff) prefix = next()
        } else require(prefix == 0xff) { "Invalid JPEG marker." }
        var marker = next()
        while (marker == 0xff) marker = next()
        if (inScan && (marker == 0 || marker in 0xd0..0xd7)) continue
        if (marker == 0xd9) return@use offset
        if (marker == 0x01) continue
        require(marker != 0 && marker != 0xd8) { "Invalid JPEG marker." }
        val length = next() * 256 + next()
        require(length >= 2) { "Invalid JPEG segment." }
        repeat(length - 2) { next() }
        inScan = marker == 0xda
    }
    @Suppress("UNREACHABLE_CODE")
    error("Missing JPEG end")
}

/** Only truncate after a complete JPEG and a validated appended ISO-BMFF video. */
internal fun removeMotionTail(file: File) {
    val end = jpegEnd(file)
    var candidate = -1L
    file.inputStream().buffered().use { input ->
        var offset = 0L
        var window = 0L
        while (true) {
            val value = input.read()
            if (value < 0) break
            window = (window shl 8) or value.toLong()
            offset++
            if (offset >= end + 8 && (window and 0xffffffffL) == 0x66747970L) {
                val start = offset - 8
                if (validVideoTail(file, start)) {
                    candidate = start
                    break
                }
            }
        }
    }
    require(candidate >= end) { "No supported embedded video found. Original was not changed." }
    RandomAccessFile(file, "rw").use { it.setLength(candidate) }
}

private fun validVideoTail(file: File, start: Long): Boolean = RandomAccessFile(file, "r").use { input ->
    var position = start
    var hasMovie = false
    var hasData = false
    while (position < input.length()) {
        if (input.length() - position < 8) return@use false
        input.seek(position)
        var size = input.readInt().toLong() and 0xffffffffL
        val type = input.readInt()
        var header = 8L
        if (size == 1L) {
            if (input.length() - position < 16) return@use false
            size = input.readLong()
            header = 16L
        } else if (size == 0L) size = input.length() - position
        if (size < header || size > input.length() - position) return@use false
        if (position == start && (type != 0x66747970 || size < header + 8)) return@use false
        if (type == 0x6d6f6f76) hasMovie = true
        if (type == 0x6d646174) hasData = true
        position += size
    }
    hasMovie && hasData
}
