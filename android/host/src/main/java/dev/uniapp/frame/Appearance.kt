package dev.uniapp.frame

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import java.io.File

/** User-chosen look of the launcher: backdrop and accent color, stored per device. */
class Appearance(private val context: Context) {
    enum class Backdrop(val key: String, val title: String) {
        VIOLET("violet", "紫绸"), SILVER("silver", "银灰"), SOLID("solid", "纯色"), PHOTO("photo", "我的图片")
    }

    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val photoFile get() = File(context.filesDir, "appearance-backdrop.jpg")

    var backdrop: Backdrop
        get() = Backdrop.values().firstOrNull { it.key == prefs.getString("backdrop", null) }
            ?.takeIf { it != Backdrop.PHOTO || photoFile.exists() } ?: Backdrop.VIOLET
        set(value) { prefs.edit().putString("backdrop", value.key).apply() }

    var accent: Int
        get() = prefs.getInt("accent", DEFAULT_ACCENT)
        set(value) { prefs.edit().putInt("accent", value or 0xFF000000.toInt()).apply() }

    /** Extra darkening over the backdrop, 0–80 percent, so text stays legible on bright photos. */
    var dim: Int
        get() = prefs.getInt("dim", 20).coerceIn(0, 80)
        set(value) { prefs.edit().putInt("dim", value.coerceIn(0, 80)).apply() }

    /** Softens a personal photo with blur (Android 12+), so panels read like frosted glass over it. */
    var soften: Boolean
        get() = prefs.getBoolean("soften", true)
        set(value) { prefs.edit().putBoolean("soften", value).apply() }

    fun reset() { prefs.edit().clear().apply(); photoFile.delete() }

    /** Copies a picked image into private storage, downscaled so it never costs much memory. */
    fun importPhoto(uri: Uri) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "不是可识别的图片" }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_EDGE || bounds.outHeight / (sample * 2) >= MAX_EDGE * 2) sample *= 2
        val bitmap = context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("图片无法读取")
        val temporary = File(context.filesDir, "appearance-backdrop.tmp")
        temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bitmap.recycle()
        if (!temporary.renameTo(photoFile)) { temporary.copyTo(photoFile, true); temporary.delete() }
        backdrop = Backdrop.PHOTO
    }

    fun photo(): Bitmap? = if (photoFile.exists()) BitmapFactory.decodeFile(photoFile.path) else null

    companion object {
        const val DEFAULT_ACCENT = 0xFF8B7BFF.toInt()
        private const val MAX_EDGE = 1440
        val SWATCHES = intArrayOf(
            0xFF8B7BFF.toInt(), 0xFF5B8CFF.toInt(), 0xFF3CC8E0.toInt(), 0xFF46D39A.toInt(),
            0xFFF2C14E.toInt(), 0xFFFF8A5B.toInt(), 0xFFFF6B9A.toInt(), 0xFFE8E6F0.toInt()
        )

        /** Dark ink or white, whichever reads better on the given fill. */
        fun onColor(fill: Int): Int {
            fun channel(c: Int): Double { val v = c / 255.0; return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
            val luminance = 0.2126 * channel(Color.red(fill)) + 0.7152 * channel(Color.green(fill)) + 0.0722 * channel(Color.blue(fill))
            return if (luminance > 0.3) 0xFF120F2E.toInt() else Color.WHITE
        }

        fun parse(value: String): Int? = try {
            val text = value.trim().removePrefix("#")
            if (text.length == 6 && text.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) Color.parseColor("#$text") else null
        } catch (_: IllegalArgumentException) { null }
    }
}
