// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.store

import com.daryan.tessera.container.Distro
import com.daryan.tessera.container.isArch
import com.daryan.tessera.container.shellQuote

/**
 * How one package manager runs a [QueueJob] and reads its output. [InstallQueue] picks the
 * implementation for the container's [Distro], so no apt command is ever emitted on Arch.
 */
interface PackageScript {
    /** The shell script that runs [job] as root. */
    fun script(job: QueueJob): String

    /**
     * Progress and a short message for one line of output, or null if it says nothing new.
     * The progress is 0..1 when the tool reports one. An implementation may keep the little
     * state its lines need: [InstallQueue] runs one job at a time, so lines arrive in order.
     */
    fun parseProgress(line: String): Pair<Float?, String>?

    /** Why a job that ended with [code] failed, from its last [lines] of output. */
    fun failure(lines: List<String>, code: Int): String

    companion object {
        /** The scripts [distro]'s package manager needs: pacman for both Arch variants. */
        fun forDistro(distro: Distro): PackageScript = if (distro.isArch) PacmanScript else AptScript
    }
}

/**
 * Debian's apt, as [InstallQueue] has always run it: two phases with machine-readable progress,
 * and Debian 13's sources at a low priority for derivatives that dropped what Tessera needs.
 */
object AptScript : PackageScript {
    /** The last phase of the job being parsed; a job always starts with a phase marker. */
    private val progress = AptProgress()

    @Synchronized
    override fun parseProgress(line: String): Pair<Float?, String>? = progress.feed(line)

    override fun failure(lines: List<String>, code: Int): String =
        lines.lastOrNull { it.startsWith("E:") }
            ?: lines.lastOrNull { "error: " in it }?.let { it.substring(it.indexOf("error: ")) }
            ?: lines.lastOrNull()
            ?: "apt failed ($code)"

    /** apt in two phases with machine-readable progress; markers tell [AptProgress] which. */
    override fun script(job: QueueJob): String {
        val opts = "-o APT::Status-Fd=1 -o Dpkg::Use-Pty=0 -o Dpkg::Progress-Fancy=0"
        val install = job.action == QueueAction.INSTALL
        val pick = install && (job.optional.isNotEmpty() || job.fallback || job.packages.any { '|' in it })
        val pkgs = if (pick) "\$pkgs" else job.packages.joinToString(" ")
        fun list(l: List<String>) = l.joinToString(" ") { shellQuote(it) }
        // A package the lists can install: a candidate, not just a name something refers to
        // (apt-cache show succeeds for those; Kali's libfuse2 is one).
        val helpers = if (!pick) "" else
            "has() { LC_ALL=C apt-cache policy \"\$1\" 2>/dev/null | grep -q '^ *Candidate: [^(]'; }; " +
                "pick() { for a in \$(printf '%s' \"\$1\" | tr '|' ' '); do " +
                "if has \"\$a\"; then echo \"\$a\"; return 0; fi; done; return 1; }; "
        // Debian 13's sources for what the system lacks, once; taken out again if they can't
        // be read, so a failed download doesn't break the system's apt.
        val fallback = if (!pick || !job.fallback) "" else
            "lack=; for p in ${list(job.packages)}; do pick \"\$p\" >/dev/null || lack=\"\$lack \${p%%|*}\"; done; " +
                "if [ -n \"\$lack\" ] && [ ! -e ${InstallQueue.FALLBACK_SOURCE} ]; then " +
                "echo \"Not in this system's sources:\$lack. Adding Debian 13's for them\"; " +
                "[ -e $DEBIAN_KEYRING ] || apt-get install -y --no-install-recommends $opts debian-archive-keyring; " +
                "if [ -e $DEBIAN_KEYRING ]; then " +
                "printf '%s\n' ${shellQuote(FALLBACK_SOURCES)} > ${InstallQueue.FALLBACK_SOURCE}; " +
                "mkdir -p ${InstallQueue.FALLBACK_PIN.substringBeforeLast('/')}; " +
                "printf '%s\n' ${shellQuote(FALLBACK_PREFERENCES)} > ${InstallQueue.FALLBACK_PIN}; " +
                "apt-get update $opts || { echo \"Couldn't read Debian's sources; going on without them\"; " +
                "rm -f ${InstallQueue.FALLBACK_SOURCE} ${InstallQueue.FALLBACK_PIN}; apt-get update $opts; }; " +
                "else echo \"Debian's keyring isn't here, so its sources can't be added\"; fi; fi; "
        // After apt-get update, so the lists it checks are current. A required package the
        // lists lack goes to apt as named, which then says so.
        val filter = if (!pick) "" else {
            val (optional, required) = job.packages.partition { it in job.optional }
            "pkgs=; " +
                (if (required.isEmpty()) "" else "for p in ${list(required)}; do a=\$(pick \"\$p\") || a=\${p%%|*}; pkgs=\"\$pkgs \$a\"; done; ") +
                (if (optional.isEmpty()) "" else "for p in ${list(optional)}; do if a=\$(pick \"\$p\"); then pkgs=\"\$pkgs \$a\"; " +
                    "else echo \"Skipping \$p: this system has no such package\"; fi; done; ") +
                "[ -n \"\$pkgs\" ] || exit 0; "
        }
        val main = when (job.action) {
            QueueAction.INSTALL -> "apt-get install -y --no-install-recommends $opts $pkgs"
            QueueAction.REMOVE -> "apt-get remove -y $opts $pkgs"
        }
        // A package setup step that was interrupted (or stumbled) leaves dpkg half done and
        // apt failing; finish what's pending and try once more before calling it failed.
        val retry = "{ echo 'Finishing interrupted package setup and trying again'; dpkg --configure -a; $main; }"
        return "export DEBIAN_FRONTEND=noninteractive; " +
            (if (install) "echo '##phase update'; apt-get update $opts || exit \$?; " else "") +
            helpers + fallback + filter + "echo '##phase main'; $main || $retry"
    }

