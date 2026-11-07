package io.legado.app.help

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import splitties.init.appCtx

/** Stores one bounded image for the main screen top bar. */
object MainTopBarImageManager {

    private const val FILE_NAME = "background.webp"

    private val rootDir by lazy {
        File(appCtx.filesDir, "mainTopBar").apply {
            require(isDirectory || mkdirs()) { "cannot create top bar image directory" }
        }
    }

    private var cachedBitmap: Bitmap? = null
    private var cachedModified = Long.MIN_VALUE

    val imageFile: File
        get() = File(rootDir, FILE_NAME)

    fun hasImage(): Boolean = imageFile.isFile && imageFile.length() > 0

    @Synchronized
    fun isLight(context: Context): Boolean {
        val bitmap = loadBitmap(context) ?: return false
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
        return ((red * 299 + green * 587 + blue * 114) / count) >= 128 * 1000
    }

    @Synchronized
    fun loadBitmap(context: Context): Bitmap? {
        val file = imageFile.takeIf { it.isFile && it.length() > 0 } ?: run {
            cachedBitmap = null
            cachedModified = Long.MIN_VALUE
            return null
        }
        val modified = file.lastModified()
        if (cachedModified == modified) return cachedBitmap
        cachedBitmap = BitmapFactory.decodeFile(file.absolutePath)?.also {
            it.density = context.resources.displayMetrics.densityDpi
        }
        cachedModified = modified
        return cachedBitmap
    }

    @Synchronized
    fun replaceFrom(source: File): Result<Unit> = runCatching {
        require(source.isFile && source.length() > 0) { "top bar image is empty" }
        val temp = File(rootDir, ".${UUID.randomUUID()}.tmp")
        try {
            source.inputStream().use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            }
            require(temp.renameTo(imageFile) || temp.copyTo(imageFile, overwrite = true).let {
                temp.delete()
                true
            }) { "cannot replace top bar image" }
            cachedBitmap = null
            cachedModified = Long.MIN_VALUE
        } finally {
            temp.delete()
        }
    }

    @Synchronized
    fun clear() {
        imageFile.delete()
        cachedBitmap = null
        cachedModified = Long.MIN_VALUE
    }

}
