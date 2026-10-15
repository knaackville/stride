package io.stride.spikes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short record of every intent delivered to the running launcher, and who sent it.
 *
 * A Home intent reveals the app grid behind the track floor, and on 2026-10-15 the grid was found
 * up on an idle console with nobody admitting to a tap. logcat had rolled over by then, so this
 * keeps its own copy: the last [MAX_LINES] lines, roughly 15 KB, in the app's external files dir,
 * where `adb shell cat` can read it on a non-debuggable build:
 *
 *   adb shell cat /sdcard/Android/data/io.stride.spikes/files/home-intents.log
 */
object HomeIntentLog {
    private const val TAG = "HomeIntentLog"
    private const val FILE_NAME = "home-intents.log"
    private const val MAX_LINES = 100

    fun record(context: Context, intent: Intent, referrer: Uri?, revealed: Boolean) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val line = "$stamp action=${intent.action} categories=${intent.categories} " +
            "flags=0x${Integer.toHexString(intent.flags)} from=${referrer ?: "unknown"} " +
            "revealedGrid=$revealed"
        Log.i(TAG, line)
        try {
            val dir = context.getExternalFilesDir(null) ?: return
            val file = File(dir, FILE_NAME)
            val kept = if (file.exists()) file.readLines().takeLast(MAX_LINES - 1) else emptyList()
            file.writeText((kept + line).joinToString("\n", postfix = "\n"))
        } catch (e: Exception) {
            // Diagnostics only. A full or missing volume must never get in the way of Home working.
            Log.w(TAG, "could not write $FILE_NAME", e)
        }
    }
}
