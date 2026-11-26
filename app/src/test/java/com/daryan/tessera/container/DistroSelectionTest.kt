// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import android.content.SharedPreferences
import com.daryan.tessera.BackendMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class DistroSelectionTest {
    @Test fun defaultIsDebian() = assertEquals(Distro.DEBIAN, DistroSelection.fromName(null))

    @Test fun unknownNameFallsBackToDebian() = assertEquals(Distro.DEBIAN, DistroSelection.fromName("nope"))

    @Test fun knownNameSelectsDistro() = assertEquals(Distro.ARCH, DistroSelection.fromName("ARCH"))

    @Test fun switchRequiresConfirmationWhenSessionsLive() {
        assertTrue(DistroSelection.needsConfirmation(1, Distro.DEBIAN, Distro.ARCH))
    }

    @Test fun reselectingCurrentDistroDoesNotNeedConfirmation() {
        assertFalse(DistroSelection.needsConfirmation(3, Distro.DEBIAN, Distro.DEBIAN))
    }

    @Test fun switchingWithNoLiveSessionsDoesNotNeedConfirmation() {
        assertFalse(DistroSelection.needsConfirmation(0, Distro.DEBIAN, Distro.ARCH))
    }

    // The process is killed right after this write; apply() would only queue the disk write.
    @Test fun distroSaveCommitsToDiskBeforeRestart() {
        val calls = mutableListOf<String>()
        assertTrue(DistroSelection.save(fakeEditor(calls, commitResult = true), Distro.ARCH))
        assertEquals(listOf("putString:active_distro=ARCH", "commit"), calls)
    }

    @Test fun failedDistroSaveIsReportedToTheCaller() {
        val calls = mutableListOf<String>()
        assertFalse(DistroSelection.save(fakeEditor(calls, commitResult = false), Distro.ARCH))
        assertTrue("commit" in calls)
    }

    // An Arch rootfs only runs on the ABI it was built for: no emulation, no silent swap.
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

    @Test
    fun isArchCoversBothArchVariants() {
        assertTrue(Distro.ARCH.isArch)
        assertTrue(Distro.ARCH_X86_64.isArch)
        assertFalse(Distro.DEBIAN.isArch)
    }

    // BackendMode is declared in com.daryan.tessera (LauncherViewModel.kt), not in .container;
    // its values are PROOT, CHROOT and DROIDSPACES.
    @Test
    fun x8664InstallRejectsChroot() {
        assertEquals("chroot is arm64-only for Arch", Distro.ARCH_X86_64.installProblem(BackendMode.CHROOT))
        assertNull(Distro.ARCH_X86_64.installProblem(BackendMode.PROOT))
        assertNull(Distro.ARCH.installProblem(BackendMode.CHROOT))
        assertNull(Distro.DEBIAN.installProblem(BackendMode.CHROOT))
    }

    private fun fakeEditor(calls: MutableList<String>, commitResult: Boolean): SharedPreferences.Editor =
        Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "putString" -> {
                    calls += "putString:${args[0]}=${args[1]}"
                    proxy
                }
                "commit" -> { calls += "commit"; commitResult }
                "apply" -> { calls += "apply"; Unit }
                else -> null
            }
        } as SharedPreferences.Editor
}
