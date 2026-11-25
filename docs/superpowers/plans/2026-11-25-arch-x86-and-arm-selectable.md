# Arch Linux x86_64 and ARM Selectable Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `Arch Linux (x86_64)` as a second explicit Setup option beside `Arch Linux ARM`, with official OCI rootfs, pacman sync, distro-aware Store catalog, ABI guards, and verified AVD smoke test.

**Architecture:** A new `Distro` enum value drives all existing distro abstractions; OCI resolution is generalized from one layer to an ordered list; first-boot and Store/catalog paths branch on `Distro.isArch` with x86_64-specific mirror/keyring choices. Setup disables options that cannot run on the host ABI.

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.serialization, Gradle Kotlin DSL, PRoot, Docker Registry v2, AUR RPC v5.

**Spec:** `docs/superpowers/specs/2026-11-25-arch-x86-and-arm-selectable-design.md`

## Global Constraints

- minSdk 24, targetSdk 28, compileSdk 37, Java 17; no new Gradle dependencies; no Robolectric.
- Paths from `HostProbe.pathsFor(context, distro)` only; the new enum name adds `files/rootfs/arch_x86_64` and `/data/local/linux/arch_x86_64`. Never hardcode `debian` in shared components.
- Debian default and its apt/dep11/dpkg behavior stay unchanged; Arch active means no apt/dpkg/dpkg fallback.
- All network reads set a descriptive User-Agent and run off the main thread.
- Official x86_64 rootfs: `library/archlinux` pinned to index digest `sha256:375a401bb3c6376b37f40b6dfa25067db37ec78d0b7af67ae6315f43a0184a9d`; two `tar+gzip` layers; no whiteout handling needed.
- ARM rootfs stays `https://ca.us.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz` with `.md5` verification; ~830 MB download.
- AUR RPC v5 base `https://aur.archlinux.org/rpc/v5`, ≥2-character search, 4000 requests/day/IP, bulk metadata preferred; `makepkg` never runs as root.
- x86_64 Arch is PRoot-only: chroot mode must be refused with a reason; ARM chroot behavior unchanged.
- First-boot x86_64 pacman sync uses `pacman -Syu --noconfirm` only when `/var/lib/pacman/sync/core.db` is missing.

## Review Focus

1. **Wrong-ABI download** — selecting Arch ARM on x86_64 must be refused before any 830 MB download; pinned by `DistroSelectionTest.abiGateRejectsArchArmOnX8664` (Task 1).
2. **Multi-layer OCI pull and cache poisoning** — both layers must download in order and a stale/renamed cache must be rejected by digest; pinned by `InstallerTest.multiLayerResolveAndVerifyRejectsWrongCache` (Task 2).
3. **Fresh x86_64 pacman cannot install** — first boot must sync the empty DB with `-Syu` and keep the image's mirrorlist; pinned by `ArchSetupTest.x8664FirstBootSyncsMissingPacmanDb` (Task 3).
4. **Store shows ARM packages on x86_64** — repo catalog must use official `core`/`extra` from `geo.mirror.pkgbuild.com` for x86_64; pinned by `ArchCatalogTest.x8664UsesOfficialRepos` (Task 4).
5. **x86_64 Prune chroot path** — chroot+ARCH_X86_64 must be rejected rather than shipping an untested root path; pinned by `DistroSelectionTest.chrootRejectsArchX8664` (Task 5).

---

### Task 1: Distro identity and ABI gating

**Files:**
- Modify: `app/src/main/java/com/daryan/tessera/container/OciRegistry.kt:21-25`
- Modify: `app/src/main/java/com/daryan/tessera/container/DistroSelection.kt`
- Modify: all `when (distro)` / `== Distro.ARCH` sites listed in Task 4 (compile-only pass here)
- Test: `app/src/test/java/com/daryan/tessera/container/DistroSelectionTest.kt`

