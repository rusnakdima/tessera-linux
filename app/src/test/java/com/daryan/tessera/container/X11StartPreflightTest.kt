// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * A build that skipped scripts/x11/build-native.sh has neither the xkb asset nor libXlorie.so.
 * The server must name BOTH in one error, before it touches a session directory or ProcessBuilder,
 * so the user gets one actionable "Couldn't start" message rather than a crash or a partial start.
 */
class X11StartPreflightTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `start names both missing artifacts before spawning a process`() = runBlocking {
        val paths = ContainerPaths(
            nativeLibDir = File(tmp.root, "lib"),
            filesDir = File(tmp.root, "files"),
            cacheDir = File(tmp.root, "cache"),
            prootRootfs = File(tmp.root, "files/rootfs"),
            chrootRootfs = File(tmp.root, "chroot"),
        )
        var spawned = 0
        val x11 = X11Server(
            ContextWrapper(null), paths,
            xkbVersion = { throw IOException("xkb/VERSION is not in this APK") },
            startServer = { spawned++; error("no X process may be spawned on this build") },
        )
        val session = Session(id = "s1", display = 1)

        val failure = runCatching { x11.start(session) }.exceptionOrNull()

        assertTrue("expected IOException, got $failure", failure is IOException)
        val message = failure!!.message.orEmpty()
        assertTrue(message, "assets/xkb/VERSION" in message)
        assertTrue(message, "libXlorie.so" in message)
        assertTrue(message, "scripts/x11/build-native.sh" in message)
        assertEquals("no process was spawned", 0, spawned)
        assertTrue("no session work happened", !paths.x11SocketDir(session).exists())
    }
}
