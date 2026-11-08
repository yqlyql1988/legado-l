package io.legado.app.ui.widget.image

import android.content.SharedPreferences
import android.graphics.Color
import android.os.SystemClock
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.model.BookCover
import io.legado.app.ui.about.AboutActivity
import io.legado.app.utils.defaultSharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoverTitleColorUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = context.defaultSharedPreferences
    private val saved = HashMap(preferences.all)
    private var oldUseDefault = AppConfig.useDefaultCover
    private var scenario: ActivityScenario<AboutActivity>? = null
    private var cover: CoverImageView? = null

    @Before
    fun setUp() {
        preferences.edit()
            .putBoolean(PreferKey.useDefaultCover, false)
            .putBoolean(PreferKey.coverShowName, true)
            .putBoolean(PreferKey.coverShowAuthor, true)
            .putBoolean(PreferKey.coverShowNameN, true)
            .putBoolean(PreferKey.coverShowAuthorN, true)
            .putBoolean(PreferKey.coverTitleCustomColor, true)
            .putBoolean(PreferKey.coverTitleCustomColorN, true)
            .putBoolean(PreferKey.coverHorizontal, false)
            .putBoolean(PreferKey.coverTitleAdaptive, true)
            .putBoolean(PreferKey.coverKeepPunctuation, true)
            .putBoolean(PreferKey.coverCustomFontSize, false)
            .commit()
        AppConfig.useDefaultCover = false
        instrumentation.runOnMainSync { BookCover.upDefaultCover() }
        scenario = ActivityScenario.launch(AboutActivity::class.java)
        scenario!!.onActivity { activity ->
            val root = FrameLayout(activity)
            val view = CoverImageView(activity)
            root.addView(view, FrameLayout.LayoutParams(240, 320))
            activity.setContentView(root)
            cover = view
            view.load(path = null, name = "封面颜色测试", author = "作者颜色")
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        preferences.edit().clear().apply { saved.forEach { (key, value) -> putValue(key, value) } }.commit()
        AppConfig.useDefaultCover = oldUseDefault
        instrumentation.runOnMainSync { BookCover.upDefaultCover() }
    }

    @Test
    fun customDayAndNightTitleColorsReachTheRenderedCover() {
        val dayText = Color.rgb(0, 200, 255)
        val dayShadow = Color.rgb(255, 0, 200)
        val nightText = Color.rgb(255, 210, 0)
        val nightShadow = Color.rgb(0, 80, 255)
        preferences.edit()
            .putBoolean(PreferKey.coverTitleCustomColor, true)
            .putInt(PreferKey.coverTitleTextColor, dayText)
            .putInt(PreferKey.coverTitleShadowColor, dayShadow)
            .putInt(PreferKey.coverTitleTextColorN, nightText)
            .putInt(PreferKey.coverTitleShadowColorN, nightShadow)
            .commit()

        AppConfig.isNightTheme = false
        instrumentation.runOnMainSync {
            BookCover.upDefaultCover()
            cover!!.invalidate()
        }
        assertTrue(BookCover.titleColorEnabled)
        assertEquals(dayText, BookCover.titleTextColor)
        assertEquals(dayShadow, BookCover.titleShadowColor)
        val dayPixels = renderedTitlePixels()
        assertTrue("day title uses the selected text color", dayPixels.any { it == dayText })
        assertTrue("day title uses the selected shadow color", dayPixels.any { it == dayShadow })

        instrumentation.runOnMainSync {
            AppConfig.isNightTheme = true
            BookCover.upDefaultCover()
            cover!!.invalidate()
        }
        assertEquals(nightText, BookCover.titleTextColor)
        assertEquals(nightShadow, BookCover.titleShadowColor)
        val nightPixels = renderedTitlePixels()
        assertTrue("night title uses the selected text color", nightPixels.any { it == nightText })
        assertTrue("night title uses the selected shadow color", nightPixels.any { it == nightShadow })
        assertFalse("night pixels differ from day pixels", dayPixels.contentEquals(nightPixels))

        preferences.edit().putBoolean(PreferKey.coverTitleCustomColor, false).commit()
        instrumentation.runOnMainSync {
            BookCover.upDefaultCover()
            cover!!.invalidate()
        }
        assertFalse(BookCover.titleColorEnabled)
    }

    private fun renderedTitlePixels(): IntArray {
        val field = CoverImageView::class.java.getDeclaredField("currentNameBitmap")
            .apply { isAccessible = true }
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            var pixels: IntArray? = null
            instrumentation.runOnMainSync {
                val view = cover!!
                if (view.width <= 0 || view.height <= 0) return@runOnMainSync
                val textColor = if (BookCover.titleColorEnabled) BookCover.titleTextColor
                else context.accentColor
                val shadowColor = if (BookCover.titleColorEnabled) BookCover.titleShadowColor
                else context.backgroundColor
                val expected = coverBitmapCacheKey(
                    "封面颜色测试",
                    "作者颜色",
                    view.width,
                    view.height,
                    BookCover.drawBookNameHorizontal,
                    BookCover.drawBookAuthor,
                    textColor,
                    shadowColor,
                    BookCover.adaptiveTitleSize,
                    BookCover.fontSizes,
                    BookCover.fontCacheKey,
                )
                val nameBitmap = (field.get(view) as? Pair<*, *>)
                    ?.takeIf { it.first == expected }
                    ?.second as? android.graphics.Bitmap
                if (nameBitmap != null) {
                    pixels = IntArray(nameBitmap.width * nameBitmap.height).also {
                        nameBitmap.getPixels(it, 0, nameBitmap.width, 0, 0, nameBitmap.width, nameBitmap.height)
                    }
                }
            }
            if (pixels != null) return pixels!!
            SystemClock.sleep(50)
        }
        error("cover title colors did not render")
    }
}

private fun SharedPreferences.Editor.putValue(key: String, value: Any?) {
    when (value) {
        is Boolean -> putBoolean(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key, value)
        is String -> putString(key, value)
        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
    }
}
