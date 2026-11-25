// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.store

import com.daryan.tessera.container.ArchPackages
import com.daryan.tessera.container.Distro

/** What the install queue needs to know about a store app, in the active distro's package names. */
data class StoreJob(
    val packages: List<String>,
    val helpers: List<String>,
    val aur: Boolean,
    val optional: Set<String>,
)

/** Turns a catalog entry into one install job; no Debian names are used on Arch, and vice versa. */
object StoreInstalls {
    /** The store tab that matches the active distro: Flathub is available in either. */
    fun storeSourceFor(source: StoreSource, distro: Distro): StoreSource = when {
        source == StoreSource.FLATHUB -> StoreSource.FLATHUB
        distro == Distro.ARCH -> if (source == StoreSource.DEBIAN) StoreSource.ARCH else source
        else -> if (source == StoreSource.ARCH || source == StoreSource.AUR) StoreSource.DEBIAN else source
    }

    fun storeJobFor(app: StoreApp, distro: Distro): StoreJob {
        val helpers = ArchPackages.packagesFor(distro).guiHelpers
        val aur = distro == Distro.ARCH && app.source == StoreSource.AUR
        return StoreJob(
            packages = if (aur) listOf(packageBaseFor(app)) else listOf(app.pkg),
            helpers = helpers,
            aur = aur,
            optional = helpers.toSet(),
        )
    }

    /**
     * An AUR app's `Name` can be a split package, but its `URLPath` names the snapshot of its
     * pkgbase, which is what makepkg clones and builds. Older cached entries have no path.
     */
    fun packageBaseFor(app: StoreApp): String =
        app.aurPath?.substringAfterLast('/')?.removeSuffix(".tar.gz")?.takeIf { it.isNotBlank() } ?: app.pkg

    /** Where an app came from, as shown on its Store page. */
    fun provenance(app: StoreApp): String = when (app.source) {
        StoreSource.FLATHUB -> "Flathub · ${app.id}"
        StoreSource.AUR -> "AUR · ${app.pkg}"
        else -> "${app.source.label} · ${app.component}"
    }
}
