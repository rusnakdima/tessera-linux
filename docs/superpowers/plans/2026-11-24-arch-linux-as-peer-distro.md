# Arch Linux as a first-class secondary OS — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Arch Linux ARM a selectable peer of Debian in Tessera with working pacman, AUR installs, full Store integration, and all existing features preserved.

**Architecture:** Keep one container backend per mode (PRoot/chroot) and add an active-distro dimension: paths, installer, package scripts, catalog and installed-package detection are selected from a persisted preference, and switching cold-restarts the app so no long-lived object keeps stale paths. Debian keeps its OCI image and apt pipeline; Arch uses the official aarch64 tarball, a pacman/makepkg script pipeline, repo DB metadata from an ALARM mirror, and AUR metadata/search from AUR archives/RPC.

**Tech Stack:** Kotlin, Jetpack Compose, Gradle Kotlin DSL, JVM unit tests (JUnit4 + `TemporaryFolder`), `HttpURLConnection`, `java.util.zip` (gzip), kotlinx.serialization, Android `StatFs`/`AlarmManager`.

**Spec:** `docs/superpowers/specs/2026-11-24-arch-linux-as-peer-distro-design.md`

## Global Constraints

- minSdk 24, targetSdk 28, compileSdk 37, Java 17 — no new dependencies.
- No Robolectric: unit tests exercise pure functions, generated shell text, parsers, and local `HttpServer` fixtures only.
- Paths come from `HostProbe.pathsFor(context, distro)`: `files/rootfs/$distro` and `/data/local/linux/$distro`. Never hardcode `debian` in a shared component.
- Debian remains the default distro; its apt/dep11/dpkg behaviour must not change when Arch is not active.
- When Arch is active: no apt commands, no dpkg detection, no silent fallback to Debian packages.
- All network reads (rootfs, `.md5`, AUR RPC/archives, mirror DBs) set a descriptive `User-Agent` and run off the main thread.
- Arch rootfs URL: `https://ca.us.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz`; checksum URL is that URL plus `.md5`; approximate download size 829,367,415 bytes.
- AUR RPC v5 base `https://aur.archlinux.org/rpc/v5` requires a User-Agent; searches need ≥2 characters; RPC requests are limited to 4000/day/IP, so the bulk archive is preferred for catalog loads.
- AUR package installs require `base-devel` and `git`; `makepkg` must never run as root.

## Review Focus

- **Stale cached Arch tarball with matching size but wrong MD5** — must be re-downloaded, not installed. Test: Task 1 `RootfsArtifactTest.cachedFileWithWrongChecksumIsRejected`.
- **AUR build fails (commonly x86_64-only package on arm64)** — must surface `makepkg` output and not report success. Test: Task 4 `PacmanScriptTest.aurFailureKeepsMakepkgOutput`.
- **Debian still active** — must never emit pacman/makepkg commands or change apt progress. Tests: Task 4 `PacmanScriptTest.debianStillUsesAptScript` and Task 7 `DistroSelectionTest.defaultIsDebian`.
- **Arch has no `/var/lib/dpkg/status`** — installed detection must read pacman local DB and still mark apps installed. Test: Task 5 `StoreRegistryTest.archInstalledFromPacmanDesc`.
- **Switching distro with a live session** — must ask before stopping and restarting, never kill silently. Test: Task 7 `DistroSelectionTest.switchRequiresConfirmationWhenSessionsLive`.

---

### Task 1: Distro enum and verified rootfs artifacts

**Files:**
- Modify: `app/src/main/java/com/daryan/tessera/container/OciRegistry.kt:20-45`
- Create: `app/src/main/java/com/daryan/tessera/container/RootfsArtifact.kt`
- Create: `app/src/main/java/com/daryan/tessera/container/HttpFetch.kt`
- Modify: `app/src/main/java/com/daryan/tessera/container/RootfsInstaller.kt:65-75,100-121`
- Modify: `app/src/main/java/com/daryan/tessera/container/RootfsInstaller.kt:86` (chroot marker text)
- Test: `app/src/test/java/com/daryan/tessera/container/RootfsArtifactTest.kt`
- Test: `app/src/test/java/com/daryan/tessera/container/InstallerTest.kt`

**Interfaces:**
- Consumes: `Distro`, `Layer`, `OciRegistry.resolve/download`, `Platform.forAbi`, `ContainerPaths.cacheDir`, `InstallState`.
- Produces: `Distro.ARCH`; `RootfsArtifact(key: String, url: String, size: Long, checksum: String, algorithm: String)` with `fun verify(file: File): Boolean`; `object RootfsSources { suspend fun resolve(distro: Distro, abi: Platform): RootfsArtifact; suspend fun download(artifact: RootfsArtifact, dest: File, onProgress: (Long) -> Unit); fun parseMd5(text: String): String }`; `object HttpFetch { fun text(url: String): String; suspend fun download(url: String, dest: File, expected: Long, onProgress: (Long) -> Unit) }`.

