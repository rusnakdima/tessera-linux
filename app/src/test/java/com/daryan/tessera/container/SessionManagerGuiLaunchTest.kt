// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import android.content.ContextWrapper
import android.content.SharedPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * A GUI session whose X server cannot start must leave a "Couldn't start" note, never an escaping
 * Error. The defect this pins: [SessionManager]'s `finally` called `closeWindow` unguarded, and
 * Termux:X11's static loadLibrary there threw `UnsatisfiedLinkError` (an Error, not an Exception),
 * which killed the whole app instead of just the session.
 */
class SessionManagerGuiLaunchTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Context with working prefs (GPU off) so launch() reaches the GUI path in a JVM test. */
    private class FakeContext : ContextWrapper(null) {
        private val prefs = object : SharedPreferences {
            override fun getAll(): MutableMap<String, Any?> = mutableMapOf()
            override fun getString(key: String?, defValue: String?): String? = defValue
            override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
            override fun getInt(key: String?, defValue: Int): Int = defValue
            override fun getLong(key: String?, defValue: Long): Long = defValue
            override fun getFloat(key: String?, defValue: Float): Float = defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = false
            override fun contains(key: String?): Boolean = false
            override fun edit(): SharedPreferences.Editor = error("not used in this test")
            override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    private class FakeX11(private val gate: CompletableDeferred<Unit>? = null) : X11 {
        val shows = java.util.concurrent.atomic.AtomicInteger(0)
        val closes = java.util.concurrent.atomic.AtomicInteger(0)
        val enteredClose = CompletableDeferred<Unit>()

        override suspend fun start(session: Session): Process {
            // Let launch() register the session before the failure, like a real X start would.
            gate?.await()
            throw IOException("this build has no xkb data; run scripts/x11/build-native.sh")
        }

        override fun showWindow(session: Session, windowId: Long?) { shows.incrementAndGet() }

        override fun closeWindow(session: Session) {
            closes.incrementAndGet()
            enteredClose.complete(Unit)
            throw UnsatisfiedLinkError("dlopen failed: library \"libXlorie.so\" not found")
        }
    }

    private class FakeBackend : ContainerBackend {
        override val name = "PRoot"
        override fun enforcement(capability: Capability, app: PermissionManifest) = Enforcement.ADVISORY
        override fun buildLaunch(app: PermissionManifest, session: Session) =
            LaunchSpec(listOf("/bin/true"), emptyMap(), "/")
    }

    private fun paths() = ContainerPaths(
        nativeLibDir = File(tmp.root, "lib"),
        filesDir = File(tmp.root, "files"),
        cacheDir = File(tmp.root, "cache"),
        prootRootfs = File(tmp.root, "files/rootfs"),
        chrootRootfs = File(tmp.root, "chroot"),
    )

    @Test fun `a failed X11 start with a crashing closeWindow is non-fatal and records why`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeX11(gate)
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            Dispatchers.Default + SupervisorJob() +
                CoroutineExceptionHandler { _, t -> synchronized(errors) { errors += t } },
        )
        val manager = SessionManager(
            FakeContext(), scope, paths(), x11 = fake, main = Dispatchers.Default,
        )
        val app = PermissionManifest(
            id = "obs", name = "OBS Studio", gui = true, exec = listOf("/bin/obs"),
            permissions = Permissions(display = true),
        )

        manager.launch(app, FakeBackend())
        gate.complete(Unit)

        val recorded = withTimeout(5_000) {
            manager.sessions.first { list -> list.any { it.session.id == "s1" && it.exitCode != null } }
                .first { it.session.id == "s1" }
        }
        withTimeout(5_000) { fake.enteredClose.await() }
        kotlinx.coroutines.delay(200)
        assertEquals(-1, recorded.exitCode)
        assertTrue(recorded.output, recorded.output.startsWith("Couldn't start:"))
        assertTrue("escaped errors: ${errors.joinToString { it.toString() }}", errors.isEmpty())
        // Cleanup is attempted exactly once; the runCatching guard must swallow its Error.
        assertEquals(1, fake.closes.get())
    }
}
