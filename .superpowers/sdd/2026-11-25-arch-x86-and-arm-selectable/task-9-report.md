# Task 9 — AUR catalog loading, source-filtered tabs, correct install copy (report)

Branch `arch-peer-distro` · BASE `95f33f08146cf6f1f188c496e0f2c6d764af4e89` · device `emulator-5556` (Android 17 x86_64 tablet) · 2026-11-26.
`fakeroot` SysV-IPC was explicitly out of scope and was not touched or worked around.

## Implemented changes

### A. `ArchCatalog.kt`
- `AurCatalog.parseArchive(InputStream)` (`ArchCatalog.kt:174`) now uses
  `json.decodeToSequence<Package>(input).mapNotNull { it.toApp() }.toList()` with
  `@OptIn(ExperimentalSerializationApi::class)`. The 55 MB archive is no longer materialised as a
  UTF-16 `String`.
- `AurCatalog.loadArchive` (`ArchCatalog.kt:124`): on HTTP 200 the gzip response is decompressed
  through a 64 KiB buffer into `aur-meta.json.tmp`, with `currentCoroutineContext().ensureActive()`
  per chunk; the temp file is parsed with `FileInputStream`, and only a complete parse may
  `renameTo(cache)` and write `aur-meta.headers`. The temp file is deleted in `finally`.
  On HTTP 304 it parses the existing cache with `FileInputStream` and throws `IOException` if the
  cache is missing. Existing User-Agent and `If-None-Match`/`If-Modified-Since` handling and
  `withContext(Dispatchers.IO)` are unchanged. `Package`/`toApp` unchanged.
- Removed the silent `runCatching { apps += AurCatalog.loadArchive(dir) }` at old line 212;
  `ArchCatalog.load` now returns official apps only (`ArchCatalog.kt:233-235`).

### B. `StoreCatalog.kt`
- `CatalogState.Ready` gained `val aurError: String? = null` (`StoreCatalog.kt:30`), so all existing
  two-argument constructor calls still compile.
- `StoreCatalog` gained injectable `officialLoader` / `aurLoader` seams with production defaults
  (`StoreCatalog.kt:80-81`).
- `loadArch` (`StoreCatalog.kt:119-159`) reads/filters cached rows to `source == ARCH`; on a miss it
  shows `Loading("Reading the Arch repositories", null)`, loads official apps only and writes an
  official-only `catalog-arch-*.json`. It then shows `Loading("Reading the AUR catalog", null)`,
  always revalidates the AUR through the seam, merges/dedups/sorts, and publishes
  `Ready(all, now, aurError)`. Cached official hits also revalidate AUR.
- The AUR catch rethrows `CancellationException`, catches `Throwable` (so `OutOfMemoryError` becomes
  `aurError`), contributes `emptyList()` to the catalog, and logs the real exception under logcat tag
  `AUR catalog` (best-effort: `android.util.Log` is a throwing stub in local JVM tests).
  `catalog-arch-*.json` never receives AUR apps; `aur-meta.json` is replaced only after a full parse.
  Debian/Flathub `load` and `download` paths are untouched apart from spelling out the existing
  cancellation rethrow.

### C. `StoreScreen.kt`
- Extracted `internal fun storeAppsFor(apps, source, query, category)` (`StoreScreen.kt:88-108`);
  ARCH keeps only `ARCH`, AUR keeps only `AUR`, other sources keep all (they only ever receive their
  own catalog). Query/category/name-first behavior preserved; `source` added to `remember` keys.
- In the `Ready` branch (`StoreScreen.kt:173-190`), a non-null `aurError` shows a visible error line
  with the error text and a `Try again` button wired to the existing `onRetry`.
- Detail install text now calls `StoreInstalls.installCopy(app, firstFlathub)` (`StoreScreen.kt:468`).

### D. `StoreInstalls.kt`
- Added `installCopy(app, firstFlathub)` (`StoreInstalls.kt:46-60`): FLATHUB keeps its exact previous
  wording including the first-app suffix; ARCH says `Arch Linux <component>` + `pacman`; AUR says
  `AUR` + `makepkg`/`pacman`; DEBIAN keeps the `Debian <component>` + `apt` sentence. No install
  logic changed.

## TDD — RED first