- [ ] **Step 1: Write failing tests for artifact verification and MD5 parsing**

```kotlin
class RootfsArtifactTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun md5FromSiblingFileIsParsedAndVerified() {
        val file = tmp.newFile("root.tar.gz").apply { writeBytes("arch".toByteArray()) }
        val artifact = RootfsArtifact(
            key = "arch-aarch64-latest", url = "http://example/root.tar.gz",
            size = 4, checksum = "7fc56270e7a70fa81a5935b72eacbe29", algorithm = "MD5",
        )
        assertTrue(artifact.verify(file))
        assertFalse(artifact.copy(checksum = "0".repeat(32)).verify(file))
    }

    @Test fun cachedFileWithWrongChecksumIsRejected() {
        val file = tmp.newFile("root.tar.gz").apply { writeBytes("old".toByteArray()) }
        val artifact = RootfsArtifact("arch-aarch64-latest", "http://example/root", 4,
            "7fc56270e7a70fa81a5935b72eacbe29", "MD5")
        assertFalse(artifact.verify(file)) // size matches, content does not
    }

    @Test fun parseMd5FileTakesFirstToken() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e",
            RootfsSources.parseMd5("d41d8cd98f00b204e9800998ecf8427e  ArchLinuxARM-aarch64-latest.tar.gz\n"))
    }
}
```

- [ ] **Step 2: Run the tests and verify they fail**

Run: `./gradlew testDebugUnitTest --tests 'com.daryan.tessera.container.RootfsArtifactTest'`
Expected: compile failure — `RootfsArtifact` / `RootfsSources` unresolved. (If the Android SDK is absent in this container, record that and continue with source-level review; do not install an SDK silently.)

- [ ] **Step 3: Implement the artifact model and HTTP helper**

In `OciRegistry.kt` add `ARCH("Arch Linux ARM", "archlinuxarm", "aarch64", 830)` to `Distro` (keep Debian first and default). In `RootfsArtifact.kt` implement `verify` with `MessageDigest.getInstance(algorithm)`, streaming the file in 64 KB chunks. In `RootfsSources.resolve`: Debian delegates to `OciRegistry.resolve` and maps `Layer` to an artifact (`key = layer.digest`, `url = blob URL`, `algorithm = "SHA-256"`, `checksum = digest after ':'`); Arch resolves size from an HTTP HEAD (or GET Content-Length) and checksum from `parseMd5(HttpFetch.text(url + ".md5"))`, key `arch-aarch64-latest`. `HttpFetch` uses `HttpURLConnection` with a `Tessera/1.0` User-Agent, follows redirects, honours HTTP errors, and streams to `dest` with `Range: bytes=<existing>-` when a partial file exists; `onProgress` reports total bytes written.

- [ ] **Step 4: Update `RootfsInstaller` to use artifacts**

`fetch(distro)` now calls `RootfsSources.resolve`, names the cache file `rootfs-${artifact.key.take(16)}.tar.gz`, verifies any cached file with `artifact.verify`, deletes and re-downloads on mismatch, and checks `StatFs(paths.cacheDir.path).availableBytes >= artifact.size * 3L` before downloading (throw `InstallFailure("About 2.5 GB of free space is needed to install Arch", "")`). Cancellation must delete partial downloads only on `CancellationException`. Chroot marker text uses `artifact.key` instead of `layer.digest`. Extraction through PRoot and `RootfsSetup.configure(root, uid, gid, distro)` (new parameter) stay as-is.

- [ ] **Step 5: Update `InstallerTest` and run all tests**