**Interfaces:**
- Consumes: existing `Distro`, `DistroSelection`.
- Produces:
  - `Distro.ARCH_X86_64("Arch Linux (x86_64)", "library/archlinux", "sha256:375a401bb3c6376b37f40b6dfa25067db37ec78d0b7af67ae6315f43a0184a9d", 135, 411, 650)`
  - existing entries gain `installedMb` and `requiredFreeMb`: `DEBIAN(..., 50, 200, 150)`, `ARCH(..., 830, 2500, 2490)`.
  - `val Distro.isArch: Boolean`
  - `fun Distro.supportedOn(abi: String): Boolean` — DEBIAN always true, ARCH only `arm64-v8a`, ARCH_X86_64 only `x86_64`.
  - `fun Distro.chrootSupported(): Boolean` — true for DEBIAN and ARCH, false for ARCH_X86_64.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun abiGateRejectsArchArmOnX8664() {
    assertFalse(Distro.ARCH.supportedOn("x86_64"))
    assertTrue(Distro.ARCH_X86_64.supportedOn("x86_64"))
    assertTrue(Distro.ARCH.supportedOn("arm64-v8a"))
    assertFalse(Distro.ARCH_X86_64.supportedOn("arm64-v8a"))
    assertTrue(Distro.DEBIAN.supportedOn("x86_64"))
}

