// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchPackagesTest {
    @Test fun archHelpersAvoidAurOnlyMatchbox() {
        val helpers = ArchPackages.guiHelpers
        assertTrue("openbox" in helpers)
        assertTrue("dbus" in helpers)
        assertTrue("python-pygobject" in helpers)
        assertFalse("matchbox-window-manager" in helpers)
    }

    @Test fun archGuiWrapperProbesModulesNotDebianDirectories() {
        val wrapper = GUI_WRAPPER
        assertTrue("python3 -c 'import gi'" in wrapper)
        assertFalse("/usr/lib/python3/dist-packages" in wrapper)
        assertTrue("openbox" in wrapper)
    }

    @Test fun debianPackageSetsAreUnchanged() {
        assertEquals(BASE_LIBRARIES, ArchPackages.packagesFor(Distro.DEBIAN).baseLibraries)
    }
}
