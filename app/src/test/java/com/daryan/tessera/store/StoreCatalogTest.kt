// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.store

import com.daryan.tessera.container.Distro
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StoreCatalogTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun archApp() = StoreApp(
        id = "arch:extra:reflector", pkg = "reflector", name = "reflector", summary = "official",
        description = "", categories = emptyList(), component = "extra", source = StoreSource.ARCH,
    )

    private fun aurApp() = StoreApp(
        id = "aur:yay-bin", pkg = "yay-bin", name = "yay-bin", summary = "from the AUR",
        description = "", categories = emptyList(), component = "aur", source = StoreSource.AUR,
    )

    private fun catalog(
        official: suspend (File, Distro) -> List<StoreApp> = { _, _ -> listOf(archApp()) },
        aur: suspend (File) -> List<StoreApp> = { listOf(aurApp()) },
    ): Pair<StoreCatalog, Job> {
        val parent = Job()
        return StoreCatalog(tmp.root, CoroutineScope(Dispatchers.IO + parent), official, aur) to parent
    }

    private fun awaitReady(catalog: StoreCatalog): CatalogState.Ready {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            when (val state = catalog.state.value) {
                is CatalogState.Ready -> return state
                is CatalogState.Failed -> throw AssertionError("catalog failed: ${state.message}")
                else -> Thread.sleep(10)
            }
        }
        throw AssertionError("catalog never became Ready: ${catalog.state.value}")
    }

    @Test fun aurFailureIsShownInReadyState() {
        val (catalog, _) = catalog(aur = { throw IOException("AUR archive unavailable") })
        catalog.loadArch(Distro.ARCH_X86_64)
        val ready = awaitReady(catalog)
        assertEquals("AUR archive unavailable", ready.aurError)
        assertEquals(listOf("arch:extra:reflector"), ready.apps.map { it.id })
        val cache = File(tmp.root, "catalog-arch-arch_x86_64.json")
        assertTrue(cache.isFile)
        assertFalse("AUR apps must never enter the official cache", cache.readText().contains("\"aur:\""))
    }

    @Test fun aurOutOfMemoryErrorIsReportedNotCrashed() {
        val (catalog, _) = catalog(aur = { throw OutOfMemoryError("Java heap space") })
        catalog.loadArch(Distro.ARCH_X86_64)
        val ready = awaitReady(catalog)
        assertTrue("expected a heap error, got ${ready.aurError}", ready.aurError?.contains("heap") == true)
        assertEquals(1, ready.apps.size)
    }

    @Test fun aurCancellationPropagates() {
        val cancelled = CancellationException("cancelled by test")
        val invoked = CountDownLatch(1)
        val (catalog, _) = catalog(aur = { invoked.countDown(); throw cancelled })
        catalog.loadArch(Distro.ARCH_X86_64)
        assertTrue("AUR loader wasn't called", invoked.await(5, TimeUnit.SECONDS))
        // A rethrown CancellationException ends the load without publishing a new state.
        Thread.sleep(100)
        val state = catalog.state.value
        assertTrue("cancelled loads must not publish a Ready catalog: $state", state !is CatalogState.Ready)
        assertTrue("cancelled loads must not publish a Failed catalog: $state", state !is CatalogState.Failed)
    }

    @Test fun cachedArchCatalogStillRetriesAur() {
        // Seed a complete official-only cache the way a failed AUR load leaves one behind.
        val first = catalog(aur = { throw IOException("no AUR yet") }).first
        first.loadArch(Distro.ARCH_X86_64)
        awaitReady(first)

        var aurCalls = 0
        val (catalog, _) = catalog(
            official = { _, _ -> throw AssertionError("official catalog must not be refetched on a fresh cache") },
            aur = { aurCalls++; listOf(aurApp()) },
        )
        catalog.loadArch(Distro.ARCH_X86_64)
        val ready = awaitReady(catalog)
        assertEquals(1, aurCalls)
        assertEquals(listOf("arch:extra:reflector", "aur:yay-bin"), ready.apps.map { it.id })
        assertNull(ready.aurError)
    }

    @Test fun retryAfterAurFailureRecovers() {
        var attempts = 0
        val (catalog, _) = catalog(aur = {
            if (++attempts == 1) throw IOException("transient") else listOf(aurApp())
        })
        catalog.loadArch(Distro.ARCH_X86_64)
        val failed = awaitReady(catalog)
        assertEquals("transient", failed.aurError)
        assertEquals(1, failed.apps.size)

        val beforeRetry = catalog.state.value
        catalog.loadArch(Distro.ARCH_X86_64)
        // The first Ready is still published until the retry coroutine starts.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && catalog.state.value === beforeRetry) Thread.sleep(10)
        val recovered = awaitReady(catalog)
        assertNull(recovered.aurError)
        assertEquals(listOf("arch:extra:reflector", "aur:yay-bin"), recovered.apps.map { it.id })
    }
}