Replace `assertEquals(Layer("sha256:x", 42), OciRegistry.singleLayer(one))` with the corresponding `RootfsSources` mapping assertion; keep the bad-manifest assertion. Run: `./gradlew testDebugUnitTest` — expected PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/daryan/tessera/container/OciRegistry.kt app/src/main/java/com/daryan/tessera/container/RootfsArtifact.kt app/src/main/java/com/daryan/tessera/container/HttpFetch.kt app/src/main/java/com/daryan/tessera/container/RootfsInstaller.kt app/src/test/java/com/daryan/tessera/container/
git commit -m "feat: add verified Arch rootfs artifact downloads"
```

---

### Task 2: Distro-aware rootfs and chroot configuration

**Files:**
- Create: `app/src/main/java/com/daryan/tessera/container/ArchSetup.kt`
- Modify: `app/src/main/java/com/daryan/tessera/container/RootfsInstaller.kt:204-280`
- Test: `app/src/test/java/com/daryan/tessera/container/ArchSetupTest.kt`

**Interfaces:**
- Consumes: `Distro`, `FakeProc`, `RootfsSetup.configureCommon`, `ChrootSetup.script`.
- Produces: `object ArchSetup { fun configure(root: File, uid: Int, gid: Int); fun firstBootScript(): String }`; `RootfsSetup.configure(root: File, uid: Int, gid: Int, distro: Distro = Distro.DEBIAN)`; `ChrootSetup.script(root: String, tarball: String, marker: String, distro: Distro = Distro.DEBIAN)`.

- [ ] **Step 1: Write failing tests**

```kotlin
class ArchSetupTest {
    @Test fun firstBootInitialisesArchLinuxArmKeyring() {
        val script = ArchSetup.firstBootScript()
        assertTrue("pacman-key --init" in script)
        assertTrue("pacman-key --populate archlinuxarm" in script)
        assertFalse("apt-get" in script)
    }

    @Test fun chrootScriptIncludesArchFirstBootWhenDistroIsArch() {
        val script = ChrootSetup.script("/root/arch", "/tmp/a.tar.gz", "arch-aarch64", Distro.ARCH)
        assertTrue("pacman-key --populate archlinuxarm" in script)
    }

    @Test fun debianChrootScriptStillHasAptTweaks() {
        val script = ChrootSetup.script("/root/debian", "/tmp/d.tar.gz", "sha256:x", Distro.DEBIAN)
        assertTrue("APT::Sandbox::User" in script)
    }
}
```

- [ ] **Step 2: Run the tests; expect compile failure on the new signatures**

Run: `./gradlew testDebugUnitTest --tests 'com.daryan.tessera.container.ArchSetupTest'`

- [ ] **Step 3: Split rootfs configuration**

In `RootfsSetup.kt` extract the shared resolv.conf/hosts/user/group/Android-aid work into `internal fun configureCommon(root, uid, gid)`; keep apt conf/component/source rewrites in a Debian-only branch of `configure(root, uid, gid, distro)`. In `ArchSetup.configure` call `configureCommon` then run `firstBootScript()` through the existing PRoot shell path (reuse the `runProot` helper pattern from `RootfsInstaller`, `-0` and `/bin/sh -c`). `firstBootScript()` sets mirrorlist to the same ALARM mirror used for the tarball check and runs the two `pacman-key` commands. In `ChrootSetup.script` append the Arch first-boot lines only when `distro == Distro.ARCH`.

- [ ] **Step 4: Run the tests and the full unit suite; expected PASS**

Run: `./gradlew testDebugUnitTest --tests 'com.daryan.tessera.container.ArchSetupTest'` then `./gradlew testDebugUnitTest`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/daryan/tessera/container/ArchSetup.kt app/src/main/java/com/daryan/tessera/container/RootfsInstaller.kt app/src/test/java/com/daryan/tessera/container/ArchSetupTest.kt
git commit -m "feat: configure Arch rootfs and chroot first boot"
```

---

### Task 3: Distro-neutral GUI wrapper and Arch package maps

**Files:**
- Create: `app/src/main/java/com/daryan/tessera/container/ArchPackages.kt`
- Modify: `app/src/main/java/com/daryan/tessera/container/ContainerBackend.kt:140-300` (make `GUI_WRAPPER` `internal const val`; package constants and wrapper)
- Test: `app/src/test/java/com/daryan/tessera/container/ArchPackagesTest.kt`

**Interfaces:**
- Consumes: `GUI_WRAPPER`, `BASE_LIBRARIES`, `GUI_HELPER_PACKAGES`, `THEME_COMPANIONS`, `DESKTOP_COMPANIONS`, `COMPOSITOR_PACKAGE`.
- Produces: `object ArchPackages { val baseLibraries: List<String>; val guiHelpers: List<String>; val themeCompanions: Map<String,String>; val desktopCompanions: Map<String,String>; const val compositor: String; fun packagesFor(distro: Distro): PackageSets }`; `data class PackageSets(...)` holding the same fields for both distros.

- [ ] **Step 1: Write failing tests**

```kotlin
class ArchPackagesTest {
    @Test fun archHelpersAvoidAurOnlyMatchbox() {
        val helpers = ArchPackages.guiHelpers
        assertTrue("openbox" in helpers)
        assertTrue("dbus" in helpers)
        assertTrue("python-pygobject" in helpers)
        assertFalse("matchbox-window-manager" in helpers)
    }

    @Test fun archGuiWrapperProbesModulesNotDebianDirectories() {
        val wrapper = GUI_WRAPPER
        assertTrue("python3 -c 'import gi'" in wrapper)
        assertFalse("/usr/lib/python3/dist-packages" in wrapper)
        assertTrue("openbox" in wrapper)
    }

    @Test fun debianPackageSetsAreUnchanged() {
        assertEquals(BASE_LIBRARIES, ArchPackages.packagesFor(Distro.DEBIAN).baseLibraries)
    }
}
```

