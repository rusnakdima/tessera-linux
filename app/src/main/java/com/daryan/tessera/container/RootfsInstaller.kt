// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import android.os.Build
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.security.MessageDigest

sealed interface InstallState {
    val running: Boolean get() = false

    data object Idle : InstallState
    data object Resolving : InstallState { override val running = true }
    data class Downloading(val done: Long, val total: Long) : InstallState { override val running = true }
    data object Extracting : InstallState { override val running = true }
    data object Configuring : InstallState { override val running = true }
    data object Done : InstallState
    /** [output] is everything the failing step printed, for diagnostics; [message] is what the user sees. */
    data class Failed(val message: String, val output: String = "") : InstallState
}

/**
 * Installs a distro's rootfs into app storage for PRoot mode:
 * download (digest-checked) -> extract through PRoot -> [RootfsSetup] -> marker file.
 */
class RootfsInstaller(
    private val paths: ContainerPaths,
    private val registry: OciRegistry,
    private val scope: CoroutineScope,
    private val uid: Int,
    private val gid: Int,
) {
    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state
    private var job: Job? = null

    /** Installs [distro] for PRoot mode, or with [su] for chroot mode (root's own location). */
    fun start(distro: Distro, su: String? = null) {
        if (_state.value.running) return
        job = scope.launch(Dispatchers.IO) {
            _state.value = try {
                if (su == null) install(distro) else installChroot(distro, su)
                InstallState.Done
            } catch (e: CancellationException) {
                InstallState.Idle
            } catch (e: Exception) {
                InstallState.Failed(e.message ?: e.javaClass.simpleName, (e as? InstallFailure)?.output.orEmpty())
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /**
     * The distro's image layers, resolved and verified against their published checksums; good
     * cached copies are kept across failed attempts. Returns artifacts and files in layer order.
     */
    internal suspend fun fetch(distro: Distro): Pair<List<RootfsArtifact>, List<File>> {
        _state.value = InstallState.Resolving
        val artifacts = RootfsSources.resolveAll(distro, Platform.forAbi(Build.SUPPORTED_ABIS.first()))
        val combinedKey = combinedKey(distro, artifacts)
        val total = artifacts.sumOf { it.size }
        if (StatFs(paths.cacheDir.path).availableBytes < distro.requiredFreeMb * 1024L * 1024L) {
            throw InstallFailure(
                "About ${distro.requiredFreeMb} MB of free space is needed to install ${distro.label}", "",
            )
        }
        val tarballs = artifacts.indices.map { File(paths.cacheDir, "rootfs-$combinedKey-$it.tar.gz") }
        var done = 0L
        for ((index, artifact) in artifacts.withIndex()) {
            val tarball = tarballs[index]
            if (tarball.isFile && artifact.verify(tarball)) {
                done += artifact.size
                _state.value = InstallState.Downloading(done, total)
                continue
            }
            // A cached copy that no longer verifies is stale: delete it and download afresh.
            tarball.delete()
            _state.value = InstallState.Downloading(done, total)
            try {
                RootfsSources.download(artifact, tarball) { bytes ->
                    _state.value = InstallState.Downloading(done + bytes, total)
                }
            } catch (e: CancellationException) {
                File(tarball.path + ".part").delete()
                throw e
            }
            done += artifact.size
        }
        return artifacts to tarballs
    }

    /** One cache identity for the whole image: if any layer changes, no layer is reused. */
    private fun combinedKey(distro: Distro, artifacts: List<RootfsArtifact>): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(artifacts.joinToString("\n") { it.key }.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "${distro.name.lowercase()}-${hash.take(16)}"
    }

    /**
     * Installs [distro] where chroot mode expects it, as root: unpacked with real ownership
     * into a scratch directory, configured, then moved into place. An existing system there is
     * never replaced.
     */
    private suspend fun installChroot(distro: Distro, su: String) {
        val (artifacts, tarballs) = fetch(distro)
        _state.value = InstallState.Extracting
        val script = File(paths.cacheDir, "chroot-install.sh")
        script.writeText(
            ChrootSetup.script(
                paths.chrootRootfs.path, tarballs.map { it.path }, "${distro.name} ${artifacts.first().key}", distro,
            ),
        )
        runInterruptible {
            val proc = ProcessBuilder(su, "-c", "/system/bin/sh ${script.path}").redirectErrorStream(true).start()
            try {
                val output = proc.inputStream.bufferedReader().readLines()
                if (proc.waitFor() != 0) throw InstallFailure(output.lastOrNull { it.isNotBlank() } ?: "chroot install failed", output.joinToString("\n"))
            } finally {
                proc.destroy()
            }
        }
        script.delete()
        tarballs.forEach { it.delete() }
    }

    private suspend fun install(distro: Distro) {
        val (artifacts, tarballs) = fetch(distro)

        // Extract straight into place: link2symlink records absolute paths, so the tree can't be
        // moved afterwards. The marker file written last is what makes it count as installed.
        _state.value = InstallState.Extracting
        val root = paths.prootRootfs
        root.deleteRecursively()
        root.mkdirs()
        val tar = hostTar()
        try {
            for (tarball in tarballs) {
                // Whiteout entries (.wh.*) are out of scope: the pinned Arch image's layers only
                // overwrite earlier files, so extracting them in manifest order is enough.
                // Unzipped here, not with tar's -z: toybox runs /system/bin/zcat for that, which
                // PRoot is refused like tar itself.
                val (code, output) = runProot(paths.proot.path, paths.prootEnv(), tarball, "--link2symlink", "-0", *tar.argv.toTypedArray(), "-xf", "-", "-C", root.path)
                if (code != 0) throw prootFailure(code, output, "extract")
            }
        } finally {
            tar.file.delete()
        }

        _state.value = InstallState.Configuring
        // Arch configures itself on first boot; Debian gets the apt tweaks. Both get the shared part.
        if (distro.isArch) ArchSetup.configure(paths, root, uid, gid, distro) else RootfsSetup.configure(root, uid, gid, distro)
        FakeProc.write(paths.fakeProcDir)
        File(root, ContainerPaths.INSTALLED_MARKER).writeText("${distro.name} ${artifacts.first().key}\n")
        tarballs.forEach { it.delete() }
    }

    /** A program copied out of /system, and the arguments that run it as tar. */
    private class HostTar(val file: File, val argv: List<String>)

    /**
     * Android's tar, copied into app storage. PRoot opens every program it runs, to read its
     * headers, and Samsung's Android 16 refuses PRoot opening anything under /system/bin
     * (Galaxy S24 to S26, #1) while the app itself may. A copy in app storage runs anywhere.
     * Named tar: /system/bin/tar is usually a link to toybox (or busybox), which picks its
     * command from the name it was run by and refuses names it doesn't know.
     */
    private fun hostTar(): HostTar {
        val copy = File(paths.cacheDir, "host-tools/tar")
        copy.parentFile?.mkdirs()
        File("/system/bin/tar").inputStream().use { input -> copy.outputStream().use { input.copyTo(it) } }
        check(copy.setExecutable(true)) { "couldn't make ${copy.name} executable" }
        return HostTar(copy, listOf(copy.path))
    }

}

/**
 * Runs PRoot with [args] — the tarball flags, or `/bin/sh -c <script>` for a first-boot script —
 * feeding it [stdin] gunzipped when it holds a tarball to read, under the host env [env]. Returns
 * the exit code and everything it printed; the caller turns a nonzero one into an [InstallFailure].
 */
internal suspend fun runProot(
    proot: String,
    env: Map<String, String>,
    stdin: File?,
    vararg args: String,
): Pair<Int, List<String>> {
    val (code, output) = runInterruptible {
        val builder = ProcessBuilder(listOf(proot) + args)
            .redirectErrorStream(true)
            .apply { environment().putAll(env) }
        // No tarball to feed (a first-boot script): don't hand the child a pipe it may wait on.
        if (stdin == null) builder.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        val proc = builder.start()
        // Fed from its own thread: tar's output must keep draining meanwhile.
        var feeder: Thread? = null
        if (stdin != null) {
            feeder = Thread {
                runCatching {
                    java.util.zip.GZIPInputStream(stdin.inputStream().buffered()).use { input ->
                        proc.outputStream.use { input.copyTo(it) }
                    }
                }
            }.apply { start() }
        }
        try {
            val output = proc.inputStream.bufferedReader().readLines()
            proc.waitFor() to output
        } finally {
            proc.destroy()
            feeder?.join()
        }
    }
    return code to output
}

/**
 * The [InstallFailure] a PRoot run that exited [code] with [output] produced; ptrace refusal gets its own advice.
 */
internal fun prootFailure(code: Int, output: List<String>, what: String = "extract"): InstallFailure =
    InstallFailure(if (ptraceRefused(output)) PTRACE_REFUSED else "$what failed ($code): ${prootErrors(output)}", output.joinToString("\n"))

/**
 * The kernel refused PRoot's PTRACE_TRACEME, so nothing at all can run under PRoot. Stock
 * Android 17 still allows it (its policy grants untrusted_app_all ptrace on itself, and GKI
 * has no Yama); ROMs can block it per app, GrapheneOS among them (#14). PRoot then also
 * reports an execve EPERM with advice about seccomp, which doesn't apply.
 */
internal fun ptraceRefused(output: List<String>): Boolean =
    output.any { "ptrace(TRACEME)" in it && "Operation not permitted" in it }

internal const val PTRACE_REFUSED =
    "This phone doesn't let Tessera trace its own processes (ptrace), which PRoot mode needs to run anything. " +
        "Some systems block it per app: on GrapheneOS, turn on Native code debugging for Tessera in its app info " +
        "(Android may also have shown a notification that leads there), then try again. With root, chroot mode doesn't need it."

/** A failed install step, with all it printed: the message keeps only the lines that matter. */
class InstallFailure(message: String, val output: String) : IOException(message)

/**
 * What PRoot said went wrong, for an error message: its "error:" and "warning:" lines name the
 * cause (the failing call and the system's reason); the advice after them is the same every time.
 */
internal fun prootErrors(output: List<String>): String {
    val errors = output.filter { (" error: " in it || " warning: " in it) && !it.startsWith("fatal error") }.distinct()
    return (errors.ifEmpty { output.takeLast(3) }).joinToString(" / ")
}

/**
 * The root-side install for chroot mode, as one shell script: what [RootfsSetup] does for
 * PRoot, but with real root (so no APT sandbox tweak is needed beyond Android's network rules)
 * and in a directory the app can't write itself.
 */
object ChrootSetup {
    /** Convenience for a single-layer image. */
    fun script(root: String, tarball: String, marker: String, distro: Distro = Distro.DEBIAN): String =
        script(root, listOf(tarball), marker, distro)

    fun script(root: String, tarballs: List<String>, marker: String, distro: Distro = Distro.DEBIAN): String {
        require(tarballs.isNotEmpty()) { "at least one tarball is needed" }
        // Whiteouts (.wh.*) are out of scope; the pinned image's layers only overwrite files, so
        // unpacking them in order into the same tree gives the final filesystem.
        val extract = tarballs.joinToString("\n") { "/system/bin/tar -xzf '$it' -C \"\$T\"" }
        val firstBoot = when {
            distro == Distro.ARCH_X86_64 -> """
                # Arch's keyring needs priming before pacman can install anything; the official
                # x86_64 image ships a working mirrorlist, so leave it exactly as it shipped.
                # pacman-key is the guest's, so it must run as the guest: give gpg the device
                # nodes a tarball may lack, then step in with the first chroot tool the phone has.
                mkdir -p "${'$'}T/dev"
                for d in null zero random urandom; do
                  [ ! -e "/dev/${'$'}d" ] || [ -e "${'$'}T/dev/${'$'}d" ] || cp -a "/dev/${'$'}d" "${'$'}T/dev/${'$'}d" || true
                done
                ENTER=
                if command -v chroot >/dev/null 2>&1; then
                  ENTER=chroot
                else
                  for bb in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox; do
                    if [ -x "${'$'}bb" ]; then ENTER="${'$'}bb chroot"; break; fi
                  done
                fi
                [ -n "${'$'}ENTER" ] || { echo "No chroot found to initialise the Arch keyring" >&2; exit 1; }
                GUEST_ENV="/usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin HOME=/root"
                ${'$'}ENTER "${'$'}T" ${'$'}GUEST_ENV /usr/bin/pacman-key --init
                ${'$'}ENTER "${'$'}T" ${'$'}GUEST_ENV /usr/bin/pacman-key --populate archlinux
                ${'$'}ENTER "${'$'}T" ${'$'}GUEST_ENV /bin/sh -c '[ -f /var/lib/pacman/sync/core.db ] || /usr/bin/pacman -Syu --noconfirm'
            """.trimIndent()
            distro == Distro.ARCH -> """
                # Arch needs its keyring primed from the ALARM mirror before pacman can install anything.
                [ -e "${'$'}T/etc/pacman.d/mirrorlist" ] || echo 'Server = https://ca.us.mirror.archlinuxarm.org/${'$'}arch/${'$'}repo' > "${'$'}T/etc/pacman.d/mirrorlist"
                # pacman-key is the guest's, so it must run as the guest: give gpg the device
                # nodes a tarball may lack, then step in with the first chroot tool the phone has.
                mkdir -p "${'$'}T/dev"
                for d in null zero random urandom; do
                  [ ! -e "/dev/${'$'}d" ] || [ -e "${'$'}T/dev/${'$'}d" ] || cp -a "/dev/${'$'}d" "${'$'}T/dev/${'$'}d" || true
                done
                ENTER=
                if command -v chroot >/dev/null 2>&1; then
                  ENTER=chroot
                else
                  for bb in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox; do
                    if [ -x "${'$'}bb" ]; then ENTER="${'$'}bb chroot"; break; fi
                  done
                fi
                [ -n "${'$'}ENTER" ] || { echo "No chroot found to initialise the Arch keyring" >&2; exit 1; }
                GUEST_ENV="/usr/bin/env -i PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin HOME=/root"
                ${'$'}ENTER "${'$'}T" ${'$'}GUEST_ENV /usr/bin/pacman-key --init
                ${'$'}ENTER "${'$'}T" ${'$'}GUEST_ENV /usr/bin/pacman-key --populate archlinuxarm
            """.trimIndent()
            else -> """
                # apt downloads as _apt, which isn't in Android's network groups.
                [ -d "${'$'}T/etc/apt/apt.conf.d" ] && echo 'APT::Sandbox::User "root";' > "${'$'}T/etc/apt/apt.conf.d/99launcher"
                for f in "${'$'}T"/etc/apt/sources.list.d/*.sources; do
                  [ -f "${'$'}f" ] && sed -i 's/^Components:.*/Components: main contrib non-free non-free-firmware/' "${'$'}f"
                done
            """.trimIndent()
        }
        return """
        set -e
        R='$root'
        T="${'$'}R.partial"
        if [ -e "${'$'}R/usr/bin/env" ]; then echo "A Linux system is already at ${'$'}R"; exit 0; fi
        # Something unusable in the way: keep it aside rather than delete it.
        [ -e "${'$'}R" ] && mv "${'$'}R" "${'$'}R.old-${'$'}(date +%s)"
        rm -rf "${'$'}T"; mkdir -p "${'$'}T"
        $extract
        rm -f "${'$'}T/etc/resolv.conf" "${'$'}T/etc/hosts"
        printf '%s' ${shellQuote(HostDns.resolvConf(emptyList()))} > "${'$'}T/etc/resolv.conf"
        printf '127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n' > "${'$'}T/etc/hosts"
        $firstBoot
        # Groups Android gives apps, named so id and bash stay quiet.
        for g in aid_inet:3003 aid_net_raw:3004 aid_everybody:9997; do
          grep -q "^${'$'}{g%%:*}:" "${'$'}T/etc/group" || echo "${'$'}{g%%:*}:x:${'$'}{g#*:}:" >> "${'$'}T/etc/group"
        done
        mkdir -p "${'$'}T/home/user" "${'$'}T/root"
        echo '$marker' > "${'$'}T/${ContainerPaths.INSTALLED_MARKER}"
        mv "${'$'}T" "${'$'}R"
        echo "Installed at ${'$'}R"
        """.trimIndent()
    }

    /**
     * Deletes the system at [root] and what installs left beside it, as root; its last line says
     * why when it fails. Sessions mount in namespaces of their own, so this shell shouldn't see
     * their mounts, but rm follows a mount it does see: with one left inside (a user's own, say),
     * phone storage could go with it. So any mount there stops it before anything is deleted.
     * Built in to the shell, so a root shell short of tools can still check.
     */
    fun removeScript(root: String): String = """
        export PATH=/system/bin:${'$'}PATH
        R=${shellQuote(root)}
        P="${'$'}{R%/*}"
        case "${'$'}R" in
          /data/local/*/*) ;;
          *) echo "Not removing ${'$'}R: it isn't Tessera's folder."; exit 2 ;;
        esac
        while read -r id parent dev base point rest; do
          case "${'$'}point" in
            "${'$'}P"|"${'$'}P"/*) echo "Nothing was removed: ${'$'}point is still mounted. Restart the phone and try again."; exit 3 ;;
          esac
        done < /proc/self/mountinfo
        rm -rf -- "${'$'}R" "${'$'}R.partial" "${'$'}R".old-*
        rmdir "${'$'}P" 2>/dev/null
        if [ -e "${'$'}R" ]; then echo "Couldn't remove all of ${'$'}R."; exit 1; fi
        echo "Removed ${'$'}R"
    """.trimIndent()

    /** Runs [removeScript] through [su]; null when it's gone, else why not. */
    fun remove(su: String, root: String): String? = runCatching {
        val p = ProcessBuilder(su, "-c", removeScript(root)).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readLines()
        if (p.waitFor() == 0) null else output.lastOrNull { it.isNotBlank() } ?: "Root access was refused."
    }.getOrElse { it.message ?: "Couldn't start the removal." }
}

/** First-boot configuration of a freshly extracted rootfs. Plain file edits, no guest code runs. */
object RootfsSetup {
    fun configure(root: File, uid: Int, gid: Int, distro: Distro = Distro.DEBIAN) {
        configureCommon(root, uid, gid)
        // apt is Debian's package manager; Arch uses pacman, and its first boot primes the keyring.
        if (distro == Distro.DEBIAN) {
            val etc = File(root, "etc").apply { mkdirs() }
            File(etc, "apt/apt.conf.d").takeIf { it.isDirectory }?.let {
                File(it, "99launcher").writeText("APT::Sandbox::User \"root\";\n")
            }
            enableAllComponents(etc)
        }
    }

    /** The rootfs setup every distro shares: DNS, the app user, and Android's supplementary groups. */
    internal fun configureCommon(root: File, uid: Int, gid: Int) {
        val etc = File(root, "etc").apply { mkdirs() }

        // Android has no /etc/resolv.conf, and image ones may be symlinks into /run. Automatic:
        // Android's own servers replace these as sessions start (HostDns).
        replace(File(etc, "resolv.conf"), HostDns.resolvConf(emptyList()))
        replace(File(etc, "hosts"), "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")

        // Non-root sessions run as the app's real uid; give it a name and a home.
        val shell = if (File(root, "bin/bash").exists()) "/bin/bash" else "/bin/sh"
        appendLineIfMissing(File(etc, "passwd"), "user:", "user:x:$uid:$gid:User:/home/user:$shell")
        appendLineIfMissing(File(etc, "group"), "user:", "user:x:$gid:")
        val appId = uid % 100_000
        // Supplementary groups Android gives every app; named so `id` and bash stay quiet.
        for ((name, id) in listOf(
            "aid_inet" to 3003, "aid_net_raw" to 3004, "aid_everybody" to 9997,
            "aid_${appId}_cache" to 20_000 + appId, "aid_${appId}_all" to 50_000 + appId,
        )) {
            appendLineIfMissing(File(etc, "group"), "$name:", "$name:x:$id:user")
        }
        File(root, "home/user").mkdirs()
        File(root, "root").mkdirs()
    }

    /**
     * Container images ship Debian with only `main` enabled; desktop users expect contrib and
     * non-free too (fonts, codecs, firmware, unrar, ...). Rewrites deb822 Components lines.
     */
    fun enableAllComponents(etc: File) {
        val sources = File(etc, "apt/sources.list.d").listFiles { f -> f.name.endsWith(".sources") }.orEmpty()
        for (f in sources) {
            val lines = f.readLines()
            val fixed = lines.map { line ->
                if (!line.startsWith("Components:")) return@map line
                val have = line.removePrefix("Components:").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                "Components: " + (have + DEBIAN_COMPONENTS.filterNot { it in have }).joinToString(" ")
            }
            if (fixed != lines) f.writeText(fixed.joinToString("\n", postfix = "\n"))
        }
    }

    private val DEBIAN_COMPONENTS = listOf("main", "contrib", "non-free", "non-free-firmware")

    private fun replace(f: File, content: String) {
        f.delete() // drops a dangling symlink rather than writing through it
        f.writeText(content)
    }

    private fun appendLineIfMissing(f: File, prefix: String, line: String) {
        val existing = if (f.exists()) f.readLines() else emptyList()
        if (existing.none { it.startsWith(prefix) }) {
            f.writeText((existing + line).joinToString("\n", postfix = "\n"))
        }
    }
}

/**
 * Stand-ins for /proc files Android's SELinux policy hides from apps. Without them htop, top,
 * uptime and friends fail. Bound over the real paths by [ProotBackend].
 */
object FakeProc {
    val NAMES = listOf("stat", "loadavg", "uptime", "version", "vmstat", "filesystems", "cpuinfo", "sys/kernel/cap_last_cap")

    /** A core without SVE (Cortex-A78), for [withoutSve]. */
    const val NO_SVE_PART = "0xd41"

    /**
     * [cpuinfo] with every core named a Cortex-A78, unless the kernel lists SVE. LLVM, and so
     * llvmpipe's shader JIT, picks instructions by core model rather than by what the kernel
     * enables: Cortex-X3 and A715 have SVE, Android kernels leave it off, and OpenGL apps
     * drawing in software died of SIGILL. enter.sh and flatpak.sh do the same in chroot mode.
     */
    fun withoutSve(cpuinfo: String): String {
        val sve = cpuinfo.lineSequence().any { it.startsWith("Features") && "sve" in it.substringAfter(':').split(' ', '\t') }
        if (sve) return cpuinfo
        return cpuinfo.replace(Regex("^(CPU part\\s*:\\s*).*$", RegexOption.MULTILINE)) { it.groupValues[1] + NO_SVE_PART }
    }

    /**
     * Once: drops the shaders llvmpipe compiled in [rootfs] before [withoutSve], which Mesa's
     * disk cache doesn't key by core. enter.sh does the same in chroot mode.
     */
    fun clearSveShaders(rootfs: File) {
        for (cache in listOf(File(rootfs, "root/.cache"), File(rootfs, "home/user/.cache"))) {
            val marker = File(cache, ".tessera-no-sve")
            if (!cache.isDirectory || marker.exists()) continue
            File(cache, "mesa_shader_cache").deleteRecursively()
            File(cache, "mesa_shader_cache_db").deleteRecursively()
            marker.createNewFile()
        }
    }

    fun write(
        dir: File,
        cpus: Int = Runtime.getRuntime().availableProcessors(),
        kernel: String = System.getProperty("os.version") ?: "6.1.0",
        cpuinfo: String = runCatching { File("/proc/cpuinfo").readText() }.getOrDefault(""),
    ) {
        fun put(name: String, text: String) = File(dir, name).apply { parentFile?.mkdirs() }.writeText(text)
        put("cpuinfo", withoutSve(cpuinfo))
        put("stat", buildString {
            append("cpu  ${cpus * 1000} 0 ${cpus * 500} ${cpus * 90000} 0 0 0 0 0 0\n")
            repeat(cpus) { append("cpu$it 1000 0 500 90000 0 0 0 0 0 0\n") }
            append("intr 0\nctxt 0\nbtime ${System.currentTimeMillis() / 1000}\n")
            append("processes 0\nprocs_running 1\nprocs_blocked 0\nsoftirq 0 0 0 0 0 0 0 0 0 0 0\n")
        })
        put("loadavg", "0.12 0.07 0.02 2/165 765\n")
        put("uptime", "1000.00 900.00\n")
        put("version", "Linux version $kernel (launcher@android) #1 SMP PREEMPT\n")
        put("vmstat", listOf(
            "nr_free_pages 100000", "nr_inactive_anon 0", "nr_active_anon 0", "nr_inactive_file 0",
            "nr_active_file 0", "nr_dirty 0", "nr_writeback 0", "pgpgin 0", "pgpgout 0",
            "pswpin 0", "pswpout 0", "pgfault 0", "pgmajfault 0",
        ).joinToString("\n", postfix = "\n"))
        put("filesystems", listOf(
            "nodev\tsysfs", "nodev\ttmpfs", "nodev\tproc", "nodev\tdevpts", "\text4", "\tf2fs", "\tvfat",
        ).joinToString("\n", postfix = "\n"))
        put("sys/kernel/cap_last_cap", "40\n")
    }
}
