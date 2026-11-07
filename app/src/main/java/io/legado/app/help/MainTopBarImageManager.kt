package io.legado.app.help

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getPrefInt
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import splitties.init.appCtx

/**
 * 主界面顶部操作栏背景图。
 *
 * 与底部操作栏沉浸式一样按日间/夜间分别保存，每天只保留一张 WebP；
 * 旧版本的单张 background.webp 在首次访问时迁移为日间图片。
 */
object MainTopBarImageManager {

    private const val FILE_DAY = "background_day.webp"
    private const val FILE_NIGHT = "background_night.webp"
    private const val FILE_LEGACY = "background.webp"

    private val rootDir by lazy {
        File(appCtx.filesDir, "mainTopBar").apply {
            require(isDirectory || mkdirs()) { "cannot create top bar image directory" }
            migrateLegacyImage()
        }
    }

    private var cachedBitmap: Bitmap? = null
    private var cachedNight = false
    private var cachedModified = Long.MIN_VALUE

    fun hasImage(night: Boolean = AppConfig.isNightTheme): Boolean {
        val file = imageFile(night)
        return file.isFile && file.length() > 0
    }

    @Synchronized
    fun isLight(context: Context, night: Boolean = AppConfig.isNightTheme): Boolean {
        val bitmap = loadBitmap(context, night) ?: return false
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        val stepX = (bitmap.width / 8).coerceAtLeast(1)
        val stepY = (bitmap.height / 8).coerceAtLeast(1)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                red += Color.red(color)
                green += Color.green(color)
                blue += Color.blue(color)
                count++
                x += stepX
            }
            y += stepY
        }
        if (count <= 0L) return false
        return ((red * 299 + green * 587 + blue * 114) / count) >= 128 * 1000
    }

    @Synchronized
    fun loadBitmap(context: Context, night: Boolean = AppConfig.isNightTheme): Bitmap? {
        val file = imageFile(night)
        if (!file.isFile || file.length() <= 0) {
            if (cachedNight == night) {
                cachedBitmap = null
                cachedModified = Long.MIN_VALUE
            }
            return null
        }
        val modified = file.lastModified()
        if (cachedNight == night && cachedModified == modified) return cachedBitmap
        cachedBitmap = BitmapFactory.decodeFile(file.absolutePath)?.also {
            it.density = context.resources.displayMetrics.densityDpi
        }
        cachedNight = night
        cachedModified = modified
        return cachedBitmap
    }

    @Synchronized
    fun replaceFrom(source: File, night: Boolean = AppConfig.isNightTheme): Result<Unit> =
        runCatching {
            require(source.isFile && source.length() > 0) { "top bar image is empty" }
            val temp = File(rootDir, ".${UUID.randomUUID()}.tmp")
            try {
                source.inputStream().use { input ->
                    FileOutputStream(temp).use { output -> input.copyTo(output) }
                }
                temp.copyTo(imageFile(night), overwrite = true)
                clearCache(night)
            } finally {
                temp.delete()
            }
        }

    @Synchronized
    fun clear(night: Boolean = AppConfig.isNightTheme) {
        imageFile(night).delete()
        clearCache(night)
    }

    fun imageFile(night: Boolean): File {
        return File(rootDir, if (night) FILE_NIGHT else FILE_DAY)
    }

    /** 顶部操作栏取景框比例，优先使用主界面实测的顶栏高度，保存和再次导入已裁剪图片时共用。 */
    fun topBarAspectRatio(context: Context): Float {
        val statusBarId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (statusBarId > 0) {
            context.resources.getDimensionPixelSize(statusBarId)
        } else {
            0
        }
        val savedHeight = context.getPrefInt(PreferKey.mainTopBarImageHeight, 0)
        val toolbarHeight = if (savedHeight > 0) savedHeight else 56.dpToPx() + statusBarHeight
        return (context.resources.displayMetrics.widthPixels.toFloat() /
            toolbarHeight.coerceAtLeast(1)).coerceIn(2f, 16f)
    }

    private fun clearCache(night: Boolean) {
        if (cachedNight != night) return
        cachedBitmap = null
        cachedModified = Long.MIN_VALUE
    }

    /** 旧版本只有一张 background.webp，升级后并入日间图片，避免丢失用户已设置的图。 */
    private fun File.migrateLegacyImage() {
        val legacy = File(this, FILE_LEGACY)
        if (!legacy.isFile || legacy.length() <= 0) return
        val day = File(this, FILE_DAY)
        if (!day.isFile) {
            legacy.copyTo(day, overwrite = true)
        }
        legacy.delete()
    }

}
