// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PacmanScriptTest {
    @Test fun repoInstallUsesPacmanWithoutApt() {
        val job = QueueJob(
            id = 1, title = "Firefox", packages = listOf("firefox"),
            action = QueueAction.INSTALL, iconFile = null,
        )
        val script = PacmanScript.script(job)
        assertTrue("pacman -S --noconfirm --needed firefox" in script)
        assertFalse("apt-get" in script)
        // The cache is never cleared behind the user's back.
        assertFalse("-Scc" in script)
        val remove = PacmanScript.script(job.copy(action = QueueAction.REMOVE))
        assertTrue("pacman -Rns --noconfirm firefox" in remove)
        assertFalse("apt-get" in remove)
    }

    @Test fun aurBuildRunsAsUserAndInstallsAsRoot() {
        val job = QueueJob(
            id = 2, title = "yay", packages = listOf("yay"),
            action = QueueAction.INSTALL, iconFile = null, aur = true,
        )
        val script = PacmanScript.script(job)
        assertTrue("runuser -u user -- makepkg -s" in script)
        assertTrue("pacman -U --noconfirm" in script)
        assertTrue("base-devel" in script && "git" in script)
        assertFalse("apt-get" in script)
        // The build tree is the user's, cloned from the AUR's pkgbase.
        assertTrue("mkdir -p ${PacmanScript.AUR_DIR}" in script && "chown user:user ${PacmanScript.AUR_DIR}" in script)
        assertTrue("git clone --depth 1 https://aur.archlinux.org/yay.git" in script)
    }

    @Test fun aurPkgbaseIsShellQuoted() {
        val job = QueueJob(
            id = 4, title = "evil", packages = listOf("evil; rm -rf /"),
            action = QueueAction.INSTALL, iconFile = null, aur = true,
        )
        val script = PacmanScript.script(job)
        assertTrue("'https://aur.archlinux.org/evil; rm -rf /.git'" in script)
        assertTrue("${PacmanScript.AUR_DIR}/'evil; rm -rf /'" in script)
    }

    @Test fun aurFailureKeepsMakepkgOutput() {
        val lines = listOf("==> ERROR: package is only available for x86_64", "makepkg failed")
        assertEquals("==> ERROR: package is only available for x86_64",
            PacmanScript.failure(lines, 2))
    }

    @Test fun debianStillUsesAptScript() {
        val job = QueueJob(
            id = 3, title = "x", packages = listOf("x"),
            action = QueueAction.INSTALL, iconFile = null,
        )
        assertEquals(InstallQueue.script(job), AptScript.script(job))
    }
}
