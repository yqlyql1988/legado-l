package io.legado.app.ui.config

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityMainTopBarImageBinding
import io.legado.app.help.MainTopBarImageManager
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor

class MainTopBarImageActivity : BaseActivity<ActivityMainTopBarImageBinding>() {

    override val binding by viewBinding(ActivityMainTopBarImageBinding::inflate)

    private var sourceBitmap: Bitmap? = null
    private var sourceFile: File? = null
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = getString(R.string.main_top_bar_image)
        binding.cropFrame.aspectRatio = topBarAspectRatio()
        binding.cropFrame.post { loadSource(savedInstanceState) }
    }

    override fun onDestroy() {
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
                withContext(kotlinx.coroutines.Dispatchers.Main) { decodeSource(file) }
            }.onFailure {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    toastOnUi(R.string.main_top_bar_image_error)
                    finish()
                }
            }
        }
    }

    private fun decodeSource(file: File) {
        lifecycleScope.launch(IO) {
            val bitmap = runCatching {
                BitmapUtils.decodeBitmap(file.absolutePath, MAX_SOURCE_DIMENSION, MAX_SOURCE_DIMENSION)
            }.getOrNull()
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) {
                    toastOnUi(R.string.main_top_bar_image_error)
                    finish()
                    return@withContext
                }
                sourceBitmap = bitmap.apply { density = android.util.DisplayMetrics.DENSITY_DEFAULT }
                binding.photoView.setImageDrawable(
                    BitmapDrawable(resources, bitmap).apply {
                        setTargetDensity(android.util.DisplayMetrics.DENSITY_DEFAULT)
                    }
                )
            }
        }
    }

    private fun resetImage() {
        val bitmap = sourceBitmap ?: return
        binding.photoView.setImageDrawable(
            BitmapDrawable(resources, bitmap).apply {
                setTargetDensity(android.util.DisplayMetrics.DENSITY_DEFAULT)
            }
        )
    }

    private fun saveImage() {
        val bitmap = sourceBitmap ?: return
        val frame = binding.cropFrame.frameRect()
        val inverse = android.graphics.Matrix()
        if (!binding.photoView.imageMatrix.invert(inverse)) {
            toastOnUi(R.string.main_top_bar_image_error)
            return
        }
        inverse.mapRect(frame)
        val left = floor(frame.left).toInt().coerceIn(0, bitmap.width - 1)
        val top = floor(frame.top).toInt().coerceIn(0, bitmap.height - 1)
        val right = ceil(frame.right).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = ceil(frame.bottom).toInt().coerceIn(top + 1, bitmap.height)
        val cropped = runCatching {
            Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        }.getOrNull() ?: run {
            toastOnUi(R.string.main_top_bar_image_error)
            return
        }
        val output = File(cacheDir, "main-top-bar-${UUID.randomUUID()}.webp")
        lifecycleScope.launch(IO) {
            val result = runCatching {
                FileOutputStream(output).use { stream ->
                    require(cropped.compress(Bitmap.CompressFormat.WEBP, 90, stream))
                }
                MainTopBarImageManager.replaceFrom(output).getOrThrow()
            }
            cropped.recycle()
            output.delete()
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                result.onSuccess {
                    setResult(Activity.RESULT_OK)
                    finish()
                }.onFailure {
                    toastOnUi(R.string.main_top_bar_image_error)
                }
            }
        }
    }

    private fun topBarAspectRatio(): Float {
        val statusBarId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (statusBarId > 0) {
            resources.getDimensionPixelSize(statusBarId)
        } else {
            0
        }
        val toolbarHeight = 56.dpToPx() + statusBarHeight
        return (resources.displayMetrics.widthPixels.toFloat() /
            toolbarHeight.coerceAtLeast(1)).coerceIn(3f, 10f)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        sourceFile?.let { outState.putString(KEY_SOURCE_PATH, it.absolutePath) }
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val KEY_SOURCE_PATH = "sourcePath"
        private const val MAX_SOURCE_DIMENSION = 4096
    }
}
