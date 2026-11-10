package lol.sinist3r.yip.core.common

import android.content.Context
import android.os.PowerManager

/** True when the system battery saver is on. */
fun Context.isPowerSaving(): Boolean =
    getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
