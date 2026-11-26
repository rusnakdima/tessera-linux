// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.ui

import com.daryan.tessera.store.StoreApp
import com.daryan.tessera.store.StoreSource
import org.junit.Assert.assertEquals
import org.junit.Test

class StoreScreenFilterTest {
    private fun app(id: String, source: StoreSource, name: String, summary: String = "desc", categories: List<String> = emptyList()) =
        StoreApp(
            id = id, pkg = id, name = name, summary = summary, description = "",
            categories = categories, component = id, source = source,
        )

    private val arch = app("arch:extra:reflector", StoreSource.ARCH, "Reflector", "mirror list")
    private val aur = app("aur:yay-bin", StoreSource.AUR, "yay-bin", "AUR helper", listOf("Utility"))
    private val debian = app("firefox-esr.desktop", StoreSource.DEBIAN, "Firefox ESR", "browser", listOf("Network"))
    private val flathub = app("org.example.App", StoreSource.FLATHUB, "Example", "flatpak app")
    private val all = listOf(arch, aur, debian, flathub)

    @Test fun archTabShowsOnlyArchApps() {
        assertEquals(listOf(arch), storeAppsFor(all, StoreSource.ARCH, "", "All"))
    }

    @Test fun aurTabShowsOnlyAurApps() {
        assertEquals(listOf(aur), storeAppsFor(all, StoreSource.AUR, "", "All"))
    }

    @Test fun debianAndFlathubTabsAreUnchanged() {
        // Those tabs only ever receive their own catalog, so they keep every app they get.
        assertEquals(all, storeAppsFor(all, StoreSource.DEBIAN, "", "All"))
        assertEquals(all, storeAppsFor(all, StoreSource.FLATHUB, "", "All"))
    }

    @Test fun queryAndCategoryStillFilterWithinTheTab() {
        val browser = app("arch:extra:firefox", StoreSource.ARCH, "Firefox", "browser", listOf("Network"))
        val game = app("arch:extra:supertux", StoreSource.ARCH, "SuperTux", "game", listOf("Game"))
        val archApps = listOf(browser, game)
        assertEquals(listOf(browser), storeAppsFor(archApps, StoreSource.ARCH, "firefox", "All"))
        assertEquals(listOf(browser), storeAppsFor(archApps, StoreSource.ARCH, "browser", "All"))
        assertEquals(listOf(aur), storeAppsFor(all, StoreSource.AUR, "helper", "All"))
        assertEquals(listOf(browser), storeAppsFor(archApps, StoreSource.ARCH, "", "Internet"))
        assertEquals(emptyList<StoreApp>(), storeAppsFor(archApps, StoreSource.ARCH, "", "Graphics"))
        // Name matches come first when searching.
        val two = listOf(
            app("arch:extra:a", StoreSource.ARCH, "Aardvark reflector", "mirror"),
            app("arch:extra:z", StoreSource.ARCH, "Reflector Pro", "mirror list"),
        )
        assertEquals(listOf("Reflector Pro", "Aardvark reflector"), storeAppsFor(two, StoreSource.ARCH, "reflector", "All").map { it.name })
    }
}
