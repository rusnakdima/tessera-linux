// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The HTTP a rootfs download goes through: a bare [HttpURLConnection] wrapper with a Tessera User
 * Agent, redirect-following, error-honouring, and resumable streaming into a partial file.
 */
object HttpFetch {
    const val USER_AGENT = "Tessera/1.0"

    fun text(url: String): String {
        val conn = open(url)
        return try {
            if (conn.responseCode != 200) throw IOException("GET $url: HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Streams [url] into [dest], resuming from a partial download; calls [onProgress] with bytes written. */
    suspend fun download(url: String, dest: File, expected: Long, onProgress: (Long) -> Unit) {
        val partial = File(dest.path + ".part")
        val resumeFrom = if (partial.exists()) partial.length() else 0L
        try {
            FileOutputStream(partial, resumeFrom > 0).use { out ->
                val conn = open(url).apply {
                    instanceFollowRedirects = true
                    if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
                }
                conn.connect()
                if (conn.responseCode !in 200..299) throw IOException("download $url: HTTP ${conn.responseCode}")
                conn.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var done = resumeFrom
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        onProgress(done)
                    }
                }
            }
            if (!partial.renameTo(dest)) throw IOException("could not move ${partial.name} into place")
        } catch (e: CancellationException) {
            partial.delete()
            throw e
        }
    }

    /** Content-Length in bytes of [url], for the free-space check and progress; null when it can't say. */
    fun contentLength(url: String): Long? {
        val conn = open(url).apply { setRequestMethod("HEAD") }
        return try {
            conn.connect()
            if (conn.responseCode !in 200..299) throw IOException("HEAD $url: HTTP ${conn.responseCode}")
            (conn as java.net.URLConnection).getHeaderField("Content-Length")?.toLongOrNull()
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 60_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", USER_AGENT)
    }
}
