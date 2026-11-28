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
        assertTrue("runuser -u user -- makepkg -d" in script)
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

    @Test fun aurBuildInstallsDependenciesAsRootWithoutSudo() {
        val job = QueueJob(
            id = 5, title = "yay", packages = listOf("yay"),
            action = QueueAction.INSTALL, iconFile = null, aur = true,
        )
        val script = PacmanScript.script(job)
        // Root install jobs skip enter.sh's user setup; chown would fail without this.
        assertTrue("id -u user >/dev/null 2>&1 || useradd" in script)
        assertTrue(script.indexOf("id -u user") < script.indexOf("chown user:user"))
        // sudo's PAM check fails in the container, so no sudo at any point.
        assertFalse("sudo" in script)
        assertFalse("sudoers" in script)
        // Dependencies come from the PKGBUILD and are installed as root before the build.
        assertTrue("makepkg --printsrcinfo" in script)
        assertTrue("pacman -S --noconfirm --needed \$deps" in script)
        assertTrue(script.indexOf("pacman -S --noconfirm --needed \$deps") < script.indexOf("makepkg -d"))
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

    @Test fun aurScriptLeavesBuildDirectoryBeforeDeletingIt() {
        val job = QueueJob(
            id = 6, title = "yay", packages = listOf("yay"),
            action = QueueAction.INSTALL, iconFile = null, aur = true,
        )
        val script = PacmanScript.script(job)
        assertTrue("cd /" in script)
        assertTrue(script.indexOf("cd /") < script.indexOf("rm -rf ${PacmanScript.AUR_DIR}"))
    }
}
