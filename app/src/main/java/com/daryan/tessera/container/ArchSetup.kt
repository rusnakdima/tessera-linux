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
     * [RootfsSetup.configureCommon] plus the Arch first boot for [distro], run through the same
     * PRoot the tarball extract used: its own `/`, the phone's `/dev` for gpg, and `/bin/sh -c`
     * for the keyring steps. [paths] supplies the PRoot binary and the host env its runs need.
     */
    suspend fun configure(paths: ContainerPaths, root: File, uid: Int, gid: Int, distro: Distro = Distro.ARCH) {
        RootfsSetup.configureCommon(root, uid, gid)
        val (code, output) = runProot(
            paths.proot.path, paths.prootEnv(), null,
            "--link2symlink", "-0",
            "--rootfs=${root.invariantSeparatorsPath}", "--cwd=/", "--bind=/dev",
            "/bin/sh", "-c", firstBootScript(distro),
        )
        if (code != 0) throw prootFailure(code, output, "first boot")
    }
}
