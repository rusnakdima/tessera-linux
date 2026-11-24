# Arch Linux as a first-class secondary OS — design

> Approved in conversation on 2026-11-24. This is the spec the implementation plan implements.

## Intent and why

Add Arch Linux as a peer to Debian in Tessera: a genuine Arch Linux ARM aarch64
environment (pacman, official repos, AUR), selectable in Setup, with every
existing feature preserved when Arch is active — windows, Material You theming,
GPU drivers, Files/task manager, Flathub, and full Store integration including
AUR search and `makepkg` installs. The user asked for a first-class peer rather
than a roadmap Droidspaces container, and FULL depth rather than terminal-only:
they want the real Arch environment ("I use Arch BTW"), not a Debian
approximation.

## Goals

1. **Install Arch like Debian.** Add `ARCH` to the `Distro` enum with a source
   descriptor: OCI image for Debian (unchanged), direct HTTPS tarball for Arch.
   Source: the official generic aarch64 rootfs from an Arch Linux ARM mirror
   (~830 MB). It publishes `.md5` and `.sig` but no SHA-256; verify the live
   published MD5 over HTTPS now, leaving GPG verification as a later option.
   Cache, progress and cancel carry over; add resume and a free-space precheck
   (~2.5 GB) because download and unpack dwarf Debian's 50 MB image. The
   existing gunzip-into-PRoot extraction path is reused unchanged.
2. **Separate roots, explicit switch.** `files/rootfs/debian` and
   `files/rootfs/arch` for PRoot; `/data/local/linux/debian` and
   `/data/local/linux/arch` for chroot, each with its own installed marker.
   Debian stays the default; a persisted active-distro preference selects the
   root every component uses. Switching is done from Setup, stops live sessions,
   and cold-restarts the app process so no long-lived object keeps stale paths.
3. **Distro-aware rootfs setup.** Split `RootfsSetup.configure`: generic parts
   (resolv.conf/hosts, user/group, Android aid groups) for both; apt
   conf/component/source rewrites only for Debian. Arch gets first-boot
   `pacman-key --init && pacman-key --populate archlinuxarm` and a mirrorlist
   check. `ChrootSetup.script` is generalized the same way.
4. **Distro-neutral GUI layer.** Replace hardcoded Debian Python module paths in
   `GUI_WRAPPER` with `python3 -c 'import ...'` probes. Select
   openbox/fluxbox/jwm before matchbox (matchbox is AUR-only on Arch). Map all
   helper sets — base libraries, theme companions, desktop companions,
   compositor and GUI helpers — to Arch package names so auto-install works.
   GPU, theme-pack and Flathub companions stay unchanged.
5. **Package-manager abstraction.** A small script abstraction behind
   `InstallQueue`: apt keeps its commands, `AptProgress` and dpkg status;
   pacman uses `pacman -S/-R --noconfirm` and lists installed packages from
   `/var/lib/pacman/local/*/desc`. AUR jobs run in two phases because `makepkg`
   refuses root: `runuser -u user -- makepkg -s` builds with `base-devel` +
   `git`, then root `pacman -U` installs the package; full output is surfaced on
   failure. No apt commands run when Arch is active, and there is no silent
   cross-source fallback: AUR packages stay AUR, repo packages come from repos.
6. **Store integration.** One Arch store view with provenance badges (official
   repo / AUR / Flathub), not separate tabs; Flathub remains available. Official
   repo metadata comes from ALARM mirror `core/extra/community/alarm.db.tar.gz`
   DBs, parsed from their `desc` files. AUR metadata comes from the cached bulk
   `packages-meta-v1.json.gz` archive with RPC v5 live search/info as a
   fallback, a User-Agent, and rate-limit awareness. No icons or screenshots
   initially — generic icons. `StoreRegistry` detects Arch packages from
   `/var/lib/pacman/local`; shared `.desktop` scanning stays, with exec overrides
   where names differ (`firefox-esr` vs `firefox`).
7. **Setup UI.** A distro selector (Debian 13 / Arch Linux ARM) with download
   size and free-space warnings. Arch chroot installs are in scope.

## Non-goals and anti-goals

Not in scope: Arch Droidspaces support (separate roadmap phase), x86_64 Arch,
ARMv7, installing pacman into Debian, a new container backend. The system must
not silently fall back to apt when Arch is active, or imply that an x86_64-only
AUR package works on arm64.

## Constraints and approaches considered

There is no official arm64 Arch image on Docker Hub: the official image is
x86_64-only. Small arm64 community images are old or unofficial, so both were
rejected. The official tarball is fresh and genuine; its size is the price of
authenticity and is handled explicitly. The AUR is shared with Arch Linux ARM,
but many AUR packages are x86_64-only — failures must be visible. The
archlinuxarm.org package DataTables endpoint now 404s, so repo metadata must
come from mirror DB tarballs instead.

## Implementation calls made in design review

- Active-distro switch rebuilds container objects via a cold app restart rather
  than re-pointing live objects; running sessions stop with confirmation.
- Switching adds a third dimension beside backend mode (PRoot/chroot ×
  Debian/Arch) in prefs and UI keys.
- AUR builds run as `user` via `runuser`, then install as root with `pacman -U`
  — no sudo dependency.
- No cross-source fallback on Arch; provenance badges, not separate store tabs.
- Cache key and installed marker generalize from SHA-256 `Layer` to an artifact
  with a verifier field, so MD5 tarballs fit the same flow.
- All GUI helper/package sets get Arch equivalents, not just the visible ones.
- The `.md5` file is fetched at install time, not pinned in the app, because the
  upstream rootfs is replaced periodically.
