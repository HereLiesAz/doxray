package com.hereliesaz.doxray.net

import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Sink for captured HTTP traffic. Production implementation writes to disk;
 * tests inject a fake to assert what was captured.
 */
interface CaptureWriter {
    fun write(filename: String, bytes: ByteArray)
}

/**
 * Writes captures into [directory]. Filename is built by the caller; this
 * class only handles disk I/O.
 */
class FileCaptureWriter(private val directory: File) : CaptureWriter {
    private val seq = AtomicLong(0L)

    override fun write(filename: String, bytes: ByteArray) {
        if (!directory.exists()) directory.mkdirs()
        val out = File(directory, filename)
        out.writeBytes(bytes)
    }

    fun nextSeq(): Long = seq.incrementAndGet()
}
