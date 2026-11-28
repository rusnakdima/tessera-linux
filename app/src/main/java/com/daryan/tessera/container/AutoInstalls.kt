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

    /**
     * Zen Browser's official tagged Linux tarballs (release 1.24.3b, the upstream binary behind
     * AUR `zen-browser 1.24.3b-1`). Both archives hold one top-level `zen/` directory with no
     * symlinks or special files, so extracting them is safe in the PRoot guest. The digests were
     * computed from the assets fetched from these exact URLs.
     */
    private data class ZenTarball(val url: String, val sha256: String)

    private val zenTarballs = mapOf(
        Distro.ARCH to ZenTarball(
            "https://github.com/zen-browser/desktop/releases/download/1.24.3b/zen.linux-aarch64.tar.xz",
            "77c04bdb0575ab918c1d6f41d04a55996d2263824a0392dc1b3f38deb873a57a",
        ),
        Distro.ARCH_X86_64 to ZenTarball(
            "https://github.com/zen-browser/desktop/releases/download/1.24.3b/zen.linux-x86_64.tar.xz",
            "dde4b4e44002128c2ff3efc56074e671c24effe56fd192ab2eee3972541e1a2d",
        ),
    )

    /**
     * Root shell script installing Zen Browser from its official prebuilt release on [distro],
     * or null on Debian. No source build, no fakeroot and no package-manager hooks: the archive
     * is verified, extracted to /opt/zen-browser and given a launcher and a .desktop entry so
     * the launcher's system-app scan finds it. The browser's runtime libraries are already in
     * the base and GUI-helper sets; `--needed` makes this a no-op when they are present.
     */
    fun zenBrowserInstallScript(distro: Distro): String? {
        val t = zenTarballs[distro] ?: return null
        return """
            set -e
            # PRoot passes Android's PATH through; the guest's commands are under /usr/bin.
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            pacman -S --noconfirm --needed tar xz curl ffmpeg libpulse libxt mime-types gtk3 nss
            tmp=${'$'}(mktemp -d)
            trap 'rm -rf "${'$'}tmp"' EXIT
            if command -v curl >/dev/null 2>&1; then
                curl -fsSL --retry 3 -o "${'$'}tmp/asset" ${t.url}
            else
                wget -q -O "${'$'}tmp/asset" ${t.url}
            fi
            echo "${t.sha256}  ${'$'}tmp/asset" | sha256sum -c - || { echo 'Checksum mismatch for zen-browser'; exit 1; }
            # The only top-level member is zen/, so strip it; no symlinks to chmod in PRoot.
            mkdir -p /opt/zen-browser
            tar -xJf "${'$'}tmp/asset" -C /opt/zen-browser --strip-components=1
            printf '#!/bin/sh\nexec /opt/zen-browser/zen "${'$'}@"\n' > "${'$'}tmp/zen-browser"
            install -m 755 "${'$'}tmp/zen-browser" /usr/bin/zen-browser
            cat > "${'$'}tmp/zen-browser.desktop" <<'EOF'
            [Desktop Entry]
            Name=Zen Browser
            Comment=Firefox-based web browser
            Exec=zen-browser %u
            Icon=zen-browser
            Type=Application
            Categories=Network;WebBrowser;
            Terminal=false
            StartupWMClass=zen
            EOF
            install -Dm644 "${'$'}tmp/zen-browser.desktop" /usr/share/applications/zen-browser.desktop
            install -Dm644 /opt/zen-browser/browser/chrome/icons/default/default128.png /usr/share/icons/hicolor/128x128/apps/zen-browser.png
            /usr/bin/zen-browser --version
        """.trimIndent()
    }
}
