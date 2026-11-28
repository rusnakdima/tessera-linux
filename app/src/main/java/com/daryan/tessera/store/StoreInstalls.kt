// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.store

import com.daryan.tessera.container.ArchPackages
import com.daryan.tessera.container.AutoInstalls
import com.daryan.tessera.container.Distro
import com.daryan.tessera.container.isArch

/** What the install queue needs to know about a store app, in the active distro's package names. */
data class StoreJob(
    val packages: List<String>,
    val helpers: List<String>,
    val aur: Boolean,
    val optional: Set<String>,
    /** A prebuilt release to run as a root script in place of the package-manager pipeline. */
    val prebuiltScript: String? = null,
)

/** Turns a catalog entry into one install job; no Debian names are used on Arch, and vice versa. */
object StoreInstalls {
    /** The AUR browser installed from Zen's own release instead of built from its source snapshot. */
    const val ZEN_BROWSER_PKG = "zen-browser"

    /** The menu entry the prebuilt script installs, read back when registering its launcher entry. */
    const val ZEN_BROWSER_DESKTOP = "zen-browser.desktop"

    /** The store tab that matches the active distro: Flathub is available in either. */
    fun storeSourceFor(source: StoreSource, distro: Distro): StoreSource = when {
        source == StoreSource.FLATHUB -> StoreSource.FLATHUB
        distro.isArch -> if (source == StoreSource.DEBIAN) StoreSource.ARCH else source
        else -> if (source == StoreSource.ARCH || source == StoreSource.AUR) StoreSource.DEBIAN else source
    }

    /**
     * The AUR `zen-browser` entry is installed from Zen's official prebuilt Linux release on Arch:
     * a makepkg/fakeroot build can't run in the PRoot guest. Other AUR apps keep the build
     * pipeline, and Debian has no AUR at all.
     */
    fun prebuiltScriptFor(app: StoreApp, distro: Distro): String? =
        if (distro.isArch && app.source == StoreSource.AUR && app.pkg == ZEN_BROWSER_PKG)
            AutoInstalls.zenBrowserInstallScript(distro)
        else null

    fun storeJobFor(app: StoreApp, distro: Distro): StoreJob {
        val helpers = ArchPackages.packagesFor(distro).guiHelpers
        val prebuilt = prebuiltScriptFor(app, distro)
        val aur = distro.isArch && app.source == StoreSource.AUR && prebuilt == null
        return StoreJob(
            packages = if (aur || prebuilt != null) listOf(packageBaseFor(app)) else listOf(app.pkg),
            helpers = helpers,
            aur = aur,
            optional = helpers.toSet(),
            prebuiltScript = prebuilt,
        )
    }

    /**
     * An AUR app's `Name` can be a split package, but its `URLPath` names the snapshot of its
     * pkgbase, which is what makepkg clones and builds. Older cached entries have no path.
     */
    fun packageBaseFor(app: StoreApp): String =
        app.aurPath?.substringAfterLast('/')?.removeSuffix(".tar.gz")?.takeIf { it.isNotBlank() } ?: app.pkg

    /** What installing an app actually does, in the active source's own words. */
    fun installCopy(app: StoreApp, firstFlathub: Boolean): String = when (app.source) {
        StoreSource.FLATHUB ->
            "Installs ${app.name} from Flathub with flatpak, with the runtime it needs, then adds it to your apps." +
                if (firstFlathub) " As the first Flathub app here, it also downloads the runtimes Flathub apps share, " +
                    "often several hundred MB, so it takes longer. Apps after it that use the same runtimes install faster." else ""
        StoreSource.ARCH ->
            "Installs ${app.pkg} from Arch Linux ${app.component} with pacman, in a terminal you can watch, then adds it to your apps."
        StoreSource.AUR ->
            if (app.pkg == ZEN_BROWSER_PKG)
                "Installs Zen Browser from its official prebuilt Linux release, verified with a SHA-256 " +
                    "checksum and installed in a terminal you can watch, then adds it to your apps."
            else
                "Installs ${app.pkg} from AUR, built on the device with makepkg and installed with pacman, " +
                    "in a terminal you can watch, then adds it to your apps."
        StoreSource.DEBIAN ->
            "Installs ${app.pkg} from Debian ${app.component} with apt, in a terminal you can watch, then adds it to your apps."
    }

    /** Where an app came from, as shown on its Store page. */
    fun provenance(app: StoreApp): String = when (app.source) {
        StoreSource.FLATHUB -> "Flathub · ${app.id}"
        StoreSource.AUR -> "AUR · ${app.pkg}"
        else -> "${app.source.label} · ${app.component}"
    }
}
