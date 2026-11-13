package dev.uniapp.frame.adapter

import android.content.Context
import io.flutter.embedding.android.FlutterActivity

/** Preserve the module's assets when Flutter recreates its own package context. */
open class UniFrameActivity : FlutterActivity() {
    override fun createPackageContext(packageName: String, flags: Int): Context =
        if (packageName == this.packageName && flags == 0) this
        else super.createPackageContext(packageName, flags)
}
