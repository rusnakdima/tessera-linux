// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

/**
 * The package names the launcher's automatic checks queue, in the active distro's own names.
 * [ArchPackages] holds the mapping; this is where the callers use it, so a first run on Arch
 * never sends Debian names like `libgl1` or `matchbox-window-manager` to pacman.
 */
object AutoInstalls {
    /** Packages in [BASE_LIBRARIES]' active-distro equivalent that [installed] lacks. */
    fun missingBase(distro: Distro, installed: Set<String>): List<String> =
        missingPackages(ArchPackages.packagesFor(distro).baseLibraries, installed)

    /**
     * Packages in [GUI_HELPER_PACKAGES]' active-distro equivalent that [installed] lacks.
     * Empty until the distro's window manager is there (Openbox on Arch, Matchbox on Debian):
     * a fresh system's first check installs the helpers before checking companions.
     */
    fun missingGui(distro: Distro, installed: Set<String>): List<String> {
        val sets = ArchPackages.packagesFor(distro)
        val windowManager = if (distro.isArch) "openbox" else "matchbox-window-manager"
        if (windowManager !in installed) return emptyList()
        val companions = (sets.themeCompanions + sets.desktopCompanions)
            .filterKeys { it in installed }.values.filterNot { it in installed }
        return sets.guiHelpers.filterNot { it in installed } + companions
    }

    /** The distro's GUI helper packages, for installs that bring a graphical app. */
    fun guiHelpers(distro: Distro): List<String> = ArchPackages.packagesFor(distro).guiHelpers

    /** ADB and fastboot: Arch ships both in `android-tools`. */
    fun adb(distro: Distro): List<String> =
        if (distro.isArch) listOf("android-tools") else PhoneAdb.PACKAGES

    /**
     * AUR helpers built from the AUR after an Arch setup, in pacman's DB names. There is no
     * Debian equivalent: apt's stores and helpers were never built from source this way.
     */
    val aurHelpers: List<String> = listOf("yay", "paru")

    /** AUR helpers [installed] lacks; empty on Debian, where nothing is built from the AUR. */
    fun missingAurHelpers(distro: Distro, installed: Set<String>): List<String> =
        if (distro.isArch) aurHelpers.filterNot { it in installed } else emptyList()
}