@Test
fun chrootRejectsArchX8664() {
    assertFalse(Distro.ARCH_X86_64.chrootSupported())
    assertTrue(Distro.ARCH.chrootSupported())
    assertTrue(Distro.DEBIAN.chrootSupported())
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests 'com.daryan.tessera.container.DistroSelectionTest' --console=plain`
Expected: FAIL — `ARCH_X86_64` / `supportedOn` unresolved.

- [ ] **Step 3: Add the enum value, fields, and helpers**

Add the constructor fields to `Distro`; add `isArch`, `supportedOn`, `chrootSupported` as top-level extensions in `OciRegistry.kt`. Make the code compile by adding `Distro.ARCH_X86_64 ->` branches beside every `Distro.ARCH ->` in the files listed in Task 4 (behavioral bodies are completed in Task 4; here they may temporarily delegate to the same expression as ARCH).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests 'com.daryan.tessera.container.DistroSelectionTest' --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/daryan/tessera/container/OciRegistry.kt app/src/main/java/com/daryan/tessera/container/DistroSelection.kt app/src/main/java/com/daryan/tessera/container/ArchPackages.kt app/src/main/java/com/daryan/tessera/container/AutoInstalls.kt app/src/main/java/com/daryan/tessera/container/ExecOverrides.kt app/src/main/java/com/daryan/tessera/store/PackageScript.kt app/src/main/java/com/daryan/tessera/store/StoreRegistry.kt app/src/main/java/com/daryan/tessera/store/StoreInstalls.kt app/src/main/java/com/daryan/tessera/store/ArchCatalog.kt app/src/main/java/com/daryan/tessera/store/StoreCatalog.kt app/src/main/java/com/daryan/tessera/LauncherViewModel.kt app/src/test/java/com/daryan/tessera/container/DistroSelectionTest.kt
git commit -m "feat: add selectable Arch x86_64 distro and ABI gates"
```

---

### Task 2: Multi-layer OCI rootfs resolution and install

**Files:**
- Modify: `app/src/main/java/com/daryan/tessera/container/OciRegistry.kt:40-162`
- Modify: `app/src/main/java/com/daryan/tessera/container/RootfsArtifact.kt`
- Modify: `app/src/main/java/com/daryan/tessera/container/RootfsSources.kt`
- Modify: `app/src/main/java/com/daryan/tessera/container/RootfsInstaller.kt:66-135,229-290`
- Test: `app/src/test/java/com/daryan/tessera/container/InstallerTest.kt`

**Interfaces:**
- Consumes: `Distro.ARCH_X86_64`, `Distro.requiredFreeMb`, `Platform.forAbi`, `Layer`.
- Produces:
  - `OciRegistry.resolveAll(distro: Distro, platform: Platform): List<Layer>`
  - `OciRegistry.layers(manifest: JsonObject): List<Layer>` (internal; replaces `singleLayer`, which may remain as a delegating alias for tests)
  - `OciRegistry.download(repo: String, layer: Layer, dest: File, onProgress: (Long) -> Unit)`; `download(distro, ...)` delegates to it with `distro.repo`.
  - `RootfsArtifact.ociRepo: String? = null`
  - `RootfsSources.resolveAll(distro: Distro, abi: Platform): List<RootfsArtifact>`
  - `RootfsSources.fromLayer(layer: Layer, repo: String): RootfsArtifact` — URL uses `repo`, not hardcoded `library/debian`.
  - `RootfsSources.artifactsFromLayers(layers: List<Layer>, repo: String): List<RootfsArtifact}` — pure helper used by tests.
  - `RootfsInstaller.fetch(distro): Pair<List<RootfsArtifact>, List<File>>`
  - `ChrootSetup.script(root: String, tarballs: List<String>, marker: String, distro: Distro = Distro.DEBIAN): String`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun multiLayerResolveAndVerifyRejectsWrongCache() = runBlocking {
    val layers = listOf(
        Layer("sha256:aaaa", 134_446_289),
        Layer("sha256:bbbb", 8_715),
    )
    val artifacts = RootfsSources.artifactsFromLayers(layers, "library/archlinux")
    assertEquals(2, artifacts.size)
    assertEquals("library/archlinux", artifacts[0].ociRepo)
    assertEquals("sha256:aaaa".substringAfter(':'), artifacts[0].checksum)
    assertFalse(artifacts[0].verify(File("stale-aaaa.tar.gz"))) // wrong bytes or missing file
}
```

Use a `TemporaryFolder` fixture with a deliberately wrong-sized file; no network in the test.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests 'com.daryan.tessera.container.InstallerTest' --console=plain`
Expected: FAIL — `resolveAll` / `artifactsFromLayers` / `ociRepo` unresolved.

- [ ] **Step 3: Generalize resolution, artifacts, and extraction**

`layers()` accepts 1..N `tar+gzip` layers in manifest order and rejects unsupported media types. `resolveAll` returns one artifact per layer for OCI distros and one for the ALARM tarball. `download` uses `ociRepo` when non-null. `fetch` caches one file per layer (`rootfs-<combinedKey>-<index>.tar.gz`), checks `StatFs.availableBytes < distro.requiredFreeMb * 1024L * 1024L` with a distro-specific message, and returns ordered files. `install()` loops the tarballs through `runProot`; `ChrootSetup.script` emits one `tar -xzf` per tarball. Keep whiteout handling out of scope and document it in a comment.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests 'com.daryan.tessera.container.InstallerTest' --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/daryan/tessera/container/OciRegistry.kt app/src/main/java/com/daryan/tessera/container/RootfsArtifact.kt app/src/main/java/com/daryan/tessera/container/RootfsSources.kt app/src/main/java/com/daryan/tessera/container/RootfsInstaller.kt app/src/test/java/com/daryan/tessera/container/InstallerTest.kt
git commit -m "feat: pull multi-layer OCI rootfs with per-layer verification"
```

---

### Task 3: x86_64 first boot and pacman sync

**Files:**
- Modify: `app/src/main/java/com/daryan/tessera/container/ArchSetup.kt`
- Modify: `app/src/main/java/com/daryan/tessera/container/RootfsInstaller.kt:229-290` (chroot first-boot text)
- Modify: `app/src/main/java/com/daryan/tessera/container/RootfsInstaller.kt:110-135` (call with distro)
- Test: `app/src/test/java/com/daryan/tessera/container/ArchSetupTest.kt`

**Interfaces:**
- Consumes: `Distro`, `Distro.isArch`, `RootfsSetup.configureCommon`, `runProot`.
- Produces: `ArchSetup.firstBootScript(distro: Distro): String`; `ArchSetup.configure(paths, root, uid, gid, distro: Distro = Distro.ARCH)`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun x8664FirstBootSyncsMissingPacmanDb() {
    val script = ArchSetup.firstBootScript(Distro.ARCH_X86_64)
    assertTrue(script.contains("pacman-key --populate archlinux\n"))
    assertFalse(script.contains("archlinuxarm"))
    assertTrue(script.contains("[ -f /var/lib/pacman/sync/core.db ] ||"))
    assertTrue(script.contains("pacman -Syu --noconfirm"))
}

@Test
fun armFirstBootIsUnchanged() {
    val script = ArchSetup.firstBootScript(Distro.ARCH)
    assertTrue(script.contains("pacman-key --populate archlinuxarm"))
    assertTrue(script.contains("ca.us.mirror.archlinuxarm.org"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests 'com.daryan.tessera.container.ArchSetupTest' --console=plain`
Expected: FAIL — `firstBootScript(distro)` unresolved.

- [ ] **Step 3: Branch first boot by distro**

For `ARCH_X86_64`: do not overwrite the shipped `/etc/pacman.d/mirrorlist`; run `pacman-key --init`, `pacman-key --populate archlinux`, and `[ -f /var/lib/pacman/sync/core.db ] || /usr/bin/pacman -Syu --noconfirm`. For `ARCH`: keep current ALARM text byte-for-byte. Update `ChrootSetup.script` to generate the matching x86_64 branch (even though Setup will refuse it), and pass `distro` from `install()`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests 'com.daryan.tessera.container.ArchSetupTest' --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/daryan/tessera/container/ArchSetup.kt app/src/main/java/com/daryan/tessera/container/RootfsInstaller.kt app/src/test/java/com/daryan/tessera/container/ArchSetupTest.kt
git commit -m "feat: sync pacman database on Arch x86_64 first boot"
```

---

### Task 4: Distribution-dependent behavior and Store catalogs

**Files:**
- Modify: `app/src/main/java/com/daryan/tessera/container/ArchPackages.kt`, `AutoInstalls.kt`, `ExecOverrides.kt`
- Modify: `app/src/main/java/com/daryan/tessera/store/PackageScript.kt`, `StoreRegistry.kt`, `StoreInstalls.kt`, `ArchCatalog.kt`, `StoreCatalog.kt`
- Modify: `app/src/main/java/com/daryan/tessera/LauncherViewModel.kt:512,1096,1118`
- Test: `app/src/test/java/com/daryan/tessera/store/ArchCatalogTest.kt`, `app/src/test/java/com/daryan/tessera/store/ArchStoreWiringTest.kt`

**Interfaces:**
- Consumes: `Distro.isArch`, `RootfsArtifact`, `StoreApp`, `StoreSource`.
- Produces:
  - `ArchCatalog.load(dir: File, distro: Distro): List<StoreApp>`
  - `ArchCatalog.repoScript(distro: Distro): String` — pure helper returning the mirror/repo URLs used by tests.
  - `StoreCatalog.loadArch(distro: Distro, refresh: Boolean = false)` using cache key `arch-$distro` and file `catalog-arch-$distro.json`.
  - `ArchPackages.packagesFor(distro)` maps `ARCH_X86_64` to the same `arch` `PackageSets` as `ARCH`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun x8664UsesOfficialRepos() {
    val script = ArchCatalog.repoScript(Distro.ARCH_X86_64)
    assertTrue(script.contains("https://geo.mirror.pkgbuild.com"))
    assertTrue(script.contains("/core/core.db.tar.gz"))
    assertTrue(script.contains("/extra/extra.db.tar.gz"))
    assertFalse(script.contains("archlinuxarm"))
}

@Test
fun archX8664GetsPacmanScriptAndOverlays() = runBlocking {
    assertEquals(PacmanScript, PackageScript.forDistro(Distro.ARCH_X86_64))
    assertTrue(ArchPackages.packagesFor(Distro.ARCH_X86_64).guiHelpers.isNotEmpty())
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests 'com.daryan.tessera.store.ArchCatalogTest' --tests 'com.daryan.tessera.store.ArchStoreWiringTest' --console=plain`
Expected: FAIL — new signatures unresolved.

- [ ] **Step 3: Replace enum checks with `isArch` and add x86_64 catalog source**

Every `distro == Distro.ARCH` / `when (distro)` in `ArchPackages`, `AutoInstalls`, `ExecOverrides`, `PackageScript`, `StoreRegistry`, `StoreInstalls`, and `LauncherViewModel` treats `ARCH_X86_64` like `ARCH`. `ArchCatalog`: `ARCH` keeps ALARM `core extra community alarm`; `ARCH_X86_64` uses `https://geo.mirror.pkgbuild.com` with `core extra` (expose `repoScript(distro)` for tests). `loadArch(distro)` is selected by `LauncherViewModel.openStore()` for both Arch values. Debian paths unchanged.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests 'com.daryan.tessera.store.ArchCatalogTest' --tests 'com.daryan.tessera.store.ArchStoreWiringTest' --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/daryan/tessera/container/ArchPackages.kt app/src/main/java/com/daryan/tessera/container/AutoInstalls.kt app/src/main/java/com/daryan/tessera/container/ExecOverrides.kt app/src/main/java/com/daryan/tessera/store/PackageScript.kt app/src/main/java/com/daryan/tessera/store/StoreRegistry.kt app/src/main/java/com/daryan/tessera/store/StoreInstalls.kt app/src/main/java/com/daryan/tessera/store/ArchCatalog.kt app/src/main/java/com/daryan/tessera/store/StoreCatalog.kt app/src/main/java/com/daryan/tessera/LauncherViewModel.kt app/src/test/java/com/daryan/tessera/store/ArchCatalogTest.kt app/src/test/java/com/daryan/tessera/store/ArchStoreWiringTest.kt
git commit -m "feat: wire Arch x86_64 packages, pacman and official store repos"
```

---

### Task 5: Setup UI and install guards

**Files:**
- Modify: `app/src/main/java/com/daryan/tessera/ui/SetupScreen.kt:151-205`
- Modify: `app/src/main/java/com/daryan/tessera/LauncherViewModel.kt:148-160,531-539`
- Test: `app/src/test/java/com/daryan/tessera/container/DistroSelectionTest.kt` (extend)

**Interfaces:**
- Consumes: `Distro.supportedOn`, `Distro.chrootSupported`, `DistroSelection.needsConfirmation`.
- Produces: `fun Distro.installProblem(mode: BackendMode): String?` in `DistroSelection.kt` (`BackendMode` is `com.daryan.tessera.container.BackendMode`) — returns `"chroot is arm64-only for Arch"` for `ARCH_X86_64` in `BackendMode.CHROOT`, otherwise `null`. `LauncherViewModel.install(distro)` calls it and sends the message before starting the installer.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun x8664InstallRejectsChroot() {
    assertEquals("chroot is arm64-only for Arch", Distro.ARCH_X86_64.installProblem(BackendMode.CHROOT))
    assertNull(Distro.ARCH_X86_64.installProblem(BackendMode.PROOT))
    assertNull(Distro.ARCH.installProblem(BackendMode.CHROOT))
    assertNull(Distro.DEBIAN.installProblem(BackendMode.CHROOT))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests 'com.daryan.tessera.container.DistroSelectionTest' --console=plain`
Expected: FAIL — helper/method unresolved.

- [ ] **Step 3: Add UI and install guards**

`SetupScreen` renders three segments from `Distro.entries`; incompatible segments are disabled (`enabled = distro.supportedOn(Build.SUPPORTED_ABIS.first())`) with a short reason under the row. Description copy: Debian unchanged; ARCH shows 830 MB / ~2.5 GB; ARCH_X86_64 shows 135 MB download / ~411 MB installed / 650 MB free. Chroot mode disables the ARCH_X86_64 segment with “chroot is arm64-only for Arch”. `requestDistroSwitch` also refuses unsupported distros. `install()` refuses chroot+ARCH_X86_64.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests 'com.daryan.tessera.container.DistroSelectionTest' --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/daryan/tessera/ui/SetupScreen.kt app/src/main/java/com/daryan/tessera/LauncherViewModel.kt app/src/main/java/com/daryan/tessera/container/DistroSelection.kt app/src/test/java/com/daryan/tessera/container/DistroSelectionTest.kt
git commit -m "feat: show both Arch distros and guard unsupported installs"
```

---

### Task 6: Verification and documentation

**Files:**
- Modify: `README.md`, `ROADMAP.md`
- No production code changes.

**Interfaces:**
- Consumes: all of the above.

- [ ] **Step 1: Run the full unit suite**

Run: `./gradlew testDebugUnitTest --console=plain`
Expected: BUILD SUCCESSFUL; all tests pass.

- [ ] **Step 2: Build the APK with PRoot binaries**

Run:
```bash
ANDROID_NDK_HOME=/tmp/opencode/android-sdk/ndk/29.0.14206865 ABIS="x86_64 arm64-v8a" JOBS=4 scripts/proot/build.sh
./gradlew assembleDebug --console=plain
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libproot
```
Expected: six `libproot*.so` entries; BUILD SUCCESSFUL.

- [ ] **Step 3: Real-device smoke test on x86_64 AVD**

Use `tessera-play37` (API 37 Google Play x86_64, visible on `DISPLAY=:1`) with env `HOME=/tmp/opencode/home ANDROID_HOME=/home/dmitriy/Android/Sdk ANDROID_AVD_HOME=/tmp/opencode/avds JAVA_TOOL_OPTIONS=-Duser.home=/tmp/opencode/home DISPLAY=:1 XAUTHORITY=/home/dmitriy/.Xauthority`. Install the APK, open Setup, select **Arch Linux (x86_64)**, tap Install, wait for completion, open Terminal, run `pacman -Syu --noconfirm`, and capture a screenshot. Expected: install completes, prompt appears, `pacman -Syu` reaches a real mirror without `database file for 'core' does not exist`.

- [ ] **Step 4: Document the result**

Update README/ROADMAP with both Arch variants, their ABI requirements, rootfs sources, sizes, and the x86_64 PRoot-only limitation. Record the AVD smoke-test result in the SDD ledger.

- [ ] **Step 5: Commit and push**

```bash
git add README.md ROADMAP.md
git commit -m "docs: document Arch x86_64 and ARM selectable installs"
git push origin arch-peer-distro
```
