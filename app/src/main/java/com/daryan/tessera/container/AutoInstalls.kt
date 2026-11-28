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
     * AUR helpers installed after an Arch setup, in pacman's DB names. There is no Debian
     * equivalent: apt's stores and helpers were never built from source this way.
     */
    val aurHelpers: List<String> = listOf("yay", "paru")

    /** AUR helpers [installed] lacks; empty on Debian, which has no AUR. */
    fun missingAurHelpers(distro: Distro, installed: Set<String>): List<String> =
        if (distro.isArch) aurHelpers.filterNot { it in installed } else emptyList()

    /**
     * A prebuilt upstream release binary, pinned to a version and SHA-256 so what we download
     * can't change underneath us. [member] is the one file streamed out of the archive and
     * [tarFlags] decompress it: yay ships a gzip tarball with a top-level directory, paru a zstd
     * tarball whose binary is at the root.
     */
    private data class Prebuilt(val url: String, val sha256: String, val member: String, val tarFlags: String)

    private val prebuiltHelpers = mapOf(
        "yay" to mapOf(
            Distro.ARCH to Prebuilt(
                "https://github.com/Jguer/yay/releases/download/v13.1.0/yay_13.1.0_aarch64.tar.gz",
                "be9bde82b0ae601365bf38515a4bfe60840a0b9945994cc8631a974c0492aa2c",
                "yay_13.1.0_aarch64/yay", "-xzOf",
            ),
            Distro.ARCH_X86_64 to Prebuilt(
                "https://github.com/Jguer/yay/releases/download/v13.1.0/yay_13.1.0_x86_64.tar.gz",
                "18acab0ff3aa3927eba21cd774bac52cfd6dd366dc68b0d1a12b10120190cd3c",
                "yay_13.1.0_x86_64/yay", "-xzOf",
            ),
        ),
        "paru" to mapOf(
            Distro.ARCH to Prebuilt(
                "https://github.com/Morganamilo/paru/releases/download/v2.2.1/paru-v2.2.1-aarch64.tar.zst",
                "7b3c18574988c64796ee043030f60ea0ce449b967287155d366f5676439f35e3",
                "paru", "--zstd -xOf",
            ),
            Distro.ARCH_X86_64 to Prebuilt(
                "https://github.com/Morganamilo/paru/releases/download/v2.2.1/paru-v2.2.1-x86_64.tar.zst",
                "dca66fdd2b7c8e0ceff1133dbf5ba552cfa486af88f807170dd9b6ca0909d745",
                "paru", "--zstd -xOf",
            ),
        ),
    )

    /**
     * Root shell script installing [helper] from its upstream prebuilt release binary on [distro],
     * or null on Debian or an unknown helper. Nothing is built from the AUR here, so neither
     * fakeroot's SYSV IPC nor an extraction-time chmod on symlinks/special files is needed in the
     * PRoot guest: only the single binary is streamed out of the archive and installed mode 755.
     */
    fun aurHelperInstallScript(helper: String, distro: Distro): String? {
        val p = prebuiltHelpers[helper]?.get(distro) ?: return null
        return """
            set -e
            # PRoot passes Android's PATH through; the guest's commands are under /usr/bin.
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            pacman -S --noconfirm --needed git curl tar zstd
            tmp=${'$'}(mktemp -d)
            trap 'rm -rf "${'$'}tmp"' EXIT
            if command -v curl >/dev/null 2>&1; then
                curl -fsSL --retry 3 -o "${'$'}tmp/asset" ${p.url}
            else
                wget -q -O "${'$'}tmp/asset" ${p.url}
            fi
            echo "${p.sha256}  ${'$'}tmp/asset" | sha256sum -c - || { echo 'Checksum mismatch for $helper'; exit 1; }
            # Stream just the binary: no extraction-time chmod on symlinks/special files.
            tar ${p.tarFlags} "${'$'}tmp/asset" ${p.member} > "${'$'}tmp/$helper"
            install -m 755 "${'$'}tmp/$helper" /usr/bin/$helper
            /usr/bin/$helper --version
        """.trimIndent()
    }
}
