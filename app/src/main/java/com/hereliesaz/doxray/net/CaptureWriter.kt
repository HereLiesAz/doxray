package com.hereliesaz.doxray.net

import java.io.File

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

    override fun write(filename: String, bytes: ByteArray) {
        if (!directory.exists()) directory.mkdirs()
        val out = File(directory, filename)
        out.writeBytes(bytes)
    }
}
