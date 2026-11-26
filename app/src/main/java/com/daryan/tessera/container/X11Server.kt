// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.termux.x11.CmdEntryPoint
import com.termux.x11.LorieApp
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException

/** The X server and window operations [SessionManager] needs; fakeable in JVM tests. */
interface X11 {
    suspend fun start(session: Session): Process
    fun showWindow(session: Session, windowId: Long? = null)
    fun closeWindow(session: Session)
}

/**
 * One Termux:X11 server per GUI session: its own display number, its own socket in the
 * session's private /tmp, and its own Android window (a tagged MainActivity document).
 */
class X11Server(
    private val context: Context,
    private val paths: ContainerPaths,
    /** Reads assets/xkb/VERSION; a seam so JVM tests can model a build without generated data. */
    private val xkbVersion: () -> String = {
        context.assets.open("xkb/VERSION").bufferedReader().use { it.readText().trim() }
    },
    /** Starts the X process; a seam so JVM tests can assert none was spawned. */
    private val startServer: (ProcessBuilder) -> Process = { it.start() },
) : X11 {

    /** Starts the server for [session]. Returns once its socket accepts connections. */
    override suspend fun start(session: Session): Process {
        // Check first, and name everything missing in one message: a build that skipped the
        // generator has neither artifact, and one at a time wastes a launch attempt per error.
        val bundledXkb = preflight()
        val display = session.display ?: throw IllegalArgumentException("session has no display")
        val xkb = installXkb(bundledXkb)
        // In chroot mode clients run as uid 1000, not as the app; let them reach the socket.
        paths.x11SocketDir(session).apply {
            mkdirs()
            setReadable(true, false)
            setWritable(true, false)
            setExecutable(true, false)
        }

        // Drain stdout/stderr from the start. Leaving ProcessBuilder's pipe unread after the
        // server becomes ready can fill the pipe and stall Xorg later, freezing every X client.
        // Keep only a bounded tail for diagnostics so a noisy server cannot grow memory forever.
        val log = StringBuilder()
        val logLock = Any()
        val proc = ProcessBuilder(
            "/system/bin/app_process", "/", CmdEntryPoint::class.java.name,
            ":$display", "-tag", windowTag(session),
        ).redirectErrorStream(true).apply {
            environment()["CLASSPATH"] = context.applicationInfo.sourceDir
            // We extract native libs (PRoot needs that), so lorie can't dlopen from the APK.
            environment()["XLORIE_LIBRARY"] = File(paths.nativeLibDir, "libXlorie.so").path
            environment()["TMPDIR"] = paths.sessionTmp(session).path
            environment()["XKB_CONFIG_ROOT"] = xkb.path
        }.let(startServer)
        val logReader = Thread({
            proc.inputStream.use { input ->
                val bytes = ByteArray(1024)
                while (true) {
                    val count = try { input.read(bytes) } catch (_: IOException) { break }
                    if (count < 0) break
                    synchronized(logLock) {
                        log.append(String(bytes, 0, count, Charsets.UTF_8))
                        if (log.length > MAX_LOG_CHARS) log.delete(0, log.length - MAX_LOG_CHARS)
                    }
                }
            }
        }, "tessera-x11-log-${session.id}").apply { isDaemon = true; start() }
        fun logTail(): String = synchronized(logLock) { log.toString().trim().lines().takeLast(3).joinToString(" / ") }

        val socket = File(paths.x11SocketDir(session), "X$display")
        repeat(100) {
            // Accepting, not just there: the socket file exists a moment before the server
            // listens, and a client connecting in between is refused. PRoot's slow start hid
            // that; chroot clients are quick enough to hit it ("Can't open display").
            if (socket.exists() && accepts(socket)) return proc
            if (!proc.isAlive) {
                logReader.join(250)
                throw IOException("X server exited (${proc.exitValue()}): ${logTail()}")
            }
            delay(50)
        }
        proc.destroy()
        logReader.join(250)
        throw IOException("X server didn't create $socket within 5 s: ${logTail()}")
    }

    /**
     * Both generated artifacts must be in the APK before any process or session work. Reports all
     * that are missing together, so the user gets one actionable error instead of a crash later.
     */
    private fun preflight(): String {
        val bundled = runCatching(xkbVersion).getOrNull()
        val nativeLib = File(paths.nativeLibDir, "libXlorie.so")
        val missing = buildList {
            if (bundled == null) add("assets/xkb/VERSION")
            if (!nativeLib.exists()) add("libXlorie.so")
        }
        if (missing.isNotEmpty()) throw IOException(
            "missing X11 artifacts: ${missing.joinToString(", ")}; run scripts/x11/build-native.sh and rebuild the app",
        )
        return bundled!!
    }

    private fun accepts(socket: File): Boolean = runCatching {
        android.net.LocalSocket().use { it.connect(android.net.LocalSocketAddress(socket.path, android.net.LocalSocketAddress.Namespace.FILESYSTEM)) }
    }.isSuccess

    /**
     * Opens (or brings back) the session's window, or with [windowId] the window of one of
     * its app's X windows: a task of its own, sharing the session's server.
     */
    override fun showWindow(session: Session, windowId: Long?) {
        val path = windowId?.let { "/$it" } ?: ""
        context.startActivity(
            Intent().setClassName(context, TAGGED_WINDOW)
                .setData(Uri.parse("termux-x11-window://${windowTag(session)}$path"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Closes the session's window; the server itself is stopped by destroying its process. */
    override fun closeWindow(session: Session) {
        (context.applicationContext as? LorieApp)?.onBroadcastReceive(
            Intent(ACTION_STOP).putExtra(CmdEntryPoint.EXTRA_DOCUMENT_TAG, windowTag(session)),
        )
    }

    /** Copies the bundled xkb data out of the APK once per version; the server reads it as files. */
    private fun installXkb(bundled: String): File {
        val dir = File(paths.filesDir, "xkb")
        val marker = File(dir, "VERSION")
        if (marker.takeIf { it.exists() }?.readText()?.trim() == bundled) return dir

        dir.deleteRecursively()
        copyAssetDir("xkb", dir)
        return dir
    }

    private fun copyAssetDir(asset: String, dest: File) {
        val children = context.assets.list(asset).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(asset).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        children.forEach { copyAssetDir("$asset/$it", File(dest, it)) }
    }

    companion object {
        /** Our window class (a Termux:X11 tagged window with Material 3 controls on top). */
        const val TAGGED_WINDOW = "com.daryan.tessera.X11Window"
        private const val ACTION_STOP = "com.termux.x11.ACTION_STOP"
        private const val MAX_LOG_CHARS = 8192

        /** Identifies a session's Termux:X11 window (its `-tag` and document data). */
        fun windowTag(session: Session) = "linux-${session.id}"

        /** The X window an Android window's data names, or null for the session's own window. */
        fun windowId(data: Uri?): Long? = data?.path?.trim('/')?.toLongOrNull()
    }
}
