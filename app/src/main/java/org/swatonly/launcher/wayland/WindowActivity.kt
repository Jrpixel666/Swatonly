package org.swatonly.launcher.wayland

import android.app.Activity
import android.app.ActivityManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.swatonly.launcher.App
import org.swatonly.launcher.R
import org.swatonly.launcher.apps.AppRepository
import org.swatonly.launcher.apps.IconLoader
import org.swatonly.launcher.linux.Tiling
import org.swatonly.launcher.ui.SystemMenu
import org.swatonly.launcher.ui.Ui

/**
 * Shows one Linux toplevel of the launcher's compositor: normally the sway
 * session, which tiles every Linux app inside it. All of them share one task.
 */
class WindowActivity : Activity(), Window.Host {

    private var window: Window? = null
    private lateinit var view: WlView
    private var extraKeys: View? = null
    private var tilingRow: View? = null
    private var tilingShown = true
    private var closedByClient = false

    override val holder: SurfaceHolder get() = view.holder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wid = intent.getIntExtra(EXTRA_WINDOW, -1)
        val server = WaylandServer.instance
        val w = server?.window(wid)
        if (w == null || w.gone) {
            finishAndRemoveTask()
            return
        }
        // one activity per window: an older one is brought back instead
        val other = w.host
        if (other is Activity && other !== this && !other.isFinishing) {
            getSystemService(ActivityManager::class.java).moveTaskToFront(other.taskId, 0)
            finish()
            return
        }
        window = w

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        view = WlView(this, server, w)
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.VERTICAL
        val tiling = buildTilingButtons()
        tilingRow = tiling
        bottom.addView(tiling, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val keys = buildExtraKeys()
        extraKeys = keys
        bottom.addView(keys, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        keys.visibility = if (App.instance.prefs.showExtraKeys) View.VISIBLE else View.GONE
        tiling.visibility = if (tilingVisible()) View.VISIBLE else View.GONE
        setContentView(root)

        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {}
            override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) { w.surfaceChanged(width, height) }
            override fun surfaceDestroyed(h: SurfaceHolder) { w.surfaceDestroyed() }
        })
        w.attach(this)
        onMetadataChanged()
        view.requestFocus()
    }

    private fun tilingVisible(): Boolean = window?.isSway == true && App.instance.prefs.showTilingButtons && tilingShown

    /** Buttons for the tiling window manager (sway or Hyprland), chosen in Settings › Tiling buttons. */
    private fun buildTilingButtons(): View {
        val theme = App.instance.themes.current()
        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.setBackgroundColor((theme.surface and 0x00FFFFFF) or 0xE6000000.toInt())
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        scroll.addView(row)
        for (id in App.instance.prefs.tilingButtons) {
            val b = Tiling.byId(id) ?: continue
            val t = TextView(this)
            t.text = b.label
            t.setTextColor(if (id == "menu") getColor(R.color.monero_green) else theme.accent)
            t.typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            t.textSize = 15f
            t.gravity = Gravity.CENTER
            t.minWidth = Ui.dp(this, 40)
            val pad = Ui.dp(this, 10)
            t.setPadding(pad, Ui.dp(this, 8), pad, Ui.dp(this, 8))
            t.background = Ui.pressable(this)
            t.contentDescription = b.title
            t.setOnClickListener {
                when {
                    b.command == "menu:" -> SystemMenu(this) {
                        startActivity(Intent(this, org.swatonly.launcher.ui.LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.show()
                    b.command == "launcher:" -> Tiling.openWmLauncher(this)
                    else -> Tiling.send(this, b.command)
                }
            }
            t.setOnLongClickListener { android.widget.Toast.makeText(this, b.title, android.widget.Toast.LENGTH_SHORT).show(); true }
            row.addView(t)
        }
        return scroll
    }

    private fun buildExtraKeys(): View {
        val theme = App.instance.themes.current()
        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.setBackgroundColor((theme.background and 0x00FFFFFF) or 0xE6000000.toInt())
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        scroll.addView(row)
        fun key(label: String, onClick: (TextView) -> Unit) {
            val t = TextView(this)
            t.text = label
            t.setTextColor(theme.text)
            t.typeface = android.graphics.Typeface.MONOSPACE
            t.textSize = 14f
            t.gravity = Gravity.CENTER
            val pad = Ui.dp(this, 12)
            t.setPadding(pad, Ui.dp(this, 9), pad, Ui.dp(this, 9))
            t.setOnClickListener { onClick(t) }
            row.addView(t)
        }
        key("⌨") { toggleSoftKeyboard() }
        if (window?.isSway == true && App.instance.prefs.showTilingButtons) key("▦") {
            tilingShown = !tilingShown
            tilingRow?.visibility = if (tilingVisible()) View.VISIBLE else View.GONE
        }
        key("ESC") { view.tapKey(Keys.KEY_ESC) }
        key("TAB") { view.tapKey(Keys.KEY_TAB) }
        for ((label, mod) in listOf("CTRL" to Keys.KEY_LEFTCTRL, "ALT" to Keys.KEY_LEFTALT, "SUPER" to Keys.KEY_LEFTMETA, "SHIFT" to Keys.KEY_LEFTSHIFT)) {
            key(label) { t ->
                val on = view.toggleSticky(mod)
                t.setTextColor(if (on) theme.accent else theme.text)
                view.onStickyCleared = { row.post { for (i in 0 until row.childCount) (row.getChildAt(i) as TextView).setTextColor(theme.text) } }
            }
        }
        key("←") { view.tapKey(Keys.KEY_LEFT) }
        key("↓") { view.tapKey(Keys.KEY_DOWN) }
        key("↑") { view.tapKey(Keys.KEY_UP) }
        key("→") { view.tapKey(Keys.KEY_RIGHT) }
        key("HOME") { view.tapKey(Keys.KEY_HOME) }
        key("END") { view.tapKey(Keys.KEY_END) }
        key("PGUP") { view.tapKey(Keys.KEY_PAGEUP) }
        key("PGDN") { view.tapKey(Keys.KEY_PAGEDOWN) }
        key("DEL") { view.tapKey(Keys.KEY_DELETE) }
        key("✕") { window?.requestClose() }
        return scroll
    }

    private fun toggleSoftKeyboard() {
        view.requestFocus()
        val imm = getSystemService(InputMethodManager::class.java)
        @Suppress("DEPRECATION")
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val w = window ?: return
        if (hasFocus) {
            val cm = getSystemService(ClipboardManager::class.java)
            val text = try { cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() } catch (_: Exception) { null }
            WaylandServer.instance?.updateAndroidClipboard(text)
        } else {
            WaylandServer.instance?.releaseAllKeys()
        }
        w.setFocused(hasFocus)
    }

    override fun onMetadataChanged() {
        runOnUiThread {
            val w = window ?: return@runOnUiThread
            val theme = App.instance.themes.current()
            val label = if (w.isSway) "Sway desktop" else w.title
            title = label
            val entry = AppRepository.linuxEntryForAppId(w.appId)
            val icon = if (entry != null) IconLoader.loadSync(this, entry, Ui.dp(this, 48)) else IconLoader.letterBitmap(label, Ui.dp(this, 48))
            @Suppress("DEPRECATION")
            setTaskDescription(ActivityManager.TaskDescription(label, icon, theme.background or 0xFF000000.toInt()))
            applyFullscreen(w.fullscreen)
        }
    }

    private fun applyFullscreen(fs: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = getWindow().insetsController ?: return
            if (fs) {
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else c.show(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            getWindow().decorView.systemUiVisibility = if (fs) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else 0
        }
        extraKeys?.visibility = if (!fs && App.instance.prefs.showExtraKeys) View.VISIBLE else View.GONE
        tilingRow?.visibility = if (!fs && tilingVisible()) View.VISIBLE else View.GONE
    }

    override fun onWindowClosed() {
        closedByClient = true
        runOnUiThread { finishAndRemoveTask() }
    }

    override fun onMinimize() {
        runOnUiThread { moveTaskToBack(true) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && !event.isFromSource(android.view.InputDevice.SOURCE_KEYBOARD)) {
            moveTaskToBack(true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        window?.let { w ->
            w.detach(this)
            if (isFinishing && !closedByClient && !w.gone) w.requestClose()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_WINDOW = "window"

        fun intent(ctx: Context, windowId: Int): Intent {
            val i = Intent(ctx, WindowActivity::class.java)
            i.putExtra(EXTRA_WINDOW, windowId)
            i.data = Uri.parse("swatonly://window/$windowId")
            // all Linux windows share one task (taskAffinity "linux"); sway tiles inside its window
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return i
        }

        /** Brings a window to the front (from the launcher bar). */
        fun focus(ctx: Context, w: Window) {
            val host = w.host
            if (host is Activity && !host.isFinishing) {
                ctx.getSystemService(ActivityManager::class.java).moveTaskToFront(host.taskId, 0)
            } else {
                ctx.startActivity(intent(ctx, w.id))
            }
        }
    }
}
