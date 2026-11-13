package dev.uniapp.frame

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.*
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.net.URI

class MainActivity : Activity() {
    private val ink = Color.rgb(34, 33, 49)
    private val muted = Color.rgb(119, 118, 137)
    private val purple = Color.rgb(101, 83, 222)
    private val paper = Color.rgb(247, 247, 251)
    private lateinit var status: TextView
    private lateinit var projects: LinearLayout
    private lateinit var sourceName: TextView
    private lateinit var sourceHost: TextView
    private lateinit var counts: TextView
    private lateinit var refreshButton: TextView
    private lateinit var progress: ProgressBar
    private lateinit var session: LinearLayout
    private lateinit var sessionName: TextView
    private lateinit var allTab: TextView
    private lateinit var installedTab: TextView
    private lateinit var catalogs: CatalogStore
    private var catalog: JSONObject? = null
    private var address = ""
    private var working = false
    private var checkingUpdate = false
    private var announcedVersion = 0L
    private var installing: String? = null
    private var installedOnly = false
    private var details = ""
    private val names = mutableMapOf<String, String>()
    private var runner: Messenger? = null
    private var bound = false
    private val app get() = application as FrameApplication
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Int = 20) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun label(value: String, size: Float, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun space(parent: LinearLayout, height: Int) { parent.addView(Space(this), LinearLayout.LayoutParams(1, dp(height))) }
    private fun action(value: String, filled: Boolean = false, click: () -> Unit) = label(value, 14f, if (filled) Color.WHITE else purple, true).apply {
        gravity = Gravity.CENTER; minHeight = dp(48); setPadding(dp(14), dp(12), dp(14), dp(12))
        background = RippleDrawable(ColorStateList.valueOf(0x226553DE), shape(if (filled) purple else 0xFFF0EDFE.toInt(), 14), null)
        isClickable = true; isFocusable = true
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.className = Button::class.java.name
            }
        }
        setOnClickListener { click() }
    }
    private fun notifyUser(message: String, technical: String = "") {
        status.text = message; details = technical
        status.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
    }
    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            1 -> {
                val busy = message.data.getBoolean("busy")
                session.visibility = if (busy) View.VISIBLE else View.GONE
                sessionName.text = "${displayName(message.data.getString("project") ?: "")}正在运行"
            }
            2 -> { runner = null; session.visibility = View.GONE; notifyUser("正在结束应用…") }
            3 -> if (message.data.getBoolean("allowed")) {
                startActivity(Intent(this, RunnerActivity::class.java).putExtra("project", message.data.getString("project"))
                    .putExtra("digest", message.data.getString("digest")))
            } else notifyUser("先结束正在运行的应用，就可以切换了。")
        }
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            runner = Messenger(binder); send(1)
            if (status.text == "正在结束应用…") notifyUser("已结束，可以打开其他应用。")
        }
        override fun onServiceDisconnected(name: ComponentName) { runner = null; session.visibility = View.GONE }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0) or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        catalogs = CatalogStore(this)
        try { address = catalogs.savedAddress(); catalog = catalogs.cached() } catch (_: Exception) { /* Keep installed applications reachable. */ }
        val root = vertical().apply { setBackgroundColor(paper) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root)
        val content = vertical().apply { setPadding(dp(24), dp(18), dp(24), dp(24)) }
        root.addView(ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(content) }, LinearLayout.LayoutParams(-1, -1))
        val masthead = row()
        masthead.addView(ImageView(this).apply { setImageResource(R.drawable.ic_brand) }, LinearLayout.LayoutParams(dp(30), dp(30)))
        masthead.addView(label("  UNI FRAME", 13f, ink, true).apply { letterSpacing = .13f; gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        val more = action("···") { }
        more.contentDescription = "更多选项"
        more.background = shape(Color.TRANSPARENT)
        more.textSize = 25f
        more.setPadding(0, 0, 0, 0)
        more.setOnClickListener { showMenu(more) }
        masthead.addView(more, LinearLayout.LayoutParams(dp(48), dp(48)))
        content.addView(masthead)
        space(content, 27)
        content.addView(label("应用空间", 32f, ink, true))
        space(content, 10)
        counts = label("你的工具，都在这里。", 14f, muted)
        content.addView(counts)
        space(content, 26)
        val sourceCard = row().apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFEDE9FF.toInt(), 0xFFF3F0FF.toInt())).apply { cornerRadius = dp(20).toFloat() }
            setPadding(dp(16), dp(17), dp(10), dp(17))
        }
        sourceCard.addView(ImageView(this).apply { setImageResource(R.drawable.ic_library) }, LinearLayout.LayoutParams(dp(36), dp(36)))
        val sourceLabels = vertical().apply { setPadding(dp(12), 0, dp(4), 0); isClickable = true; isFocusable = true; contentDescription = "设置分发站"; setOnClickListener { editSource() } }
        sourceName = label("连接你的应用库", 15f, ink, true)
        sourceHost = label("一个网址，发现更多应用", 12f, muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        sourceLabels.addView(sourceName); space(sourceLabels, 6); sourceLabels.addView(sourceHost)
        sourceCard.addView(sourceLabels, LinearLayout.LayoutParams(0, -2, 1f))
        refreshButton = action("↻") { if (address.isBlank()) editSource() else synchronize(address) }.apply {
            textSize = 25f; setPadding(0, 0, 0, 0); contentDescription = "刷新应用库"; background = shape(Color.TRANSPARENT)
        }
        sourceCard.addView(refreshButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        sourceCard.setOnClickListener { editSource() }
        content.addView(sourceCard)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(purple); visibility = View.GONE }
        content.addView(progress, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(10) })
        status = label("", 12f, muted).apply {
            visibility = View.GONE; setPadding(dp(2), dp(12), dp(2), dp(2))
            setOnLongClickListener { if (details.isNotBlank()) showDetails(details); true }
        }
        content.addView(status)
        session = row().apply { visibility = View.GONE; setPadding(dp(14), dp(12), dp(8), dp(12)); background = shape(0xFFE8F3EE.toInt(), 16) }
        sessionName = label("", 13f, 0xFF2C6A53.toInt(), true)
        session.addView(sessionName, LinearLayout.LayoutParams(0, -2, 1f))
        session.addView(action("结束") { stopProject() }.apply { contentDescription = "结束当前应用"; background = shape(Color.TRANSPARENT) })
        content.addView(session, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        space(content, 28)
        val tabs = row()
        allTab = action("全部应用") { installedOnly = false; refresh() }
        installedTab = action("已安装") { installedOnly = true; refresh() }
        tabs.addView(allTab); tabs.addView(installedTab, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        content.addView(tabs)
        space(content, 14)
        projects = vertical(); content.addView(projects)
        space(content, 24)
        content.addView(label("属于你的应用，留在你的设备。", 12f, muted).apply { gravity = Gravity.CENTER })
        refresh()
        if (address.isNotBlank()) synchronize(address)
    }
    override fun onStart() {
        super.onStart()
        if (!bound) bound = bindService(Intent(this, RunnerControlService::class.java), connection, Context.BIND_AUTO_CREATE)
        else if (runner != null) send(1)
    }
    override fun onDestroy() { if (bound) unbindService(connection); super.onDestroy() }
    private fun displayName(id: String): String = names[id] ?: "应用"
    private fun showDetails(value: String) { AlertDialog.Builder(this).setTitle("详细信息").setMessage(value).setPositiveButton("关闭", null).show() }
    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("设置分发站"); menu.add("检查更新"); menu.add("结束当前应用"); menu.add("运行诊断"); menu.add("关于")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "设置分发站" -> editSource()
                    "检查更新" -> checkHostUpdate(true)
                    "结束当前应用" -> stopProject()
                    "运行诊断" -> { val file = File(filesDir, "runner-diagnostic.json"); showDetails(if (file.exists()) file.readText() else "还没有运行记录。") }
                    "关于" -> showDetails("Uni App Frame ${packageManager.getPackageInfo(packageName, 0).versionName}\n\n连接应用库，安装并打开你的应用。\n已安装应用可离线使用。\n\n本版仅接受受信发布方签名的应用。")
                }; true
            }; show()
        }
    }
    private fun editSource() {
        if (working) { notifyUser("请等待当前下载完成。"); return }
        val container = vertical().apply { setPadding(dp(24), dp(8), dp(24), 0) }
        container.addView(label("粘贴网站地址或 Google Drive 应用库链接，自动获取应用列表。", 14f, muted))
        val input = EditText(this).apply { hint = "网站地址或 Drive 应用库链接"; contentDescription = "分发站网址"; inputType = 17; isSingleLine = true; setText(address) }
        container.addView(input, LinearLayout.LayoutParams(-1, dp(56)))
        val dialog = AlertDialog.Builder(this).setTitle("连接应用库").setView(container).setNegativeButton("取消", null).setPositiveButton("连接", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val candidate = input.text.toString().trim()
            try { CatalogVerifier.endpoint(candidate); dialog.dismiss(); synchronize(candidate) }
            catch (failure: Exception) { input.error = if (failure.message == "DRIVE_CATALOG_FILE_LINK_REQUIRED") "请使用应用库文件的分享链接，而不是文件夹链接" else "请填写有效的 HTTPS 网址或 Drive 文件链接" }
        } }
        dialog.show()
    }
    private fun stopProject() {
        AlertDialog.Builder(this).setTitle("结束当前应用？").setMessage("设备连接会断开。请先完成采集、传输或固件升级。")
            .setNegativeButton("继续运行", null).setPositiveButton("结束应用") { _, _ -> send(2) }.show()
    }
    private fun send(command: Int, data: Bundle = Bundle()) {
        try { (runner ?: error("正在准备运行环境，请稍后再试。")).send(Message.obtain(null, command).apply { this.data = data; replyTo = replies }) }
        catch (failure: Exception) { notifyUser("正在准备运行环境，请稍后再试。", failure.toString()) }
    }
    private fun busy(value: Boolean) {
        working = value; refreshButton.isEnabled = !value; refreshButton.alpha = if (value) .4f else 1f
        progress.visibility = if (value) View.VISIBLE else View.GONE
        refresh()
    }
    private fun synchronize(candidate: String) {
        if (working) return
        notifyUser("正在同步应用库…"); busy(true)
        app.work.execute {
            var fetched = false
            try {
                val result = catalogs.fetch(candidate)
                fetched = true
                runOnUiThread { catalog = result; address = candidate; notifyUser("应用库已更新") }
            } catch (failure: Exception) { runOnUiThread { notifyUser(if (catalog == null) "暂时无法连接，请检查分发站网址。" else "暂时无法连接 · 已安装应用仍可打开", failure.toString()) } }
            finally { runOnUiThread { busy(false); if (fetched) checkHostUpdate(false) } }
        }
    }
    private fun checkHostUpdate(manual: Boolean) {
        if (checkingUpdate || working) {
            if (manual) notifyUser("请等待当前操作完成，再检查更新。")
            return
        }
        if (address.isBlank()) { if (manual) { notifyUser("先连接应用库，即可检查宿主更新。"); editSource() }; return }
        checkingUpdate = true
        if (manual) notifyUser("正在检查 Uni App Frame 更新…")
        val site = address
        app.work.execute {
            try {
                val update = HostUpdates(this).check(site, catalogs.cached(false))
                @Suppress("DEPRECATION")
                val info = packageManager.getPackageInfo(packageName, 0)
                @Suppress("DEPRECATION")
                val installed = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                runOnUiThread {
                    if (isFinishing || isDestroyed || address != site) return@runOnUiThread
                    when {
                        update == null -> if (manual) notifyUser("此应用库还没有发布宿主更新。")
                        update.getLong("versionCode") <= installed -> if (manual) notifyUser("已是最新版本 · ${info.versionName}")
                        !HostUpdateVerifier.newer(update, installed, Build.VERSION.SDK_INT) -> if (manual) notifyUser("新版需要更高的 Android 系统，当前版本仍可使用。")
                        manual || announcedVersion != update.getLong("versionCode") -> {
                            announcedVersion = update.getLong("versionCode")
                            notifyUser("发现新版 Uni App Frame · ${update.getString("versionName")}")
                            AlertDialog.Builder(this).setTitle("发现新版本 ${update.getString("versionName")}")
                                .setMessage(update.getString("notes") + "\n\n将通过浏览器下载 APK，再由安卓系统确认覆盖安装。安装前请先结束正在运行的应用。")
                                .setNegativeButton("稍后", null).setPositiveButton("下载新版") { _, _ ->
                                    try {
                                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(update.getString("apkUrl"))).addCategory(Intent.CATEGORY_BROWSABLE))
                                    } catch (_: ActivityNotFoundException) { notifyUser("未找到浏览器，请先安装浏览器后重试。") }
                                }.show()
                        }
                    }
                }
            } catch (failure: Exception) {
                if (manual) runOnUiThread { notifyUser("暂时无法检查更新，请稍后重试。", failure.toString()) }
            } finally { runOnUiThread { checkingUpdate = false } }
        }
    }
    private fun install(id: String) {
        if (working) return
        installing = id; notifyUser("正在安装 ${displayName(id)}…"); busy(true)
        app.work.execute {
            try {
                val apps = catalogs.cached(false)!!.getJSONArray("apps")
                val item = (0 until apps.length()).map { apps.getJSONObject(it) }.first { it.getString("projectId") == id }
                val release = catalogs.release(item, app.store) ?: error("设备暂不支持此版本")
                app.store.download(release.getString("url"), id, release.getString("sha256"), release.getLong("sequence"), item.getString("name"))
                runOnUiThread { notifyUser("${item.getString("name")} 已就绪") }
            } catch (failure: Exception) { runOnUiThread { notifyUser("安装未完成，已有版本保留。长按此处查看详情。", failure.toString()) } }
            finally { runOnUiThread { installing = null; busy(false) } }
        }
    }
    private fun refresh() {
        if (!::projects.isInitialized) return
        projects.removeAllViews()
        sourceName.text = catalog?.optString("name") ?: "连接你的应用库"
        sourceHost.text = if (address.isBlank()) "一个网址，发现更多应用" else try { URI(CatalogVerifier.endpoint(address).toString()).host } catch (_: Exception) { "点击修改分发站" }
        for ((tab, selected) in listOf(allTab to !installedOnly, installedTab to installedOnly)) {
            tab.setTextColor(if (selected) purple else muted); tab.background = shape(if (selected) 0xFFECE8FD.toInt() else Color.TRANSPARENT, 12); tab.isSelected = selected
        }
        try {
            val installed = app.store.index()
            val entries = linkedMapOf<String, JSONObject>()
            catalog?.getJSONArray("apps")?.let { list -> for (i in 0 until list.length()) { val item = list.getJSONObject(i); entries[item.getString("projectId")] = item } }
            installed.keys().asSequence().sorted().forEach { id -> if (!entries.containsKey(id)) entries[id] = JSONObject().put("name", installed.getJSONObject(id).optString("name", id)).put("description", "已保存在此设备，可离线打开。") }
            names.clear(); entries.forEach { (id, item) -> names[id] = item.getString("name") }
            counts.text = if (entries.isEmpty()) "你的工具，都在这里。" else "${entries.size} 款应用 · ${installed.length()} 款已安装"
            val visible = entries.entries.filter { !installedOnly || installed.has(it.key) }
            if (visible.isEmpty()) {
                val empty = vertical().apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(32), dp(24), dp(32)); background = shape(Color.WHITE, 24) }
                empty.addView(ImageView(this).apply { setImageResource(R.drawable.ic_library) }, LinearLayout.LayoutParams(dp(64), dp(64)))
                space(empty, 22)
                empty.addView(label(if (installedOnly) "还没有安装应用" else "从一个网址开始", 20f, ink, true).apply { gravity = Gravity.CENTER })
                space(empty, 12)
                empty.addView(label(if (installedOnly) "去全部应用中，挑选你需要的工具。" else "连接应用库，让常用工具在这里相聚。", 13f, muted).apply { gravity = Gravity.CENTER; setLineSpacing(dp(4).toFloat(), 1f) })
                space(empty, 24)
                empty.addView(action(if (installedOnly) "浏览应用" else "连接应用库", true) { if (installedOnly) { installedOnly = false; refresh() } else editSource() })
                projects.addView(empty); return
            }
            val columns = when { resources.configuration.screenWidthDp >= 840 -> 4; resources.configuration.screenWidthDp >= 600 -> 3; resources.configuration.fontScale > 1.35f -> 1; else -> 2 }
            visible.chunked(columns).forEach { group ->
                val line = row().apply { gravity = Gravity.TOP }
                group.forEach { (id, item) ->
                    val record = installed.optJSONObject(id)
                    val release = if (item.has("releases")) catalogs.release(item, app.store) else null
                    val name = item.getString("name")
                    val update = release != null && (record == null || release.getLong("sequence") > record.getLong("highestSequence"))
                    val card = vertical().apply { setPadding(dp(16), dp(18), dp(16), dp(16)); background = shape(Color.WHITE, 22) }
                    line.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(8)) })
                    val palette = arrayOf(intArrayOf(0xFF4F7A59.toInt(), 0xFF739C6B.toInt()), intArrayOf(0xFF386C7C.toInt(), 0xFF609997.toInt()), intArrayOf(purple, 0xFF9D80E8.toInt()), intArrayOf(0xFFA76D43.toInt(), 0xFFD4A36D.toInt()))
                    val colors = palette[(id.hashCode() and Int.MAX_VALUE) % palette.size]
                    card.addView(label(name.take(1).uppercase(), 28f, Color.WHITE, true).apply {
                        gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = dp(19).toFloat() }
                    }, LinearLayout.LayoutParams(dp(62), dp(62)))
                    space(card, 18)
                    card.addView(label(name, 16f, ink, true).apply { maxLines = 2; minHeight = dp(23); ellipsize = android.text.TextUtils.TruncateAt.END })
                    space(card, 8)
                    card.addView(label(item.getString("description"), 12f, muted).apply { minLines = 3; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(dp(3).toFloat(), 1f) })
                    space(card, 14)
                    card.addView(label(when { installing == id -> "正在安装…"; update && record != null -> "● 有更新"; record != null -> "● 可离线使用"; release == null -> "此设备暂不支持"; else -> "可从应用库安装" }, 11f, if (record != null) 0xFF55816B.toInt() else muted))
                    space(card, 12)
                    if (record != null) {
                        card.addView(action("打开", true) { send(3, Bundle().apply { putString("project", id); putString("digest", record.getString("latest")) }) }.apply { contentDescription = "打开 $name" }, LinearLayout.LayoutParams(-1, -2))
                        if (update) card.addView(action("更新") { install(id) }.apply { contentDescription = "更新 $name"; isEnabled = !working; alpha = if (working) .4f else 1f }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                    } else {
                        card.addView(action(if (installing == id) "安装中…" else if (release == null) "暂不可用" else "安装") { install(id) }.apply { contentDescription = "安装 $name"; isEnabled = !working && release != null; alpha = if (isEnabled) 1f else .45f }, LinearLayout.LayoutParams(-1, -2))
                    }
                }
                repeat(columns - group.size) { line.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f)) }
                projects.addView(line)
            }
        } catch (failure: Exception) { notifyUser("暂时无法读取应用列表。", failure.toString()) }
    }
}
