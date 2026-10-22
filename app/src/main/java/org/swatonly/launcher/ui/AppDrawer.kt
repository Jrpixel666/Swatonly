package org.swatonly.launcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.swatonly.launcher.App
import org.swatonly.launcher.apps.AppEntry
import org.swatonly.launcher.apps.AppRepository
import org.swatonly.launcher.apps.IconLoader
import org.swatonly.launcher.apps.MenuCategory
import org.swatonly.launcher.term.TerminalActivity

/**
 * "My apps": a rofi-styled menu (manjaro-sway's $menu is `rofi -show combi
 * -combi-modes drun,run`). Android apps and the Manjaro install's desktop
 * entries, grouped by menu category, with CLI/TUI tools in their own group
 * and a `run` line for anything typed that is not an app.
 */
@SuppressLint("ViewConstructor")
class AppDrawer(context: Context, private val onClose: () -> Unit) : LinearLayout(context) {

    private val theme get() = App.instance.themes.current()
    private val prefs get() = App.instance.prefs
    private val search = EditText(context)
    private val chips = LinearLayout(context)
    private val list = RecyclerView(context)
    private val adapter = Adapter()
    private var category = MenuCategory.ALL
    private var shown: List<AppEntry> = emptyList()
    private var runLine: String? = null

    init {
        orientation = VERTICAL
        isClickable = true
        build()
    }

    fun build() {
        removeAllViews()
        val pad = Ui.dp(context, 14)
        setPadding(pad, pad, pad, pad)
        background = Ui.rounded((theme.background and 0x00FFFFFF) or 0xF2000000.toInt(), Ui.dpf(context, 12f), Ui.dp(context, 2), theme.accent)

        // prompt row: [drun] search…
        val prompt = Ui.horizontal(context)
        val mode = Ui.text(context, "drun", 14f, theme.background, bold = true).apply {
            background = Ui.rounded(theme.accent, Ui.dpf(context, 5f))
            setPadding(Ui.dp(context, 10), Ui.dp(context, 6), Ui.dp(context, 10), Ui.dp(context, 6))
        }
        prompt.addView(mode)
        search.apply {
            hint = "search apps or type a command"
            setHintTextColor(theme.subtext)
            setTextColor(theme.text)
            typeface = Ui.mono
            textSize = 15f
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(Ui.dp(context, 12), Ui.dp(context, 6), Ui.dp(context, 6), Ui.dp(context, 6))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { filter() }
            })
            setOnEditorActionListener { _, action, ev ->
                if (action == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { launchFirst(); true } else false
            }
        }
        prompt.addView(search, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(prompt, Ui.matchWrap())

        val chipScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        chips.orientation = HORIZONTAL
        chipScroll.addView(chips)
        addView(chipScroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(context, 8) })

