// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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

    @Test fun archNeedsBothAurHelpersAfterSetup() {
        // A fresh Arch system has neither helper; both are installed from prebuilt binaries.
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

    @Test fun yayPrebuiltScriptDownloadsVerifiesAndInstallsBinaryOnArm() {
        val s = AutoInstalls.aurHelperInstallScript("yay", Distro.ARCH)!!
        // The exact upstream release asset and its published SHA-256, not a source tarball.
        assertTrue("releases/download/v13.1.0/yay_13.1.0_aarch64.tar.gz" in s)
        assertTrue("be9bde82b0ae601365bf38515a4bfe60840a0b9945994cc8631a974c0492aa2c" in s)
        assertTrue("sha256sum -c" in s)
        // Only the single binary is streamed out of the archive: no extraction-time chmod,
        // so no SYSV IPC or symlink-permission trouble in the PRoot guest.
        assertTrue("tar -xzOf" in s)
        assertTrue("yay_13.1.0_aarch64/yay" in s)
        assertTrue("install -m 755" in s)
        assertTrue("/usr/bin/yay" in s)
        assertTrue("yay --version" in s)
        assertTrue("pacman -S --noconfirm --needed" in s)
        assertFalse("makepkg" in s)
        assertFalse("fakeroot" in s)
        assertFalse("sudo" in s)
    }

    @Test fun paruPrebuiltScriptUsesZstdReleaseAndNeedsZstd() {
        val s = AutoInstalls.aurHelperInstallScript("paru", Distro.ARCH)!!
        assertTrue("releases/download/v2.2.1/paru-v2.2.1-aarch64.tar.zst" in s)
        assertTrue("7b3c18574988c64796ee043030f60ea0ce449b967287155d366f5676439f35e3" in s)
        assertTrue("sha256sum -c" in s)
        assertTrue("tar --zstd -xOf" in s)
        assertTrue("zstd" in s)
        assertTrue("install -m 755" in s)
        assertTrue("/usr/bin/paru" in s)
        assertTrue("paru --version" in s)
        assertFalse("makepkg" in s)
        assertFalse("fakeroot" in s)
        assertFalse("sudo" in s)
    }

    @Test fun x8664HelpersUseTheirOwnReleaseAssets() {
        val yay = AutoInstalls.aurHelperInstallScript("yay", Distro.ARCH_X86_64)!!
        assertTrue("yay_13.1.0_x86_64.tar.gz" in yay)
        assertTrue("18acab0ff3aa3927eba21cd774bac52cfd6dd366dc68b0d1a12b10120190cd3c" in yay)
        assertFalse("aarch64" in yay)
        val paru = AutoInstalls.aurHelperInstallScript("paru", Distro.ARCH_X86_64)!!
        assertTrue("paru-v2.2.1-x86_64.tar.zst" in paru)
        assertTrue("dca66fdd2b7c8e0ceff1133dbf5ba552cfa486af88f807170dd9b6ca0909d745" in paru)
        assertFalse("aarch64" in paru)
    }

    @Test fun debianHasNoPrebuiltAurHelperScript() {
        // Arch-only gating: Debian never downloads an AUR helper's release binary.
        assertNull(AutoInstalls.aurHelperInstallScript("yay", Distro.DEBIAN))
        assertNull(AutoInstalls.aurHelperInstallScript("paru", Distro.DEBIAN))
        assertNull(AutoInstalls.aurHelperInstallScript("something-else", Distro.ARCH))
    }

    @Test fun prebuiltScriptIsPassedAsAJobScriptRatherThanAnAurBuild() {
        // Whatever helper is missing, its install is a plain root script, never a makepkg job.
        AutoInstalls.missingAurHelpers(Distro.ARCH, emptySet()).forEach { helper ->
            val script = AutoInstalls.aurHelperInstallScript(helper, Distro.ARCH)!!
            assertTrue("install -m 755" in script && "/usr/bin/$helper" in script)
        }
    }

    @Test fun prebuiltScriptsAreValidShell() {
        assumeTrue(java.io.File("/bin/sh").canExecute())
        for (distro in listOf(Distro.ARCH, Distro.ARCH_X86_64)) {
            for (helper in AutoInstalls.aurHelpers) {
                val script = AutoInstalls.aurHelperInstallScript(helper, distro)!!
                // -n parses without running: nothing is downloaded or installed here.
                val p = ProcessBuilder("/bin/sh", "-n", "-c", script).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                assertEquals(out, 0, p.waitFor())
            }
        }
    }

    @Test fun zenPrebuiltScriptPinsOfficialX8664ReleaseAndInstallsLauncher() {
        val s = AutoInstalls.zenBrowserInstallScript(Distro.ARCH_X86_64)!!
        // The official tagged Linux tarball, and the SHA-256 of the actual downloaded asset.
        assertTrue("releases/download/1.24.3b/zen.linux-x86_64.tar.xz" in s)
        assertTrue("dde4b4e44002128c2ff3efc56074e671c24effe56fd192ab2eee3972541e1a2d" in s)
        assertTrue("sha256sum -c" in s)
        assertTrue("tar -xJf" in s)
        assertTrue("--strip-components=1" in s)
        assertTrue("/opt/zen-browser" in s)
        assertTrue("install -m 755" in s)
        assertTrue("/usr/bin/zen-browser" in s)
        assertTrue("zen-browser.desktop" in s)
        assertTrue("/usr/share/applications" in s)
        assertTrue("zen-browser --version" in s)
        // PRoot has no SYSV IPC for fakeroot and no user-space init: nothing here builds or elevates.
        assertFalse("makepkg" in s)
        assertFalse("fakeroot" in s)
        assertFalse("sudo" in s)
        assertFalse("systemd" in s)
    }

    @Test fun zenPrebuiltScriptPinsOfficialAarch64Release() {
        val s = AutoInstalls.zenBrowserInstallScript(Distro.ARCH)!!
        assertTrue("releases/download/1.24.3b/zen.linux-aarch64.tar.xz" in s)
        assertTrue("77c04bdb0575ab918c1d6f41d04a55996d2263824a0392dc1b3f38deb873a57a" in s)
        assertFalse("x86_64" in s)
    }

    @Test fun debianNeverDownloadsZenPrebuilt() {
        assertNull(AutoInstalls.zenBrowserInstallScript(Distro.DEBIAN))
    }

    @Test fun zenPrebuiltScriptIsValidShell() {
        assumeTrue(java.io.File("/bin/sh").canExecute())
        for (distro in listOf(Distro.ARCH, Distro.ARCH_X86_64)) {
            val script = AutoInstalls.zenBrowserInstallScript(distro)!!
            // -n parses without running: nothing is downloaded or installed here.
            val p = ProcessBuilder("/bin/sh", "-n", "-c", script).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals(out, 0, p.waitFor())
        }
    }

    @Test fun zenLauncherDropsToUnprivilegedUserAndHome() {
        for (distro in listOf(Distro.ARCH, Distro.ARCH_X86_64)) {
            val s = AutoInstalls.zenBrowserInstallScript(distro)!!
            // Root's Firefox disables its content sandbox and its content processes die under
            // PRoot; the generated launcher must hand the browser to the container's normal user.
            assertTrue("runuser -u user --" in s)
            assertTrue("HOME=/home/user" in s)
            assertTrue("/opt/zen-browser/zen" in s)
            // A fixed profile carries the pref below, since Zen's ~/.zen/<hash> profile is not
            // one Tessera's GUI wrapper already configures; it is chowned so that user can write.
            assertTrue("-profile /home/user/.zen-tessera" in s)
            assertTrue("chown -R user:user" in s)
            // A normal PRoot session is already the unprivileged user; runuser must be guarded
            // by a root check or it aborts with "runuser: may not be used by non-root users".
            assertTrue("id -u" in s)
        }
    }

    @Test fun zenLauncherDisablesEveryContentSandboxAndPref() {
        for (distro in listOf(Distro.ARCH, Distro.ARCH_X86_64)) {
            val s = AutoInstalls.zenBrowserInstallScript(distro)!!
            assertTrue("MOZ_DISABLE_CONTENT_SANDBOX=1" in s)
            assertTrue("MOZ_DISABLE_GMP_SANDBOX=1" in s)
            assertTrue("MOZ_DISABLE_RDD_SANDBOX=1" in s)
            assertTrue("MOZ_DISABLE_SOCKET_PROCESS_SANDBOX=1" in s)
            // The pref is authoritative: the env alone still leaves Zen's content processes
            // trying to start an unsupported sandbox and crashing with "Gah. Your tab just crashed."
            assertTrue("security.sandbox.content.level\", 0" in s)
            assertTrue("user.js" in s)
            assertTrue("prefs.js" in s)
        }
    }

    @Test fun zenLauncherForcesSoftwareRendering() {
        for (distro in listOf(Distro.ARCH, Distro.ARCH_X86_64)) {
            val s = AutoInstalls.zenBrowserInstallScript(distro)!!
            assertTrue("LIBGL_ALWAYS_SOFTWARE=1" in s)
            assertTrue("MOZ_ACCELERATED=0" in s)
        }
    }

    @Test fun zenLauncherKeepsNetworkingInMainProcess() {
        for (distro in listOf(Distro.ARCH, Distro.ARCH_X86_64)) {
            val s = AutoInstalls.zenBrowserInstallScript(distro)!!
            // Under PRoot the dedicated socket/network child process crashes on startup and
            // takes every real http(s) load down with it ("Gah. Your tab just crashed."). Keep
            // the network stack in the main/content process and disable the socket sandbox.
            assertTrue("network.process.enabled\", false" in s)
            assertTrue("security.sandbox.socket.process.level\", 0" in s)
        }
    }

    @Test fun zenLauncherPreservesTheNoSourceBuildGuarantees() {
        for (distro in listOf(Distro.ARCH, Distro.ARCH_X86_64)) {
            val s = AutoInstalls.zenBrowserInstallScript(distro)!!
            assertFalse("makepkg" in s)
            assertFalse("fakeroot" in s)
            assertFalse("sudo" in s)
        }
    }
}
