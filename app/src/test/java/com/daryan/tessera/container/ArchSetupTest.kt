// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchSetupTest {
    @Test fun firstBootInitialisesArchLinuxArmKeyring() {
        val script = ArchSetup.firstBootScript()
        assertTrue("pacman-key --init" in script)
        assertTrue("pacman-key --populate archlinuxarm" in script)
        assertFalse("apt-get" in script)
    }

    @Test fun chrootScriptIncludesArchFirstBootWhenDistroIsArch() {
        val script = ChrootSetup.script("/root/arch", "/tmp/a.tar.gz", "arch-aarch64", Distro.ARCH)
        assertTrue("pacman-key --populate archlinuxarm" in script)
    }

    @Test fun debianChrootScriptStillHasAptTweaks() {
        val script = ChrootSetup.script("/root/debian", "/tmp/d.tar.gz", "sha256:x", Distro.DEBIAN)
        assertTrue("APT::Sandbox::User" in script)
    }
}
