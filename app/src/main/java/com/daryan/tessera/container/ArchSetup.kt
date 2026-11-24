// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import java.io.File

/**
 * First-boot configuration of a freshly extracted Arch Linux ARM rootfs: prime the keyring from
 * the ALARM mirror and let pacman populate it, so the first install has a keyring to work with.
 * Plain shell text, no guest code runs.
 */
object ArchSetup {
    fun firstBootScript(): String = """
        set -e
        # PRoot passes the app's Android PATH through; the guest's commands are under /usr/bin.
        export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        mkdir -p /etc/pacman.d
        echo 'Server = https://ca.us.mirror.archlinuxarm.org/os/' > /etc/pacman.d/mirrorlist
        /usr/bin/pacman-key --init
        /usr/bin/pacman-key --populate archlinuxarm
    """.trimIndent()

    /**
     * [RootfsSetup.configureCommon] plus the Arch first boot, run through the same PRoot the
     * tarball extract used: its own `/`, the phone's `/dev` for gpg, and `/bin/sh -c` for the
     * keyring steps. [paths] supplies the PRoot binary and the host env its runs need.
     */
    suspend fun configure(paths: ContainerPaths, root: File, uid: Int, gid: Int) {
        RootfsSetup.configureCommon(root, uid, gid)
        val (code, output) = runProot(
            paths.proot.path, paths.prootEnv(), null,
            "--link2symlink", "-0",
            "--rootfs=${root.invariantSeparatorsPath}", "--cwd=/", "--bind=/dev",
            "/bin/sh", "-c", firstBootScript(),
        )
        if (code != 0) throw prootFailure(code, output, "first boot")
    }
}
