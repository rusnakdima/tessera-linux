// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.GZIPInputStream

/**
 * Reading an image layer's member names is enough to know which paths a later layer may replace.
 * No hardcoding of individual distro files: the names come from the layer that is about to run.
 */
internal object TarMembers {
    /** Member names of a gzip-compressed tar, in archive order, normalised and safety-checked. */
    fun read(file: File): List<String> = GZIPInputStream(file.inputStream().buffered()).use { read(it) }

    internal fun read(input: InputStream): List<String> {
        val names = mutableListOf<String>()
        val header = ByteArray(512)
        var longName: String? = null
        var paxPath: String? = null
        while (readFully(input, header, 512)) {
            if (header.all { it == 0.toByte() }) break // the zero block(s) ending the archive
            val type = header[156].toInt().toChar()
            val size = octal(header, 124, 12)
            when (type) {
                'L' -> { // GNU long name: applies to the next real member
                    longName = readData(input, size).toString(Charsets.UTF_8).trimEnd('\u0000')
                }
                'x', 'g' -> { // PAX extended header: "path=" overrides the next member's name
                    if (type == 'x') paxPath = paxPath(readData(input, size))
                }
                else -> {
                    skipFully(input, size + padding(size))
                    val raw = longName ?: paxPath ?: (field(header, 0, 100) + field(header, 345, 155))
                    longName = null
                    paxPath = null
                    normalise(raw)?.let { if (it.isNotEmpty()) names.add(it) }
                }
            }
        }
        return names
    }

    private fun field(header: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { header[it] == 0.toByte() } ?: (offset + length)
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    private fun octal(header: ByteArray, offset: Int, length: Int): Long {
        var value = 0L
        for (i in offset until offset + length) {
            val c = header[i].toInt()
            if (c == 0 || c == 32) continue
            require(c in 0x30..0x37) { "invalid tar size field" }
            value = value * 8 + (c - 0x30)
        }
        return value
    }

    private fun readData(input: InputStream, size: Long): ByteArray {
        require(size in 0..1_048_576) { "oversized tar metadata record" }
        val data = ByteArray(size.toInt())
        if (!readFully(input, data, data.size)) throw IOException("truncated tar metadata")
        skipFully(input, padding(size))
        return data
    }

    private fun skipFully(input: InputStream, size: Long) {
        val buffer = ByteArray(8192)
        var left = size
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (n < 0) throw IOException("truncated tar archive")
            left -= n
        }
    }

    private fun padding(size: Long): Long = (512 - size % 512) % 512

    private fun readFully(input: InputStream, target: ByteArray, length: Int): Boolean {
        var off = 0
        while (off < length) {
            val n = input.read(target, off, length - off)
            if (n < 0) return off == 0
            off += n
        }
        return true
    }

    /** Drops "./" and empty components; absolute paths and ".." are never allowed under the root. */
    private fun normalise(raw: String): String? {
        val name = raw.trim().trimEnd('\u0000')
        if (name.isEmpty()) return null
        require(!name.startsWith("/")) { "unsafe tar member: $name" }
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        require(parts.none { it == ".." }) { "unsafe tar member: $name" }
        return parts.joinToString("/")
    }

    /** Reads the "path=" value from a PAX extended header's length-prefixed records. */
    private fun paxPath(data: ByteArray): String? {
        var offset = 0
        var found: String? = null
        while (offset < data.size) {
            var space = offset
            while (space < data.size && data[space] != ' '.code.toByte()) space++
            if (space >= data.size) break
            val length = String(data, offset, space - offset, Charsets.US_ASCII).toIntOrNull() ?: break
            if (length <= 0 || offset + length > data.size) break
            val record = String(data, space + 1, length - (space - offset) - 1, Charsets.UTF_8)
            if (record.startsWith("path=")) found = record.removePrefix("path=").trimEnd('\n')
            offset += length
        }
        return found
    }
}

/** One layer's tarball and the names of the members it will write. */
internal data class LayerInput(val tarball: String, val members: List<String>)

/** What extracting the layers in order involves. */
internal sealed interface ExtractStep {
    /** Unlink symlinks that a later layer's members (or their parents) would collide with. */
    data class ClearSymlinks(val paths: List<String>) : ExtractStep

    data class Unpack(val tarball: String) : ExtractStep
}

/**
 * The first layer goes straight into the empty root. Every later layer may overwrite entries
 * the earlier ones created, so clear those paths — and symlinked parents of them — first.
 */
internal fun extractionPlan(layers: List<LayerInput>): List<ExtractStep> {
    require(layers.isNotEmpty()) { "at least one layer is needed" }
    val steps = mutableListOf<ExtractStep>()
    for ((index, layer) in layers.withIndex()) {
        if (index > 0) steps += ExtractStep.ClearSymlinks(cleanupPaths(layer.members))
        steps += ExtractStep.Unpack(layer.tarball)
    }
    return steps
}

/** Prepares the plan inputs; the first layer's members are never read (single-layer behavior stays put). */
internal fun layersOf(tarballs: List<File>): List<LayerInput> = tarballs.mapIndexed { index, file ->
    LayerInput(file.path, if (index == 0) emptyList() else TarMembers.read(file))
}

/** A member and each of its parents, shallowest first, so a symlinked parent is dropped before use. */
internal fun cleanupPaths(members: List<String>): List<String> {
    val paths = linkedSetOf<String>()
    for (member in members) {
        val parts = member.split('/').filter { it.isNotEmpty() }
        for (i in parts.indices) paths.add(parts.take(i + 1).joinToString("/"))
    }
    return paths.toList()
}

/** The root-side shell form of [clearSymlinks], run inside the script where $T is the staging root. */
internal fun clearSymlinkShell(paths: List<String>): String =
    "for p in " + paths.joinToString(" ") { shellQuote(it) } + "; do " +
        "if [ -L \"\$T/\$p\" ]; then rm -f -- \"\$T/\$p\"; fi; done"

/** Removes only symlinks, never real files or directories, at [paths] under [root]. */
internal fun clearSymlinks(root: File, paths: List<String>) {
    for (path in paths) {
        val file = File(root, path)
        if (Files.isSymbolicLink(file.toPath())) Files.delete(file.toPath())
    }
}
