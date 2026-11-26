// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchSetupTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun firstBootArgvKillsLeftoverTracees() {
        val root = File("/data/user/0/com.daryan.tessera/files/rootfs/arch_x86_64")
        val argv = ArchSetup.firstBootArgv(root, Distro.ARCH_X86_64)
        // pacman-key leaves a daemonised gpg-agent behind; without this PRoot waits on it forever.
        assertEquals("--kill-on-exit", argv.firstOrNull())
        assertTrue("--link2symlink" in argv)
        assertTrue("--rootfs=${root.invariantSeparatorsPath}" in argv)
        assertTrue("--cwd=/" in argv)
        assertTrue("--bind=/dev" in argv)
        assertTrue("/bin/sh" in argv)
        assertTrue("-c" in argv)
        assertEquals(ArchSetup.firstBootScript(Distro.ARCH_X86_64), argv.lastOrNull())
    }

    @Test fun firstBootReplacesFallbackDnsWithAndroidServers() {
        val root = tmp.newFolder("arch-root")
        ArchSetup.prepare(root, 10483, 10483, listOf("10.0.2.3"))
        val resolv = File(root, "etc/resolv.conf").readText()
        assertTrue("nameserver 10.0.2.3" in resolv)
        assertFalse("1.1.1.1" in resolv)
        assertFalse("8.8.8.8" in resolv)
        assertTrue(resolv.startsWith(HostDns.AUTO_MARKER))
    }

    @Test fun firstBootKeepsPublicDnsOnlyWhenAndroidReportsNone() {
        val root = tmp.newFolder("arch-no-dns")
        ArchSetup.prepare(root, 10483, 10483, emptyList())
        val resolv = File(root, "etc/resolv.conf").readText()
        assertTrue("nameserver 1.1.1.1" in resolv)
        assertTrue("nameserver 8.8.8.8" in resolv)
    }

    @Test fun firstBootInitialisesArchLinuxArmKeyring() {
        val script = ArchSetup.firstBootScript()
        assertTrue("pacman-key --init" in script)
        assertTrue("pacman-key --populate archlinuxarm" in script)
        assertFalse("apt-get" in script)
    }

    @Test
    fun x8664FirstBootSyncsMissingPacmanDb() {
        val script = ArchSetup.firstBootScript(Distro.ARCH_X86_64)
        assertTrue(script.contains("pacman-key --populate archlinux\n"))
        assertFalse(script.contains("archlinuxarm"))
        assertTrue(script.contains("[ -f /var/lib/pacman/sync/core.db ] ||"))
        assertTrue(script.contains("pacman -Syu --noconfirm"))
    }

    @Test
    fun armFirstBootIsUnchanged() {
        val script = ArchSetup.firstBootScript(Distro.ARCH)
        assertTrue(script.contains("pacman-key --populate archlinuxarm"))
        assertTrue(script.contains("ca.us.mirror.archlinuxarm.org"))
    }

    @Test fun chrootScriptIncludesArchFirstBootWhenDistroIsArch() {
        val script = ChrootSetup.script("/root/arch", "/tmp/a.tar.gz", "arch-aarch64", Distro.ARCH)
        assertTrue("pacman-key --populate archlinuxarm" in script)
    }

    @Test fun debianChrootScriptStillHasAptTweaks() {
        val script = ChrootSetup.script("/root/debian", "/tmp/d.tar.gz", "sha256:x", Distro.DEBIAN)
        assertTrue("APT::Sandbox::User" in script)
    }

    @Test fun firstBootUsesArchRepoMirrorAndKeepsAnExistingList() {
        val script = ArchSetup.firstBootScript()
        assertTrue(ARCH_MIRROR in script)
        assertFalse("/os/" in script)
        // The tarball's own mirrorlist works; only write one if the image has none.
        assertTrue("[ -e /etc/pacman.d/mirrorlist ] ||" in script)
    }

    @Test fun chrootArchFirstBootUsesArchRepoMirror() {
        val script = ChrootSetup.script("/root/arch", "/tmp/a.tar.gz", "arch-aarch64", Distro.ARCH)
        assertTrue(ARCH_MIRROR in script)
        assertFalse("/os/" in script)
    }

    @Test fun debianChrootScriptHasNoArchMirror() {
        val script = ChrootSetup.script("/root/debian", "/tmp/d.tar.gz", "sha256:x", Distro.DEBIAN)
        assertFalse("ca.us.mirror.archlinuxarm.org" in script)
    }

    private companion object {
        /** pacman expands these two placeholders itself; they must stay literal in the text. */
        const val ARCH_MIRROR = "Server = https://ca.us.mirror.archlinuxarm.org/\$arch/\$repo"
    }
}
