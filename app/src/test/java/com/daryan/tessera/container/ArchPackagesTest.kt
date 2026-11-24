// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchPackagesTest {
    @Test fun archHelpersUseArchPackageNames() {
        val helpers = ArchPackages.guiHelpers
        assertTrue("openbox" in helpers)
        assertTrue("dbus" in helpers)
        assertTrue("python-gobject" in helpers)
        assertTrue("python-atspi" in helpers)
        assertFalse("python-pygobject" in helpers)
        assertFalse("python-pyatspi" in helpers)
        assertFalse("matchbox-window-manager" in helpers)
    }

    @Test fun archThemeCompanionsAreBundled() {
        assertTrue(ArchPackages.themeCompanions.isEmpty())
    }

    @Test fun archGuiWrapperProbesModulesNotDebianDirectories() {
        val wrapper = GUI_WRAPPER
        assertTrue("python3 -c 'import gi'" in wrapper)
        assertFalse("/usr/lib/python3/dist-packages" in wrapper)
        assertTrue("openbox" in wrapper)
    }

    @Test fun archOpenboxLaunchesUndecoratedAndMaximized() {
        val wrapper = GUI_WRAPPER
        assertTrue("openbox --config-file" in wrapper)
        assertTrue("<decor>no</decor>" in wrapper)
        assertTrue("<maximized>yes</maximized>" in wrapper)
    }

    @Test fun debianPackageSetsAreUnchanged() {
        assertEquals(BASE_LIBRARIES, ArchPackages.packagesFor(Distro.DEBIAN).baseLibraries)
    }
}
