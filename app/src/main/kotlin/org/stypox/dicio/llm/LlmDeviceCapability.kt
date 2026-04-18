package org.stypox.dicio.llm

import android.app.ActivityManager
import android.content.Context

object LlmDeviceCapability {
    fun totalRamGb(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        // Round up to nearest GB so 11.9 GB reports as 12.
        return ((info.totalMem + (1L shl 30) - 1) / (1L shl 30)).toInt()
    }
}