- [ ] **Step 2: Run tests; expect failure on missing symbols / old wrapper text**

Run: `./gradlew testDebugUnitTest --tests 'com.daryan.tessera.container.ArchPackagesTest'`

- [ ] **Step 3: Add Arch package sets and neutralize `GUI_WRAPPER`**

`ArchPackages.packagesFor(DEBIAN)` returns the existing constants verbatim; for `ARCH` maps base libraries to Arch equivalents (`mesa`, `libglvnd`, `lib32-libglvnd` not needed on arm64, `fuse2`, `gtk3`, `ca-certificates`, `ttf-dejavu`), GUI helpers to `openbox dbus at-spi2-core python-pyatspi pulseaudio python-xlib xsettingsd`, theme companions to `qt6-gtk-platformtheme`, `qt5-gtk-platformtheme`, `libreoffice-gtk3`, desktop companions `xfwm4 -> thunar`, compositor `picom`. Change `private const val GUI_WRAPPER` to `internal const val GUI_WRAPPER` for the test. In `GUI_WRAPPER`, replace each Debian path check with `python3 -c 'import gi'`/`python3 -c 'import Xlib'`/`python3 -c 'import pyatspi'`; keep the GTK platform-theme glob as a fallback, not a requirement; choose WM by probing `openbox`, `fluxbox`, `jwm`, then `matchbox-window-manager`. Keep Firefox profile handling for both `firefox` and `firefox-esr`.

- [ ] **Step 4: Run tests; expected PASS**

Run: `./gradlew testDebugUnitTest`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/daryan/tessera/container/ArchPackages.kt app/src/main/java/com/daryan/tessera/container/ContainerBackend.kt app/src/test/java/com/daryan/tessera/container/ArchPackagesTest.kt
git commit -m "feat: make GUI helpers work on Arch Linux"
```

---

### Task 4: Package-script abstraction and AUR installs

**Files:**
- Create: `app/src/main/java/com/daryan/tessera/store/PackageScript.kt`
- Modify: `app/src/main/java/com/daryan/tessera/store/InstallQueue.kt:20-100,139-213,226-275`
- Test: `app/src/test/java/com/daryan/tessera/store/PacmanScriptTest.kt`
- Test: `app/src/test/java/com/daryan/tessera/store/InstallQueueTest.kt`

**Interfaces:**
- Consumes: `QueueJob`, `QueueAction`, `InstallQueue`, `ContainerBackend`, `Pty`.
- Produces: `interface PackageScript { fun script(job: QueueJob): String; fun parseProgress(line: String): Pair<Int?, String>?; fun failure(lines: List<String>, code: Int): String }`; `object AptScript`, `object PacmanScript : PackageScript`; `QueueJob.aur: Boolean` and `InstallQueue.enqueue(..., aur: Boolean = false, ...)`; `InstallQueue(scope, paths, distro: Distro = Distro.DEBIAN)`.

- [ ] **Step 1: Write failing tests**

```kotlin
class PacmanScriptTest {
    @Test fun repoInstallUsesPacmanWithoutApt() {
        val job = QueueJob(1, "Firefox", listOf("firefox"), QueueAction.INSTALL, iconFile = null)
        val script = PacmanScript.script(job)
        assertTrue("pacman -S --noconfirm --needed firefox" in script)
        assertFalse("apt-get" in script)
    }

    @Test fun aurBuildRunsAsUserAndInstallsAsRoot() {
        val job = QueueJob(2, "yay", listOf("yay"), QueueAction.INSTALL, iconFile = null, aur = true)
        val script = PacmanScript.script(job)
        assertTrue("runuser -u user -- makepkg -s" in script)
        assertTrue("pacman -U --noconfirm" in script)
        assertTrue("base-devel" in script && "git" in script)
    }

    @Test fun aurFailureKeepsMakepkgOutput() {
        val lines = listOf("==> ERROR: package is only available for x86_64", "makepkg failed")
        assertEquals("==> ERROR: package is only available for x86_64",
            PacmanScript.failure(lines, 2))
    }

