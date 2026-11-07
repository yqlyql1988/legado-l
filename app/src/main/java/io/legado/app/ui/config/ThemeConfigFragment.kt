package io.legado.app.ui.config

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.SeekBar
import androidx.core.view.MenuProvider
import androidx.lifecycle.lifecycleScope
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.base.AppContextWrapper
import io.legado.app.constant.AppConst
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.DialogImageBlurringBinding
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.MainTopBarImageManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.prefs.ColorPreference
import io.legado.app.lib.prefs.SwitchPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.WallpaperTheme
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.applyTint
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.inputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.readUri
import io.legado.app.utils.removePref
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt


@Suppress("SameParameterValue")
class ThemeConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener,
    MenuProvider {

    private val requestCodeBgLight = 121
    private val requestCodeBgDark = 122
    private val requestCodeTopBar = 123
    private val requestCodeTopBarNight = 124
    private var topBarImageNight = false
    private val selectImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            when (it.requestCode) {
                requestCodeBgLight -> setBgFromUri(uri, PreferKey.bgImage) {
                    upTheme(false)
                }

                requestCodeBgDark -> setBgFromUri(uri, PreferKey.bgImageN) {
                    upTheme(true)
                }

                requestCodeTopBar -> handleTopBarUri(uri, false)
                requestCodeTopBarNight -> handleTopBarUri(uri, true)
            }
        }
    }

    private val cropTopBarResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == android.app.Activity.RESULT_OK) {
            val isNight = it.data?.getBooleanExtra(
                MainTopBarImageActivity.EXTRA_NIGHT,
                topBarImageNight
            ) ?: topBarImageNight
            upPreferenceSummary(topBarImageKey(isNight))
            postEvent(EventBus.MAIN_TOP_BAR_IMAGE, isNight)
        }
    }

    private var exportTopBarNight = false
    private val exportTopBarResult = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/webp")
    ) { uri ->
        if (uri != null) exportTopBarImageTo(uri, exportTopBarNight)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_theme)
        if (Build.VERSION.SDK_INT < 26) {
            preferenceScreen.removePreferenceRecursively(PreferKey.launcherIcon)
        }
        if (!WallpaperTheme.isAvailable()) {
            preferenceScreen.removePreferenceRecursively(PreferKey.wallpaperColorFollow)
            preferenceScreen.removePreferenceRecursively(PreferKey.wallpaperColorAutoUpdate)
        }
        upPreferenceSummary(PreferKey.bgImage, getPrefString(PreferKey.bgImage))
        upPreferenceSummary(PreferKey.bgImageN, getPrefString(PreferKey.bgImageN))
        upPreferenceSummary(PreferKey.mainTopBarImage)
        upPreferenceSummary(PreferKey.mainTopBarImageNight)
        bindTopBarImmersive(PreferKey.mainTopBarImmersive, night = false)
        bindTopBarImmersive(PreferKey.mainTopBarImmersiveNight, night = true)
        upPreferenceSummary(PreferKey.barElevation, AppConfig.elevation.toString())
        upPreferenceSummary(PreferKey.fontScale)
        findPreference<ColorPreference>(PreferKey.cBackground)?.let {
            it.onSaveColor = { color ->
                if (!ColorUtils.isColorLight(color)) {
                    toastOnUi(R.string.day_background_too_dark)
                    true
                } else {
                    false
                }
            }
        }
        findPreference<ColorPreference>(PreferKey.cNBackground)?.let {
            it.onSaveColor = { color ->
                if (ColorUtils.isColorLight(color)) {
                    toastOnUi(R.string.night_background_too_light)
                    true
                } else {
                    false
                }
            }
        }
        findPreference<SwitchPreference>(PreferKey.wallpaperColorFollow)
            ?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                val autoUpdate = getPrefBoolean(PreferKey.wallpaperColorAutoUpdate, true)
                if (!WallpaperTheme.setFollow(requireContext(), enabled, autoUpdate)) {
                    toastOnUi(R.string.wallpaper_colors_unavailable)
                    false
                } else {
                    true
                }
            }
        findPreference<SwitchPreference>(PreferKey.wallpaperColorAutoUpdate)
            ?.setOnPreferenceChangeListener { _, newValue ->
                if (getPrefBoolean(PreferKey.wallpaperColorFollow)) {
                    val updated = WallpaperTheme.setFollow(
                        requireContext(),
                        enabled = true,
                        autoUpdate = newValue as Boolean,
                    )
                    if (!updated) {
                        toastOnUi(R.string.wallpaper_colors_unavailable)
                        return@setOnPreferenceChangeListener false
                    }
                }
                true
            }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.theme_setting)
        listView.setEdgeEffectColor(primaryColor)
        activity?.addMenuProvider(this, viewLifecycleOwner)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.theme_config, menu)
        menu.applyTint(requireContext())
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.menu_theme_mode -> {
                AppConfig.isNightTheme = !AppConfig.isNightTheme
                ThemeConfig.applyDayNight(requireContext())
                return true
            }
        }
        return false
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        sharedPreferences ?: return
        when (key) {
            PreferKey.launcherIcon -> LauncherIconHelp.changeIcon(getPrefString(key))
            PreferKey.wallpaperColorFollow ->
                findPreference<SwitchPreference>(PreferKey.wallpaperColorFollow)?.isChecked =
                    getPrefBoolean(PreferKey.wallpaperColorFollow)
            PreferKey.transparentStatusBar,
            PreferKey.mainTopBarImmersive,
            PreferKey.mainTopBarImmersiveNight,
            PreferKey.immNavigationBar,
            PreferKey.disablePredictiveBack -> recreateActivities()
            PreferKey.cPrimary,
            PreferKey.cAccent,
            PreferKey.cBackground,
            PreferKey.cBBackground,
            PreferKey.tNavBar -> {
                if (WallpaperTheme.isApplyingColors) return
                if (key != PreferKey.tNavBar) {
                    WallpaperTheme.onColorPreferenceChanged(requireContext())
                }
                upTheme(false)
            }

            PreferKey.cNPrimary,
            PreferKey.cNAccent,
            PreferKey.cNBackground,
            PreferKey.cNBBackground,
            PreferKey.tNavBarN -> {
                if (WallpaperTheme.isApplyingColors) return
                if (key != PreferKey.tNavBarN) {
                    WallpaperTheme.onColorPreferenceChanged(requireContext())
                }
                upTheme(true)
            }

            PreferKey.bgImage,
            PreferKey.bgImageN -> {
                upPreferenceSummary(key, getPrefString(key))
            }

            PreferKey.mainTopBarImage,
            PreferKey.mainTopBarImageNight -> upPreferenceSummary(key)
        }

    }

    @SuppressLint("PrivateResource")
    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (val key = preference.key) {
            PreferKey.barElevation -> NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.bar_elevation))
                .setMaxValue(32)
                .setMinValue(0)
                .setValue(AppConfig.elevation)
                .setCustomButton((R.string.btn_default_s)) {
                    AppConfig.elevation = AppConst.sysElevation
                    recreateActivities()
                }
                .show {
                    AppConfig.elevation = it
                    recreateActivities()
                }

            PreferKey.fontScale -> NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.font_scale))
                .setMaxValue(16)
                .setMinValue(8)
                .setValue(
                    (AppContextWrapper.getFontScale(requireContext()) * 10)
                        .roundToInt()
                        .coerceIn(8, 16)
                )
                .setCustomButton((R.string.btn_default_s)) {
                    putPrefInt(PreferKey.fontScale, 0)
                    recreateActivities()
                }
                .show {
                    putPrefInt(PreferKey.fontScale, it)
                    recreateActivities()
                }

            PreferKey.bgImage -> selectBgAction(false)
            PreferKey.bgImageN -> selectBgAction(true)
            PreferKey.mainTopBarImage -> selectTopBarImage(false)
            PreferKey.mainTopBarImageNight -> selectTopBarImage(true)
            "themeList" -> ThemeListDialog().show(childFragmentManager, "themeList")
            PreferKey.bottomBarSkin -> startActivity<BottomBarSkinActivity>()
            "saveDayTheme",
            "saveNightTheme" -> alertSaveTheme(key)

            "coverConfig" -> startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.COVER_CONFIG)
            }

            "welcomeStyle" -> startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.WELCOME_CONFIG)
            }
        }
        return super.onPreferenceTreeClick(preference)
    }

    /**
     * 顶部沉浸开关未单独设置时跟随对应底栏开关，所以这里不把默认值写回偏好，
     * 点击后再持久化，避免打开一次设置页就固化默认值。
     */
    private fun bindTopBarImmersive(preferenceKey: String, night: Boolean) {
        findPreference<SwitchPreference>(preferenceKey)?.apply {
            isPersistent = false
            isChecked = AppConfig.mainTopBarImmersive(night)
            setOnPreferenceChangeListener { _, newValue ->
                putPrefBoolean(preferenceKey, newValue as Boolean)
                true
            }
        }
    }

    private fun selectTopBarImage(isNight: Boolean) {
        val key = topBarImageKey(isNight)
        val hasImage = MainTopBarImageManager.hasImage(isNight)
        val actions = arrayListOf(getString(R.string.select_image))
        if (hasImage) {
            actions += getString(R.string.main_top_bar_image_export)
            actions += getString(R.string.delete)
        }
        context?.selector(items = actions) { _, index ->
            when {
                index == 0 -> selectImage.launch {
                    requestCode = if (isNight) requestCodeTopBarNight else requestCodeTopBar
                    mode = HandleFileContract.IMAGE
                }

                hasImage && index == 1 -> {
                    exportTopBarNight = isNight
                    exportTopBarResult.launch(
                        if (isNight) "top_bar_night.webp" else "top_bar_day.webp"
                    )
                }

                else -> {
                    MainTopBarImageManager.clear(isNight)
                    upPreferenceSummary(key)
                    postEvent(EventBus.MAIN_TOP_BAR_IMAGE, isNight)
                }
            }
        }
    }

    private fun handleTopBarUri(uri: Uri, isNight: Boolean) {
        lifecycleScope.launch {
            val expectedRatio = MainTopBarImageManager.topBarAspectRatio(requireContext())
            val ratio = readImageRatio(uri)
            if (ratio != null && abs(ratio - expectedRatio) / expectedRatio <= 0.02f &&
                importTopBarImage(uri, isNight, expectedRatio)
            ) {
                upPreferenceSummary(topBarImageKey(isNight))
                postEvent(EventBus.MAIN_TOP_BAR_IMAGE, isNight)
                toastOnUi(R.string.main_top_bar_image_used)
            } else {
                cropTopBarImage(uri, isNight)
            }
        }
    }

    private suspend fun readImageRatio(uri: Uri): Float? = withContext(IO) {
        runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            requireContext().contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            if (options.outWidth > 0 && options.outHeight > 0) {
                options.outWidth.toFloat() / options.outHeight
            } else {
                null
            }
        }.getOrNull()
    }

    /**
     * 从裁剪页导出、再选回来的图片比例和取景框一致时直接使用，省掉再次裁剪。
     * 按屏幕宽度压缩后仍只保存一张 WebP，不保留原图副本。
     */
    private suspend fun importTopBarImage(uri: Uri, isNight: Boolean, ratio: Float): Boolean =
        withContext(IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                requireContext().contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                } ?: return@runCatching false
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching false
                val displayMetrics = requireContext().resources.displayMetrics
                val targetWidth = displayMetrics.widthPixels.coerceAtMost(bounds.outWidth)
                val targetHeight = (targetWidth / ratio).toInt().coerceAtLeast(1)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetWidth &&
                    bounds.outHeight / (sample * 2) >= targetHeight
                ) {
                    sample *= 2
                }
                val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
                val decoded = requireContext().contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOptions)
                } ?: return@runCatching false
                val cropped = if (decoded.width.toFloat() / decoded.height > ratio) {
                    val width = (decoded.height * ratio).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(decoded, (decoded.width - width) / 2, 0, width, decoded.height)
                } else {
                    val height = (decoded.width / ratio).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(decoded, 0, (decoded.height - height) / 2, decoded.width, height)
                }
                val scaled = if (cropped.width != targetWidth || cropped.height != targetHeight) {
                    Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
                } else {
                    cropped
                }
                val temp = File(requireContext().cacheDir, "main-top-bar-${UUID.randomUUID()}.webp")
                try {
                    FileOutputStream(temp).use { stream ->
                        require(scaled.compress(Bitmap.CompressFormat.WEBP, 90, stream))
                    }
                    MainTopBarImageManager.replaceFrom(temp, isNight).getOrThrow()
                } finally {
                    temp.delete()
                    if (scaled !== cropped) scaled.recycle()
                    if (cropped !== decoded) cropped.recycle()
                    decoded.recycle()
                }
                true
            }.getOrDefault(false)
        }

    private fun exportTopBarImageTo(uri: Uri, isNight: Boolean) {
        lifecycleScope.launch {
            val result = withContext(IO) {
                runCatching {
                    val source = MainTopBarImageManager.imageFile(isNight)
                    require(source.isFile && source.length() > 0) { "top bar image is empty" }
                    requireContext().contentResolver.openOutputStream(uri).use { stream ->
                        requireNotNull(stream)
                        source.inputStream().use { input -> input.copyTo(stream) }
                    }
                }
            }
            result.onSuccess {
                toastOnUi(R.string.main_top_bar_image_export_success)
            }.onFailure {
                toastOnUi(R.string.main_top_bar_image_export_failed)
            }
        }
    }

    private fun cropTopBarImage(uri: Uri, isNight: Boolean) {
        topBarImageNight = isNight
        cropTopBarResult.launch(
            Intent(requireContext(), MainTopBarImageActivity::class.java)
                .setData(uri)
                .putExtra(MainTopBarImageActivity.EXTRA_NIGHT, isNight)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }

    private fun topBarImageKey(isNight: Boolean): String {
        return if (isNight) PreferKey.mainTopBarImageNight else PreferKey.mainTopBarImage
    }

    @SuppressLint("InflateParams")
    private fun alertSaveTheme(key: String) {
        alert(R.string.theme_name) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "name"
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let { themeName ->
                    when (key) {
                        "saveDayTheme" -> {
                            ThemeConfig.saveDayTheme(requireContext(), themeName)
                        }

                        "saveNightTheme" -> {
                            ThemeConfig.saveNightTheme(requireContext(), themeName)
                        }
                    }
                }
            }
            cancelButton()
        }
    }

    private fun selectBgAction(isNight: Boolean) {
        val bgKey = if (isNight) PreferKey.bgImageN else PreferKey.bgImage
        val blurringKey = if (isNight) PreferKey.bgImageNBlurring else PreferKey.bgImageBlurring
        val actions = arrayListOf(
            getString(R.string.background_image_blurring),
            getString(R.string.select_image)
        )
        if (!getPrefString(bgKey).isNullOrEmpty()) {
            actions.add(getString(R.string.delete))
        }
        context?.selector(items = actions) { _, i ->
            when (i) {
                0 -> alertImageBlurring(blurringKey) {
                    upTheme(isNight)
                }

                1 -> {
                    if (isNight) {
                        selectImage.launch {
                            requestCode = requestCodeBgDark
                            mode = HandleFileContract.IMAGE
                        }
                    } else {
                        selectImage.launch {
                            requestCode = requestCodeBgLight
                            mode = HandleFileContract.IMAGE
                        }
                    }
                }

                2 -> {
                    removePref(bgKey)
                    upTheme(isNight)
                }
            }
        }
    }

    private fun alertImageBlurring(preferKey: String, success: () -> Unit) {
        alert(R.string.background_image_blurring) {
            val alertBinding = DialogImageBlurringBinding.inflate(layoutInflater).apply {
                getPrefInt(preferKey, 0).let {
                    seekBar.progress = it
                    textViewValue.text = it.toString()
                }
                seekBar.setOnSeekBarChangeListener(object : SeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        textViewValue.text = progress.toString()
                    }
                })
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.seekBar.progress.let {
                    putPrefInt(preferKey, it)
                    success.invoke()
                }
            }
            cancelButton()
        }
    }

    private fun upTheme(isNightTheme: Boolean) {
        if (AppConfig.isNightTheme == isNightTheme) {
            listView.post {
                ThemeConfig.applyTheme(requireContext())
                recreateActivities()
            }
        }
    }

    private fun recreateActivities() {
        postEvent(EventBus.RECREATE, "")
    }

    private fun upPreferenceSummary(preferenceKey: String, value: String? = null) {
        val preference = findPreference<Preference>(preferenceKey) ?: return
        when (preferenceKey) {
            PreferKey.barElevation -> preference.summary =
                getString(R.string.bar_elevation_s, value)

            PreferKey.fontScale -> {
                val fontScale = AppContextWrapper.getFontScale(requireContext())
                preference.summary = getString(R.string.font_scale_summary, fontScale)
            }

            PreferKey.bgImage,
            PreferKey.bgImageN -> preference.summary = if (value.isNullOrBlank()) {
                getString(R.string.select_image)
            } else {
                value
            }

            PreferKey.mainTopBarImage,
            PreferKey.mainTopBarImageNight -> {
                val isNight = preferenceKey == PreferKey.mainTopBarImageNight
                preference.summary = if (MainTopBarImageManager.hasImage(isNight)) {
                    getString(R.string.main_top_bar_image_set)
                } else {
                    getString(R.string.main_top_bar_image_summary)
                }
            }

            else -> preference.summary = value
        }
    }

    private fun setBgFromUri(uri: Uri, preferenceKey: String, success: () -> Unit) {
        if (uri.scheme?.lowercase() in listOf("http", "https")) {
            lifecycleScope.launch {
                kotlin.runCatching {
                    appCtx.toastOnUi("下载背景图片中...")
                    val analyzeUrl = AnalyzeUrl(uri.toString())
                    val url = analyzeUrl.urlNoQuery
                    var file = requireContext().externalFiles
                    val res = okHttpClient.newCallResponse(0) {
                        addHeaders(analyzeUrl.headerMap)
                        url(url)
                    }
                    val contentType = res.header("Content-Type") ?: "image/jpeg"
                    val imageType = when {
                        contentType.contains("png", ignoreCase = true) -> "png"
                        contentType.contains("gif", ignoreCase = true) -> "gif"
                        contentType.contains("webp", ignoreCase = true) -> "webp"
                        else -> "jpg"
                    }
                    val suffix = if (url.contains(".9.png", true)) {
                        ".9.png"
                    } else {
                        ".$imageType"
                    }
                    val fileName = MD5Utils.md5Encode(url) + suffix
                    file = FileUtils.createFileIfNotExist(file, preferenceKey, fileName)
                    res.body.byteStream().use { inputStream ->
                        FileOutputStream(file).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    putPrefString(preferenceKey, file.absolutePath)
                    if (isAdded && context != null) {
                        success()
                    }
                }.onSuccess {
                    appCtx.toastOnUi("设定成功")
                }.onFailure {
                    appCtx.toastOnUi(it.localizedMessage)
                }
            }
            return
        }
        readUri(uri) { fileDoc, inputStream ->
            kotlin.runCatching {
                var file = requireContext().externalFiles
                val suffix = if (fileDoc.name.contains(".9.png", true)) {
                    ".9.png"
                } else {
                    "." + fileDoc.name.substringAfterLast(".")
                }
                val fileName = uri.inputStream(requireContext()).getOrThrow().use {
                    MD5Utils.md5Encode(it) + suffix
                }
                file = FileUtils.createFileIfNotExist(file, preferenceKey, fileName)
                FileOutputStream(file).use {
                    inputStream.copyTo(it)
                }
                putPrefString(preferenceKey, file.absolutePath)
                success()
            }.onFailure {
                appCtx.toastOnUi(it.localizedMessage)
            }
        }
    }

}
