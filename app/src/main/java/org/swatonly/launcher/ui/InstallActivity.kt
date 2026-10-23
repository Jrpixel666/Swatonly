package org.swatonly.launcher.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.swatonly.launcher.App
import org.swatonly.launcher.BuildConfig
import org.swatonly.launcher.linux.Installer
import org.swatonly.launcher.term.TerminalActivity

/**
 * Installs Manjaro under proot: download + unpack here, then setup.sh runs
 * in a terminal (pacman needs a tty and its output is worth seeing).
 */
class InstallActivity : Activity(), Installer.Listener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var step: TextView
    private lateinit var progress: ProgressBar
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private var installer: Installer? = null
    private var running = false
    private lateinit var mode: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra(EXTRA_MODE) ?: "sway"
        val theme = Ui.theme
        window.statusBarColor = theme.background
        window.navigationBarColor = theme.background
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val app = App.instance
        val rootFs = app.rootFs

        val root = Ui.vertical(this)
        root.setBackgroundColor(theme.background)
        root.setPadding(Ui.dp(this, 20), Ui.dp(this, 24), Ui.dp(this, 20), Ui.dp(this, 20))
        val desktop = mode == "sway" || mode == "omarchy"
        root.addView(Ui.text(this, when (mode) { "omarchy" -> "Launcher + Omarchy"; "sway" -> "Launcher + Sway"; else -> "Manjaro base" }, 22f, theme.accent, bold = true))
        val src = rootFs.defaultSource()
        val desc = buildString {
            append("Installs Manjaro into the app's private storage and runs it with proot: no root needed.\n\n")
            append("• rootfs: ${if (app.prefs.rootfsUrl.isNotBlank()) app.prefs.rootfsUrl else src.label}\n")
            if (desktop) append("• then sway, waybar, foot, rofi, mako… and manjaro-sway-settings from release ${BuildConfig.MANJARO_SWAY_TAG}\n")
            if (mode == "omarchy") append("• then Omarchy (basecamp/omarchy ${omarchyRef().take(7)}): its commands, menu, themes and configs, plus the Omarchy packages Manjaro has for this CPU. Bootloader, plymouth, sddm and hardware services are skipped: they cannot work under proot\n")
            append("• firmware and kernel modules are not unpacked (useless under proot)\n")
            append("• needs about ${when (mode) { "omarchy" -> "4"; "sway" -> "2"; else -> "1" }} GB of free space and a good connection\n")
            if (rootFs.isExtracted) append("\nA rootfs is already present: setup runs again on top of it (repair).")
        }
        root.addView(Ui.text(this, desc, 14f).apply { setPadding(0, Ui.dp(this@InstallActivity, 12), 0, Ui.dp(this@InstallActivity, 12)) })

        val skipFw = CheckBox(this).apply {
            text = "Skip firmware & kernel modules (recommended)"
            isChecked = app.prefs.skipFirmwareAndModules
            setTextColor(theme.text); typeface = Ui.mono
            buttonTintList = android.content.res.ColorStateList.valueOf(theme.accent)
            setOnCheckedChangeListener { _, on -> app.prefs.skipFirmwareAndModules = on }
        }
        root.addView(skipFw)
        val fresh = CheckBox(this).apply {
            text = "Download a fresh rootfs (erases the current one)"
            isChecked = false
            visibility = if (rootFs.isExtracted) View.VISIBLE else View.GONE
            setTextColor(theme.text); typeface = Ui.mono
            buttonTintList = android.content.res.ColorStateList.valueOf(theme.accent)
        }
        root.addView(fresh)

        step = Ui.text(this, "", 15f, theme.text, bold = true).apply { setPadding(0, Ui.dp(this@InstallActivity, 16), 0, Ui.dp(this@InstallActivity, 6)) }
        root.addView(step)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(theme.accent)
            visibility = View.INVISIBLE
        }
        root.addView(progress, Ui.matchWrap())
        logScroll = ScrollView(this)
        log = Ui.text(this, "", 11f, theme.subtext)
        logScroll.addView(log)
        root.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = Ui.dp(this@InstallActivity, 8) })

        val buttons = Ui.horizontal(this)
        val cancel = Ui.button(this, "Cancel") { installer?.cancelled = true; if (!running) finish() }
        val start = Ui.button(this, "Install", primary = true) {}
        start.setOnClickListener {
            if (running) return@setOnClickListener
            running = true
            start.alpha = 0.4f
            skipFw.isEnabled = false; fresh.isEnabled = false
            begin(fresh.isChecked)
        }
        buttons.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(View(this), LinearLayout.LayoutParams(Ui.dp(this, 12), 1))
        buttons.addView(start, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons, Ui.matchWrap())
        setContentView(root)

        if (app.prefs.bindStorage && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    private fun omarchyRef(): String = try { assets.open("omarchy/REF").bufferedReader().use { it.readText().trim() } } catch (_: Exception) { "HEAD" }

    private fun begin(fresh: Boolean) {
        val inst = Installer(this, this)
        installer = inst
        Thread {
            try {
                inst.prepare(mode, fresh)
                main.post {
                    onStep("Running setup.sh in a terminal…")
                    TerminalActivity.start(this, "Manjaro setup", inst.setupCommand(mode), setupMode = mode)
                    finish()
                }
            } catch (e: Exception) {
                main.post {
                    running = false
                    onStep("Failed: ${e.message}")
                    onLog(e.toString())
                }
            }
        }.start()
    }

    override fun onStep(text: String) {
        main.post {
            step.text = "==> $text"
            appendLog("==> $text")
        }
    }

    override fun onProgress(done: Long, total: Long) {
        main.post {
            progress.visibility = View.VISIBLE
            if (total > 0) {
                progress.isIndeterminate = false
                progress.progress = (done * 1000 / total).toInt()
                step.text = step.text.toString().substringBefore("  [") + "  [${done / 1048576} / ${total / 1048576} MB]"
            } else {
                progress.isIndeterminate = true
            }
        }
    }

    override fun onLog(line: String) { main.post { appendLog(line) } }

    private fun appendLog(line: String) {
        log.append(line + "\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        installer?.cancelled = true
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_MODE = "mode"

        fun start(ctx: Context, mode: String) {
            ctx.startActivity(Intent(ctx, InstallActivity::class.java).putExtra(EXTRA_MODE, mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
