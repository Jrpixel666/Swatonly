package org.swatonly.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.swatonly.launcher.App
import org.swatonly.launcher.apps.AppRepository
import org.swatonly.launcher.linux.LinuxService
import org.swatonly.launcher.linux.Proot
import org.swatonly.launcher.linux.Session
import org.swatonly.launcher.menu.FontList
import org.swatonly.launcher.menu.MenuModel
import org.swatonly.launcher.term.TerminalActivity

/**
 * The system menu, drawn like the install's own (Omarchy's walker menu or
 * manjaro-sway's rofi): a breadcrumb, a filter, and one level of the tree.
 * Typing searches the whole tree.
 */
class SystemMenu(private val activity: Activity, private val onApps: () -> Unit) {

    private val theme get() = App.instance.themes.current()
    private val model = MenuModel.load(activity)
    private val states = HashMap<String, MenuModel.State>()
    private val evaluated = HashSet<String>()
    private var route = ""
    private var dialog: AlertDialog? = null
    private lateinit var crumb: TextView
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var filter: EditText
    private val iconFont: Typeface? = if (App.instance.prefs.menuShowIcons) MenuModel.iconTypeface() else null

    fun show(start: String = "") {
        val ctx = activity
        val box = Ui.vertical(ctx)
        val head = Ui.horizontal(ctx)
        head.setPadding(Ui.dp(ctx, 8), 0, Ui.dp(ctx, 12), Ui.dp(ctx, 6))
        head.addView(Ui.text(ctx, "‹", 22f, theme.accent, bold = true).apply {
            setPadding(Ui.dp(ctx, 8), 0, Ui.dp(ctx, 10), 0)
            background = Ui.pressable(ctx)
            setOnClickListener { up() }
        })
        crumb = Ui.text(ctx, "", 13f, theme.subtext).apply { maxLines = 2 }
        head.addView(crumb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Ui.text(ctx, if (model.source == "omarchy") "omarchy" else "manjaro-sway", 11f, theme.accent))
        box.addView(head, Ui.matchWrap())

        filter = EditText(ctx).apply {
            hint = "search"
            setHintTextColor(theme.subtext)
            setTextColor(theme.text)
            typeface = Ui.mono
            isSingleLine = true
            background = Ui.rounded(theme.surface, Ui.dpf(ctx, 6f))
            setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 8))
        }
        box.addView(filter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(Ui.dp(ctx, 12), 0, Ui.dp(ctx, 12), Ui.dp(ctx, 6))
        })
        scroll = ScrollView(ctx)
        list = Ui.vertical(ctx)
        scroll.addView(list)
        box.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 420)))
        filter.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { render() }
        })

        val d = Ui.dialog(ctx, if (model.source == "omarchy") "Omarchy menu" else "Manjaro Sway menu", box, scroll = false)
        d.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && route.isNotEmpty()) { up(); true } else false
        }
        dialog = d
        d.show()
        open(start.takeIf { model.item(it) != null || it.isEmpty() } ?: "")
    }

    private fun up() {
        if (filter.text.isNotEmpty()) { filter.setText(""); return }
        if (route.isEmpty()) { dialog?.dismiss(); return }
        open(route.substringBeforeLast('.', ""))
    }

    private fun open(id: String) {
        route = id
        crumb.text = if (id.isEmpty()) "/" else model.breadcrumb(id)
        render()
        val children = model.children(id)
        if (id !in evaluated && children.any { it.whenCond != null || it.checkedCond != null || it.disabledCond != null }) {
            evaluated += id
            Thread {
                val r = model.evaluate(activity, children)
                activity.runOnUiThread {
                    states.putAll(r)
                    if (route == id) render()
                }
            }.start()
        }
    }

    private fun render() {
        list.removeAllViews()
        scroll.scrollTo(0, 0)
        val q = filter.text.toString().trim()
        val entries = if (q.isEmpty()) model.children(route)
        else model.all().filter { !it.isSubmenu && (it.label.contains(q, true) || it.id.contains(q, true)) }.take(80)
        var shown = 0
        var waiting = false
        for (e in entries) {
            val st = states[e.id]
            // in a submenu, conditional rows wait for their condition so on/off pairs never both show;
            // search results show them unless the condition is known to fail
            if (e.whenCond != null && st == null && q.isEmpty()) { waiting = true; continue }
            if (st != null && !st.visible) continue
            list.addView(row(e, st, if (q.isNotEmpty()) model.breadcrumb(e.parent) else null))
            shown++
        }
        val note = when {
            waiting -> "…checking the install"
            shown == 0 -> "nothing here"
            else -> null
        }
        if (note != null) list.addView(Ui.text(activity, "  $note", 12f, theme.subtext).apply {
            setPadding(Ui.dp(activity, 16), Ui.dp(activity, 8), 0, Ui.dp(activity, 8))
        })
    }

    private fun row(e: MenuModel.Item, st: MenuModel.State?, context: String?): View {
        val ctx = activity
        val row = Ui.horizontal(ctx)
        row.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 10), Ui.dp(ctx, 14), Ui.dp(ctx, 10))
        val disabled = st?.disabled == true
        val iconView = TextView(ctx).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(theme.accent)
            if (iconFont != null && e.icon.isNotEmpty()) { typeface = iconFont; text = e.icon }
            else { typeface = Ui.monoBold; text = if (e.isSubmenu) "▸" else "•" }
        }
        row.addView(iconView, LinearLayout.LayoutParams(Ui.dp(ctx, 28), ViewGroup.LayoutParams.WRAP_CONTENT))
        val texts = Ui.vertical(ctx)
        texts.addView(Ui.text(ctx, e.label + if (st?.checked == true || disabled) "  ✓" else "", 15f, if (disabled) theme.subtext else theme.text))
        val sub = listOfNotNull(e.hint, context?.ifEmpty { null }).joinToString("  ·  ")
        if (sub.isNotEmpty()) texts.addView(Ui.text(ctx, sub, 11f, theme.subtext))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = Ui.dp(ctx, 8) })
        if (e.isSubmenu || e.provider != null) row.addView(Ui.text(ctx, "›", 16f, theme.subtext))
        if (!disabled) {
            row.background = Ui.pressable(ctx, radius = 0f)
            row.setOnClickListener { pick(e) }
        } else row.alpha = 0.6f
        return row
    }

    private fun pick(e: MenuModel.Item) {
        when {
            e.provider == "apps" -> { dialog?.dismiss(); onApps() }
            e.provider == "fonts" -> fonts(e)
            e.provider != null -> run(e.label, e.action ?: "omarchy-menu summon ${e.id}", terminal = false)
            e.launcher != null -> { dialog?.dismiss(); launcher(activity, e.launcher, onApps) }
            e.action != null -> { dialog?.dismiss(); run(e.label, e.action, e.terminal) }
            else -> { filter.setText(""); open(e.id) }
        }
    }

    private fun fonts(e: MenuModel.Item) {
        route = e.id
        crumb.text = model.breadcrumb(e.id)
        list.removeAllViews()
        list.addView(Ui.text(activity, "  …listing fonts", 12f, theme.subtext).apply { setPadding(Ui.dp(activity, 16), Ui.dp(activity, 8), 0, Ui.dp(activity, 8)) })
        Thread {
            val fonts = FontList.monospace(activity)
            activity.runOnUiThread {
                if (route != e.id) return@runOnUiThread
                list.removeAllViews()
                if (fonts.isEmpty()) list.addView(Ui.row(activity, "No fonts found", "install Omarchy or a font package first"))
                for (f in fonts) list.addView(Ui.row(activity, f) { dialog?.dismiss(); run("Font $f", "omarchy-font-set ${Proot.quote(f)}", terminal = false) })
            }
        }.start()
    }

    private fun run(label: String, action: String, terminal: Boolean) = Companion.run(activity, label, action, terminal)

    companion object {
        /** Runs a menu action: in the terminal, inside the desktop session, or detached. */
        fun run(ctx: Context, label: String, action: String, terminal: Boolean) {
            val app = App.instance
            val rootFs = app.rootFs
            if (!rootFs.isExtracted) {
                Toast.makeText(ctx, "Install Linux first: Settings › Run setup choice", Toast.LENGTH_LONG).show()
                return
            }
            when {
                terminal -> TerminalActivity.start(ctx, label, listOf("/bin/bash", "-lc", "$action\necho; read -rp 'Press enter to close'"))
                rootFs.swayInstalled -> {
                    Toast.makeText(ctx, label, Toast.LENGTH_SHORT).show()
                    Session.runInDesktop(ctx, label, action)
                }
                else -> Session.launch(ctx, label, action)
            }
        }

        /** Swatonly's own menu targets ("launcher" field). */
        fun launcher(ctx: Context, target: String, onApps: () -> Unit) {
            when {
                target == "apps" -> onApps()
                target == "terminal" -> AppRepository.runAction(ctx, "terminal")
                target == "setup" -> ctx.startActivity(Intent(ctx, LauncherActivity::class.java)
                    .putExtra(LauncherActivity.EXTRA_SETUP_CHOICE, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                target == "stop" -> { Session.stopAll(); LinuxService.running?.stopSelf() }
                target.startsWith("install:") -> InstallActivity.start(ctx, target.removePrefix("install:"))
                target.startsWith("settings:") -> ctx.startActivity(
                    (if (target == "settings:main") Intent(ctx, SettingsActivity::class.java) else SettingsActivity.intent(ctx, target.removePrefix("settings:")))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
