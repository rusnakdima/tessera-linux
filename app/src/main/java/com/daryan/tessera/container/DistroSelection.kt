// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import android.content.SharedPreferences

/**
 * The distribution Tessera installs into and launches apps from. Changing it needs a cold start:
 * paths, the container scope and every store read are built around the active distro.
 */
object DistroSelection {
    const val KEY = "active_distro"

    /** Unknown or missing preferences mean the original Debian install. */
    fun fromName(name: String?) = runCatching { Distro.valueOf(name ?: "") }.getOrDefault(Distro.DEBIAN)

    /** Changing systems with live sessions would pull their rootfs out from under them. */
    fun needsConfirmation(runningSessions: Int, from: Distro, to: Distro) =
        runningSessions > 0 && from != to

    /**
     * Writes the new distro synchronously: [SharedPreferences.Editor.apply] only queues the disk
     * write, and the caller kills the process to restart into the other rootfs immediately.
     * False means the switch must not proceed, or the restart would come back as Debian.
     */
    fun save(editor: SharedPreferences.Editor, distro: Distro): Boolean =
        editor.putString(KEY, distro.name).commit()
}