Command: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest --tests
'com.daryan.tessera.store.ArchCatalogTest' --tests 'com.daryan.tessera.store.StoreCatalogTest'
--tests 'com.daryan.tessera.store.ArchStoreWiringTest' --tests
'com.daryan.tessera.ui.StoreScreenFilterTest' --console=plain`.

RED: `:app:compileDebugUnitTestKotlin FAILED`, each new test failing for the expected missing API:
- `ArchCatalogTest.aurArchiveIsDecodedFromGzipStream` and `truncatedAurArchiveThrows` —
  `Argument type mismatch: actual type is 'GZIPInputStream', but 'String' was expected` (only the
  old private `parseArchive(String)` existed);
- `ArchStoreWiringTest.installCopyNamesSourceAndTool` — 5× `Unresolved reference 'installCopy'`;
- `StoreCatalogTest` — `Too many arguments for 'constructor(dir: File, scope: CoroutineScope)'`
  and 6× `Unresolved reference 'aurError'`;
- `StoreScreenFilterTest` — `Unresolved reference 'storeAppsFor'` (11 call sites).

Nine new tests (2 + 5 + 1 + 1) plus the three pre-existing tests in the touched classes were then
implemented against. During the first GREEN attempt four assertions failed and were corrected in the
*tests*, not the product code:
- `aurCancellationPropagates` — a child coroutine rethrowing `CancellationException` does not cancel
  the parent `Job`; the test now waits for the AUR loader invocation and asserts no `Ready`/`Failed`
  state is published.
- `retryAfterAurFailureRecovers` — the previous `Ready` is still observable until the retry
  coroutine starts; the test now waits for state identity to change.
- `debianAndFlathubTabsAreUnchanged` / `queryAndCategoryStillFilterWithinTheTab` — the brief says
  non-ARCH/AUR sources "keep all"; the tests were corrected to that contract and moved the
  query/category assertions onto the ARCH/AUR tabs.

GREEN: same command → `BUILD SUCCESSFUL`, 26 tests in the four classes, 0 failures.

## Full suite

`JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew testDebugUnitTest --console=plain --rerun-tasks`
→ **BUILD SUCCESSFUL**. Parsed `app/build/test-results/testDebugUnitTest/TEST-*.xml`:
**60 classes, 350 tests, 0 failures, 0 errors, 0 skipped**. No test failures by name.
Compiler emitted only pre-existing warnings (X11Window.kt, MicPipe.kt, DesktopShell.kt, FilesScreen.kt,
PcTrackpadScreen.kt, X11Overlay.kt, GtkThemeReaderTest.kt, GuestLocaleTest.kt).

## Device verification

1. `./gradlew assembleDebug` → BUILD SUCCESSFUL. `adb -s emulator-5556 install -r
   app/build/outputs/apk/debug/app-debug.apk` → Success.
2. Device-only cleanup: `run-as com.daryan.tessera rm -f files/store/catalog-arch-arch_x86_64.json
   files/store/aur-meta.json files/store/aur-meta.headers files/store/aur-meta.json.tmp`.
3. Launched Tessera → Store. After the first load: `127207 apps from AUR`, `aur-meta.json` 56,888,282 B
   and `aur-meta.headers` written; `catalog-arch-arch_x86_64.json` 3,679,396 B. Parsed the cached
   official catalog on the host: 15,598 entries, all `source: ARCH` (`extra` 15,298 / `core` 300),
   **0** AUR ids. After all reloads the final official cache was still 15,598 / ARCH-only / 0 AUR.
4. AUR tab search `yay-bin` → `1 apps from AUR`, row `yay-bin` (previously `0 apps from AUR`).
   ARCH tab with the same query → `0 apps from Arch Linux`; ARCH tab `reflector` → `1 apps from
   Arch Linux` (no AUR/official mislabelling).
5. Detail sheets: `reflector` (already installed) shows `Arch Linux · extra` and Open/Uninstall, so
   its install sentence is intentionally hidden; an uninstalled Arch app (`fastfetch`) showed
   **"Installs fastfetch from Arch Linux extra with pacman, in a terminal you can watch, then adds it
   to your apps."** `yay-bin` showed **"Installs yay-bin from AUR, built on the device with makepkg
   and installed with pacman, in a terminal you can watch, then adds it to your apps."** and
   `AUR · yay-bin`.
6. Error path exercised on device: airplane mode on, source switch → error line
   `The AUR catalog couldn't load: Unable to resolve host "aur.archlinux.org": No address associated
   with hostname. Official Arch apps are still available.` plus a `Try again` button; AUR tab showed
   `0 apps from AUR` (no official apps mislabelled as AUR). Airplane mode off → `Try again` →
   error gone, `1 apps from AUR` (127,208 AUR apps total after upstream refresh).
7. `adb logcat -d -s 'AUR catalog'` was empty — no AUR load errors after the fix. The logcat tag is
   present in code for any future failure.

Screenshots: `/tmp/tessera-smoke/task9-00-launch.png` through `task9-17-aur-retry-recovered.png`
(key ones: `02` AUR loaded, `03` AUR `yay-bin`, `04` ARCH rejects `yay-bin`, `08` reflector detail,
`10` Arch install copy, `14` AUR `yay-bin`, `15` AUR install copy, `16` error + Try again,
`17` recovery). Catalog evidence: `/tmp/tessera-smoke/task9-official-cache.json` and
`task9-official-cache-final.json`.

## Concerns / notes
- Per the brief, `fakeroot`/SysV-IPC remains an environment limitation; no AUR install was attempted
  and no install scripts were changed. The catalog and wording defects are fixed and verified.
- Parsing still builds ~127k `StoreApp` objects; the 110 MB UTF-16 intermediate `String` is gone,
  but very low-memory devices could still struggle. The failure is now visible and retryable instead
  of silent.
- The AUR count changed from 127,207 to 127,208 between device reloads (upstream archive changed),
  not from a code change.
- Switching Store source triggers a catalog reload; while AUR is being revalidated the loading hint
  under the ARCH tab still says "reading the Arch package databases from the repositories". This is
  pre-existing copy and outside the brief's scope.