        list.adapter = adapter
        list.layoutManager = if (prefs.drawerGrid) GridLayoutManager(context, 4) else LinearLayoutManager(context)
        list.overScrollMode = OVER_SCROLL_NEVER
        addView(list, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = Ui.dp(context, 8) })
        refresh()
    }

    fun refresh() {
        buildChips()
        filter()
    }

    fun open() {
        search.setText("")
        category = MenuCategory.ALL
        refresh()
        list.scrollToPosition(0)
    }

    fun focusSearch() {
        search.requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
    }

    private fun visibleApps(): List<AppEntry> = AppRepository.apps.filter {
        when (it) {
            is AppEntry.Android -> prefs.showAndroidApps
            is AppEntry.Linux -> prefs.showLinuxApps && (!it.terminal || prefs.showCliApps)
            is AppEntry.Action -> true
        }
    }

    private fun buildChips() {
        chips.removeAllViews()
        val apps = visibleApps()
        val present = HashSet<String>()
        apps.forEach { present.addAll(it.categories) }
        val cats = MenuCategory.ORDER.filter {
            it == MenuCategory.ALL || (it == MenuCategory.PINNED && prefs.pinned.isNotEmpty()) || it in present
        }
        if (category !in cats) category = MenuCategory.ALL
        for (c in cats) {
            val count = when (c) {
                MenuCategory.ALL -> apps.size
                MenuCategory.PINNED -> prefs.pinned.size
                else -> apps.count { c in it.categories }
            }
            val selected = c == category
            val chip = Ui.text(context, "$c $count", 12f, if (selected) theme.background else theme.text, bold = selected).apply {
                background = Ui.pressable(context, if (selected) theme.accent else theme.surface, Ui.dpf(context, 14f))
                setPadding(Ui.dp(context, 12), Ui.dp(context, 6), Ui.dp(context, 12), Ui.dp(context, 6))
                setOnClickListener { category = c; refresh() }
            }
            chips.addView(chip, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = Ui.dp(context, 6) })
        }
    }

    private fun filter() {
        val q = search.text.toString().trim().lowercase()
        var apps = visibleApps()
        apps = when (category) {
            MenuCategory.ALL -> apps
            MenuCategory.PINNED -> prefs.pinned.mapNotNull { AppRepository.find(it) }
            else -> apps.filter { category in it.categories }
        }
        if (q.isNotEmpty()) {
            apps = apps.filter { e ->
                e.label.lowercase().contains(q) || e.description.lowercase().contains(q) ||
                    (e is AppEntry.Linux && (e.keywords.any { it.lowercase().contains(q) } || e.exec.lowercase().contains(q))) ||
                    (e is AppEntry.Android && e.component.packageName.contains(q))
            }.sortedWith(compareBy({ !it.label.lowercase().startsWith(q) }, { it.label.lowercase() }))
        }
        shown = apps
        runLine = if (q.isNotEmpty() && App.instance.rootFs.isExtracted) search.text.toString().trim() else null
        adapter.notifyDataSetChanged()
    }

    private fun launchFirst() {
        val first = shown.firstOrNull()
        if (first != null) launch(first) else runLine?.let { run(it) }
    }

    private fun launch(e: AppEntry) {
        hideKeyboard()
        AppRepository.launch(context, e)
        onClose()
    }

    private fun run(cmd: String) {
        hideKeyboard()
        TerminalActivity.start(context, cmd, listOf("/bin/bash", "-lc", "$cmd; echo; read -rp '[exit $?] press enter to close'"))
        onClose()
    }

    private fun menu(e: AppEntry) {
        val box = Ui.vertical(context)
        var dlg: android.app.AlertDialog? = null
        val pinned = e.key in prefs.pinned
        box.addView(Ui.row(context, if (pinned) "Remove from bar" else "Add to bar") {
            prefs.pinned = if (pinned) prefs.pinned - e.key else prefs.pinned + e.key
            (context as? LauncherActivity)?.refreshBar()
            dlg?.dismiss()
        })
        when (e) {
            is AppEntry.Android -> {
                box.addView(Ui.row(context, "App info") {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${e.component.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    dlg?.dismiss()
                })
                box.addView(Ui.row(context, "Uninstall") {
                    @Suppress("DEPRECATION")
                    context.startActivity(Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:${e.component.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    dlg?.dismiss()
                })
            }
            is AppEntry.Linux -> {
                box.addView(Ui.row(context, "Run in terminal", e.exec) {
                    TerminalActivity.start(context, e.label, listOf("/bin/bash", "-lc", org.swatonly.launcher.apps.DesktopEntry.shellCommand(e.exec)))
                    dlg?.dismiss()
                })
                box.addView(Ui.row(context, "Desktop entry", e.id + if (e.categories.isNotEmpty()) " · " + e.categories.joinToString(", ") else ""))
            }
            is AppEntry.Action -> {}
        }
        dlg = Ui.dialog(context, e.label, box)
        dlg.show()
    }

    private inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewWithTag("icon")
        val title: TextView = v.findViewWithTag("title")
        val sub: TextView? = v.findViewWithTag("sub")
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = shown.size + if (runLine != null) 1 else 0

        override fun getItemViewType(position: Int) = if (prefs.drawerGrid) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val icon = ImageView(ctx).apply { tag = "icon" }
            val title = Ui.text(ctx, "", if (viewType == 1) 12f else 15f).apply { tag = "title"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            val v: LinearLayout
            if (viewType == 1) {
                v = Ui.vertical(ctx).apply { gravity = Gravity.CENTER_HORIZONTAL }
                v.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 10), Ui.dp(ctx, 4), Ui.dp(ctx, 10))
                v.addView(icon, LayoutParams(Ui.dp(ctx, 48), Ui.dp(ctx, 48)))
                title.gravity = Gravity.CENTER
                v.addView(title, Ui.matchWrap())
            } else {
                v = Ui.horizontal(ctx)
                v.setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 7), Ui.dp(ctx, 8), Ui.dp(ctx, 7))
                v.addView(icon, LayoutParams(Ui.dp(ctx, 36), Ui.dp(ctx, 36)))
                val texts = Ui.vertical(ctx).apply { setPadding(Ui.dp(ctx, 12), 0, 0, 0) }
                texts.addView(title)
                texts.addView(Ui.text(ctx, "", 12f, theme.subtext).apply { tag = "sub"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                v.addView(texts, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            v.background = Ui.pressable(ctx)
            return Holder(v)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            if (position >= shown.size) {
                val cmd = runLine ?: return
                h.title.text = "run: $cmd"
                h.sub?.text = "run the command in a Manjaro terminal"
                h.icon.setImageBitmap(IconLoader.letterBitmap(">_", Ui.dp(context, 36)))
                h.itemView.setOnClickListener { run(cmd) }
                h.itemView.setOnLongClickListener(null)
                return
            }
            val e = shown[position]
            h.title.text = e.label
            h.sub?.text = when (e) {
                is AppEntry.Android -> "android · ${e.component.packageName}"
                is AppEntry.Linux -> (if (e.terminal) "tui · " else "") + e.description.ifEmpty { e.exec }
                is AppEntry.Action -> e.description
            }
            IconLoader.load(context, e, Ui.dp(context, if (prefs.drawerGrid) 48 else 36), h.icon)
            h.itemView.setOnClickListener { launch(e) }
            h.itemView.setOnLongClickListener { menu(e); true }
        }
    }
}
