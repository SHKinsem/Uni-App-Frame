package dev.uniapp.frame.adapter

import android.app.Application
import android.content.Context

/** Explicit Application ancestry lets Shadow transform the module's Android context. */
open class UniFrameApplication : Application() {
    override fun createPackageContext(packageName: String, flags: Int): Context =
        if (packageName == this.packageName && flags == 0) this
        else super.createPackageContext(packageName, flags)
}
