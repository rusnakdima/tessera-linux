// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

/**
 * The packages the launcher queues for one distribution, in that distribution's own names:
 * Debian's come from [BASE_LIBRARIES] and its neighbours, Arch's from [ArchPackages].
 */
data class PackageSets(
    val baseLibraries: List<String>,
    val guiHelpers: List<String>,
    val themeCompanions: Map<String, String>,
    val desktopCompanions: Map<String, String>,
    val compositor: String,
)

/**
 * Arch Linux ARM's names for what [BASE_LIBRARIES], [GUI_HELPER_PACKAGES], [THEME_COMPANIONS],
 * [DESKTOP_COMPANIONS] and [COMPOSITOR_PACKAGE] name on Debian: the same jobs, in Arch's repos.
 * Its window manager is Openbox, as matchbox-window-manager is AUR-only on Arch, and its Mesa
 * packages carry what Debian splits up. Arch Linux ARM is 64-bit only, so the 32-bit GL
 * libraries (lib32-libglvnd) have nothing to run here.
 */
object ArchPackages {
    /**
     * [BASE_LIBRARIES]' Arch equivalents: libglvnd for GL, EGL and GLES (its libGL.so.1,
     * libEGL.so.1, libGLESv2.so.2), Mesa for its drivers and GBM, the Vulkan loader Turnip
     * presents through, glxinfo (mesa-utils, which the GPU driver probe reads), and what
     * AppImages (FUSE 2) and Electron apps load. xcb-util-keysyms is what Turnip's X11
     * presenting links, as libxcb-keysyms1 is on Debian.
     */
    val baseLibraries: List<String> = listOf(
        "mesa", "libglvnd", "glu", "vulkan-icd-loader", "mesa-utils",
        "xcb-util-keysyms",
        "fuse2", "nss", "libxkbcommon", "alsa-lib", "libxss", "libxtst", "libsecret",
        "gtk3",
        "ca-certificates", "ttf-dejavu",
    )

    /**
     * [GUI_HELPER_PACKAGES]' Arch equivalents: Openbox in place of Debian's matchbox, and
     * python-gobject for the `gi` module and python-atspi for `pyatspi` (python3-gi and
     * python3-pyatspi on Debian).
     */
    val guiHelpers: List<String> = listOf(
        "openbox", "dbus", "at-spi2-core", "python-atspi", "pulseaudio", "python-xlib",
        "xsettingsd", "python-gobject",
    )

    /**
     * [THEME_COMPANIONS]' Arch equivalents: none. Arch's qt6-base and qt5-base already ship
     * the GTK 3 platform theme plugin, and libreoffice-fresh and libreoffice-still ship
     * libvclplug_gtk3lo.so; there is no separate companion package to queue.
     */
    val themeCompanions: Map<String, String> = emptyMap()

    /** [DESKTOP_COMPANIONS]: what a desktop (xfwm4) needs beyond its window manager. */
    val desktopCompanions: Map<String, String> = mapOf(
        "xfwm4" to "thunar",
    )

    /** [COMPOSITOR_PACKAGE]'s Arch package: the same compositor, under the same name. */
    const val compositor: String = "picom"

    private val debian = PackageSets(
        baseLibraries = BASE_LIBRARIES,
        guiHelpers = GUI_HELPER_PACKAGES,
        themeCompanions = THEME_COMPANIONS,
        desktopCompanions = DESKTOP_COMPANIONS,
        compositor = COMPOSITOR_PACKAGE,
    )

    private val arch = PackageSets(
        baseLibraries = baseLibraries,
        guiHelpers = guiHelpers,
        themeCompanions = themeCompanions,
        desktopCompanions = desktopCompanions,
        compositor = compositor,
    )

    /** The package sets of [distro]; Debian's are the constants above, unchanged. */
    fun packagesFor(distro: Distro): PackageSets = when (distro) {
        Distro.DEBIAN -> debian
        Distro.ARCH -> arch
    }
}