    @Test fun debianStillUsesAptScript() {
        val job = QueueJob(3, "x", listOf("x"), QueueAction.INSTALL, iconFile = null)
        assertEquals(InstallQueue.script(job), AptScript.script(job))
    }
}
```

- [ ] **Step 2: Run tests; expect compile failure on new signatures**

Run: `./gradlew testDebugUnitTest --tests 'com.daryan.tessera.store.PacmanScriptTest'`

- [ ] **Step 3: Implement script selection**

Move the existing `InstallQueue.script` body into `AptScript.script` unchanged; keep a delegating `InstallQueue.script` for compatibility. `PacmanScript.script`: repo install `pacman -S --noconfirm --needed <shellQuoted pkgs>`, remove `pacman -Rns --noconfirm ...`, followed by `pacman -Scc --noconfirm` only on explicit user request (not automatic). AUR script: install `base-devel git`, create `/tmp/tessera-aur` owned by `user:user`, `runuser -u user -- git clone --depth 1 https://aur.archlinux.org/<pkgbase>.git ...`, `runuser -u user -- makepkg -s --noconfirm`, then `pacman -U --noconfirm <built package>`; `pkgbase` is `job.packages.single()` and must be shell-quoted. `failure` prefers lines containing `==> ERROR:` then `error:`/last line. In `InstallQueue.run`, select `script` by `distro`, use `AptScript.parseProgress` for Debian and raw latest-line progress for Arch (no machine-readable percent), and use the selected `failure(...)` for the failure message. `retry` and `enqueue` carry `aur`.

- [ ] **Step 4: Extend `InstallQueueTest` with a fake pacman binary on PATH**

Add a test that enqueues an Arch install against `Distro.ARCH` with a fake `pacman` script recording its arguments and asserts the command line contains `-S --noconfirm --needed` and no `apt-get`. Run: `./gradlew testDebugUnitTest --tests 'com.daryan.tessera.store.InstallQueueTest'`

- [ ] **Step 5: Run the full unit suite; expected PASS, then commit**

```bash
git add app/src/main/java/com/daryan/tessera/store/PackageScript.kt app/src/main/java/com/daryan/tessera/store/InstallQueue.kt app/src/test/java/com/daryan/tessera/store/
git commit -m "feat: add pacman and AUR install pipeline"
```

---

### Task 5: Installed-package detection for Arch

**Files:**
- Modify: `app/src/main/java/com/daryan/tessera/container/ConfigFiles.kt:32-99` and both implementations
- Modify: `app/src/main/java/com/daryan/tessera/store/StoreRegistry.kt:38-60,200-215`
- Test: `app/src/test/java/com/daryan/tessera/store/StoreRegistryTest.kt`

**Interfaces:**
- Consumes: `ConfigStore`, `StoreRegistry.refreshInstalled(store: ConfigStore?)`.
- Produces: `ConfigStore.list(path: String): List<String>`; `StoreRegistry.refreshInstalled(store: ConfigStore?, distro: Distro = Distro.DEBIAN)`; `fun parsePacmanDesc(text: String): String?` in `StoreRegistry` companion.

- [ ] **Step 1: Write failing tests**

```kotlin
class StoreRegistryTest {
    @Test fun archInstalledFromPacmanDesc() {
        val desc = "%NAME%\n\nfirefox\n\n%VERSION%\n\n1.0-1\n\n"
        assertEquals("firefox", StoreRegistry.parsePacmanDesc(desc))
    }

    @Test fun debStatusStillParsedForDebian() {
        val status = "Package: nano\nStatus: install ok installed\nVersion: 8\n\n"
        assertEquals(setOf("nano"), StoreRegistry.installedPackages(status))
    }
}
```

- [ ] **Step 2: Run tests; expect compile failure on `parsePacmanDesc`**

Run: `./gradlew testDebugUnitTest --tests 'com.daryan.tessera.store.StoreRegistryTest'`

- [ ] **Step 3: Implement listing and pacman parsing**

Add `suspend fun list(path: String): List<String>` to `ConfigStore`: `DirectConfigStore` uses `File(path).listFiles()?.map { it.name }`; `SuConfigStore` runs `ls -1 <shellQuoted path>` via its existing su invocation. In `StoreRegistry.refreshInstalled`, when `distro == ARCH`, list `/var/lib/pacman/local`, read `<entry>/desc` for each directory, parse the first `%NAME%` block with `parsePacmanDesc`, and publish the set; on any error publish `emptySet()` rather than falling back to dpkg. Default Debian path unchanged. Thread `distro` from `LauncherContainer` in Task 7; all existing call sites keep compiling via the default argument.

- [ ] **Step 4: Run the full suite; expected PASS, then commit**

```bash
git add app/src/main/java/com/daryan/tessera/container/ConfigFiles.kt app/src/main/java/com/daryan/tessera/store/StoreRegistry.kt app/src/test/java/com/daryan/tessera/store/StoreRegistryTest.kt
git commit -m "feat: detect installed Arch packages from pacman DB"
```

