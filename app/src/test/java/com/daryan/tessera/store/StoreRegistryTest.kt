// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.store

import com.daryan.tessera.container.ConfigStore
import com.daryan.tessera.container.Distro
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class StoreRegistryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `dpkg status lists only fully installed packages`() {
        val status = """
            Package: gimp
            Status: install ok installed
            Version: 3.0

            Package: vlc
            Status: deinstall ok config-files

            Package: firefox-esr
            Status: install ok installed
        """.trimIndent()
        assertEquals(setOf("gimp", "firefox-esr"), StoreRegistry.installedPackages(status))
    }

    @Test fun `a pacman desc gives the package name`() {
        val desc = "%NAME%\n\nfirefox\n\n%VERSION%\n\n1.0-1\n\n"
        assertEquals("firefox", StoreRegistry.parsePacmanDesc(desc))
    }

    @Test fun `a pacman desc without a name gives null`() {
        assertNull(StoreRegistry.parsePacmanDesc("%VERSION%\n\n1.0-1\n\n"))
        assertNull(StoreRegistry.parsePacmanDesc("%NAME%\n\n\n"))
        assertNull(StoreRegistry.parsePacmanDesc("%NAME%\n\n%VERSION%\n\n1.0-1\n\n"))
    }

    @Test fun `arch installed packages come from the pacman local database`() = runBlocking {
        val store = FakeConfigStore(
            files = mapOf(
                "/var/lib/pacman/local/firefox-120.0-1/desc" to "%NAME%\n\nfirefox\n\n%VERSION%\n\n120.0-1\n\n",
                "/var/lib/pacman/local/nano-8.0-1/desc" to "%NAME%\n\nnano\n\n%VERSION%\n\n8.0-1\n\n",
            ),
            dirs = mapOf("/var/lib/pacman/local" to listOf("firefox-120.0-1", "nano-8.0-1")),
        )
        val registry = registry()
        registry.refreshInstalled(store, Distro.ARCH)
        assertEquals(setOf("firefox", "nano"), registry.installed.value)
        assertFalse("/var/lib/dpkg/status" in store.reads)
    }

    @Test fun `an unreadable pacman database publishes an empty set, not dpkg`() = runBlocking {
        val store = FakeConfigStore(
            files = mapOf("/var/lib/dpkg/status" to "Package: nano\nStatus: install ok installed\n\n"),
            failList = true,
        )
        val registry = registry()
        registry.refreshInstalled(store, Distro.ARCH)
        assertEquals(emptySet<String>(), registry.installed.value)
        assertFalse("/var/lib/dpkg/status" in store.reads)
    }

    @Test fun `debian still reads dpkg and never the pacman database`() = runBlocking {
        val store = FakeConfigStore(
            files = mapOf("/var/lib/dpkg/status" to "Package: nano\nStatus: install ok installed\n\n"),
            dirs = mapOf("/var/lib/pacman/local" to listOf("firefox-120.0-1")),
        )
        val registry = registry()
        registry.refreshInstalled(store)
        assertEquals(setOf("nano"), registry.installed.value)
        assertEquals(emptyList<String>(), store.lists)
    }

    private fun registry() = StoreRegistry(
        tmp.newFolder("apps"),
        StoreCatalog(tmp.newFolder("catalog"), CoroutineScope(Dispatchers.Unconfined + Job())),
    )

    private class FakeConfigStore(
        private val files: Map<String, String> = emptyMap(),
        private val dirs: Map<String, List<String>> = emptyMap(),
        private val failList: Boolean = false,
    ) : ConfigStore {
        val reads = mutableListOf<String>()
        val lists = mutableListOf<String>()

        override suspend fun read(path: String): String? {
            reads += path
            return files[path]
        }

        override suspend fun write(path: String, text: String) = Unit

        override suspend fun list(path: String): List<String> {
            lists += path
            if (failList) throw IOException("su: permission denied")
            return dirs[path].orEmpty()
        }
    }

    @Test fun `a browser gets network and Downloads, a GUI window and sound`() {
        val app = StoreApp("org.example.browser.desktop", "browser", "Browser", "Browse", "", listOf("Network", "WebBrowser"), "browser_browser.png", "main", "browser.desktop")
        val m = StoreRegistry.manifestFor(app, DesktopEntry("Browser", listOf("browser", "--new"), terminal = false, icon = null), "/x/icon.png")
        assertEquals("Internet", m.category)
        assertEquals(listOf("browser", "--new"), m.exec)
        assertTrue(m.gui && m.permissions.display && m.permissions.audio && m.permissions.network)
        assertEquals(listOf("/home/user/Downloads"), m.permissions.filesystem.map { it.mountAt })
        assertEquals("/x/icon.png", m.iconFile)
        assertEquals(emptyList<String>(), m.validate())
    }

    @Test fun `an offline image editor gets Pictures but no network`() {
        val app = StoreApp("gimp.desktop", "gimp", "GIMP", "", "", listOf("Graphics", "2DGraphics"), null, "main", "gimp.desktop")
        val m = StoreRegistry.manifestFor(app, null, null)
        assertFalse(m.permissions.network)
        assertEquals(listOf("/home/user/Pictures"), m.permissions.filesystem.map { it.mountAt })
        // Without a .desktop file it falls back to the package name.
        assertEquals(listOf("/usr/bin/gimp"), m.exec)
    }

    @Test fun `terminal apps run in a terminal without a display`() {
        val app = StoreApp("htop.desktop", "htop", "htop", "", "", listOf("System", "Monitor"), null, "main", "htop.desktop")
        val m = StoreRegistry.manifestFor(app, DesktopEntry("htop", listOf("htop"), terminal = true, icon = null), null)
        assertFalse(m.gui || m.permissions.display)
        assertEquals("System", m.category)
    }
}
