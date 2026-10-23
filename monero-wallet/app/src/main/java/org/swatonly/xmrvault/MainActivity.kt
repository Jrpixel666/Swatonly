package org.swatonly.xmrvault

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : Activity() {
    /** Every native call runs here, one at a time. */
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var guard: Guard

    /** Bumped on every lock; work started under an older value is discarded. */
    @Volatile private var epoch = 0
    @Volatile private var syncing = false
    private var unlocked = false
    private var back: (() -> Unit)? = null
    private var copiedAt = 0L

    private val autoLock = Runnable { if (unlocked) lock("Locked after inactivity") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, no screen recording, blank in the recent-apps switcher.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        guard = Guard(this)
        io.execute { Native.init(noBackupFilesDir.absolutePath) }
        start()
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app locks it. Keys never sit in memory in the background.
        if (unlocked) lock(null)
        clearClipboard()
    }

    override fun onDestroy() {
        lock(null)
        io.shutdown()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (unlocked) {
            ui.removeCallbacks(autoLock)
            ui.postDelayed(autoLock, guard.autoLockSeconds * 1000L)
        }
        return super.dispatchTouchEvent(ev)
    }

    @Deprecated("Activity back handling")
    override fun onBackPressed() {
        val b = back
        if (b != null) b() else moveTaskToBack(true)
    }

    // ---- plumbing ----------------------------------------------------------------------------

    /** Run [work] on the native thread, then [done] on the UI thread unless the wallet locked. */
    private fun <T> bg(work: () -> T, done: (T) -> Unit, failed: (Throwable) -> Unit = { toast(it.message ?: "Error") }) {
        val e = epoch
        io.execute {
            val r = try { Result.success(work()) } catch (t: Throwable) { Result.failure(t) }
            ui.post {
                if (e != epoch || isFinishing) return@post
                r.fold(done, failed)
            }
        }
    }

    private fun show(v: View, onBack: (() -> Unit)?) {
        back = onBack
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.addView(v)
        scroll.filterTouchesWhenObscured = true
        scroll.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setContentView(scroll)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun busy(title: String, msg: String) {
        val c = column()
        c.title(title)
        c.text(msg, color = DIM)
        show(c, null)
    }

    private fun warnings(c: LinearLayout) {
        for (p in Integrity.problems(this)) c.text("⚠ $p", 13f, WARN)
    }

    /** Load the pepper, hand it to [f], zero it (and the PIN) whatever happens. */
    private fun <T> withPepper(pin: ByteArray, f: (ByteArray) -> T): T {
        val pepper = KeyGuard.load(this)
        try {
            return f(pepper)
        } finally {
            pepper.fill(0)
            pin.fill(0)
        }
    }

    private fun copy(label: String, text: String) {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
        }
        cm.setPrimaryClip(clip)
        copiedAt = System.currentTimeMillis()
        toast("Copied. The clipboard is cleared in 60 s.")
        ui.postDelayed({ clearClipboard() }, 60_000)
    }

    private fun clearClipboard() {
        if (copiedAt == 0L) return
        copiedAt = 0L
        try { getSystemService(ClipboardManager::class.java)?.clearPrimaryClip() } catch (_: Exception) {}
    }

    private fun lock(msg: String?) {
        epoch++
        syncing = false
        unlocked = false
        ui.removeCallbacks(autoLock)
        io.execute { Native.lock() }
        if (msg != null || !isFinishing) showLock(msg)
    }

    private fun wipeEverything(then: () -> Unit) {
        epoch++
        syncing = false
        unlocked = false
        clearClipboard()
        io.execute {
            try { Native.wipe() } catch (_: Throwable) {}
            KeyGuard.destroy(this)
            ui.post {
                guard.reset()
                then()
            }
        }
    }

    // ---- start / create / restore ------------------------------------------------------------

    private fun start() {
        busy("XMR Vault", "Starting…")
        bg({ Native.exists() }, { exists ->
            when {
                !exists -> welcome()
                !KeyGuard.exists(this) -> broken()
                else -> showLock(null)
            }
        })
    }

    private fun broken() {
        val c = column()
        c.title("Device key missing")
        c.text("The wallet file is here but the hardware key that opens it is gone (the app's keys were reset). It can't be opened. Restore from your seed.")
        c.button("Erase and start over") { wipeEverything { welcome() } }
        show(c, null)
    }

    private fun welcome() {
        val c = column()
        c.title("XMR Vault")
        c.text("A Monero wallet that keeps your keys on this phone, encrypted with your PIN and the phone's secure hardware. Blocks are scanned on the device, so the node never learns your addresses or balance.", color = DIM)
        warnings(c)
        c.spacer()
        c.text("Network", bold = true)
        val net = RadioGroup(this)
        listOf("Mainnet", "Stagenet (test coins)").forEachIndexed { i, s ->
            net.addView(RadioButton(this).apply { text = s; id = 100 + i; isChecked = i == 0 })
        }
        c.addView(net)
        c.text("New seed type", bold = true)
        val kind = RadioGroup(this)
        listOf("Polyseed, 16 words (recommended: includes the wallet date)", "Classic 25-word Monero seed").forEachIndexed { i, s ->
            kind.addView(RadioButton(this).apply { text = s; id = 200 + i; isChecked = i == 0 })
        }
        c.addView(kind)
        c.spacer()
        c.button("Create a new wallet") {
            val n = net.checkedRadioButtonId - 100
            val legacy = kind.checkedRadioButtonId == 201
            choosePin({ welcome() }) { pin -> create(n, legacy, pin) }
        }
        c.button("Restore from seed") { restoreScreen(net.checkedRadioButtonId - 100) }
        show(c, null)
    }

    /** Ask for a new PIN twice. */
    private fun choosePin(onBack: () -> Unit, title: String = "Choose a PIN", done: (ByteArray) -> Unit) {
        val c = column()
        c.title(title)
        c.text("At least 6 digits; 8 or more is better. The keypad is shuffled every time.", color = DIM)
        val status = c.text("", color = WARN)
        var first: ByteArray? = null
        lateinit var pad: PinPad
        pad = PinPad(this) { pin ->
            val f = first
            when {
                f == null && pin.size < 6 -> { pin.fill(0); status.text = "Too short." }
                f == null -> { first = pin; status.text = "Enter it again."; pad.shuffle() }
                MessageDigest.isEqual(f, pin) -> { f.fill(0); done(pin) }
                else -> { f.fill(0); pin.fill(0); first = null; status.text = "They didn't match. Start again."; pad.shuffle() }
            }
        }
        c.addView(pad)
        show(c) { first?.fill(0); onBack() }
    }

    private fun create(net: Int, legacy: Boolean, pin: ByteArray) {
        busy("Creating wallet", "Generating keys and sealing them. This takes a few seconds.")
        bg({
            val pepper = KeyGuard.create(this)
            try { Native.create(pin, pepper, net, legacy) } catch (t: Throwable) { KeyGuard.destroy(this); throw t } finally { pepper.fill(0); pin.fill(0) }
        }, { seed ->
            guard.network = net
            guard.succeed()
            unlocked = true
            showSeed(seed, verify = true)
        }, { toast(it.message ?: "Failed"); welcome() })
    }

    private fun restoreScreen(net: Int) {
        val c = column()
        c.title("Restore")
        c.text("Type your 16-word Polyseed or 25-word seed. Check that your keyboard is not a cloud keyboard.", color = DIM)
        val seed = c.field("seed words", lines = 4)
        c.text("Restore height (optional). Polyseeds carry their own date. For a 25-word seed, a block height from before the wallet's first transaction saves hours of scanning.", 13f, DIM)
        val height = c.field("e.g. 3100000", InputType.TYPE_CLASS_NUMBER)
        c.button("Continue") {
            val h = height.text.toString().toLongOrNull() ?: -1L
            val phrase = seed.text!!.takeBytes()
            choosePin({ phrase.fill(0); restoreScreen(net) }) { pin -> restore(net, phrase, h, pin) }
        }
        show(c) { welcome() }
    }

    private fun restore(net: Int, phrase: ByteArray, height: Long, pin: ByteArray) {
        busy("Restoring", "Checking the seed and sealing the keys…")
        bg({
            val pepper = KeyGuard.create(this)
            try { Native.restore(pin, pepper, net, phrase, height) } catch (t: Throwable) { KeyGuard.destroy(this); throw t } finally { pepper.fill(0); pin.fill(0); phrase.fill(0) }
        }, {
            guard.network = net
            guard.succeed()
            unlocked = true
            home()
        }, { toast(it.message ?: "Failed"); restoreScreen(net) })
    }

    /** Show the seed. Words are drawn individually and not selectable or copyable. */
    private fun showSeed(seed: ByteArray, verify: Boolean) {
        val words = String(seed, Charsets.UTF_8).split(' ')
        seed.fill(0)
        val c = column()
        c.title("Your seed")
        c.text("Write these ${words.size} words on paper, in order. Never photograph them, never type them anywhere else. Whoever has them owns your coins; without them a lost phone means lost coins.", color = WARN)
        val grid = GridLayout(this)
        grid.columnCount = 2
        sensitive(grid)
        words.forEachIndexed { i, w ->
            val t = TextView(this)
            t.text = "${i + 1}. $w"
            t.textSize = 18f
            t.setTextIsSelectable(false)
            t.setPadding(dp(4), dp(8), dp(24), dp(8))
            grid.addView(t)
        }
        c.addView(grid)
        c.button(if (verify) "I wrote them down" else "Done") {
            grid.removeAllViews()
            if (verify) verifySeed(words) else settings()
        }
        show(c) { grid.removeAllViews(); if (verify) home() else settings() }
    }

    private fun verifySeed(words: List<String>) {
        val rnd = SecureRandom()
        val picks = words.indices.shuffled(rnd).take(3).sorted()
        val c = column()
        c.title("Check your copy")
        c.text("Type these words from your paper copy.", color = DIM)
        val fields = picks.map { c.field("word #${it + 1}") }
        c.button("Check") {
            val ok = picks.zip(fields).all { (i, f) -> f.text.toString().trim().equals(words[i], ignoreCase = true) }
            fields.forEach { it.text.clear() }
            if (ok) home() else { toast("That doesn't match. Look at the seed again."); seedAgain() }
        }
        c.button("Skip (not recommended)") { home() }
        show(c) { home() }
    }

    // ---- lock screen -------------------------------------------------------------------------

    private fun showLock(msg: String?) {
        val c = column()
        c.title("XMR Vault")
        if (msg != null) c.text(msg, color = DIM)
        warnings(c)
        val status = c.text("", color = WARN)
        fun refresh() {
            val wait = guard.waitMillis()
            status.text = when {
                wait > 0 -> "Too many wrong PINs. Try again in ${(wait + 999) / 1000} s."
                guard.failures > 0 -> "${guard.failures} wrong. The wallet is erased after ${guard.wipeAfter}."
                else -> ""
            }
            if (wait > 0) ui.postDelayed({ if (!unlocked) refresh() }, 1000)
        }
        refresh()
        lateinit var pad: PinPad
        pad = PinPad(this) { pin ->
            if (guard.waitMillis() > 0) { pin.fill(0); refresh(); return@PinPad }
            status.text = "Unlocking…"
            pad.isEnabled = false
            bg({ withPepper(pin) { Native.unlock(pin, it) } }, { r ->
                when (r) {
                    Native.UNLOCKED -> { guard.succeed(); unlocked = true; home() }
                    // Duress: the wallet file is already gone. Finish the erase quietly and look
                    // like a fresh install.
                    Native.DURESS -> wipeEverything { welcome() }
                    else -> if (guard.fail()) {
                        wipeEverything { toast("Too many wrong PINs. The wallet was erased."); welcome() }
                    } else { pad.shuffle(); refresh() }
                }
            }, { status.text = it.message ?: "Error" })
        }
        c.addView(pad)
        show(c, null)
    }

    // ---- home --------------------------------------------------------------------------------

    private fun home() {
        ui.removeCallbacks(autoLock)
        ui.postDelayed(autoLock, guard.autoLockSeconds * 1000L)
        val c = column()
        val net = c.text("", 13f, WARN)
        val balance = c.text("…", 34f, bold = true)
        val unlockedLine = c.text("", color = DIM)
        val sync = c.text("", 13f, DIM)
        c.spacer()
        c.button("Receive") { receive() }
        c.button("Send") { send() }
        c.button("History") { history() }
        c.button("Settings") { settings() }
        c.button("Lock") { lock("Locked") }
        show(c, null)

        fun render(s: JSONObject) {
            net.text = if (s.getString("net") != "Mainnet") "${s.getString("net").uppercase()}: test coins only" else ""
            balance.text = "${xmr(s.getString("balance"))} XMR"
            unlockedLine.text = "Spendable now: ${xmr(s.getString("unlocked_balance"))} XMR"
            if (!s.getBoolean("node_set")) sync.text = "No node set. Add one in Settings to sync."
        }
        bg({ JSONObject(Native.status()) }, { s ->
            render(s)
            if (s.getBoolean("node_set") && !syncing) startSync(sync, ::render)
        })
    }

    private fun startSync(line: TextView, render: (JSONObject) -> Unit) {
        syncing = true
        val e = epoch
        line.text = "Connecting to node…"
        fun step() {
            if (!syncing || e != epoch) return
            bg({ JSONObject(Native.syncStep()) to JSONObject(Native.status()) }, { (p, s) ->
                val tip = p.getLong("tip")
                val h = p.getLong("height")
                line.text = if (p.getBoolean("done")) "Synced at block $tip" else "Scanning block $h of $tip (${tip - h} left)"
                if (line.isAttachedToWindow) render(s)
                if (p.getBoolean("done")) {
                    syncing = false
                    // Look for new blocks every 2 minutes while the screen is open.
                    ui.postDelayed({ if (line.isAttachedToWindow && e == epoch && !syncing) startSync(line, render) }, 120_000)
                } else step()
            }, {
                syncing = false
                line.text = "Sync failed: ${it.message}. Retrying in 30 s."
                ui.postDelayed({ if (line.isAttachedToWindow && e == epoch && !syncing) startSync(line, render) }, 30_000)
            })
        }
        step()
    }

    // ---- receive -----------------------------------------------------------------------------

    private fun receive(index: Int? = null) {
        bg({
            val s = JSONObject(Native.status())
            val i = index ?: s.getInt("subaddresses")
            i to Native.address(i)
        }, { (i, address) ->
            val c = column()
            c.title("Receive")
            c.text(if (i == 0) "Primary address" else "Subaddress #$i", color = DIM)
            c.addView(qrView(this, Native.qr("monero:$address")))
            c.text(grouped(address), 15f, mono = true)
            c.button("Copy address") { copy("Monero address", address) }
            c.button("New subaddress") {
                bg({ JSONObject(Native.newSubaddress()) }, { receive(it.getInt("index")) })
            }
            c.text("Give each payer a different subaddress: payments to them can't be linked to each other by the payers.", 13f, DIM)
            show(c) { home() }
        })
    }

    // ---- send --------------------------------------------------------------------------------

    private fun send() {
        val c = column()
        c.title("Send")
        val to = c.field("recipient address", lines = 3)
        val amount = c.field("amount in XMR", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        c.text("Fee priority", bold = true)
        val prio = RadioGroup(this)
        listOf("Low", "Normal", "High", "Urgent").forEachIndexed { i, s ->
            prio.addView(RadioButton(this).apply { text = s; id = 300 + i; isChecked = i == 1 })
        }
        c.addView(prio)
        c.text("Malware can swap a copied address for its own. Always compare the address on the next screen with the one the recipient gave you.", 13f, WARN)
        c.button("Review") {
            val address = to.text.toString().filterNot { it.isWhitespace() }
            val atomic = parseXmr(amount.text.toString())
            if (atomic == null) { toast("Enter an amount (up to 12 decimals)."); return@button }
            busy("Preparing", "Choosing inputs and decoys, building the proofs…")
            bg({
                if (!Native.validateAddress(address)) throw IllegalArgumentException("That is not a valid address for this network.")
                JSONObject(Native.prepareSend(address, atomic, prio.checkedRadioButtonId - 300))
            }, { review(it) }, { toast(it.message ?: "Failed"); send() })
        }
        show(c) { home() }
    }

    private fun review(p: JSONObject) {
        val amount = p.getLong("amount")
        val fee = p.getLong("fee")
        val dest = p.getString("destination")
        val c = column()
        c.title("Confirm")
        c.text("To", bold = true)
        c.text(grouped(dest), 15f, mono = true)
        c.text("Starts ${dest.take(6)} · ends ${dest.takeLast(6)}", 13f, ORANGE)
        c.text("Amount: ${xmr(amount.toULong().toString())} XMR", 18f, bold = true)
        c.text("Network fee: ${xmr(fee.toString())} XMR", color = DIM)
        c.text("Total: ${xmr((amount.toULong() + fee.toULong()).toString())} XMR", bold = true)
        if (fee * 20 > amount) c.text("The fee is more than 5% of the amount.", color = WARN)
        c.text("Enter your PIN to sign and send. This can't be undone.", color = DIM)
        val status = c.text("", color = WARN)
        lateinit var pad: PinPad
        pad = PinPad(this) { pin ->
            status.text = "Sending…"
            bg({ withPepper(pin) { Native.confirmSend(pin, it) } }, { txid ->
                guard.succeed()
                sent(txid)
            }, { t ->
                if (t is SecurityException) {
                    if (guard.fail()) wipeEverything { toast("Too many wrong PINs. The wallet was erased."); welcome() }
                    else { status.text = "Wrong PIN (${guard.failures}/${guard.wipeAfter})."; pad.shuffle() }
                } else { toast(t.message ?: "Failed"); home() }
            })
        }
        c.addView(pad)
        c.button("Cancel") { io.execute { Native.cancelSend() }; home() }
        show(c) { io.execute { Native.cancelSend() }; home() }
    }

    private fun sent(txid: String) {
        val c = column()
        c.title("Sent")
        c.text("The transaction was broadcast. It confirms in the next block or two; the coins it spent are reserved until then.")
        c.text(txid, 13f, DIM, mono = true)
        c.button("Copy transaction ID") { copy("Transaction ID", txid) }
        c.button("Done") { home() }
        show(c) { home() }
    }

    // ---- history -----------------------------------------------------------------------------

    private fun history() {
        bg({ JSONArray(Native.history()) }, { items ->
            val c = column()
            c.title("History")
            if (items.length() == 0) c.text("Nothing yet.", color = DIM)
            val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            for (i in 0 until items.length()) {
                val t = items.getJSONObject(i)
                val net = t.getString("net")
                val incoming = !net.startsWith("-")
                val when_ = if (t.isNull("height")) "pending" else "${t.getLong("confirmations")} confirmations"
                c.text("${if (incoming) "+" else ""}${xmr(net)} XMR", 18f, if (incoming) 0xFF81C784.toInt() else BAD, bold = true)
                c.text("${fmt.format(Date(t.getLong("timestamp") * 1000))} · $when_", 13f, DIM)
                if (!t.isNull("destination")) c.text("to ${t.getString("destination").take(12)}…", 13f, DIM)
                val txid = t.getString("txid")
                c.text(txid.take(16) + "…", 12f, DIM, mono = true).setOnClickListener { copy("Transaction ID", txid) }
                c.spacer(8)
            }
            show(c) { home() }
        })
    }

    // ---- settings ----------------------------------------------------------------------------

    private fun settings() {
        bg({ JSONObject(Native.node()) to JSONObject(Native.status()) }, { (node, status) ->
            val c = column()
            c.title("Settings")

            c.text("Node", 18f, ORANGE, bold = true)
            c.text("Your own node is best. Otherwise use Tor: install Orbot and keep the proxy at 127.0.0.1:9050. Plain http is only allowed for .onion and local-network nodes.", 13f, DIM)
            val url = c.field("https://node.example:18089 or http://….onion:18081", value = node.optString("url"))
            val proxy = c.field("Tor SOCKS proxy (empty = direct)", value = node.optString("tor_proxy", "").ifEmpty { if (node.optString("url").isEmpty()) "127.0.0.1:9050" else "" })
            val torOnly = CheckBox(this).apply { text = "Tor only: never connect without Tor"; isChecked = node.optBoolean("tor_only", node.optString("url").isEmpty()) }
            c.addView(torOnly)
            val pin = c.field("Certificate SHA-256 pin (optional, for self-signed nodes)", value = node.optString("cert_sha256"))
            val nodeStatus = c.text("", 13f, DIM)
            c.button("Save and test node") {
                val cfg = JSONObject()
                    .put("url", url.text.toString().trim())
                    .put("tor_proxy", proxy.text.toString().trim())
                    .put("tor_only", torOnly.isChecked)
                    .put("cert_sha256", pin.text.toString().trim())
                nodeStatus.text = "Connecting…"
                bg({ Native.setNode(cfg.toString()); Native.testNode() }, { h ->
                    nodeStatus.text = "OK: node at block $h"
                }, { nodeStatus.text = "Failed: ${it.message}" })
            }

            c.spacer()
            c.text("Security", 18f, ORANGE, bold = true)
            c.text("Device key: ${KeyGuard.securityLevel()}", 13f, DIM)
            c.button("Show seed") { askPin("Show seed") { p -> bg({ withPepper(p) { Native.revealSeed(p, it) } }, { showSeed(it, false) }, ::pinFailed) } }
            c.button("Change PIN") { changePin() }
            val duress = status.getBoolean("duress_set")
            c.button(if (duress) "Remove duress PIN" else "Set a duress PIN") { duressPin(duress) }
            c.text("A duress PIN looks like a normal unlock attempt but silently erases the wallet. Restore from your seed afterwards.", 12f, DIM)
            val lockBtn = c.button("Auto-lock after ${guard.autoLockSeconds} s") {}
            lockBtn.setOnClickListener {
                val opts = listOf(30, 60, 120, 300)
                guard.autoLockSeconds = opts[(opts.indexOf(guard.autoLockSeconds) + 1) % opts.size]
                lockBtn.text = "Auto-lock after ${guard.autoLockSeconds} s"
            }
            val wipeBtn = c.button("Erase after ${guard.wipeAfter} wrong PINs") {}
            wipeBtn.setOnClickListener {
                val opts = listOf(5, 10, 20)
                guard.wipeAfter = opts[(opts.indexOf(guard.wipeAfter) + 1) % opts.size]
                wipeBtn.text = "Erase after ${guard.wipeAfter} wrong PINs"
            }

            c.spacer()
            c.text("Wallet", 18f, ORANGE, bold = true)
            c.text("Restore height ${status.getLong("restore_height")}, scanned to ${status.getLong("height")}", 13f, DIM)
            val rescanAt = c.field("rescan from height (empty = restore height)", InputType.TYPE_CLASS_NUMBER)
            c.button("Rescan") {
                val h = rescanAt.text.toString().toLongOrNull() ?: -1L
                bg({ Native.rescan(h) }, { syncing = false; home() })
            }
            c.button("Erase wallet from this phone") { confirmWipe() }
            show(c) { home() }
        })
    }

    private fun pinFailed(t: Throwable) {
        if (t is SecurityException && guard.fail()) {
            wipeEverything { toast("Too many wrong PINs. The wallet was erased."); welcome() }
        } else {
            toast(if (t is SecurityException) "Wrong PIN (${guard.failures}/${guard.wipeAfter})" else (t.message ?: "Error"))
            settings()
        }
    }

    private fun askPin(title: String, done: (ByteArray) -> Unit) {
        val c = column()
        c.title(title)
        c.text("Enter your PIN.", color = DIM)
        c.addView(PinPad(this) { done(it) })
        show(c) { settings() }
    }

    private fun changePin() = askPin("Change PIN") { old ->
        choosePin({ old.fill(0); settings() }, "New PIN") { new ->
            busy("Changing PIN", "Re-sealing the wallet…")
            bg({ withPepper(old) { pep -> try { Native.changePin(old, new, pep) } finally { new.fill(0) } } }, {
                guard.succeed(); toast("PIN changed. Any duress PIN was removed."); settings()
            }, ::pinFailed)
        }
    }

    private fun duressPin(remove: Boolean) = askPin(if (remove) "Remove duress PIN" else "Duress PIN") { pin ->
        if (remove) {
            bg({ withPepper(pin) { Native.setDuressPin(pin, null, it) } }, { toast("Duress PIN removed."); settings() }, ::pinFailed)
        } else {
            choosePin({ pin.fill(0); settings() }, "Choose the duress PIN") { duress ->
                bg({ withPepper(pin) { pep -> try { Native.setDuressPin(pin, duress, pep) } finally { duress.fill(0) } } }, {
                    toast("Duress PIN set."); settings()
                }, ::pinFailed)
            }
        }
    }

    private fun confirmWipe() {
        AlertDialog.Builder(this)
            .setTitle("Erase wallet?")
            .setMessage("The wallet and its device key are destroyed. Without your seed the coins are gone for good.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Erase") { _, _ ->
                askPin("Confirm erase") { p ->
                    bg({ withPepper(p) { Native.verifyPin(p, it) } }, { ok ->
                        if (ok) wipeEverything { welcome() } else pinFailed(SecurityException("wrong PIN"))
                    }, ::pinFailed)
                }
            }
            .show()
    }

    /** After a failed seed check, show the words again (PIN required). */
    private fun seedAgain() {
        val c = column()
        c.title("Show the seed again")
        c.text("Enter your PIN to see the words again.", color = DIM)
        c.addView(PinPad(this) { p -> bg({ withPepper(p) { Native.revealSeed(p, it) } }, { showSeed(it, true) }, ::pinFailed) })
        show(c) { home() }
    }
}
