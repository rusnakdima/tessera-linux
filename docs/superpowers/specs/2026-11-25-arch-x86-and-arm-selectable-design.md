# Arch Linux x86_64 and ARM as selectable distributions

> Status: design for approval. Approach B (two explicit Setup options) was chosen by the user.

## Intent and why

Tessera already installs Arch Linux ARM (aarch64) from the official ALARM rootfs, with pacman, AUR, and Store integration. The user also wants genuine Arch on x86_64 devices/emulators so it can be tested here on the x86_64 AVD, while keeping the aarch64 build for Waydroid/arm64 devices. Two explicit Setup choices make the ABI contract visible and avoid silently guessing from the device.

## Goals

- Add `Arch Linux (x86_64)` as a second, user-selectable distribution alongside `Arch Linux ARM`.
- x86_64 rootfs comes from canonical `library/archlinux` (official Arch Docker image), pinned by index digest, SHA-256 content-addressed.
- aarch64 keeps the existing ALARM tarball path and MD5 verification.
- Both variants get the same pacman/AUR/Store/GUI capabilities already built for `Distro.ARCH`.
- Setup disables the option that cannot run on the device ABI, with a clear reason, so no 830 MB / 135 MB download is wasted.
- Real-device verification: install Arch x86_64 on the x86_64 AVD and run `pacman` in Terminal; arm64 path covered by unit tests and the user's Waydroid/arm64 device.

## Non-goals

- No CPU emulation/translation: aarch64 rootfs will not be offered on x86_64 and vice versa.
- No zstd/bootstrap-tarball path; no whiteout handling for generic OCI images (the pinned image has none).
- No Droidspaces changes; no new third-party/unofficial images.
- No change to Debian's apt/dep11/dpkg behavior.

## Constraints

- minSdk 24, targetSdk 28, compileSdk 37, Java 17; no new Gradle dependencies; no Robolectric.
- Paths remain `HostProbe.pathsFor(context, distro.name.lowercase())`; the new enum name adds `files/rootfs/arch_x86_64` and `/data/local/linux/arch_x86_64`.
- All network reads set a descriptive User-Agent and run off main thread.
- Arch active means no apt/dpkg/fallback; `makepkg` never runs as root.
- Existing PRoot binaries are built by `scripts/proot/build.sh` (gitignored jniLibs) for x86_64 and arm64-v8a; no native rebuild is required for this change.

## Approaches

- **A (rejected by user): one ABI-aware Arch option.** Smallest code change but hides which rootfs will be used.
- **B (chosen):** two explicit enum values and Setup segments. Costs exhaustive-`when` updates across ~15 files, but makes the ABI contract explicit and keeps the existing arm64 behavior untouched.
- **C (rejected): official zstd bootstrap tarball.** Strong SHA-256/PGP integrity but needs new zstd decompression, `root.x86_64/` prefix stripping, and numeric-owner handling — a heavier change than reusing the OCI path.

## Design

### 1. Distribution identity

In `container/OciRegistry.kt`, add:

```kotlin
ARCH_X86_64(
    "Arch Linux (x86_64)",
    "library/archlinux",
    "sha256:375a401bb3c6376b37f40b6dfa25067db37ec78d0b7af67ae6315f43a0184a9d",
    135
)
```

`ARCH` keeps label `Arch Linux ARM`, repo `archlinuxarm`, tag `aarch64`, 830 MB. A digest-valued `Distro.tag` is already accepted by `resolve()`: the index is returned, `selectManifest()` filters out the `unknown/unknown` attestation and picks `linux/amd64`, and the manifest/blob paths work with the token scope from `distro.repo`.

Add `Distro.isArch: Boolean` and `Distro.supportedOn(abi: String): Boolean` (DEBIAN always true; ARCH only `arm64-v8a`; ARCH_X86_64 only `x86_64`). Use `isArch` to replace the scattered `== Distro.ARCH` checks instead of adding more enum-specific branches.

### 2. Rootfs sources and multi-layer OCI pull

`OciRegistry`:
- Keep `resolve(distro, platform)` but return `List<Layer>` instead of a single `Layer` (or add `resolveAll`). `selectManifest()` stays unchanged.
- Replace `singleLayer` with `layers(manifest)` that accepts 1..N `tar+gzip` layers, in manifest order, and returns them all. Reject zero layers or unsupported media types.
- Add `download(repo: String, layer: Layer, dest: File, onProgress)` overload; the existing `download(distro, ...)` delegates with `distro.repo`.

`RootfsArtifact`: add `ociRepo: String? = null`. For OCI layers, `checksum` is the layer digest, `algorithm = "SHA-256"`, `url` is informational, and `ociRepo` selects `OciRegistry.download` in `RootfsSources.download`. Add `RootfsSources.resolveAll(distro, abi): List<RootfsArtifact>`:
- DEBIAN and ARCH (ARM): one artifact, current behavior.
- ARCH_X86_64: one artifact per OCI layer, with URLs/token scope from `library/archlinux`.

### 3. Installation and first boot

`RootfsInstaller`:
- `fetch()` resolves a list of artifacts, caches one file per layer (`rootfs-<combinedKey>-<index>.tar.gz`), verifies each, and returns the ordered list.
- Free-space warning text comes from the active distro artifact, not hardcoded Arch copy.
- `install()` extracts every tarball in order under PRoot via `runProot` and `GZIPInputStream`; the second layer (ldconfig/os-release) simply overwrites files, no whiteouts needed.
- `ChrootSetup.script()` emits one `tar -xzf` per layer and uses ABI-specific first-boot text.

