package io.legado.app.ui.config

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityMainTopBarImageBinding
import io.legado.app.help.MainTopBarImageManager
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil

class MainTopBarImageActivity : BaseActivity<ActivityMainTopBarImageBinding>() {

    override val binding by viewBinding(ActivityMainTopBarImageBinding::inflate)

    private var sourceBitmap: Bitmap? = null
    private var sourceFile: File? = null
    private var isNight = false

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        isNight = intent.getBooleanExtra(EXTRA_NIGHT, false)
        binding.titleBar.title = getString(R.string.main_top_bar_image)
        binding.cropFrame.aspectRatio = MainTopBarImageManager.topBarAspectRatio(this)
        binding.photoView.minScale = MIN_SCALE
        binding.photoView.isFreeScroll = true
        binding.cropFrame.post { loadSource(savedInstanceState) }
    }

    override fun onDestroy() {
        binding.photoView.setImageDrawable(null)
        if (!isChangingConfigurations) sourceFile?.delete()
        sourceBitmap?.recycle()
        sourceFile = null
        sourceBitmap = null
        super.onDestroy()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_top_bar_image, menu)
        return true
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_reset -> {
                resetImage()
                true
            }

            R.id.menu_save -> {
                saveImage()
                true
            }

            R.id.menu_export -> {
                exportResult.launch(exportFileName())
                true
            }

            else -> super.onCompatOptionsItemSelected(item)
        }
    }

    private fun loadSource(savedInstanceState: Bundle?) {
        val restoredPath = savedInstanceState?.getString(KEY_SOURCE_PATH)
        if (!restoredPath.isNullOrBlank()) {
            val restoredFile = File(restoredPath)
            if (restoredFile.isFile) {
                sourceFile = restoredFile
                decodeSource(restoredFile)
                return
            }
        }
        val uri = intent.data ?: run {
            toastOnUi(R.string.main_top_bar_image_error)
            finish()
            return
        }
        val file = File(cacheDir, "main-top-bar-${UUID.randomUUID()}.source")
        sourceFile = file
        lifecycleScope.launch(IO) {
            runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(file).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 64L * 1024 * 1024) { "image is too large" }
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: error("cannot read image")
            }.onSuccess {
                withContext(Main) { decodeSource(file) }
            }.onFailure {
                withContext(Main) {
                    toastOnUi(R.string.main_top_bar_image_error)
                    finish()
                }
            }
        }
    }

    private fun decodeSource(file: File) {
        lifecycleScope.launch(IO) {
            val bitmap = runCatching {
                BitmapUtils.decodeBitmap(
                    file.absolutePath,
                    MAX_SOURCE_DIMENSION,
                    MAX_SOURCE_DIMENSION
                )
            }.getOrNull()
            withContext(Main) {
                if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
                    toastOnUi(R.string.main_top_bar_image_error)
                    finish()
                    return@withContext
                }
                @Suppress("DEPRECATION")
                sourceBitmap = bitmap.apply { density = android.util.DisplayMetrics.DENSITY_DEFAULT }
                binding.photoView.setImageDrawable(createDrawable(bitmap))
            }
        }
    }

    private fun resetImage() {
        val bitmap = sourceBitmap ?: return
        binding.photoView.setImageDrawable(createDrawable(bitmap))
    }

    @Suppress("DEPRECATION")
    private fun createDrawable(bitmap: Bitmap): BitmapDrawable {
        return BitmapDrawable(resources, bitmap).apply {
            setTargetDensity(android.util.DisplayMetrics.DENSITY_DEFAULT)
        }
    }

    /**
     * 按取景框输出图片。图片缩小后可能小于取景框，越界部分保持透明，
     * 不再像旧实现那样把坐标夹到图片边缘导致拉伸。
     */
    private fun saveImage() {
        val bitmap = sourceBitmap ?: return
        val frame = binding.cropFrame.frameRect()
        val viewImg = currentImageRect()
        if (frame.width() < 1f || frame.height() < 1f || viewImg == null) {
            toastOnUi(R.string.main_top_bar_image_error)
            return
        }
        val outputWidth = ceil(frame.width()).toInt().coerceAtLeast(1)
        val outputHeight = ceil(frame.height()).toInt().coerceAtLeast(1)
        val output = File(cacheDir, "main-top-bar-${UUID.randomUUID()}.webp")
        lifecycleScope.launch(IO) {
            val result = runCatching {
                val target = renderCropped(bitmap, frame, viewImg, outputWidth, outputHeight)
                try {
                    FileOutputStream(output).use { stream ->
                        require(target.compress(Bitmap.CompressFormat.WEBP, 90, stream))
                    }
                } finally {
                    target.recycle()
                }
                MainTopBarImageManager.replaceFrom(output, isNight).getOrThrow()
            }
            output.delete()
            withContext(Main) {
                result.onSuccess {
                    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_NIGHT, isNight))
                    finish()
                }.onFailure {
                    toastOnUi(R.string.main_top_bar_image_error)
                }
            }
        }
    }

    /** 把当前取景框内容导出到用户选择的位置，下次可以直接选回这张图。 */
    private fun exportImage(uri: Uri) {
        val bitmap = sourceBitmap ?: return
        val frame = binding.cropFrame.frameRect()
        val viewImg = currentImageRect()
        if (frame.width() < 1f || frame.height() < 1f || viewImg == null) {
            toastOnUi(R.string.main_top_bar_image_export_failed)
            return
        }
        val outputWidth = ceil(frame.width()).toInt().coerceAtLeast(1)
        val outputHeight = ceil(frame.height()).toInt().coerceAtLeast(1)
        lifecycleScope.launch(IO) {
            val result = runCatching {
                val target = renderCropped(bitmap, frame, viewImg, outputWidth, outputHeight)
                try {
                    contentResolver.openOutputStream(uri).use { stream ->
                        requireNotNull(stream)
                        require(target.compress(Bitmap.CompressFormat.PNG, 100, stream))
                    }
                } finally {
                    target.recycle()
                }
            }
            withContext(Main) {
                result.onSuccess {
                    toastOnUi(R.string.main_top_bar_image_export_success)
                }.onFailure {
                    toastOnUi(R.string.main_top_bar_image_export_failed)
                }
            }
        }
    }

    /** 图片在 PhotoView 中的实际显示矩形，由 PhotoView 自己维护的矩阵计算。 */
    private fun currentImageRect(): RectF? {
        val rect = RectF(binding.photoView.getInfo().mImgRect)
        return rect.takeIf { it.width() > 0f && it.height() > 0f }
    }

    /**
     * 取景框坐标和图片显示坐标都在 PhotoView 的父布局坐标系里，
     * 先按两者比例换算出源图矩形，再直接缩放绘制到目标位图，
     * 不依赖 Canvas 变换的先后顺序。
     */
    private fun renderCropped(
        bitmap: Bitmap,
        frame: RectF,
        viewImg: RectF,
        width: Int,
        height: Int
    ): Bitmap {
        val scaleX = bitmap.width / viewImg.width()
        val scaleY = bitmap.height / viewImg.height()
        val src = RectF(
            (frame.left - viewImg.left) * scaleX,
            (frame.top - viewImg.top) * scaleY,
            (frame.right - viewImg.left) * scaleX,
            (frame.bottom - viewImg.top) * scaleY
        )
        val clipped = RectF(src)
        require(clipped.intersect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())) {
            "crop frame is outside the image"
        }
        val dst = RectF(
            (clipped.left - src.left) / src.width() * width,
            (clipped.top - src.top) / src.height() * height,
            (clipped.right - src.left) / src.width() * width,
            (clipped.bottom - src.top) / src.height() * height
        )
        val srcRect = Rect(
            clipped.left.toInt().coerceAtLeast(0),
            clipped.top.toInt().coerceAtLeast(0),
            ceil(clipped.right).toInt().coerceAtMost(bitmap.width),
            ceil(clipped.bottom).toInt().coerceAtMost(bitmap.height)
        )
        require(!srcRect.isEmpty) { "crop frame is too small" }
        val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(target).drawBitmap(
            bitmap,
            srcRect,
            dst,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        return target
    }

    private fun exportFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "top_bar_$stamp.png"
    }

    private val exportResult = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        if (uri != null) exportImage(uri)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        sourceFile?.let { outState.putString(KEY_SOURCE_PATH, it.absolutePath) }
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val EXTRA_NIGHT = "night"
        private const val KEY_SOURCE_PATH = "sourcePath"
        private const val MAX_SOURCE_DIMENSION = 4096

        /** 允许把图片缩小到初始适应大小的四分之一，松手后不再弹回。 */
        private const val MIN_SCALE = 0.25f
    }
}
