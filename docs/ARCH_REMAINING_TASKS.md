# Arch Linux as a first-class container — remaining tasks

Status: 2026-11-26. Branch `arch-peer-distro` at `095fcc3`, pushed to `origin/arch-peer-distro`, worktree clean.

## Where we are

**Done**
- Arch Linux ARM is a first-class peer of Debian: official ALARM aarch64 rootfs, pacman, AUR, Store catalog, GUI helpers, theming, GPU/window handling, Files/task manager, Flathub, per-distro roots, and Setup switching with in-process container rebuild. All 9 original tasks landed and were reviewed; final code commit `51393c1` (plus the distro-restart fix in that commit).
- Design and detailed 6-task plan for adding an explicit **Arch Linux (x86_64)** option beside **Arch Linux ARM** were added in `d613c29`:
  - Design: `docs/superpowers/specs/2026-11-25-arch-x86-and-arm-selectable-design.md`
  - Plan: `docs/superpowers/plans/2026-11-25-arch-x86-and-arm-selectable.md`
- Task 1's first tests were added in `095fcc3` (`DistroSelectionTest`: ABI gating, chroot rejection, `isArch`). They are currently **RED / non-compiling** because the production APIs they reference do not exist yet.

**Goal**
- Let Arch be used like Debian on every supported ABI: explicit x86_64 and arm64 Setup choices, correct rootfs per ABI, pacman/AUR in both Terminal and Store, GUI/theming/GPU/window parity, Flathub, Files/task manager, and no apt/dpkg/fallback while Arch is active. Debian stays the default and its behavior is unchanged.

## Binding constraints

- `minSdk 24`, `targetSdk 28`, `compileSdk 37`, Java 17; no new Gradle dependencies; no Robolectric.
- Paths come only from `HostProbe.pathsFor(context, distro)`. The new enum value adds `files/rootfs/arch_x86_64` and `/data/local/linux/arch_x86_64`; never hardcode `debian` in shared components.
- All network reads set a descriptive `User-Agent` and run off the main thread.
- x86_64 rootfs: official `library/archlinux`, pinned index digest `sha256:375a401bb3c6376b37f40b6dfa25067db37ec78d0b7af67ae6315f43a0184a9d`, two `tar+gzip` layers, SHA-256 content addressing, no whiteout handling.
- ARM rootfs stays `https://ca.us.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz` with `.md5` verification (~830 MB).
- AUR RPC v5 base `https://aur.archlinux.org/rpc/v5`, ≥2-character search, 4000 requests/day/IP, bulk metadata preferred; `makepkg` never runs as root.
- x86_64 Arch is PRoot-only: chroot mode must be refused with a reason. ARM chroot behavior is unchanged.
- First-boot x86_64 pacman sync uses `pacman -Syu --noconfirm` only when `/var/lib/pacman/sync/core.db` is missing.

## Remaining tasks

The authoritative step-by-step requirements are in `docs/superpowers/plans/2026-11-25-arch-x86-and-arm-selectable.md`. This table is the status view.

| # | Task | Acceptance / review focus | Status |
|---|------|---------------------------|--------|
| 1 | Distro identity and ABI gating | `Distro.ARCH_X86_64`, `supportedOn`, `chrootSupported`, `isArch`; wrong-ABI ARM install refused before any 830 MB download; all `when (distro)` sites compile | RED tests committed, implementation not started |
| 2 | Multi-layer OCI rootfs resolution and install | Both `library/archlinux` layers resolve and verify in order; stale/renamed cache rejected by digest; ARM path unchanged | Pending |
| 3 | x86_64 first boot and pacman sync | Missing `core.db` triggers `pacman -Syu --noconfirm`; image mirrorlist preserved; chroot refuses x86_64 | Pending |
| 4 | Distribution-dependent behavior and Store catalogs | x86_64 uses official `core`/`extra` repo DBs from `geo.mirror.pkgbuild.com`; AUR shared; no ARM packages shown on x86_64; all Arch branches use `isArch` | Pending |
| 5 | Setup UI and install guards | Two explicit Arch options with ABI-aware disabling and a clear reason; safe install/cancel flow; no wasted download | Pending |
| 6 | Verification and documentation | Full unit suite green; `assembleDebug` green; x86_64 AVD smoke test installs Arch and runs `pacman` in Terminal; README/ROADMAP updated | Pending |

## Immediate next action

1. Confirm RED: `./gradlew testDebugUnitTest --tests 'com.daryan.tessera.container.DistroSelectionTest' --console=plain` (expected: compile failure on `ARCH_X86_64`, `supportedOn`, `chrootSupported`, `isArch`).
2. Implement Task 1: add the new `Distro` value and helpers in `OciRegistry.kt` / `DistroSelection.kt`, and do a compile-only pass over every `when (distro)` / `== Distro.ARCH` site.
3. Re-run the focused test GREEN, then the full suite, commit, and continue through Tasks 2–6 in order with a review gate after each task.

## Environment notes for verification

- Windowed x86_64 AVD `tessera-play37` is available on display `:1` (`XAUTHORITY=/home/dmitriy/.Xauthority`) with 3 GB RAM; this is the target for the x86_64 Arch real-content smoke test.
- API 37 ATD emulator has a known gfxstream/SurfaceFlinger black-screen bug; API 35 ATD AVD `tessera-35` was prepared as a fallback for visual checks.
- Current APK (code equivalent to `51393c1`): `/mnt/Other/Another/tessera-linux/.worktrees/arch-peer-distro/app/build/outputs/apk/debug/app-debug.apk`.
- Genuine arm64 Arch rootfs still requires Waydroid or an arm64 host; it cannot run on this x86_64 machine.
- ADB environment: `HOME=/tmp/opencode/home ANDROID_HOME=/tmp/opencode/android-sdk ANDROID_AVD_HOME=/tmp/opencode/avds JAVA_TOOL_OPTIONS=-Duser.home=/tmp/opencode/home`.
