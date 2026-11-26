// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/**
 * A later OCI layer can replace a symlink an earlier layer created. toybox tar refuses to
 * overwrite such a symlink ("not under" the extract root), so the conflicting links have to be
 * removed between passes, driven by the names in the upcoming layer.
 */
class LayerSymlinkCleanupTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `chroot script clears layer two member symlinks between the tar passes`() {
        val first = tmp.newFile("layer-0.tar.gz").apply { writeTarGz("usr/bin" to "") }
        val second = tmp.newFile("layer-1.tar.gz").apply {
            writeTarGz(
                "etc/os-release" to "ID=arch\n",
                "usr/lib/os-release" to "ID=arch\n",
            )
        }

        val script = ChrootSetup.script(
            "/data/local/linux/arch_x86_64", listOf(first.path, second.path), "arch marker", Distro.ARCH_X86_64,
        )

        val tarLines = script.lines().filter { "/system/bin/tar -xzf" in it }
        assertEquals(2, tarLines.size)
        val clear = script.lines().firstOrNull { "for p in" in it && "-L" in it }
        assertNotNull("layer 2 should clear conflicting symlinks before tar runs", clear)
        assertTrue(clear!!.contains("etc/os-release"))
        assertTrue("parent components must be cleared too", clear.contains("etc"))
        assertTrue(script.indexOf(clear) > script.indexOf(tarLines[0]))
        assertTrue(script.indexOf(clear) < script.indexOf(tarLines[1]))
    }

    @Test
    fun `single layer chroot script is unchanged with no cleanup step`() {
        val script = ChrootSetup.script("/root/debian", "/tmp/d.tar.gz", "sha256:x", Distro.DEBIAN)
        assertEquals(1, script.lines().count { "/system/bin/tar -xzf" in it })
        assertFalse(script.contains(" -L "))
        assertEquals(
            "/system/bin/tar -xzf '/tmp/d.tar.gz' -C \"\$T\"",
            script.lines().first { "/system/bin/tar -xzf" in it }.trim(),
        )
    }

    @Test
    fun `proot extraction plan clears layer two members before unpacking it`() {
        val plan = extractionPlan(
            listOf(
                LayerInput("/cache/0.tar.gz", listOf("usr/bin")),
                LayerInput(
                    "/cache/1.tar.gz", listOf("etc/os-release", "usr/lib/os-release"),
                ),
            ),
        )
        assertEquals(
            listOf(
                ExtractStep.Unpack("/cache/0.tar.gz"),
                ExtractStep.ClearSymlinks(
                    listOf("etc", "etc/os-release", "usr", "usr/lib", "usr/lib/os-release"),
                ),
                ExtractStep.Unpack("/cache/1.tar.gz"),
            ),
            plan,
        )
    }

    @Test
    fun `member names come from the upcoming layer and unsafe names are rejected`() {
        val layer = tmp.newFile("members.tar.gz").apply {
            writeTarGz("etc/os-release" to "ID=arch\n", "usr/lib/os-release" to "ID=arch\n")
        }
        assertEquals(listOf("etc/os-release", "usr/lib/os-release"), TarMembers.read(layer))

        val absolute = tmp.newFile("absolute.tar.gz").apply { writeTarGz("/etc/passwd" to "") }
        assertThrows(IllegalArgumentException::class.java) { TarMembers.read(absolute) }
        val traversing = tmp.newFile("traversing.tar.gz").apply { writeTarGz("etc/../../etc/passwd" to "") }
        assertThrows(IllegalArgumentException::class.java) { TarMembers.read(traversing) }
    }

    @Test
    fun `clearing drops only symlinks and never follows them outside the root`() {
        val root = tmp.newFolder("root").toPath()
        val outside = tmp.newFolder("outside").toPath()
        Files.createSymbolicLink(root.resolve("etc"), outside)
        Files.createSymbolicLink(outside.resolve("os-release"), File("/nowhere").toPath())
        Files.createDirectories(root.resolve("usr"))
        Files.write(root.resolve("usr/keep"), "real".toByteArray())

        clearSymlinks(File(root.toString()), cleanupPaths(listOf("etc/os-release", "usr/keep")))

        assertFalse(Files.isSymbolicLink(root.resolve("etc")))
        assertTrue("a symlink reached through a parent must be untouched", Files.isSymbolicLink(outside.resolve("os-release")))
        assertTrue(Files.isRegularFile(root.resolve("usr/keep")))
    }
}

/** A minimal gzip tar with regular-file members, enough for the member-name reader. */
internal fun File.writeTarGz(vararg entries: Pair<String, String>) {
    GZIPOutputStream(outputStream().buffered()).use { out ->
        for ((name, body) in entries) {
            val header = ByteArray(512)
            val bytes = name.toByteArray(Charsets.UTF_8)
            bytes.copyInto(header, 0, 0, minOf(bytes.size, 100))
            header[156] = '0'.code.toByte() // regular file
            fun octal(offset: Int, len: Int, value: Long) {
                val field = value.toString(8).padStart(len - 1, '0') + "\u0000"
                field.toByteArray().copyInto(header, offset, 0, len)
            }
            octal(100, 8, 0x1A4) // 0644
            octal(108, 8, 0)
            octal(116, 8, 0)
            octal(124, 12, body.length.toLong())
            octal(136, 12, 0)
            out.write(header)
            val data = body.toByteArray()
            out.write(data)
            out.write(ByteArray((512 - data.size % 512) % 512))
        }
        out.write(ByteArray(1024))
    }
}