---

### Task 6: Arch Store catalog (official repos + AUR)

**Files:**
- Create: `app/src/main/java/com/daryan/tessera/store/ArchCatalog.kt`
- Modify: `app/src/main/java/com/daryan/tessera/store/Tar.kt:10-41` (add `entries`)
- Modify: `app/src/main/java/com/daryan/tessera/store/Dep11.kt` (`StoreSource`, `StoreApp.aurPath`)
- Modify: `app/src/main/java/com/daryan/tessera/store/StoreCatalog.kt` (`loadArch`)
- Test: `app/src/test/java/com/daryan/tessera/store/ArchCatalogTest.kt`

**Interfaces:**
- Consumes: `Tar`, `StoreApp`, `StoreSource`, `CatalogState`, `HttpFetch`, kotlinx.serialization `Json`.
- Produces: `object ArchRepoDb { fun parse(db: InputStream, repo: String): List<StoreApp> }`; `object AurCatalog { fun parseSearch(json: String): List<StoreApp>; suspend fun search(query: String): List<StoreApp>; suspend fun loadArchive(dir: File): List<StoreApp> }`; `object ArchCatalog { suspend fun load(dir: File): List<StoreApp> }`; `StoreCatalog.loadArch(refresh: Boolean = false)`; `StoreSource.ARCH`, `StoreSource.AUR`.

- [ ] **Step 1: Write failing tests using fixture strings**

```kotlin
class ArchCatalogTest {
    @Test fun repoDescFilesBecomeStoreApps() {
        val gz = gzipUstar("%NAME%\n\ntestpkg\n\n%VERSION%\n\n1.2-3\n\n%DESC%\n\nA test\n\n".toByteArray(), "testpkg-1.2-3/desc")
        val apps = ArchRepoDb.parse(gz, "extra")
        assertEquals("testpkg", apps.single().pkg)
        assertEquals("A test", apps.single().summary)
        assertEquals(StoreSource.ARCH, apps.single().source)
    }

    @Test fun aurRpcResponseBecomesStoreApps() {
        val json = """{"version":5,"type":"search","resultcount":1,"results":[{"Name":"foo","PackageBase":"foo","Version":"1-1","Description":"Foo app","URLPath":"/cgit/aur.git/snapshot/foo.tar.gz","NumVotes":3}]}"""
        val app = AurCatalog.parseSearch(json).single()
        assertEquals("foo", app.pkg)
        assertEquals(StoreSource.AUR, app.source)
        assertEquals("/cgit/aur.git/snapshot/foo.tar.gz", app.aurPath)
    }

    @Test fun shortAurQueryIsRejectedWithoutRequest() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { AurCatalog.search("a") } }
    }
}
```

- [ ] **Step 2: Run tests; expect compile failure on missing symbols**

Run: `./gradlew testDebugUnitTest --tests 'com.daryan.tessera.store.ArchCatalogTest'`

- [ ] **Step 3: Implement parsers and catalog loading**

Add `Tar.entries(input, accept)` exposing `(path, bytes)` pairs from the existing ustar/pax reader and reimplement `extractFlat` on top of it. `ArchRepoDb.parse` gunzips the DB stream, keeps entries ending in `/desc`, and reads `%NAME%`, `%VERSION%`, `%DESC%` blocks into `StoreApp(id="arch:$repo:$name", pkg=name, name=name, summary=desc, source=ARCH, icon="", desktopId="")`. Add `aurPath: String? = null` to `StoreApp` (serialized with a default so existing caches stay readable). `AurCatalog.parseSearch` decodes the v5 JSON with `@Serializable` DTOs (fields from the spec), maps `Name`→`pkg`, `Description`→`summary`, `URLPath`→`aurPath`, `source=AUR`; ignore malformed entries. `search` enforces ≥2 characters, builds `GET .../search/<encoded>?by=name-desc` with a User-Agent, and throws on `type=error`. `loadArchive` downloads `packages-meta-v1.json.gz`, caches it as `aur-meta.json` with ETag/Last-Modified revalidation, and parses it with the same DTOs. `ArchCatalog.load` merges the four repo DBs from `https://ca.us.mirror.archlinuxarm.org/aarch64/<repo>/<repo>.db.tar.gz` with the AUR archive; failures of one source drop that source only, never the other. `StoreCatalog.loadArch` publishes `CatalogState.Ready(apps, updated)` to the same state flow; `openStore` in Task 8 selects it.

- [ ] **Step 4: Run the full suite; expected PASS, then commit**

