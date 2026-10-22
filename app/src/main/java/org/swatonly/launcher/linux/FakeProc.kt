package org.swatonly.launcher.linux

import java.io.File

/**
 * Android hides several /proc files from apps; programs like btop, ps and
 * pacman's progress output expect them. Static stand-ins are bound over the
 * ones that cannot be read.
 */
object FakeProc {
    private val files = mapOf(
        "loadavg" to "0.12 0.07 0.02 2/165 765\n",
        "uptime" to "7713.21 15240.38\n",
        "version" to "Linux version 6.1.0-swatonly (proot@android) #1 SMP PREEMPT\n",
        "stat" to """
            cpu  1957 0 2877 93280 262 342 254 87 0 0
            cpu0 31 0 226 12027 82 10 4 9 0 0
            ctxt 140223
            btime 1680020856
            processes 772
            procs_running 2
            procs_blocked 0
            softirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677
        """.trimIndent() + "\n",
        "vmstat" to "nr_free_pages 1743136\nnr_zone_inactive_anon 179281\nnr_zone_active_anon 7183\nnr_zone_inactive_file 22858\nnr_zone_active_file 51328\n",
        "sys/kernel/cap_last_cap" to "40\n",
        "sys/fs/inotify/max_user_watches" to "65536\n",
    )

    fun binds(rootFs: RootFs): List<Pair<String, String>> {
        val dir = File(rootFs.dir, ".swatonly/proc")
        val out = ArrayList<Pair<String, String>>()
        for ((name, content) in files) {
            val host = File("/proc/$name")
            if (host.canRead() && runCatching { host.readText().isNotEmpty() }.getOrDefault(false)) continue
            val fake = File(dir, name)
            if (!fake.exists()) {
                fake.parentFile?.mkdirs()
                fake.writeText(content)
            }
            out += fake.path to "/proc/$name"
        }
        return out
    }
}