    private const val DEBIAN_KEYRING = "/usr/share/keyrings/debian-archive-keyring.gpg"

    private val FALLBACK_SOURCES = """
        Types: deb
        URIs: http://deb.debian.org/debian
        Suites: trixie trixie-updates
        Components: main
        Signed-By: $DEBIAN_KEYRING
    """.trimIndent()

    /**
     * Below 100: a package comes from Debian only when the system has none of its own, and
     * what's installed is never "upgraded" to Debian's.
     */
    private val FALLBACK_PREFERENCES = """
        Explanation: Tessera: Debian 13 only for what this system has no package for
        Package: *
        Pin: origin deb.debian.org
        Pin-Priority: 50
    """.trimIndent()
}

/**
 * Arch Linux's pacman, and the AUR's two-phase build: `makepkg` refuses root, so the package is
 * built as `user` (with base-devel and git) and installed as root with `pacman -U`. Pacman has
 * no machine-readable progress, so the latest line is the progress text. The cache is never
 * cleared automatically; `pacman -Scc` would only follow an explicit user request.
 */
object PacmanScript : PackageScript {
    /** Where AUR packages are cloned and built; wiped before each build. */
    const val AUR_DIR = "/tmp/tessera-aur"

    override fun script(job: QueueJob): String = when {
        job.aur && job.action == QueueAction.INSTALL -> aurScript(job)
        job.action == QueueAction.INSTALL -> "pacman -S --noconfirm --needed " + quote(job.packages)
        else -> "pacman -Rns --noconfirm " + quote(job.packages)
    }

    /** Pacman prints no percentage: [InstallQueue] shows its latest line as the progress text. */
    override fun parseProgress(line: String): Pair<Float?, String>? = null

    override fun failure(lines: List<String>, code: Int): String =
        lines.lastOrNull { "==> ERROR:" in it }
            ?: lines.lastOrNull { "error:" in it }
            ?: lines.lastOrNull()
            ?: "pacman failed ($code)"

    private fun quote(packages: List<String>) = packages.joinToString(" ") { shellQuote(it) }

    /**
     * `pkgbase` is the one package name, and it comes from the AUR, so it's shell-quoted. The
     * clone and the build run as `user`; every dependency install and `pacman -U` run as root.
     */
    private fun aurScript(job: QueueJob): String {
        require(job.packages.size == 1) {
            "An AUR install takes exactly one package, not ${job.packages.size}"
        }
        val pkgbase = job.packages.single()
        val repo = shellQuote("https://aur.archlinux.org/$pkgbase.git")
        val dir = "$AUR_DIR/${shellQuote(pkgbase)}"
        // Root install jobs skip enter.sh's user setup, so create the builder if it isn't there.
        // makepkg -s calls sudo for dependencies outside base-devel, but sudo has no working
        // PAM auth in the container. Read the exact dependencies from .SRCINFO instead.
        val deps = """runuser -u user -- makepkg --printsrcinfo | """ +
            """sed -n -E 's/^[[:space:]]*(depends|makedepends)[[:space:]]*=[[:space:]]*([^<>=]+).*/\2/p' | """ +
            """sort -u | tr '\n' ' '"""
        // A previous job may have left the shell inside $AUR_DIR; leave it before deleting it.
        return "cd / && pacman -S --noconfirm --needed base-devel git && " +
            "(id -u user >/dev/null 2>&1 || useradd -m -u 10000 user) && " +
            "rm -rf $AUR_DIR && mkdir -p $AUR_DIR && chown user:user $AUR_DIR && " +
            "runuser -u user -- git clone --depth 1 $repo $dir && " +
            "cd $dir && deps=\$($deps) && " +
            "[ -z \"\$deps\" ] || pacman -S --noconfirm --needed \$deps && " +
            "runuser -u user -- makepkg -d --noconfirm && " +
            "pacman -U --noconfirm $dir/*.pkg.tar.*"
    }
}
