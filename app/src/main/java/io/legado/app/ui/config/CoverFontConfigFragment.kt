package io.legado.app.ui.config

import android.content.SharedPreferences
import android.os.Bundle
import android.graphics.Typeface
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.model.BookCover
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.ui.font.installFontFile
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.FileDoc
import io.legado.app.utils.externalFiles
import io.legado.app.utils.openInputStream
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CoverFontConfigFragment : PreferenceFragment(), SharedPreferences.OnSharedPreferenceChangeListener,
    FontSelectDialog.CallBack {
    companion object {
        const val EXTRA_NIGHT = "coverStyleNight"
    }

    private val sizes = listOf(
        PreferKey.coverTitleLargeSize,
        PreferKey.coverTitleSmallSize,
        PreferKey.coverAuthorLargeSize,
        PreferKey.coverAuthorSmallSize,
        PreferKey.coverTitleLargeSizeN,
        PreferKey.coverTitleSmallSizeN,
        PreferKey.coverAuthorLargeSizeN,
        PreferKey.coverAuthorSmallSizeN,
    )
    private val fontKeys = listOf(PreferKey.coverFont, PreferKey.coverFontN)
    private val switchKeys = listOf(
        PreferKey.coverHorizontal,
        PreferKey.coverTitleAdaptive,
        PreferKey.coverKeepPunctuation,
        PreferKey.coverCustomFontSize,
        PreferKey.coverTitleCustomColor,
        PreferKey.coverHorizontalN,
        PreferKey.coverTitleAdaptiveN,
        PreferKey.coverKeepPunctuationN,
        PreferKey.coverCustomFontSizeN,
        PreferKey.coverTitleCustomColorN,
    )
    private val colorKeys = listOf(
        PreferKey.coverTitleTextColor,
        PreferKey.coverTitleShadowColor,
        PreferKey.coverTitleTextColorN,
        PreferKey.coverTitleShadowColorN,
    )
    private val styleKeys = switchKeys + sizes + fontKeys + colorKeys
    private var fontJob: Job? = null
    private var selectingNightFont = false

    private val nightStyle: Boolean
        get() = requireActivity().intent.getBooleanExtra(EXTRA_NIGHT, false)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        if (nightStyle) seedNightStyleFromDay()
        addPreferencesFromResource(R.xml.pref_config_cover_font)
        val hiddenCategory =
            if (nightStyle) "coverDayStyleCategory" else "coverNightStyleCategory"
        findPreference<androidx.preference.PreferenceCategory>(hiddenCategory)?.let {
            preferenceScreen.removePreference(it)
        }
        styleKeys.forEach(::updateSummary)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(
            if (nightStyle) R.string.cover_font_config_night
            else R.string.cover_font_config_day
        )
    }

    override fun onDestroy() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (!isAdded) return
        if (key !in styleKeys) return
        key?.let(::updateSummary)
        BookCover.upDefaultCover()
        findPreference<CoverPreviewPreference>("coverPreview")?.refresh()
        postEvent(EventBus.BOOKSHELF_REFRESH, "")
    }

    /**
     * 夜间键第一次使用前复制白天值，旧版本只需白天设置也能保持日夜一致。
     */
    private fun seedNightStyleFromDay() {
        val prefs = requireContext().defaultSharedPreferences
        val edit = prefs.edit()
        var changed = false
        fun copyBoolean(dayKey: String, nightKey: String) {
            if (!prefs.contains(nightKey) && prefs.contains(dayKey)) {
                edit.putBoolean(nightKey, prefs.getBoolean(dayKey, false))
                changed = true
            }
        }

        fun copyInt(dayKey: String, nightKey: String) {
            if (!prefs.contains(nightKey) && prefs.contains(dayKey)) {
                edit.putInt(nightKey, prefs.getInt(dayKey, 100))
                changed = true
            }
        }

        fun copyString(dayKey: String, nightKey: String) {
            if (!prefs.contains(nightKey) && prefs.contains(dayKey)) {
                edit.putString(nightKey, prefs.getString(dayKey, null))
                changed = true
            }
        }

        copyBoolean(PreferKey.coverHorizontal, PreferKey.coverHorizontalN)
        copyBoolean(PreferKey.coverTitleAdaptive, PreferKey.coverTitleAdaptiveN)
        copyBoolean(PreferKey.coverKeepPunctuation, PreferKey.coverKeepPunctuationN)
        copyBoolean(PreferKey.coverCustomFontSize, PreferKey.coverCustomFontSizeN)
        copyBoolean(PreferKey.coverTitleCustomColor, PreferKey.coverTitleCustomColorN)
        copyString(PreferKey.coverFont, PreferKey.coverFontN)
        copyInt(PreferKey.coverTitleLargeSize, PreferKey.coverTitleLargeSizeN)
        copyInt(PreferKey.coverTitleSmallSize, PreferKey.coverTitleSmallSizeN)
        copyInt(PreferKey.coverAuthorLargeSize, PreferKey.coverAuthorLargeSizeN)
        copyInt(PreferKey.coverAuthorSmallSize, PreferKey.coverAuthorSmallSizeN)
        copyInt(PreferKey.coverTitleTextColor, PreferKey.coverTitleTextColorN)
        copyInt(PreferKey.coverTitleShadowColor, PreferKey.coverTitleShadowColorN)
        if (changed) edit.apply()
    }

    private fun updateSummary(key: String) {
        if (key in sizes) {
            findPreference<Preference>(key)?.summary = "${effectiveInt(key).coerceIn(50, 200)}%"
        }
        if (key in fontKeys) {
            findPreference<Preference>(key)?.summary = effectiveFontPath(key)
                .takeIf { it.isNotBlank() }?.let { File(it).name }
                ?: getString(R.string.default_font)
        }
    }

    private fun effectiveInt(key: String): Int {
        val prefs = requireContext().defaultSharedPreferences
        val dayKey = when (key) {
            PreferKey.coverTitleLargeSizeN -> PreferKey.coverTitleLargeSize
            PreferKey.coverTitleSmallSizeN -> PreferKey.coverTitleSmallSize
            PreferKey.coverAuthorLargeSizeN -> PreferKey.coverAuthorLargeSize
            PreferKey.coverAuthorSmallSizeN -> PreferKey.coverAuthorSmallSize
            else -> key
        }
        return if (prefs.contains(key)) prefs.getInt(key, 100) else prefs.getInt(dayKey, 100)
    }

    private fun effectiveFontPath(key: String): String {
        val prefs = requireContext().defaultSharedPreferences
        val dayKey = if (key == PreferKey.coverFontN) PreferKey.coverFont else key
        return if (prefs.contains(key)) {
            prefs.getString(key, "").orEmpty()
        } else {
            prefs.getString(dayKey, "").orEmpty()
        }
    }

    override val curFontPath: String
        get() = effectiveFontPath(
            if (selectingNightFont) PreferKey.coverFontN else PreferKey.coverFont
        )
    override val selectSystemTypefaceOnDefault = false

    override fun selectFont(path: String) {
        val targetKey =
            if (selectingNightFont) PreferKey.coverFontN else PreferKey.coverFont
        fontJob?.cancel()
        if (path.isEmpty()) {
            putPrefString(targetKey, "")
            return
        }
        val directory = File(requireContext().externalFiles, "font")
        fontJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val source = FileDoc.fromFile(path)
                    source.openInputStream().getOrThrow().use { input ->
                        installFontFile(input, source.name, directory) {
                            runCatching { Typeface.createFromFile(it) }.isSuccess
                        }.absolutePath
                    }
                }
            }
            result.onSuccess { putPrefString(targetKey, it) }
                .onFailure { requireContext().toastOnUi(it.localizedMessage) }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            PreferKey.coverFont -> {
                selectingNightFont = false
                showDialogFragment(FontSelectDialog())
                return true
            }

            PreferKey.coverFontN -> {
                selectingNightFont = true
                showDialogFragment(FontSelectDialog())
                return true
            }

            in sizes -> {
                NumberPickerDialog(requireContext())
                    .setTitle(preference.title.toString())
                    .setMinValue(50).setMaxValue(200)
                    .setValue(getPrefInt(preference.key, 100).coerceIn(50, 200))
                    .setCustomButton(R.string.btn_default_s) { putPrefInt(preference.key, 100) }
                    .show { putPrefInt(preference.key, it) }
                return true
            }
        }
        return super.onPreferenceTreeClick(preference)
    }
}
