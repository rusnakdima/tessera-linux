// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConfigStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `first save keeps the original, later saves don't overwrite it`() = runBlocking {
        val root = tmp.newFolder("rootfs")
        File(root, "root").mkdirs()
        File(root, "root/.bashrc").writeText("original\n")
        val store = DirectConfigStore(root)

        assertEquals("original\n", store.read("/root/.bashrc"))
        store.write("/root/.bashrc", "edit 1\n")
        store.write("/root/.bashrc", "edit 2\n")
        assertEquals("edit 2\n", store.read("/root/.bashrc"))
        assertEquals("original\n", File(root, "root/.bashrc.bak-launcher").readText())
    }

    @Test fun `missing files read as null and are created on save`() = runBlocking {
        val store = DirectConfigStore(tmp.newFolder("rootfs"))
        assertNull(store.read("/home/user/.bashrc"))
        store.write("/home/user/.bashrc", "alias ll='ls -l'\n")
        assertEquals("alias ll='ls -l'\n", store.read("/home/user/.bashrc"))
    }

    @Test fun `paths can't escape the rootfs`() {
        val store = DirectConfigStore(tmp.newFolder("rootfs"))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.read("/etc/../../outside") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.write("relative", "x") } }
    }

    @Test fun `listing names a directory's entries and misses read as empty`() = runBlocking {
        val root = tmp.newFolder("rootfs")
        File(root, "var/lib/pacman/local/firefox-120.0-1").mkdirs()
        File(root, "var/lib/pacman/local/nano-8.0-1").mkdirs()
        val store = DirectConfigStore(root)

        assertEquals(setOf("firefox-120.0-1", "nano-8.0-1"), store.list("/var/lib/pacman/local").toSet())
        assertEquals(emptyList<String>(), store.list("/var/lib/pacman/local/missing"))
    }

    @Test fun `catalog paths are absolute and unique`() {
        val paths = ConfigCatalog.files.map { it.path }
        assertTrue(paths.all { it.startsWith("/") })
        assertEquals(paths.size, paths.toSet().size)
    }
}
