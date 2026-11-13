package dev.uniapp.frame

import android.app.Application
import android.util.Log
import com.tencent.shadow.core.common.Logger
import com.tencent.shadow.core.common.LoggerFactory
import java.lang.reflect.Proxy
import java.util.concurrent.Executors

class FrameApplication : Application() {
    // The host process owns downloads and accepted-release records through this single queue.
    val work = Executors.newSingleThreadExecutor()
    val store by lazy { ModuleStore(this) }
    override fun onCreate() {
        super.onCreate()
        LoggerFactory.setILoggerFactory { name ->
            Proxy.newProxyInstance(Logger::class.java.classLoader, arrayOf(Logger::class.java)) { _, method, args ->
                when {
                    method.name == "getName" || method.name == "toString" -> name
                    method.returnType == Boolean::class.javaPrimitiveType -> false
                    method.name == "hashCode" -> name.hashCode()
                    else -> { Log.d("UniShadow", "$name ${method.name}: ${args?.joinToString()}"); null }
                }
            } as Logger
        }
    }
}
