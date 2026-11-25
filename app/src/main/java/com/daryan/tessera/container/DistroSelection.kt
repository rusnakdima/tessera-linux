// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

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
}
