// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchAutoInstallsTest {
    @Test fun archBaseLibrariesUseArchNames() {
        val missing = AutoInstalls.missingBase(Distro.ARCH, emptySet())
        assertTrue("mesa" in missing)
        assertFalse("libgl1" in missing)
        assertFalse("libegl1" in missing)
    }

    @Test fun archFirstRunChecksOpenboxNotMatchbox() {
        // A fresh Arch system has openbox after the GUI helper install; matchbox is Debian-only.
        val missing = AutoInstalls.missingGui(Distro.ARCH, setOf("openbox"))
        assertTrue("python-gobject" in missing)
        assertFalse("matchbox-window-manager" in missing)
        assertFalse("python3-pyatspi" in missing)

        val fresh = AutoInstalls.missingGui(Distro.ARCH, emptySet())
        assertTrue(fresh.isEmpty())
    }

    @Test fun debianFirstRunKeepsItsNames() {
        val missing = AutoInstalls.missingGui(Distro.DEBIAN, setOf("matchbox-window-manager"))
        assertTrue("dbus-x11" in missing)
        assertFalse("python-gobject" in missing)
    }

    @Test fun archHelperListsUseArchNamesEverywhere() {
        assertEquals(ArchPackages.guiHelpers, AutoInstalls.guiHelpers(Distro.ARCH))
        assertEquals(GUI_HELPER_PACKAGES, AutoInstalls.guiHelpers(Distro.DEBIAN))
        assertEquals(listOf("android-tools"), AutoInstalls.adb(Distro.ARCH))
        assertEquals(PhoneAdb.PACKAGES, AutoInstalls.adb(Distro.DEBIAN))
    }

    @Test fun archFirstRunNeverQueuesDebianPackages() {
        val queued = AutoInstalls.missingBase(Distro.ARCH, emptySet()) +
            AutoInstalls.missingGui(Distro.ARCH, setOf("openbox"))
        assertFalse("libgl1" in queued)
        assertFalse("libfuse2t64|libfuse2" in queued)
        assertFalse("matchbox-window-manager" in queued)
    }

    @Test fun archBuildsBothAurHelpersAfterSetup() {
        // A fresh Arch system has neither helper; both come from the AUR, not pacman's repos.
        assertEquals(listOf("yay", "paru"), AutoInstalls.missingAurHelpers(Distro.ARCH, emptySet()))
        assertEquals(listOf("yay", "paru"), AutoInstalls.missingAurHelpers(Distro.ARCH_X86_64, emptySet()))
    }

    @Test fun archSkipsAurHelpersPacmanAlreadyKnows() {
        assertEquals(listOf("paru"), AutoInstalls.missingAurHelpers(Distro.ARCH, setOf("yay")))
        assertTrue(AutoInstalls.missingAurHelpers(Distro.ARCH, setOf("yay", "paru")).isEmpty())
    }

    @Test fun debianNeverBuildsAurHelpers() {
        assertTrue(AutoInstalls.missingAurHelpers(Distro.DEBIAN, emptySet()).isEmpty())
        assertTrue(AutoInstalls.missingAurHelpers(Distro.DEBIAN, setOf("yay", "paru")).isEmpty())
    }
}