```bash
git add app/src/main/java/com/daryan/tessera/store/ app/src/test/java/com/daryan/tessera/store/
git commit -m "feat: add Arch repo and AUR store catalog"
```

---

### Task 7: Active distro selection and cold restart

**Files:**
- Modify: `app/src/main/java/com/daryan/tessera/LauncherApplication.kt:130-207`
- Modify: `app/src/main/java/com/daryan/tessera/LauncherViewModel.kt:120-135,257-265,496-510,1037-1050,1540-1560`
- Create: `app/src/main/java/com/daryan/tessera/container/DistroSelection.kt`
- Create: `app/src/main/java/com/daryan/tessera/container/AppRestart.kt`
- Modify: `app/src/main/java/com/daryan/tessera/ui/SetupScreen.kt:130-195`
- Modify: `app/src/main/java/com/daryan/tessera/ui/LauncherApp.kt:225-245`
- Test: `app/src/test/java/com/daryan/tessera/container/DistroSelectionTest.kt`

**Interfaces:**
- Consumes: `LauncherContainer`, `Distro`, `HostProbe.pathsFor`, `LauncherViewModel.install`, `SessionManager.stopAll/sessions`.
- Produces: `object DistroSelection { const val KEY = "active_distro"; fun fromName(name: String?): Distro; fun needsConfirmation(runningSessions: Int, from: Distro, to: Distro): Boolean }`; `AppRestart.schedule(context: Context)`; `LauncherContainer.distro: Distro`; `LauncherViewModel.distro: StateFlow<Distro>`, `fun requestDistroSwitch(distro: Distro)`, `val restartRequested: StateFlow<Boolean>`.

- [ ] **Step 1: Write failing tests**

```kotlin
class DistroSelectionTest {
    @Test fun defaultIsDebian() = assertEquals(Distro.DEBIAN, DistroSelection.fromName(null))

    @Test fun unknownNameFallsBackToDebian() = assertEquals(Distro.DEBIAN, DistroSelection.fromName("nope"))

    @Test fun switchRequiresConfirmationWhenSessionsLive() {
        assertTrue(DistroSelection.needsConfirmation(1, Distro.DEBIAN, Distro.ARCH))
    }

    @Test fun reselectingCurrentDistroDoesNotNeedConfirmation() {
        assertFalse(DistroSelection.needsConfirmation(3, Distro.DEBIAN, Distro.DEBIAN))
    }
}
```

- [ ] **Step 2: Run tests; expect compile failure on missing symbols**

Run: `./gradlew testDebugUnitTest --tests 'com.daryan.tessera.container.DistroSelectionTest'`

- [ ] **Step 3: Wire active distro into the container scope**

`LauncherContainer` reads `DistroSelection.fromName(app.getSharedPreferences("launcher", ...).getString(KEY, null))`, stores `val distro`, and passes it to `HostProbe.pathsFor(app, distro.name.lowercase())`, `RootfsInstaller`, `InstallQueue`, and `StoreRegistry.refreshInstalled` call sites via the VM. `LauncherViewModel.distro` is a `StateFlow` seeded from `container.distro`; `requestDistroSwitch` checks `needsConfirmation`, stops sessions when approved, persists the pref, and flips `restartRequested`. `install(distro)` keeps an explicit parameter for Setup but rejects installing a distro that is not active with a clear user message.

- [ ] **Step 4: Add Setup selector and restart behaviour**

In `SetupScreen`, replace the hardcoded `Distro.DEBIAN` with the selected distro, show a two-item segmented control (Debian 13 / Arch Linux ARM), display `distro.downloadMb` plus "about 2.5 GB free space needed" for Arch, and route selection through `requestDistroSwitch`. Show a confirmation dialog when `needsConfirmation` is true, naming the sessions that will stop. In `LauncherApp`, collect `restartRequested` and call `AppRestart.schedule(context)` — an `AlarmManager` `PendingIntent.getActivity` to `com.daryan.tessera.MainActivity` (with immutable/UPDATE_CURRENT flags where available) plus `Runtime.getRuntime().exit(0)` — so the restarted process rebuilds `LauncherContainer` with the new paths.

- [ ] **Step 5: Run the full suite; expected PASS, then commit**

```bash
git add app/src/main/java/com/daryan/tessera/LauncherApplication.kt app/src/main/java/com/daryan/tessera/LauncherViewModel.kt app/src/main/java/com/daryan/tessera/container/DistroSelection.kt app/src/main/java/com/daryan/tessera/container/AppRestart.kt app/src/main/java/com/daryan/tessera/ui/ app/src/test/java/com/daryan/tessera/container/DistroSelectionTest.kt
git commit -m "feat: select active distro from Setup"
```

---

