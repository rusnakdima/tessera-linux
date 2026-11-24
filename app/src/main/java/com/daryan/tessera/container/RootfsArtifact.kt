// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import java.io.File
import java.security.MessageDigest

/**
 * One downloadable rootfs: where it is, how big it is, and the checksum that proves a copy of it
 * is the real one. [key] names the artifact — the Arch tarball is published under a moving name
 * (`arch-aarch64-latest`) and re-published over time, so the key is what a stale cache entry is
 * recognised by, not a content digest.
 */
data class RootfsArtifact(
    val key: String,
    val url: String,
    val size: Long,
    val checksum: String,
    val algorithm: String,
) {
    /**
     * True when [file] is a complete copy of this artifact: the right size and the right digest
     * ([algorithm], hex — `MD5` for the Arch tarball, `SHA-256` for a registry layer).
     */
    fun verify(file: File): Boolean {
        if (file.length() != size) return false
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(checksum.trim().lowercase(), ignoreCase = true)
    }
}
