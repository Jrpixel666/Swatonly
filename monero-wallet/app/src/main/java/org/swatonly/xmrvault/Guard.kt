package org.swatonly.xmrvault

import android.content.Context
import android.os.SystemClock

/**
 * Failed-PIN accounting: escalating lockouts, then a wipe. Survives app restarts; the lockout is
 * measured on both the wall clock and the boot clock so changing the time doesn't skip it.
 */
class Guard(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("guard", Context.MODE_PRIVATE)

    var failures: Int
        get() = prefs.getInt("failures", 0)
        private set(v) { prefs.edit().putInt("failures", v).commit() }

    /** Wipe after this many wrong PINs in a row. */
    var wipeAfter: Int
        get() = prefs.getInt("wipe_after", 10)
        set(v) { prefs.edit().putInt("wipe_after", v.coerceIn(3, 50)).commit() }

    /** Lock after this many seconds without a touch. */
    var autoLockSeconds: Int
        get() = prefs.getInt("auto_lock", 60)
        set(v) { prefs.edit().putInt("auto_lock", v.coerceIn(15, 600)).commit() }

    var network: Int
        get() = prefs.getInt("network", 0)
        set(v) { prefs.edit().putInt("network", v).commit() }

    /** Milliseconds until another attempt is allowed. */
    fun waitMillis(): Long {
        val untilWall = prefs.getLong("until_wall", 0) - System.currentTimeMillis()
        val bootDelta = prefs.getLong("until_boot", 0) - SystemClock.elapsedRealtime()
        val sameBoot = prefs.getLong("boot_id", -1) == bootId()
        return maxOf(untilWall, if (sameBoot) bootDelta else 0L, 0L)
    }

    /** Record a wrong PIN. Returns true when the wallet must now be wiped. */
    fun fail(): Boolean {
        val n = failures + 1
        failures = n
        if (n >= wipeAfter) return true
        // 3 free tries, then 30 s doubling up to an hour.
        val delay = if (n < 3) 0L else minOf(30_000L shl (n - 3), 3_600_000L)
        prefs.edit()
            .putLong("until_wall", System.currentTimeMillis() + delay)
            .putLong("until_boot", SystemClock.elapsedRealtime() + delay)
            .putLong("boot_id", bootId())
            .commit()
        return false
    }

    fun succeed() {
        prefs.edit().putInt("failures", 0).remove("until_wall").remove("until_boot").commit()
    }

    fun reset() = prefs.edit().clear().commit()

    // Wall-clock time of boot, rounded: stable within a boot, different across boots.
    private fun bootId() = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60_000
}