### Task 8: Store wiring, AUR installs and exec overrides

**Files:**
- Modify: `app/src/main/java/com/daryan/tessera/LauncherViewModel.kt:296-300,1048-1140`
- Modify: `app/src/main/java/com/daryan/tessera/store/StoreRegistry.kt:90-175`
- Modify: `app/src/main/java/com/daryan/tessera/store/ExecOverrides.kt` or `app/src/main/assets/apps/firefox.json` (exec path)
- Test: `app/src/test/java/com/daryan/tessera/store/ArchStoreWiringTest.kt`

**Interfaces:**
- Consumes: `StoreApp`, `StoreSource`, `StoreCatalog.loadArch`, `ArchCatalog`, `InstallQueue.enqueue(aur=...)`, `ArchPackages.packagesFor`, `StoreRegistry.refreshInstalled(store, distro)`.
- Produces: `fun storeJobFor(app: StoreApp, distro: Distro): StoreJob` in a new `store/StoreInstalls.kt`, where `StoreJob(packages: List<String>, helpers: List<String>, aur: Boolean, optional: Set<String>)`.

- [ ] **Step 1: Write failing tests**

```kotlin
class ArchStoreWiringTest {
    @Test fun aurAppQueuesAurJob() {
        val app = StoreApp(id = "aur:foo", pkg = "foo", name = "Foo", summary = "desc",
            description = "", categories = emptyList(), component = "aur", source = StoreSource.AUR)
        val job = StoreInstalls.storeJobFor(app, Distro.ARCH)
        assertTrue(job.aur)
        assertEquals(listOf("foo"), job.packages)
    }

    @Test fun archRepoAppNeverQueuesDebianHelpers() {
        val app = StoreApp(id = "arch:extra:firefox", pkg = "firefox", name = "Firefox",
            summary = "web", description = "", categories = emptyList(), component = "extra",
            source = StoreSource.ARCH)
        val job = StoreInstalls.storeJobFor(app, Distro.ARCH)
        assertFalse(job.aur)
        assertTrue("openbox" in job.helpers)
        assertFalse("matchbox-window-manager" in job.helpers)
        assertFalse("libgl1" in job.helpers)
    }
}
```

- [ ] **Step 2: Run tests; expect compile failure on `StoreInstalls`**

Run: `./gradlew testDebugUnitTest --tests 'com.daryan.tessera.store.ArchStoreWiringTest'`

- [ ] **Step 3: Implement install routing**

`StoreInstalls.storeJobFor` selects helpers from `ArchPackages.packagesFor(distro)` and sets `aur = app.source == AUR`; Debian output must match today's `app.pkg + GUI_HELPER_PACKAGES + optional helpers + fallback` behaviour. In `LauncherViewModel.openStore` route `distro == ARCH` to `container.catalog.loadArch(refresh)` and keep Flathub loading; `storeInstall`/`storeUninstall` use `storeJobFor` and never call `debianFallback()` when Arch is active. AUR installs pass the package base from `app.aurPath`'s snapshot filename and show "Building from AUR — output in Installs" in the job title. `StoreRegistry` gives Arch/AUR apps generic icons when `catalog.iconFile` has no file, and `manifestFor` uses the existing exec override map with `firefox -> /usr/bin/firefox` recorded for Arch.

- [ ] **Step 4: Run the full suite; expected PASS, then commit**

```bash
git add app/src/main/java/com/daryan/tessera/store/StoreInstalls.kt app/src/main/java/com/daryan/tessera/LauncherViewModel.kt app/src/main/java/com/daryan/tessera/store/StoreRegistry.kt app/src/test/java/com/daryan/tessera/store/
git commit -m "feat: wire Arch repos and AUR into the Store"
```

---

### Task 9: Final verification and docs

**Files:**
- Modify: `README.md`, `ROADMAP.md` (Arch support section)
- No source changes expected.

**Interfaces:**
- Consumes: all of the above.
- Produces: documentation describing Arch install size/free-space requirement, AUR caveats (arm64-only support, 4000 requests/day), and that Droidspaces remains Debian-only.

- [ ] **Step 1: Run the full verification set**

Run: `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` (requires Android SDK 37; if unavailable in this container, report the limitation explicitly). Expected: tests PASS, debug APK builds.

- [ ] **Step 2: Review the diff for global constraints**

Confirm by grep: no `apt-get` in `PacmanScript`; no `dpkg` branch reachable with `Distro.ARCH`; no hardcoded `/data/local/linux/debian` outside Debian-only strings; every new network call has a User-Agent.

- [ ] **Step 3: Update docs and commit**

```bash
git add README.md ROADMAP.md
git commit -m "docs: document Arch Linux support and AUR caveats"
```
