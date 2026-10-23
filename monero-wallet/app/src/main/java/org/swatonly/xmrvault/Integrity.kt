package org.swatonly.xmrvault

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Debug
import android.provider.Settings
import org.json.JSONObject
import java.io.File

/**
 * Environment checks. None of these can be relied on against a determined attacker who controls
 * the device; they exist to warn honest users that their phone is not a safe place for keys.
 */
object Integrity {
    fun problems(ctx: Context): List<String> {
        val out = mutableListOf<String>()
        val km = ctx.getSystemService(KeyguardManager::class.java)
        if (km?.isDeviceSecure != true) out += "No screen lock is set. Anyone holding the phone can open the device."
        if (rooted()) out += "This device appears to be rooted. Other apps may be able to read wallet memory."
        if ((ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) out += "This is a debuggable build."
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) out += "A debugger is attached."
        if (emulator()) out += "Running on an emulator."
        if (Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1) {
            out += "USB debugging is on. Turn it off in Developer options."
        }
        try {
            val r = JSONObject(Native.securityReport())
            if (r.optBoolean("traced")) out += "Another process is tracing the wallet."
            if (r.optBoolean("hooked")) out += "A code-injection framework (Frida/Xposed) is loaded."
        } catch (_: Throwable) {}
        if (KeyGuard.exists(ctx) && KeyGuard.securityLevel() == "software") {
            out += "No secure hardware: the device key lives in software."
        }
        return out
    }

    private fun rooted(): Boolean {
        val paths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/su", "/vendor/bin/su",
            "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su", "/su/bin/su",
            "/system/app/Superuser.apk", "/data/adb/magisk", "/data/adb/ksu", "/data/adb/modules",
        )
        if (paths.any { File(it).exists() }) return true
        if (Build.TAGS?.contains("test-keys") == true) return true
        return System.getenv("PATH")?.split(':')?.any { File(it, "su").exists() } == true
    }

    private fun emulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") ||
            Build.MODEL.contains("Emulator") || Build.MODEL.contains("Android SDK built for") ||
            Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu") ||
            Build.PRODUCT.contains("sdk")
}
