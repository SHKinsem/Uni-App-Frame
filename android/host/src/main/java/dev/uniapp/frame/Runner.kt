package dev.uniapp.frame

import android.app.Activity
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.*
import android.widget.TextView
import com.tencent.shadow.core.common.InstalledApk
import com.tencent.shadow.core.load_parameters.LoadParameters
import com.tencent.shadow.core.loader.ShadowPluginLoader
import com.tencent.shadow.core.loader.infos.ContainerProviderInfo
import com.tencent.shadow.core.loader.managers.ComponentManager
import com.tencent.shadow.core.runtime.container.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object RunnerState {
    var project = ""
    var digest = ""
    var phase = "idle"
    var loaded = false
    val busy get() = project.isNotEmpty()
    fun snapshot() = Bundle().apply {
        putBoolean("busy", busy); putString("project", project); putString("digest", digest)
        putString("phase", phase); putInt("pid", android.os.Process.myPid())
    }
}

class RunnerControlService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            1 -> message.replyTo?.send(Message.obtain(null, 1).apply { data = RunnerState.snapshot() })
            2 -> {
                // Only an explicit user stop reaches this command. Downloads never terminate a session.
                message.replyTo?.send(Message.obtain(null, 2))
                Handler(Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 100)
            }
            3 -> {
                val allowed = !RunnerState.busy
                if (allowed) {
                    RunnerState.project = message.data.getString("project") ?: ""
                    RunnerState.digest = message.data.getString("digest") ?: ""
                    RunnerState.phase = "reserved"
                }
                message.replyTo?.send(Message.obtain(null, 3).apply { data = RunnerState.snapshot().apply { putBoolean("allowed", allowed) } })
            }
        }
        true
    })
    override fun onBind(intent: Intent): IBinder = messenger.binder
}

class FrameLoader(context: Context) : ShadowPluginLoader(context) {
    private val manager = object : ComponentManager() {
        override fun onBindContainerActivity(pluginActivity: ComponentName) = ComponentName(context, ProjectActivity::class.java)
        override fun onBindContainerContentProvider(pluginContentProvider: ComponentName) =
            ContainerProviderInfo(PluginContainerContentProvider::class.java.name, "${context.packageName}.plugin-provider")
    }
    override fun getComponentManager(): ComponentManager = manager
}

class ProjectActivity : PluginContainerActivity()

class RunnerActivity : Activity() {
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "正在验证并打开项目…"; textSize = 18f; setPadding(32, 64, 32, 32) }
        setContentView(status)
        val project = intent.getStringExtra("project") ?: ""
        val digest = intent.getStringExtra("digest") ?: ""
        if (RunnerState.project != project || RunnerState.digest != digest || RunnerState.phase != "reserved") {
            status.text = "项目进程已失效，请返回宿主重新打开。"
            return
        }
        val app = application as FrameApplication
        app.work.execute {
            try {
                RunnerState.phase = "verifying"
                val prepared = app.store.prepare(project, digest)
                val loader = FrameLoader(applicationContext)
                loader.onCreate()
                DelegateProviderHolder.setDelegateProvider(loader.delegateProviderKey, loader)
                ContentProviderDelegateProviderHolder.setContentProviderDelegateProvider(loader)
                val parcel = Parcel.obtain()
                val extras = try {
                    LoadParameters("project-$project", project, null, emptyArray()).writeToParcel(parcel, 0)
                    parcel.marshall()
                } finally { parcel.recycle() }
                val apk = InstalledApk(prepared.apk.path, prepared.odex.path, prepared.nativeDir.path, extras)
                RunnerState.phase = "loading"
                loader.loadPlugin(apk).get(60, TimeUnit.SECONDS)
                runOnUiThread {
                    try {
                        loader.callApplicationOnCreate(project)
                        RunnerState.loaded = true
                        val parts = loader.getPluginParts(project)!!
                        val diagnostic = JSONObject().put("projectId", project).put("buildId", prepared.manifest.getString("buildId"))
                            .put("sourceCommit", prepared.manifest.getString("sourceCommit"))
                            .put("hostApkSha256", BundleVerifier.hash(File(applicationInfo.sourceDir)))
                            .put("apk", prepared.apk.path).put("nativeDir", prepared.nativeDir.path)
                            .put("dataDir", parts.application.filesDir.path)
                            .put("classLoader", parts.classLoader.toString())
                            .put("phase", "activity-requested; first-frame and business health not yet verified")
                        File(filesDir, "runner-diagnostic.json").writeText(diagnostic.toString(2))
                        val pluginIntent = Intent().setClassName(packageName, prepared.manifest.getString("entryActivity"))
                        RunnerState.phase = "activity-requested"
                        startActivity(loader.mComponentManager.convertPluginActivityIntent(pluginIntent))
                        finish()
                    } catch (failure: Throwable) { fail(failure) }
                }
            } catch (failure: Throwable) { runOnUiThread { fail(failure) } }
        }
    }
    private fun fail(failure: Throwable) {
        RunnerState.phase = "failed"
        status.text = "项目启动失败，宿主入口仍可使用。\n${failure.javaClass.simpleName}: ${failure.message}\n请返回宿主，结束项目后再试。"
        File(filesDir, "runner-diagnostic.json").writeText(JSONObject().put("phase", "failed")
            .put("projectId", RunnerState.project).put("error", failure.stackTraceToString()).toString(2))
    }
}
