package com.breakoverlay.app.util

import android.content.Context
import android.provider.Settings

object BootUtils {
    /**
     * Settings.Global.BOOT_COUNT is available from API 24. The value is used to make sure
     * a persisted monotonic/elapsed schedule is never interpreted across a reboot.
     */
    fun currentBootCount(context: Context): Int = try {
        Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.BOOT_COUNT,
            0,
        )
    } catch (_: RuntimeException) {
        0
    }
}
