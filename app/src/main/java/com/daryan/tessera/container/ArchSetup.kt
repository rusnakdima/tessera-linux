// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import java.io.File

/**
 * First-boot configuration of a freshly extracted Arch rootfs: prime the keyring and let pacman
 * populate it, so the first install has keys to work with. Arch Linux ARM writes an ALARM mirror
 * only when the image has none; official x86_64 keeps the mirrorlist it ships with and syncs its
 * package database once. Plain shell text, no guest code runs here.
 */
object ArchSetup {
    fun firstBootScript(distro: Distro = Distro.ARCH): String = if (distro == Distro.ARCH_X86_64) """
        set -e
        # PRoot passes the app's Android PATH through; the guest's commands are under /usr/bin.
        export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        # The official x86_64 image ships a working mirrorlist; leave it exactly as it shipped.
        /usr/bin/pacman-key --init
        /usr/bin/pacman-key --populate archlinux
        [ -f /var/lib/pacman/sync/core.db ] || /usr/bin/pacman -Syu --noconfirm
    """.trimIndent() else """
        set -e
        # PRoot passes the app's Android PATH through; the guest's commands are under /usr/bin.
        export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        mkdir -p /etc/pacman.d
        # pacman expands the ${'$'}arch and ${'$'}repo placeholders itself; the image's own list already works, so only fill in a missing one.
        [ -e /etc/pacman.d/mirrorlist ] || echo 'Server = https://ca.us.mirror.archlinuxarm.org/${'$'}arch/${'$'}repo' > /etc/pacman.d/mirrorlist
        /usr/bin/pacman-key --init
        /usr/bin/pacman-key --populate archlinuxarm
    """.trimIndent()

    /**
     * PRoot's arguments for the first boot, separate from the run so a JVM test can check them.
     * `--kill-on-exit` is needed here, unlike in a session: `pacman-key` daemonises `gpg-agent`,
     * and without it PRoot's event loop waits on that tracee forever (and `readLines()` never
     * sees EOF). Normal and Flatpak sessions deliberately keep their own argv in [ProotBackend].
     */
    internal fun firstBootArgv(root: File, distro: Distro = Distro.ARCH): List<String> = listOf(
        "--kill-on-exit",
        "--link2symlink", "-0",
        "--rootfs=${root.invariantSeparatorsPath}", "--cwd=/", "--bind=/dev",
        "/bin/sh", "-c", firstBootScript(distro),
    )

    /**
     * The plain-file part of first boot. [RootfsSetup.configureCommon] starts with the public DNS
     * fallback; Android's own [servers] go in before pacman runs, so `pacman -Syu` can reach the
     * mirror during install. The marker stays, so sessions may refresh it as the network changes.
     * Kept apart from PRoot so it can be tested on the JVM.
     */
    internal fun prepare(root: File, uid: Int, gid: Int, servers: List<String>) {
        RootfsSetup.configureCommon(root, uid, gid)
        HostDns.refresh(File(root, "etc/resolv.conf"), servers)
    }

    /**
     * [prepare] plus the Arch first boot for [distro], run through the same PRoot the tarball
     * extract used: its own `/`, the phone's `/dev` for gpg, and `/bin/sh -c` for the keyring
     * steps. [paths] supplies the PRoot binary and the host env its runs need; [dns] is Android's
     * current name servers, consulted once here rather than only at the first session's start.
     */
    suspend fun configure(
        paths: ContainerPaths,
        root: File,
        uid: Int,
        gid: Int,
        distro: Distro = Distro.ARCH,
        dns: () -> List<String> = { emptyList() },
    ) {
        prepare(root, uid, gid, dns())
        val (code, output) = runProot(
            paths.proot.path, paths.prootEnv(), null, *firstBootArgv(root, distro).toTypedArray(),
        )
        if (code != 0) throw prootFailure(code, output, "first boot")
    }
}
