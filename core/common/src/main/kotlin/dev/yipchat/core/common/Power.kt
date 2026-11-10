package dev.yipchat.core.common

import android.content.Context
import android.os.PowerManager

fun Context.isPowerSaving(): Boolean =
    getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