`ArchSetup.firstBootScript(distro: Distro)`:
- ARCH: unchanged ALARM mirror + `pacman-key --populate archlinuxarm`.
- ARCH_X86_64: do not overwrite the image's shipped mirrorlist; run `pacman-key --init && pacman-key --populate archlinux`.

### 4. Distribution-dependent behavior

Replace `distro == Distro.ARCH` with `distro.isArch` in:
- `container/ArchPackages.kt` (map ARCH_X86_64 to the same package sets as ARCH).
- `store/PackageScript.kt`, `store/StoreRegistry.kt`, `store/StoreInstalls.kt`, `store/InstallQueue.kt`, `store/StoreCatalog.kt` (`loadArch` for both), `container/AutoInstalls.kt`, `container/ExecOverrides.kt`, `LauncherViewModel.kt`.
- Keep DEBIAN paths byte-identical.

### 5. Setup UI

- `SetupScreen` renders three segments automatically from `Distro.entries`.
- Description text branches on the three distros: Debian; Arch Linux ARM (~830 MB / ~2.5 GB); Arch Linux x86_64 (~135 MB download / ~411 MB installed).
- Each incompatible segment is disabled with a short reason ("requires arm64" / "requires x86_64") using `Distro.supportedOn(Build.SUPPORTED_ABIS.first())`. `LauncherViewModel.requestDistroSwitch` and `install` enforce the same guard.

### 6. Tests

- `InstallerTest`: multi-layer resolve returns ordered layers; wrong layer checksum rejected; x86_64 artifact uses `library/archlinux` repo; free-space requirement uses download + installed + margin; Debian single-layer still works.
- `ArchSetupTest`: x86_64 first boot uses `archlinux` keyring, does not write the ALARM mirror, and runs `pacman -Syu` only when `core.db` is missing; ARM first boot unchanged.
- `ArchCatalogTest`: x86_64 repo catalog uses `geo.mirror.pkgbuild.com` `core`/`extra`; ARM catalog unchanged.
- `ArchPackagesTest` / `PackageScriptTest` / `StoreRegistryTest`: ARCH_X86_64 maps to Arch packages, PacmanScript, and pacman DB installed detection.
- `DistroSelectionTest`: supportedOn ABI matrix; switch guard.
- Device smoke test: x86_64 AVD selects Arch Linux x86_64, Install downloads/extracts via PRoot, Terminal opens with pacman available.

## Decisions settled by the controller (builder check)

These were raised as open questions by the builder check. They are settled here as implementation calls; the human partner can still correct them while reviewing this document.

1. **pacman sync database:** the pinned image ships an empty `/var/lib/pacman/sync`, so `pacman -S` would fail. `ArchSetup.firstBootScript()` and the matching chroot text will, for `ARCH_X86_64` only, run `pacman -Syu --noconfirm` when `core.db` is missing. `-Syu` is used instead of `-Sy` to avoid a partial-upgrade hazard. ARM first boot and Debian behavior stay unchanged.
2. **Store catalog:** `store/ArchCatalog.kt` becomes distro-aware. `ARCH` keeps the ALARM `aarch64` repos (`core extra community alarm`); `ARCH_X86_64` uses the official x86_64 mirror `https://geo.mirror.pkgbuild.com` with `core`/`extra` DB tarballs. AUR metadata stays shared. This is required for the promised Store parity.
3. **Chroot mode:** `ARCH_X86_64` is PRoot-only in this change. Setup disables the chroot mode selector with “chroot is arm64-only for Arch” and `LauncherViewModel.install()` rejects that combination. The x86_64 chroot text is still generated for completeness but is not an offered/installable path.
4. **Free-space check:** `Distro` gains `installedMb` and `requiredFreeMb`. Debian keeps its existing 150 MB floor; ARM keeps the existing 3× download value (~2490 MB); x86_64 uses `135 + 411 + 15% margin`, rounded to 650 MB. Setup/`diagnostics` show both download and installed sizes for the Arch options. This prevents the x86_64 install from passing a check based only on the 135 MB cache.

## Files touched

- `container/OciRegistry.kt`, `container/RootfsArtifact.kt`, `container/RootfsSources.kt`, `container/RootfsInstaller.kt`, `container/ArchSetup.kt`, `container/HostProbe.kt`, `container/DistroSelection.kt`, `container/ArchPackages.kt`, `container/AutoInstalls.kt`, `container/ExecOverrides.kt`
- `store/PackageScript.kt`, `store/InstallQueue.kt`, `store/StoreCatalog.kt`, `store/ArchCatalog.kt`, `store/StoreRegistry.kt`, `store/StoreInstalls.kt`
- `LauncherViewModel.kt`, `ui/SetupScreen.kt`, `ui/LauncherApp.kt`
- Tests in `app/src/test/java/com/daryan/tessera/**`
- `README.md`, `ROADMAP.md`

## Builder latitude

Exact enum constant name, digest value, cache key format, and the shape of `resolveAll` are fixed above. Everything else (function bodies, ordering of guards, UI wording details) is the implementer's call as long as behavior matches this design.
