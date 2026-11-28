// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.store

import com.daryan.tessera.container.ArchPackages
import com.daryan.tessera.container.Distro
import com.daryan.tessera.container.GUI_HELPER_PACKAGES
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchStoreWiringTest {
    private fun aurApp(pkg: String = "foo", aurPath: String? = null) = StoreApp(
        id = "aur:$pkg", pkg = pkg, name = "Foo", summary = "desc",
        description = "", categories = emptyList(), component = "aur", source = StoreSource.AUR,
        aurPath = aurPath,
    )

    private fun archApp() = StoreApp(
        id = "arch:extra:firefox", pkg = "firefox", name = "Firefox",
        summary = "web", description = "", categories = emptyList(), component = "extra",
        source = StoreSource.ARCH,
    )

    private fun zenApp() = aurApp(pkg = "zen-browser", aurPath = "/cgit/aur.git/snapshot/zen-browser.tar.gz")
        .copy(name = "Zen Browser")

    @Test fun aurAppQueuesAurJob() {
        val job = StoreInstalls.storeJobFor(aurApp(), Distro.ARCH)
        assertTrue(job.aur)
        assertEquals(listOf("foo"), job.packages)
    }

    @Test fun aurInstallBuildsTheSnapshotPackageBase() {
        val job = StoreInstalls.storeJobFor(
            aurApp(pkg = "librewolf-bin", aurPath = "/packages/librewolf/librewolf.tar.gz"), Distro.ARCH,
        )
        assertTrue(job.aur)
        assertEquals(listOf("librewolf"), job.packages)
    }

    @Test fun archRepoAppNeverQueuesDebianHelpers() {
        val job = StoreInstalls.storeJobFor(archApp(), Distro.ARCH)
        assertFalse(job.aur)
        assertEquals(listOf("firefox"), job.packages)
        assertTrue("openbox" in job.helpers)
        assertFalse("matchbox-window-manager" in job.helpers)
        assertFalse("libgl1" in job.helpers)
        assertEquals(job.helpers.toSet(), job.optional)
    }

    @Test fun debianAppKeepsItsHelpersAndOptionalFallback() {
        val app = archApp().copy(id = "firefox-esr.desktop", source = StoreSource.DEBIAN, component = "debian-trixie/main")
        val job = StoreInstalls.storeJobFor(app, Distro.DEBIAN)
        assertFalse(job.aur)
        assertEquals(listOf("firefox"), job.packages)
        assertEquals(GUI_HELPER_PACKAGES, job.helpers)
        assertEquals(GUI_HELPER_PACKAGES.toSet(), job.optional)
    }

    @Test fun archFirefoxEsrManifestRunsArchFirefox() {
        val manifest = StoreRegistry.manifestFor(aurApp(pkg = "firefox-esr"), null, null, Distro.ARCH)
        assertEquals(listOf("/usr/bin/firefox"), manifest.exec)
    }

    @Test fun debianFirefoxEsrManifestKeepsDebianBinary() {
        val manifest = StoreRegistry.manifestFor(aurApp(pkg = "firefox-esr"), null, null, Distro.DEBIAN)
        assertEquals(listOf("/usr/bin/firefox-esr"), manifest.exec)
    }

    @Test fun archStoreTabNeverShowsDebianCatalog() {
        assertEquals(StoreSource.ARCH, StoreInstalls.storeSourceFor(StoreSource.DEBIAN, Distro.ARCH))
        assertEquals(StoreSource.AUR, StoreInstalls.storeSourceFor(StoreSource.AUR, Distro.ARCH))
        assertEquals(StoreSource.FLATHUB, StoreInstalls.storeSourceFor(StoreSource.FLATHUB, Distro.ARCH))
    }

    @Test fun debianStoreTabNeverShowsArchCatalog() {
        assertEquals(StoreSource.DEBIAN, StoreInstalls.storeSourceFor(StoreSource.ARCH, Distro.DEBIAN))
        assertEquals(StoreSource.DEBIAN, StoreInstalls.storeSourceFor(StoreSource.AUR, Distro.DEBIAN))
    }

    @Test fun archAndAurAppsShowTheirOwnProvenance() {
        assertTrue(StoreInstalls.provenance(archApp()).startsWith("Arch Linux"))
        assertFalse("Debian" in StoreInstalls.provenance(archApp()))
        assertTrue(StoreInstalls.provenance(aurApp()).startsWith("AUR"))
        assertEquals("Flathub · org.example.App",
            StoreInstalls.provenance(archApp().copy(id = "org.example.App", source = StoreSource.FLATHUB)))
        assertEquals("Debian · main", StoreInstalls.provenance(archApp().copy(
            source = StoreSource.DEBIAN, component = "main")))
    }

    @Test fun installCopyNamesSourceAndTool() {
        val arch = StoreInstalls.installCopy(archApp(), false)
        assertTrue(arch, "pacman" in arch && "Arch Linux extra" in arch)
        assertFalse("Debian" in arch)
        assertFalse("apt" in arch)

        val aur = StoreInstalls.installCopy(aurApp(pkg = "yay-bin"), false)
        assertTrue(aur, "AUR" in aur && "makepkg" in aur && "pacman" in aur)

        val debian = StoreInstalls.installCopy(
            archApp().copy(id = "firefox-esr.desktop", source = StoreSource.DEBIAN, component = "main"), false,
        )
        assertTrue(debian, "Debian main" in debian && "apt" in debian)

        val flathub = archApp().copy(id = "org.example.App", source = StoreSource.FLATHUB)
        assertEquals(
            "Installs Firefox from Flathub with flatpak, with the runtime it needs, then adds it to your apps.",
            StoreInstalls.installCopy(flathub, false),
        )
        assertTrue(StoreInstalls.installCopy(flathub, true).contains("As the first Flathub app here"))
    }

    @Test
    fun archX8664GetsPacmanScriptAndOverlays() = runBlocking {
        assertEquals(PacmanScript, PackageScript.forDistro(Distro.ARCH_X86_64))
        assertTrue(ArchPackages.packagesFor(Distro.ARCH_X86_64).guiHelpers.isNotEmpty())
    }

    @Test fun zenAurEntryRoutesToTheOfficialPrebuiltOnArch() {
        val script = StoreInstalls.prebuiltScriptFor(zenApp(), Distro.ARCH)!!
        assertTrue("zen.linux-aarch64.tar.xz" in script)
        val job = StoreInstalls.storeJobFor(zenApp(), Distro.ARCH)
        assertFalse(job.aur)
        assertEquals(script, job.prebuiltScript)
        val x86 = StoreInstalls.storeJobFor(zenApp(), Distro.ARCH_X86_64)
        assertFalse(x86.aur)
        assertTrue("zen.linux-x86_64.tar.xz" in x86.prebuiltScript!!)
    }

    @Test fun otherAurAppsAndDebianKeepTheMakepkgPipeline() {
        assertNull(StoreInstalls.prebuiltScriptFor(aurApp(), Distro.ARCH))
        assertNull(StoreInstalls.prebuiltScriptFor(zenApp(), Distro.DEBIAN))
        val job = StoreInstalls.storeJobFor(aurApp(), Distro.ARCH)
        assertTrue(job.aur)
        assertNull(job.prebuiltScript)
        assertFalse(StoreInstalls.storeJobFor(aurApp(), Distro.DEBIAN).aur)
    }

    @Test fun zenInstallCopyDescribesThePrebuiltInsteadOfMakepkg() {
        val copy = StoreInstalls.installCopy(zenApp(), false)
        assertTrue("Zen Browser" in copy)
        assertTrue("prebuilt" in copy)
        assertFalse("makepkg" in copy)
        assertTrue("makepkg" in StoreInstalls.installCopy(aurApp(), false))
    }
}
