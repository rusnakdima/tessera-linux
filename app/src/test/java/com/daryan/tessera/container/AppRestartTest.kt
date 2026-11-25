// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import org.junit.Assert.assertEquals
import org.junit.Test

/** The distro switch must clear the old task so a ViewModel bound to the old rootfs is dropped. */
class AppRestartTest {
    @Test
    fun restartClearsTaskInsteadOfRelyingOnAnAlarm() {
        // FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK.
        assertEquals(0x10000000 or 0x00008000, AppRestart.restartFlags())
    }
}
