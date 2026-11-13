package dev.uniapp.frame

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.*
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.Calendar

class MainActivity : Activity() {
    private val ink = Color.WHITE
    private val muted = 0xB8FFFFFF.toInt()
    private val faint = 0x8CFFFFFF.toInt()
    private val live = 0xFF6CFFB8.toInt()
    private val night = 0xFF0A0A0D.toInt()
    private var accent = Appearance.DEFAULT_ACCENT
    private var onAccent = Color.WHITE
    private lateinit var appearance: Appearance
    private lateinit var backdrop: ImageView
    private lateinit var scrim: View
    private lateinit var status: TextView
    private lateinit var statusIcon: ImageView
    private lateinit var statusDetails: TextView
    private lateinit var toast: LinearLayout
    private lateinit var projects: LinearLayout
    private lateinit var sourceName: TextView
    private lateinit var sourceHost: TextView
    private lateinit var counts: TextView
    private lateinit var refreshButton: View
    private lateinit var progress: ProgressBar
    private lateinit var session: LinearLayout
    private lateinit var sessionName: TextView
    private lateinit var sessionOpen: TextView
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
    private var runningProject = ""
    private var introduced = false
    private val names = mutableMapOf<String, String>()
    private var runner: Messenger? = null
    private var bound = false
    private val handler = Handler(Looper.getMainLooper())
    private val hideToast = Runnable { if (::toast.isInitialized) toast.animate().alpha(0f).setDuration(220).withEndAction { toast.visibility = View.GONE }.start() }
    private val app get() = application as FrameApplication
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Int = 20) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    /** Frosted-glass surface: a faint white sheen, a hairline rim and soft corners. */
    private fun glass(radius: Int, strength: Float = 1f) = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        intArrayOf(alpha(Color.WHITE, .13f * strength), alpha(Color.WHITE, .04f * strength))).apply {
        cornerRadius = dp(radius).toFloat(); setStroke(dp(1), alpha(Color.WHITE, .16f * strength.coerceAtMost(1.2f)))
    }
    private fun alpha(color: Int, value: Float) = Color.argb((value.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
    private fun blend(from: Int, to: Int, amount: Float) = Color.rgb(
        (Color.red(from) + (Color.red(to) - Color.red(from)) * amount).toInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * amount).toInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount).toInt())
    private fun ripple(content: Drawable, color: Int = 0x33FFFFFF) = RippleDrawable(ColorStateList.valueOf(color), content, null)

    private fun label(value: String, size: Float, color: Int = ink, bold: Boolean = false, light: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
        typeface = when { bold -> Typeface.create("sans-serif-medium", Typeface.NORMAL); light -> Typeface.create("sans-serif-light", Typeface.NORMAL); else -> Typeface.DEFAULT }
    }
    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun space(parent: LinearLayout, height: Int) { parent.addView(Space(this), LinearLayout.LayoutParams(1, dp(height))) }
    private fun asButton(view: View) {
        view.isClickable = true; view.isFocusable = true
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info); info.className = Button::class.java.name
            }
        }
    }

    private enum class Look { FILLED, WHITE, GLASS }
    private fun action(value: String, look: Look = Look.GLASS, click: () -> Unit) = label(value, 14f,
        when (look) { Look.FILLED -> onAccent; Look.WHITE -> 0xFF120F2E.toInt(); Look.GLASS -> ink }, look != Look.GLASS).apply {
        gravity = Gravity.CENTER; minHeight = dp(46); setPadding(dp(18), dp(12), dp(18), dp(12))
        background = ripple(when (look) {
            Look.FILLED -> shape(accent, 23)
            Look.WHITE -> shape(0xF2FFFFFF.toInt(), 23)
            Look.GLASS -> glass(23, 1.2f)
        }, if (look == Look.GLASS) 0x33FFFFFF else alpha(Color.BLACK, .12f))
        asButton(this); setOnClickListener { click() }
    }
    private fun iconButton(icon: Int, description: String, size: Int = 46, click: () -> Unit) = ImageView(this).apply {
        setImageResource(icon); imageTintList = ColorStateList.valueOf(ink); scaleType = ImageView.ScaleType.CENTER
        background = ripple(glass(size / 2, 1.2f)); contentDescription = description
        asButton(this); setOnClickListener { click() }
    }

    /** A glossy app tile: colored gradient with a soft specular highlight, the app's initial on top. */
    private fun appIcon(id: String, name: String, size: Int, pending: Boolean = false): TextView {
        val palette = arrayOf(
            intArrayOf(0xFF9C83FF.toInt(), 0xFF4B2FD6.toInt()), intArrayOf(0xFF5FE6FF.toInt(), 0xFF1C7FD6.toInt()),
            intArrayOf(0xFFFF9AC8.toInt(), 0xFFE0367E.toInt()), intArrayOf(0xFFFFD27A.toInt(), 0xFFE0782E.toInt()))
        val colors = palette[(id.hashCode() and Int.MAX_VALUE) % palette.size]
        val radius = dp(size * 1 / 3).toFloat()
        val base = if (pending) GradientDrawable().apply { setColor(0x1AFFFFFF); setStroke(dp(1), 0x33FFFFFF); cornerRadius = radius }
            else GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = radius }
        val shine = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = dp(size) * .55f; setGradientCenter(.28f, .22f)
            setColors(intArrayOf(0xA6FFFFFF.toInt(), 0x00FFFFFF)); cornerRadius = radius
        }
        return label(name.take(1).uppercase(), size * .42f, if (pending) muted else Color.WHITE, true).apply {
            gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = if (pending) base else LayerDrawable(arrayOf(base, shine))
            if (!pending) setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0x40000000)
        }
    }

    private fun notifyUser(message: String, technical: String = "") {
        status.text = message; details = technical
        handler.removeCallbacks(hideToast)
        if (message.isBlank()) { toast.visibility = View.GONE; return }
        val problem = technical.isNotBlank()
        statusIcon.visibility = if (problem) View.VISIBLE else View.GONE
        statusDetails.visibility = if (problem) View.VISIBLE else View.GONE
        if (toast.visibility != View.VISIBLE) { toast.alpha = 0f; toast.translationY = dp(12).toFloat() }
        toast.visibility = View.VISIBLE
        toast.animate().alpha(1f).translationY(0f).setDuration(220).start()
        if (!problem && !working) handler.postDelayed(hideToast, 4200)
    }
    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            1 -> {
                val busy = message.data.getBoolean("busy")
                runningProject = if (busy) message.data.getString("project") ?: "" else ""
                session.visibility = if (busy) View.VISIBLE else View.GONE
                sessionName.text = displayName(runningProject)
                sessionOpen.visibility = if (runningProject.isNotBlank() && app.store.index().has(runningProject)) View.VISIBLE else View.GONE
                updateSessionIcon()
            }
            2 -> { runner = null; runningProject = ""; session.visibility = View.GONE; notifyUser("正在结束应用…") }
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
        appearance = Appearance(this)
        accent = appearance.accent; onAccent = Appearance.onColor(accent)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        catalogs = CatalogStore(this)
        try { address = catalogs.savedAddress(); catalog = catalogs.cached() } catch (_: Exception) { /* Keep installed applications reachable. */ }

        val root = FrameLayout(this).apply { setBackgroundColor(night) }
        backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        scrim = View(this)
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(alpha(night, .6f), alpha(night, 0f)))
        }, FrameLayout.LayoutParams(-1, dp(180)))
        applyBackdrop()
        setContentView(root)

        val content = vertical().apply { setPadding(dp(20), dp(18), dp(20), dp(132)); clipToPadding = false }
        val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; clipToPadding = false; addView(content) }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        val masthead = row()
        val heading = vertical()
        heading.addView(label("UNI FRAME", 12f, muted, true).apply { letterSpacing = .28f })
        space(heading, 6)
        heading.addView(label(greeting(), 30f, ink, light = true).apply { letterSpacing = .02f })
        masthead.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        refreshButton = iconButton(R.drawable.ic_refresh, "刷新应用库") { if (address.isBlank()) editSource() else synchronize(address) }
        masthead.addView(refreshButton, LinearLayout.LayoutParams(dp(46), dp(46)))
        content.addView(masthead)
        space(content, 22)

        session = vertical().apply { visibility = View.GONE; setPadding(dp(20), dp(20), dp(20), dp(20)); background = glass(30, 1.5f) }
        val sessionTop = row()
        val sessionLabels = vertical().apply { setPadding(dp(14), 0, 0, 0) }
        val runningLine = row()
        runningLine.addView(View(this).apply { background = shape(live, 4) }, LinearLayout.LayoutParams(dp(7), dp(7)))
        runningLine.addView(label("正在运行", 12f, muted).apply { setPadding(dp(6), 0, 0, 0) })
        sessionLabels.addView(runningLine); space(sessionLabels, 6)
        sessionName = label("", 20f, ink, true)
        sessionLabels.addView(sessionName)
        val sessionIcon = FrameLayout(this)
        sessionTop.addView(sessionIcon, LinearLayout.LayoutParams(dp(60), dp(60)))
        sessionTop.addView(sessionLabels, LinearLayout.LayoutParams(0, -2, 1f))
        session.addView(sessionTop)
        space(session, 18)
        val sessionActions = row()
        sessionOpen = action("回到应用", Look.WHITE) { reopenRunning() }.apply { contentDescription = "回到正在运行的应用" }
        sessionActions.addView(sessionOpen, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(10) })
        sessionActions.addView(action("结束") { stopProject() }.apply { contentDescription = "结束当前应用" }, LinearLayout.LayoutParams(-2, -2))
        session.addView(sessionActions)
        session.tag = sessionIcon
        content.addView(session, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        val sourceCard = row().apply { background = ripple(glass(24)); setPadding(dp(14), dp(14), dp(14), dp(14)); contentDescription = "设置应用库"; asButton(this) }
        sourceCard.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_link); imageTintList = ColorStateList.valueOf(blend(accent, Color.WHITE, .35f)); scaleType = ImageView.ScaleType.CENTER
            background = shape(alpha(accent, .22f), 14)
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val sourceLabels = vertical().apply { setPadding(dp(12), 0, dp(4), 0) }
        sourceName = label("连接你的应用库", 15f, ink, true)
        sourceHost = label("一个网址，发现更多应用", 12f, muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        sourceLabels.addView(sourceName); space(sourceLabels, 6); sourceLabels.addView(sourceHost)
        sourceCard.addView(sourceLabels, LinearLayout.LayoutParams(0, -2, 1f))
        sourceCard.addView(label("修改", 13f, muted))
        sourceCard.setOnClickListener { editSource() }
        content.addView(sourceCard)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(accent); visibility = View.GONE }
        content.addView(progress, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(10); leftMargin = dp(16); rightMargin = dp(16) })

        space(content, 22)
        val sectionHead = row()
        sectionHead.addView(label("我的应用", 17f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        counts = label("", 13f, muted)
        sectionHead.addView(counts)
        content.addView(sectionHead)
        space(content, 14)
        projects = vertical(); content.addView(projects)
        space(content, 24)
        content.addView(label("属于你的应用，留在你的设备。", 12f, faint).apply { gravity = Gravity.CENTER })

        toast = row().apply {
            visibility = View.GONE; setPadding(dp(16), dp(8), dp(8), dp(8)); minimumHeight = dp(52)
            background = GradientDrawable().apply { setColor(0xEB1C1D24.toInt()); cornerRadius = dp(20).toFloat(); setStroke(dp(1), 0x24FFFFFF) }
            elevation = dp(8).toFloat()
            setOnClickListener { if (details.isNotBlank()) showDetails(details) else hideToast.run() }
        }
        statusIcon = ImageView(this).apply { setImageResource(R.drawable.ic_alert); imageTintList = ColorStateList.valueOf(0xFFFFB27A.toInt()); visibility = View.GONE }
        toast.addView(statusIcon, LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(10) })
        status = label("", 13f, ink, true).apply { setLineSpacing(dp(2).toFloat(), 1f) }
        toast.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        statusDetails = label("详情", 13f, ink, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0); minHeight = dp(40); background = ripple(shape(0x1FFFFFFF, 14)); visibility = View.GONE
            asButton(this); setOnClickListener { showDetails(details) }
        }
        toast.addView(statusDetails, LinearLayout.LayoutParams(-2, dp(40)).apply { leftMargin = dp(8) })
        root.addView(toast, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = dp(20); rightMargin = dp(20); bottomMargin = dp(100) })

        val dock = row().apply {
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply { setColor(0xC71C1D24.toInt()); cornerRadius = dp(32).toFloat(); setStroke(dp(1), 0x24FFFFFF) }
            elevation = dp(12).toFloat()
        }
        allTab = dockTab("全部", R.drawable.ic_grid) { if (installedOnly) { installedOnly = false; introduced = false; refresh() } }
        installedTab = dockTab("已安装", R.drawable.ic_download) { if (!installedOnly) { installedOnly = true; introduced = false; refresh() } }
        dock.addView(allTab, LinearLayout.LayoutParams(-2, dp(48)))
        dock.addView(installedTab, LinearLayout.LayoutParams(-2, dp(48)).apply { leftMargin = dp(2) })
        dock.addView(dockIcon(R.drawable.ic_palette, "外观") { showAppearance() }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(2) })
        val more = dockIcon(R.drawable.ic_more, "更多选项") { }
        more.setOnClickListener { showMenu(more) }
        dock.addView(more, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(2) })
        root.addView(dock, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(24) })

        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION") val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION") val bottom = insets.systemWindowInsetBottom
            content.setPadding(dp(20), top + dp(18), dp(20), bottom + dp(132))
            (dock.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottom + dp(20); dock.requestLayout()
            (toast.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottom + dp(92); toast.requestLayout()
            insets
        }
        refresh()
        if (address.isNotBlank()) synchronize(address)
        else defaultCatalog()?.let { synchronize(it) }
    }
    /** Build-time default library, used only until the user has connected one themselves. */
    private fun defaultCatalog(): String? = getString(R.string.default_catalog_url).trim().takeIf {
        it.isNotEmpty() && try { CatalogVerifier.endpoint(it); true } catch (_: Exception) { false }
    }
    private fun dockTab(value: String, icon: Int, click: () -> Unit) = label(value, 14f, ink, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(18), 0); compoundDrawablePadding = dp(8)
        setCompoundDrawablesRelative(getDrawable(icon)!!.mutate().apply { setBounds(0, 0, dp(18), dp(18)) }, null, null, null)
        asButton(this); setOnClickListener { click() }
    }
    private fun dockIcon(icon: Int, description: String, click: () -> Unit) = ImageView(this).apply {
        setImageResource(icon); imageTintList = ColorStateList.valueOf(ink); scaleType = ImageView.ScaleType.CENTER
        background = ripple(shape(Color.TRANSPARENT, 24)); contentDescription = description
        asButton(this); setOnClickListener { click() }
    }
    private fun updateSessionIcon() {
        val holder = session.tag as? FrameLayout ?: return
        holder.removeAllViews()
        if (runningProject.isNotBlank()) holder.addView(appIcon(runningProject, displayName(runningProject), 60), FrameLayout.LayoutParams(-1, -1))
    }
    private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..10 -> "早上好"; in 11..12 -> "中午好"; in 13..17 -> "下午好"; else -> "晚上好"
    }

    private fun applyBackdrop() {
        if (Build.VERSION.SDK_INT >= 31) backdrop.setRenderEffect(null)
        when (appearance.backdrop) {
            Appearance.Backdrop.VIOLET -> backdrop.setImageResource(R.drawable.bg_ribbon_violet)
            Appearance.Backdrop.SILVER -> backdrop.setImageResource(R.drawable.bg_ribbon_silver)
            Appearance.Backdrop.SOLID -> backdrop.setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(blend(night, accent, .34f), blend(night, accent, .1f), night)))
            Appearance.Backdrop.PHOTO -> {
                val photo = try { appearance.photo() } catch (_: Exception) { null }
                if (photo == null) backdrop.setImageResource(R.drawable.bg_ribbon_violet) else {
                    backdrop.setImageBitmap(photo)
                    if (Build.VERSION.SDK_INT >= 31 && appearance.soften)
                        backdrop.setRenderEffect(RenderEffect.createBlurEffect(dp(18).toFloat(), dp(18).toFloat(), Shader.TileMode.CLAMP))
                }
            }
        }
        val photoDim = if (appearance.backdrop == Appearance.Backdrop.PHOTO) .15f else 0f
        scrim.setBackgroundColor(alpha(night, appearance.dim / 100f + photoDim))
    }

    override fun onStart() {
        super.onStart()
        if (!bound) bound = bindService(Intent(this, RunnerControlService::class.java), connection, Context.BIND_AUTO_CREATE)
        else if (runner != null) send(1)
    }
    override fun onDestroy() { handler.removeCallbacks(hideToast); if (bound) unbindService(connection); super.onDestroy() }
    private fun displayName(id: String): String = names[id] ?: "应用"
    private fun showDetails(value: String) { AlertDialog.Builder(this).setTitle("详细信息").setMessage(value).setPositiveButton("关闭", null).show() }
    private fun reopenRunning() {
        val record = app.store.index().optJSONObject(runningProject) ?: return
        send(3, Bundle().apply { putString("project", runningProject); putString("digest", record.getString("latest")) })
    }
    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("外观"); menu.add("设置分发站"); menu.add("检查更新"); menu.add("结束当前应用"); menu.add("运行诊断"); menu.add("关于")
            setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "外观" -> showAppearance()
                    "设置分发站" -> editSource()
                    "检查更新" -> checkHostUpdate(true)
                    "结束当前应用" -> stopProject()
                    "运行诊断" -> { val file = File(filesDir, "runner-diagnostic.json"); showDetails(if (file.exists()) file.readText() else "还没有运行记录。") }
                    "关于" -> showDetails("Uni App Frame ${packageManager.getPackageInfo(packageName, 0).versionName}\n\n连接应用库，安装并打开你的应用。\n已安装应用可离线使用。\n\n本版仅接受受信发布方签名的应用。")
                }; true
            }; show()
        }
    }

    /** Bottom sheet for backdrop, dimming and accent color. Changes preview live; color applies on close. */
    private fun showAppearance() {
        val sheet = android.app.Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar)
        val body = vertical().apply {
            setPadding(dp(22), dp(10), dp(22), dp(20))
            background = GradientDrawable().apply {
                setColor(0xF51C1D24.toInt()); setStroke(dp(1), 0x24FFFFFF)
                cornerRadii = floatArrayOf(dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        body.addView(View(this).apply { background = shape(0x40FFFFFF, 2) }, LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        space(body, 14)
        body.addView(label("外观", 22f, ink, true))
        space(body, 6)
        body.addView(label("背景和主色只保存在这台设备上。", 13f, muted))
        space(body, 18)
        body.addView(label("背景", 13f, muted, true))
        space(body, 10)
        var chosenAccent = accent
        val options = row()
        val tiles = mutableListOf<Pair<Appearance.Backdrop, FrameLayout>>()
        fun markTiles() = tiles.forEach { (kind, tile) ->
            (tile.foreground as GradientDrawable).setStroke(dp(if (kind == appearance.backdrop) 2 else 1), if (kind == appearance.backdrop) chosenAccent else 0x24FFFFFF)
            tile.isSelected = kind == appearance.backdrop
        }
        for (kind in Appearance.Backdrop.values()) {
            val tile = FrameLayout(this).apply {
                clipToOutline = true; background = shape(0xFF26272E.toInt(), 16)
                foreground = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dp(16).toFloat(); setStroke(dp(1), 0x24FFFFFF) }
                contentDescription = "背景：${kind.title}"; asButton(this)
            }
            when (kind) {
                Appearance.Backdrop.VIOLET, Appearance.Backdrop.SILVER -> tile.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageResource(if (kind == Appearance.Backdrop.VIOLET) R.drawable.bg_ribbon_violet else R.drawable.bg_ribbon_silver)
                }, FrameLayout.LayoutParams(-1, -1))
                Appearance.Backdrop.SOLID -> tile.addView(View(this).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(blend(night, accent, .45f), night))
                }, FrameLayout.LayoutParams(-1, -1))
                Appearance.Backdrop.PHOTO -> tile.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_image); imageTintList = ColorStateList.valueOf(ink); scaleType = ImageView.ScaleType.CENTER
                }, FrameLayout.LayoutParams(-1, -1))
            }
            tile.addView(label(kind.title, 11f, ink, true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(6))
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xB3000000.toInt(), 0x00000000))
            }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            tile.setOnClickListener {
                if (kind == Appearance.Backdrop.PHOTO) { sheet.dismiss(); pickPhoto() }
                else { appearance.backdrop = kind; applyBackdrop(); markTiles() }
            }
            tiles += kind to tile
            options.addView(tile, LinearLayout.LayoutParams(0, dp(80), 1f).apply { if (tiles.size > 1) leftMargin = dp(10) })
        }
        body.addView(options)
        space(body, 14)

        val dimRow = row()
        dimRow.addView(label("背景暗化", 13f, muted, true), LinearLayout.LayoutParams(0, -2, 1f))
        val dimValue = label("${appearance.dim}%", 13f, ink)
        dimRow.addView(dimValue)
        body.addView(dimRow)
        body.addView(SeekBar(this).apply {
            max = 80; progress = appearance.dim; contentDescription = "背景暗化"
            progressTintList = ColorStateList.valueOf(accent); thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) { appearance.dim = value; dimValue.text = "$value%"; applyBackdrop() }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }, LinearLayout.LayoutParams(-1, dp(44)))
        if (Build.VERSION.SDK_INT >= 31) body.addView(Switch(this).apply {
            text = "柔化我的图片"; textSize = 13f; setTextColor(muted); isChecked = appearance.soften; minHeight = dp(44)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setOnCheckedChangeListener { _, value -> appearance.soften = value; applyBackdrop() }
        })
        space(body, 8)

        body.addView(label("主色", 13f, muted, true))
        space(body, 12)
        val swatches = row()
        val hex = EditText(this)
        val swatchViews = mutableListOf<Pair<Int, View>>()
        fun markSwatches() = swatchViews.forEach { (color, view) ->
            (view.background as GradientDrawable).setStroke(dp(if (color == chosenAccent) 3 else 0), Color.WHITE)
        }
        for (color in Appearance.SWATCHES) {
            val swatch = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
                contentDescription = String.format("主色 #%06X", color and 0xFFFFFF); asButton(this)
                setOnClickListener { chosenAccent = color; hex.setText(String.format("#%06X", color and 0xFFFFFF)); markSwatches(); markTiles() }
            }
            swatchViews += color to swatch
            val cell = FrameLayout(this)
            cell.addView(swatch, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
            swatches.addView(cell, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        body.addView(swatches, LinearLayout.LayoutParams(-1, dp(44)))
        space(body, 10)
        val custom = row()
        hex.apply {
            setText(String.format("#%06X", accent and 0xFFFFFF)); hint = "#8B7BFF"; isSingleLine = true; textSize = 15f
            setTextColor(ink); setHintTextColor(faint); contentDescription = "自定义主色，十六进制"
            setPadding(dp(14), 0, dp(14), 0); background = shape(0x14FFFFFF, 14)
        }
        custom.addView(hex, LinearLayout.LayoutParams(0, dp(46), 1f))
        custom.addView(action("使用") {
            val parsed = Appearance.parse(hex.text.toString())
            if (parsed == null) hex.error = "请输入 6 位十六进制颜色，例如 #8B7BFF"
            else { chosenAccent = parsed or 0xFF000000.toInt(); markSwatches(); markTiles() }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        body.addView(custom)
        space(body, 18)

        val footer = row()
        footer.addView(action("恢复默认") {
            appearance.reset(); sheet.dismiss(); recreate()
        }, LinearLayout.LayoutParams(-2, -2))
        footer.addView(action("完成", Look.FILLED) { sheet.dismiss() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        body.addView(footer)

        sheet.setContentView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(body) })
        sheet.setOnDismissListener { if (chosenAccent != accent) { appearance.accent = chosenAccent; recreate() } }
        sheet.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM); setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(.35f)
        }
        markTiles(); markSwatches()
        sheet.show()
    }
    private fun pickPhoto() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), PICK_PHOTO)
        } catch (_: ActivityNotFoundException) { notifyUser("没有找到可以选择图片的应用。") }
    }
    @Deprecated("Activity result API is not available without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != PICK_PHOTO || resultCode != RESULT_OK || uri == null) return
        notifyUser("正在设置背景…")
        app.work.execute {
            try { appearance.importPhoto(uri); runOnUiThread { applyBackdrop(); notifyUser("背景已更新") } }
            catch (failure: Exception) { runOnUiThread { notifyUser("这张图片无法用作背景。", failure.toString()) } }
        }
    }

    private fun editSource() {
        if (working) { notifyUser("请等待当前下载完成。"); return }
        val container = vertical().apply { setPadding(dp(24), dp(8), dp(24), 0) }
        container.addView(label("粘贴网站地址或 Google Drive 应用库链接，自动获取应用列表。", 14f, muted).apply { setLineSpacing(dp(3).toFloat(), 1f) })
        space(container, 14)
        val input = EditText(this).apply {
            hint = "网站地址或 Drive 应用库链接"; contentDescription = "分发站网址"; inputType = 17; isSingleLine = true; setText(address)
            textSize = 15f; setTextColor(ink); setHintTextColor(faint); setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply { setColor(0x14FFFFFF); cornerRadius = dp(14).toFloat(); setStroke(dp(2), accent) }
        }
        container.addView(input, LinearLayout.LayoutParams(-1, dp(52)))
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
        if (value) refreshButton.animate().rotationBy(360f).setDuration(900).start()
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
            } catch (failure: Exception) { runOnUiThread { notifyUser("安装未完成，已有版本保留。", failure.toString()) } }
            finally { runOnUiThread { installing = null; busy(false) } }
        }
    }
    private fun refresh() {
        if (!::projects.isInitialized) return
        projects.removeAllViews()
        sourceName.text = catalog?.optString("name")?.takeIf { it.isNotBlank() } ?: "连接你的应用库"
        sourceHost.text = if (address.isBlank()) "一个网址，发现更多应用" else try { URI(CatalogVerifier.endpoint(address).toString()).host } catch (_: Exception) { "点击修改分发站" }
        for ((tab, selected) in listOf(allTab to !installedOnly, installedTab to installedOnly)) {
            val color = if (selected) 0xFF120F2E.toInt() else ink
            tab.setTextColor(color); tab.compoundDrawablesRelative[0]?.setTint(color)
            tab.background = ripple(shape(if (selected) 0xF2FFFFFF.toInt() else Color.TRANSPARENT, 24)); tab.isSelected = selected
        }
        try {
            val installed = app.store.index()
            val entries = linkedMapOf<String, JSONObject>()
            catalog?.getJSONArray("apps")?.let { list -> for (i in 0 until list.length()) { val item = list.getJSONObject(i); entries[item.getString("projectId")] = item } }
            installed.keys().asSequence().sorted().forEach { id -> if (!entries.containsKey(id)) entries[id] = JSONObject().put("name", installed.getJSONObject(id).optString("name", id)).put("description", "已保存在此设备，可离线打开。") }
            names.clear(); entries.forEach { (id, item) -> names[id] = item.getString("name") }
            if (runningProject.isNotBlank()) { sessionName.text = displayName(runningProject); updateSessionIcon() }
            counts.text = if (entries.isEmpty()) "" else "${installed.length()} / ${entries.size} 已安装"
            val visible = entries.entries.filter { !installedOnly || installed.has(it.key) }
            if (visible.isEmpty()) {
                val empty = vertical().apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(36), dp(24), dp(28)); background = glass(28, 1.2f) }
                empty.addView(ImageView(this).apply {
                    setImageResource(if (installedOnly) R.drawable.ic_download else R.drawable.ic_link); imageTintList = ColorStateList.valueOf(Color.WHITE)
                    scaleType = ImageView.ScaleType.CENTER; background = LayerDrawable(arrayOf(
                        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(blend(accent, Color.WHITE, .25f), blend(accent, Color.BLACK, .3f))).apply { cornerRadius = dp(22).toFloat() },
                        GradientDrawable().apply { gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = dp(40).toFloat(); setGradientCenter(.28f, .22f); setColors(intArrayOf(0x99FFFFFF.toInt(), 0x00FFFFFF)); cornerRadius = dp(22).toFloat() }))
                }, LinearLayout.LayoutParams(dp(68), dp(68)))
                space(empty, 22)
                empty.addView(label(if (installedOnly) "还没有安装应用" else "从一个网址开始", 20f, ink, true).apply { gravity = Gravity.CENTER })
                space(empty, 10)
                empty.addView(label(if (installedOnly) "去全部应用中，挑选你需要的工具。" else "连接应用库，让常用工具在这里相聚。", 13f, muted).apply { gravity = Gravity.CENTER; setLineSpacing(dp(4).toFloat(), 1f) })
                space(empty, 22)
                empty.addView(action(if (installedOnly) "浏览应用" else "连接应用库", Look.WHITE) { if (installedOnly) { installedOnly = false; refresh() } else editSource() })
                projects.addView(empty); return
            }
            val columns = when { resources.configuration.screenWidthDp >= 840 -> 4; resources.configuration.screenWidthDp >= 600 -> 3; resources.configuration.fontScale > 1.35f -> 1; else -> 2 }
            var index = 0
            visible.chunked(columns).forEach { group ->
                val line = row().apply { gravity = Gravity.TOP }
                group.forEach { (id, item) ->
                    val record = installed.optJSONObject(id)
                    val release = if (item.has("releases")) catalogs.release(item, app.store) else null
                    val name = item.getString("name")
                    val update = release != null && (record == null || release.getLong("sequence") > record.getLong("highestSequence"))
                    val unavailable = record == null && release == null
                    val card = FrameLayout(this).apply {
                        background = ripple(if (record != null) glass(26) else GradientDrawable().apply {
                            setColor(0x0DFFFFFF); cornerRadius = dp(26).toFloat(); setStroke(dp(1), 0x40FFFFFF, dp(6).toFloat(), dp(4).toFloat())
                        })
                        alpha = if (unavailable) .55f else 1f
                    }
                    val inner = vertical().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
                    card.addView(inner, FrameLayout.LayoutParams(-1, -2))
                    line.addView(card, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
                    inner.addView(appIcon(id, name, 48, record == null), LinearLayout.LayoutParams(dp(48), dp(48)))
                    space(inner, 14)
                    inner.addView(label(name, 15f, ink, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    space(inner, 5)
                    inner.addView(label(when {
                        installing == id -> "安装中…"
                        update && record != null -> "新版本可用"
                        record != null -> "可离线使用"
                        release == null -> "此设备暂不支持"
                        else -> "轻点安装"
                    }, 12f, if (installing == id) blend(accent, Color.WHITE, .4f) else muted).apply { maxLines = 1 })
                    space(inner, 4)
                    inner.addView(label(item.optString("description"), 11f, faint).apply { maxLines = 2; minLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(dp(2).toFloat(), 1f) })
                    if (update && record != null) card.addView(label("更新", 11f, onAccent, true).apply {
                        gravity = Gravity.CENTER; setPadding(dp(10), 0, dp(10), 0); background = ripple(shape(accent, 12)); contentDescription = "更新 $name"
                        isEnabled = !working; asButton(this); setOnClickListener { install(id) }
                    }, FrameLayout.LayoutParams(-2, dp(26), Gravity.TOP or Gravity.END).apply { topMargin = dp(14); rightMargin = dp(14) })
                    asButton(card)
                    when {
                        record != null -> { card.contentDescription = "打开 $name"; card.setOnClickListener { send(3, Bundle().apply { putString("project", id); putString("digest", record.getString("latest")) }) } }
                        release != null -> { card.contentDescription = "安装 $name"; card.isEnabled = !working; card.setOnClickListener { install(id) } }
                        else -> { card.contentDescription = "$name，此设备暂不支持"; card.isEnabled = false }
                    }
                    if (!introduced) {
                        card.alpha = 0f; card.translationY = dp(10).toFloat()
                        card.animate().alpha(if (unavailable) .55f else 1f).translationY(0f).setStartDelay(30L * index++).setDuration(260).start()
                    }
                }
                repeat(columns - group.size) { line.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(6), 0, dp(6), 0) }) }
                projects.addView(line, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = -dp(6); rightMargin = -dp(6) })
            }
            introduced = true
        } catch (failure: Exception) { notifyUser("暂时无法读取应用列表。", failure.toString()) }
    }

    companion object { private const val PICK_PHOTO = 41 }
}
