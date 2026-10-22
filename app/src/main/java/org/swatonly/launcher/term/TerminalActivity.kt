package org.swatonly.launcher.term

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import org.swatonly.launcher.App
import org.swatonly.launcher.apps.AppRepository
import org.swatonly.launcher.linux.Proot
import org.swatonly.launcher.linux.Session
import org.swatonly.launcher.ui.Ui
import java.util.Properties

/** A terminal on the Manjaro rootfs (proot), themed with the sway theme's foot colours. */
class TerminalActivity : Activity(), TerminalSessionClient, TerminalViewClient {

    private lateinit var terminal: TerminalView
    private var session: TerminalSession? = null
    private var textSize = 0
    private var ctrl = false
    private var alt = false
    private var ctrlKey: TextView? = null
    private var altKey: TextView? = null
    private var setupMode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val theme = App.instance.themes.current()
        title = intent.getStringExtra(EXTRA_TITLE) ?: "Terminal"
        setupMode = intent.getStringExtra(EXTRA_SETUP)
        @Suppress("DEPRECATION")
        setTaskDescription(android.app.ActivityManager.TaskDescription(title.toString(), null, theme.background or 0xFF000000.toInt()))
        window.statusBarColor = theme.background
        window.navigationBarColor = theme.background

        applyColors()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(theme.background)

        terminal = TerminalView(this, null)
        terminal.setTerminalViewClient(this)
        textSize = Ui.dp(this, 13)
        terminal.setTextSize(textSize)
        terminal.isFocusable = true
        terminal.isFocusableInTouchMode = true
        root.addView(terminal, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(extraKeys(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        val args = intent.getStringArrayExtra(EXTRA_ARGS)?.toList() ?: listOf("/bin/bash", "-l")
        val argv = Proot.command(this, args)
        val env = Proot.hostEnv(this).map { "${it.key}=${it.value}" }.toTypedArray()
        val s = TerminalSession(argv[0], filesDir.path, arrayOf("proot") + argv.drop(1).toTypedArray(), env, 3000, this)
        session = s
        terminal.attachSession(s)
        terminal.requestFocus()
    }

    private fun applyColors() {
        val colors = App.instance.themes.current().terminalColors()
        val props = Properties()
        for ((k, v) in colors) props[k] = String.format("#%06x", v and 0xFFFFFF)
        props["cursor"] = String.format("#%06x", App.instance.themes.current().accent and 0xFFFFFF)
        try { TerminalColors.COLOR_SCHEME.updateWith(props) } catch (_: Exception) {}
    }

    private fun extraKeys(): View {
        val theme = App.instance.themes.current()
        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.setBackgroundColor(theme.surface)
        val row = LinearLayout(this)
        scroll.addView(row)
        fun key(label: String, action: (TextView) -> Unit): TextView {
            val t = TextView(this)
            t.text = label
            t.setTextColor(theme.text)
            t.typeface = android.graphics.Typeface.MONOSPACE
            t.textSize = 14f
            t.gravity = Gravity.CENTER
            val p = Ui.dp(this, 12)
            t.setPadding(p, Ui.dp(this, 9), p, Ui.dp(this, 9))
            t.setOnClickListener { action(t) }
            row.addView(t)
            return t
        }
        key("⌨") { showKeyboard() }
        key("ESC") { send("\u001b") }
        key("TAB") { send("\t") }
        ctrlKey = key("CTRL") { ctrl = !ctrl; updateSticky() }
        altKey = key("ALT") { alt = !alt; updateSticky() }
        key("←") { send("\u001b[D") }
        key("↓") { send("\u001b[B") }
        key("↑") { send("\u001b[A") }
        key("→") { send("\u001b[C") }
        key("HOME") { send("\u001b[H") }
        key("END") { send("\u001b[F") }
        key("PGUP") { send("\u001b[5~") }
        key("PGDN") { send("\u001b[6~") }
        key("|") { send("|") }
        key("~") { send("~") }
        key("/") { send("/") }
        key("-") { send("-") }
        key("PASTE") { onPasteTextFromClipboard(session) }
        return scroll
    }

    private fun send(s: String) { session?.write(s) }

    private fun updateSticky() {
        val theme = App.instance.themes.current()
        ctrlKey?.setTextColor(if (ctrl) theme.accent else theme.text)
        altKey?.setTextColor(if (alt) theme.accent else theme.text)
    }

    private fun showKeyboard() {
        terminal.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(terminal, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onDestroy() {
        if (isFinishing) session?.finishIfRunning()
        super.onDestroy()
    }

    // ---- TerminalSessionClient ---------------------------------------------

    override fun onTextChanged(changedSession: TerminalSession) { terminal.onScreenUpdated() }
    override fun onTitleChanged(changedSession: TerminalSession) {}

    override fun onSessionFinished(finishedSession: TerminalSession) {
        val mode = setupMode
        if (mode != null) {
            AppRepository.reload(this)
            val installed = App.instance.rootFs.installedMode
            if (finishedSession.exitStatus == 0 && installed != null) {
                if (isFinishing || isDestroyed) return
                AlertDialog.Builder(this)
                    .setTitle("Manjaro is ready")
                    .setMessage(if (installed == "sway") "Sway and the manjaro-sway settings are installed. Start the Sway desktop now?" else "The Linux base is ready.")
                    .setPositiveButton(if (installed == "sway") "Start Sway" else "OK") { _, _ ->
                        if (installed == "sway") Session.startSway(this)
                        finish()
                    }
                    .setNegativeButton("Close") { _, _ -> finish() }
                    .show()
            } else {
                Toast.makeText(this, "Setup did not finish (exit ${finishedSession.exitStatus}). Scroll up for the error; run it again from Settings > Linux.", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (!isFinishing) finish()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("terminal", text ?: ""))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip ?: return
        val text = clip.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        terminal.mEmulator?.paste(text)
    }

    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null

    // ---- TerminalViewClient --------------------------------------------------

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            val increase = scale > 1f
            textSize = (textSize + if (increase) 2 else -2).coerceIn(Ui.dp(this, 7), Ui.dp(this, 30))
            terminal.setTextSize(textSize)
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent?) { showKeyboard() }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false
    override fun readControlKey(): Boolean = ctrl
    override fun readAltKey(): Boolean = alt
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        if (ctrl || alt) {
            ctrl = false; alt = false
            runOnUiThread { updateSticky() }
        }
        return false
    }

    override fun onEmulatorSet() {
        terminal.mEmulator?.mColors?.reset()
        terminal.setTerminalCursorBlinkerRate(500)
        terminal.setTerminalCursorBlinkerState(true, true)
    }

    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_ARGS = "args"
        private const val EXTRA_SETUP = "setup"
        private var counter = 0

        fun start(ctx: Context, title: String, guestArgs: List<String>, setupMode: String? = null) {
            val i = Intent(ctx, TerminalActivity::class.java)
            i.putExtra(EXTRA_TITLE, title)
            i.putExtra(EXTRA_ARGS, guestArgs.toTypedArray())
            setupMode?.let { i.putExtra(EXTRA_SETUP, it) }
            i.data = Uri.parse("swatonly://terminal/${System.currentTimeMillis()}-${counter++}")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }
}
