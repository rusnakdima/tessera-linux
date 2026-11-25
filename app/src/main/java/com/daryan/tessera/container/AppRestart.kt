// Copyright (C) 2026 Daryan Kamaran Karim
// SPDX-License-Identifier: GPL-3.0-or-later

package com.daryan.tessera.container

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.daryan.tessera.LauncherApplication
import com.daryan.tessera.MainActivity

/**
 * Rebuilds the process-wide container for the newly selected distro and starts a fresh launcher.
 *
 * An alarm plus Process.exit used to do this, but Android 34+ blocks the system-sent alarm
 * PendingIntent once the creator process is gone (the task keeps no visible window). Rebuilding
 * in place keeps the restart tied to the user's tap and never relies on a background launch.
 */
object AppRestart {
    /** Clearing the task drops the ViewModel and all flows bound to the old rootfs. */
    fun restartFlags(): Int = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

    fun schedule(context: Context) {
        val app = context.applicationContext as LauncherApplication
        // Cancel the old scope before the new container's flows start collecting.
        app.rebuildContainer()
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(restartFlags()))
        if (context is Activity) context.finish()
    }
}
