package org.swatonly.launcher.linux

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import org.swatonly.launcher.App
import org.swatonly.launcher.R
import org.swatonly.launcher.ui.LauncherActivity
import org.swatonly.launcher.wayland.WaylandServer
import java.io.File

/**
 * Keeps the Linux side alive while the launcher is in the background: the
 * Wayland compositor (every Linux window becomes an Android task), the D-Bus
 * session bus and the programs started from the launcher.
 */
class LinuxService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = this
        startForeground(NOTIFICATION_ID, buildNotification())
        Session.startBackground(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Session.stopAll()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SWAY -> Session.startSway(this)
            ACTION_WAKELOCK -> toggleWakeLock()
        }
        updateNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        running = null
        wakeLock?.let { if (it.isHeld) it.release() }
        Session.stopAll()
        super.onDestroy()
    }

    private fun toggleWakeLock() {
        val wl = wakeLock
        if (wl != null && wl.isHeld) { wl.release(); wakeLock = null; return }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "swatonly:linux").apply { acquire() }
    }

    fun updateNotification() {
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, LauncherActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun action(a: String, req: Int) = PendingIntent.getService(this, req, Intent(this, LinuxService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        val windows = WaylandServer.instance?.windowCount() ?: 0
        val text = buildString {
            append(if (Session.swayRunning) "Sway running" else "Compositor ready")
            append(" · $windows window")
            if (windows != 1) append('s')
            append(" · ${Session.processCount()} process")
            if (Session.processCount() != 1) append("es")
            if (wakeLock?.isHeld == true) append(" · wake lock")
        }
        return Notification.Builder(this, App.CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Manjaro Sway")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Exit Linux", action(ACTION_STOP, 1)).build())
            .addAction(Notification.Action.Builder(null, if (wakeLock?.isHeld == true) "Release wake lock" else "Wake lock", action(ACTION_WAKELOCK, 2)).build())
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "org.swatonly.launcher.STOP"
        const val ACTION_SWAY = "org.swatonly.launcher.SWAY"
        const val ACTION_WAKELOCK = "org.swatonly.launcher.WAKELOCK"

        @Volatile
        var running: LinuxService? = null

        fun start(ctx: Context, action: String? = null) {
            val i = Intent(ctx, LinuxService::class.java)
            if (action != null) i.action = action
            ctx.startForegroundService(i)
        }
    }
}

/** Everything running inside proot, plus the compositor they draw into. */
object Session {
    private const val TAG = "SwatonlySession"
    private val processes = mutableListOf<Pair<String, Process>>()
    private var dbus: Process? = null
    private var sway: Process? = null

    val swayRunning: Boolean get() = sway?.isAlive == true

    @Synchronized
    fun processCount(): Int {
        processes.removeAll { !it.second.isAlive }
        return processes.size
    }

    @Synchronized
    fun runningNames(): List<String> {
        processes.removeAll { !it.second.isAlive }
        return processes.map { it.first }
    }

    /** Compositor + D-Bus; idempotent. */
    @Synchronized
    fun startBackground(ctx: Context) {
        val app = App.instance
        WaylandServer.start(app)
        if (!app.rootFs.isExtracted) return
        try { Installer.stagePayload(ctx) } catch (e: Exception) { Log.w(TAG, "staging helpers failed", e) }
        if (dbus?.isAlive != true && app.rootFs.has("/usr/bin/dbus-daemon")) {
            File(app.rootFs.runDir, "bus").delete()
            dbus = try {
                Proot.spawn(ctx, "dbus", listOf("/usr/bin/dbus-daemon", "--session", "--nofork", "--nopidfile",
                    "--address=unix:path=${Proot.GUEST_RUNTIME_DIR}/bus"))
            } catch (e: Exception) { Log.w(TAG, "dbus failed", e); null }
        }
    }

    /** Starts a Linux program; its windows open as Android tasks. */
    @Synchronized
    fun launch(ctx: Context, name: String, shellCommand: String) {
        LinuxService.start(ctx)
        startBackground(ctx)
        val rootFs = App.instance.rootFs
        val inSway = App.instance.prefs.appsInSway && rootFs.swayInstalled
        val p = if (inSway) {
            // open it inside sway, which tiles it, and bring the sway window forward
            startSway(ctx)
            val cmd = shellCommand.removePrefix("exec ")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                WaylandServer.instance?.focusWindowByAppId("sway", ctx)
            }, 800)
            Proot.spawn(ctx, logName(name), listOf("/bin/bash", "-lc", "/opt/swatonly/sway-run ${Proot.quote(cmd)}"))
        } else {
            Proot.spawn(ctx, logName(name), listOf("/bin/bash", "-lc", shellCommand))
        }
        processes += name to p
        LinuxService.running?.updateNotification()
    }

    @Synchronized
    fun startSway(ctx: Context) {
        if (swayRunning) {
            WaylandServer.instance?.focusWindowByAppId("sway", ctx)
            return
        }
        LinuxService.start(ctx)
        startBackground(ctx)
        Tweaks.write(ctx)
        sway = Proot.spawn(ctx, "sway", listOf("/bin/bash", "/opt/swatonly/sway-session.sh"),
            mapOf("XDG_CURRENT_DESKTOP" to "sway:wlroots"))
        sway?.let { processes += "sway" to it }
        LinuxService.running?.updateNotification()
    }

    @Synchronized
    fun stopSway() {
        sway?.destroy()
        sway = null
    }

    /** `swaymsg reload` if sway runs, so tweaks and themes apply live. */
    fun reloadSway(ctx: Context) {
        if (!swayRunning) return
        Thread {
            Proot.run(ctx, listOf("/bin/bash", "-lc", "SWAYSOCK=\$(ls ${Proot.GUEST_RUNTIME_DIR}/sway-ipc.*.sock 2>/dev/null | head -1) swaymsg reload"), timeoutSec = 20)
        }.start()
    }

    @Synchronized
    fun stopAll() {
        for ((_, p) in processes) p.destroy()
        processes.clear()
        sway?.destroy(); sway = null
        dbus?.destroy(); dbus = null
        WaylandServer.stop()
    }

    private fun logName(name: String) = name.lowercase().replace(Regex("[^a-z0-9._-]"), "_").take(40)
}
