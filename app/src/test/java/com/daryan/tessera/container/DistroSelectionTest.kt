// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
