package org.swatonly.launcher

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import org.swatonly.launcher.linux.RootFs
import org.swatonly.launcher.theme.ThemeRepository

class App : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var rootFs: RootFs
        private set
    lateinit var themes: ThemeRepository
        private set

    /** Activities of this app that are currently resumed (used to decide how new Linux windows appear). */
    @Volatile
    var resumedActivities = 0
        private set

    /** The first-boot / every-start choice is asked once per process start. */
    var setupChoiceAsked = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        rootFs = RootFs(this)
        themes = ThemeRepository(this)

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_SESSION, getString(R.string.service_channel), NotificationManager.IMPORTANCE_LOW)
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_WINDOWS, "Linux windows", NotificationManager.IMPORTANCE_HIGH)
        )

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) { resumedActivities++ }
            override fun onActivityPaused(activity: Activity) { resumedActivities-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    companion object {
        const val CHANNEL_SESSION = "session"
        const val CHANNEL_WINDOWS = "windows"

        lateinit var instance: App
            private set
    }
}
