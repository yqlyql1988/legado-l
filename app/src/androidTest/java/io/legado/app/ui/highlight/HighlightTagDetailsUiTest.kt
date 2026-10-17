package io.legado.app.ui.highlight

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.HighlightTag
import io.legado.app.constant.PreferKey
import io.legado.app.help.HighlightStyle
import io.legado.app.lib.theme.ThemeStorePrefKeys
import io.legado.app.ui.widget.TitleBar
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import org.hamcrest.Matchers.allOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HighlightTagDetailsUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun toolbarBackKeepsTheUnsavedNoteConfirmation() {
        val tags = appDb.highlightTagDao.all()
        val links = appDb.highlightTagDao.links()
        val orders = appDb.highlightTagDao.orders()
        val times = appDb.bookHighlightDao.all.map { it.time }
        val fixture = HighlightTag(id = "unsaved-note", name = "草稿验证", note = "原备注")
        try {
            appDb.highlightTagDao.replaceDefinitions(listOf(fixture))
            ActivityScenario.launch<HighlightTagManageActivity>(
                Intent(context, HighlightTagManageActivity::class.java)).use {
                onView(allOf(withText(fixture.name), isDescendantOfA(withId(R.id.recycler_view)))).perform(click())
                onView(withId(R.id.et_note)).inRoot(isDialog()).perform(replaceText("待保存的备注"), closeSoftKeyboard())
                onView(withId(R.id.btn_ok)).inRoot(isDialog()).perform(click())
                onView(withContentDescription(R.string.back)).perform(click())
                onView(withText(R.string.highlight_tag_unsaved)).inRoot(isDialog()).check(matches(isDisplayed()))
                onView(withText(R.string.cancel)).inRoot(isDialog()).perform(click())
                onView(withText("待保存的备注")).check(matches(isDisplayed()))
                assertEquals("原备注", appDb.highlightTagDao.all().single().note)
                onView(withContentDescription(R.string.back)).perform(click())
                onView(withText(R.string.highlight_tag_discard)).inRoot(isDialog()).perform(click())
                assertEquals("原备注", appDb.highlightTagDao.all().single().note)
            }
        } finally { appDb.highlightTagDao.restore(tags, links, times, orders) }
    }

    @Test fun navigationContrastAndIndependentSamplesFollowToolbarThemes() {
        val prefs = context.getSharedPreferences(ThemeStorePrefKeys.CONFIG_PREFS_KEY_DEFAULT, Context.MODE_PRIVATE)
        val keys = listOf(ThemeStorePrefKeys.KEY_PRIMARY_COLOR, ThemeStorePrefKeys.KEY_BACKGROUND_COLOR,
            ThemeStorePrefKeys.KEY_BOTTOM_BACKGROUND, ThemeStorePrefKeys.KEY_TRANSPARENT_NAV_BAR)
        val saved = keys.associateWith { prefs.all[it] }
        val savedNightMode = AppCompatDelegate.getDefaultNightMode()
        val savedThemeMode = context.defaultSharedPreferences.getString(PreferKey.themeMode, null)
        val tags = appDb.highlightTagDao.all()
        val links = appDb.highlightTagDao.links()
        val orders = appDb.highlightTagDao.orders()
        val times = appDb.bookHighlightDao.all.map { it.time }
        val style = HighlightStyle(fill = 0x80FFE082.toInt(), fillShape = HighlightStyle.FillShape.PILL,
            textColor = 0xFFB54E2F.toInt(), bold = true, italic = true,
            underline = HighlightStyle.Underline(HighlightStyle.Kind.DOUBLE, 0xFF43864C.toInt(), 3f, 12f),
            emphasis = HighlightStyle.Deco(0xFF43864C.toInt()), shadow = HighlightStyle.Shadow())
        val fixture = listOf(
            HighlightTag(id = "details-a", name = "很长的标签名称用于验证名称省略与右侧示例布局",
                style = GSON.toJson(style), note = "用来说明标签的具体含义，较长的备注在列表中最多显示两行。\n完整说明在编辑页查看。"),
            HighlightTag(id = "details-b", name = "无备注", sortOrder = 1,
                style = GSON.toJson(HighlightStyle(underline = HighlightStyle.Underline(color = Color.BLUE)))),
            HighlightTag(id = "details-c", name = "大字号边距", sortOrder = 2,
                style = GSON.toJson(style.copy(fontSize = 100f, horizontalPadding = 64f,
                    underline = style.underline!!.copy(width = 10f, distance = 30f)))))
        try {
            appDb.highlightTagDao.replaceDefinitions(fixture)
            for ((name, primary, background, transparent) in listOf(
                ThemeCase("light", 0xFFFAFAFA.toInt(), 0xFFFAFAFA.toInt(), false),
                ThemeCase("dark", 0xFF202124.toInt(), 0xFF202124.toInt(), false),
                ThemeCase("colored", 0xFF305C46.toInt(), 0xFFFAFAFA.toInt(), false),
                ThemeCase("transparent", 0xFF202124.toInt(), 0xFFFAFAFA.toInt(), true)
            )) {
                instrumentation.runOnMainSync {
                    context.defaultSharedPreferences.edit()
                        .putString(PreferKey.themeMode, if (name == "dark") "2" else "1").commit()
                    AppCompatDelegate.setDefaultNightMode(if (name == "dark")
                        AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
                }
                prefs.edit().putInt(keys[0], primary).putInt(keys[1], background)
                    .putInt(keys[2], background).putBoolean(keys[3], transparent).commit()
                ActivityScenario.launch<HighlightTagManageActivity>(
                    Intent(context, HighlightTagManageActivity::class.java)).use { scenario ->
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        val titleBar = activity.findViewById<TitleBar>(R.id.title_bar)
                        val icon = requireNotNull(titleBar.toolbar.navigationIcon)
                        val bounds = Rect(icon.bounds)
                        val image = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
                        try {
                            val barColor = if (transparent) background else primary
                            image.eraseColor(barColor)
                            icon.setBounds(0, 0, 48, 48)
                            icon.draw(Canvas(image))
                            val contrast = (0 until 48).maxOf { x -> (0 until 48).maxOf { y ->
                                ColorUtils.calculateContrast(image.getPixel(x, y), barColor)
                            } }
                            assertTrue("$name arrow contrast=$contrast automatic=${titleBar.usesTransparentForeground} " +
                                "background=${titleBar.background} alpha=${titleBar.background.alpha} icon=${icon.javaClass}", contrast >= 3.0)
                        } finally { icon.bounds = bounds; image.recycle() }
                        val save = titleBar.toolbar.findViewById<TextView>(R.id.btn_save)
                        assertTrue("$name save text contrast",
                            ColorUtils.calculateContrast(save.currentTextColor,
                                if (transparent) background else primary) >= 4.5)
                        val recycler = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler_view)
                        val first = requireNotNull(recycler.findViewHolderForAdapterPosition(0)).itemView
                        val label = first.findViewById<TextView>(R.id.tv_name)
                        val preview = first.findViewById<HighlightTagPreview>(R.id.tv_preview)
                        assertFalse(label is HighlightTagPreview)
                        assertEquals("开源阅读", preview.text.toString())
                        assertTrue(preview.left >= label.width)
                        assertEquals(style, preview.highlightStyle)
                        val empty = requireNotNull(recycler.findViewHolderForAdapterPosition(1)).itemView
                        assertFalse(empty.findViewById<TextView>(R.id.tv_note).isShown)
                    }
                    screenshot("manager-$name")
                    onView(allOf(withText(fixture.first().name),
                        isDescendantOfA(withId(R.id.recycler_view)))).perform(click())
                    onView(withId(R.id.btn_ok)).inRoot(isDialog()).check { view, error ->
                        if (error != null) throw error
                        assertTrue("$name editor save text contrast",
                            ColorUtils.calculateContrast((view as TextView).currentTextColor,
                                if (transparent) background else primary) >= 4.5)
                    }
                    screenshot("editor-$name")
                    onView(withText(R.string.action_save)).inRoot(isDialog()).perform(click())
                    // The normal toolbar arrow still honors the manager's unsaved-change flow.
                    scenario.onActivity {
                        (it.supportFragmentManager.fragments.firstOrNull() as? HighlightTagEditDialog)?.dismiss()
                    }
                }
            }
        } finally {
            appDb.highlightTagDao.restore(tags, links, times, orders)
            prefs.edit().apply { saved.forEach { (key, value) -> when (value) {
                null -> remove(key)
                is Int -> putInt(key, value)
                is Boolean -> putBoolean(key, value)
            } } }.commit()
            instrumentation.runOnMainSync {
                context.defaultSharedPreferences.edit().apply {
                    if (savedThemeMode == null) remove(PreferKey.themeMode)
                    else putString(PreferKey.themeMode, savedThemeMode)
                }.commit()
                AppCompatDelegate.setDefaultNightMode(savedNightMode)
            }
        }
    }

    private data class ThemeCase(val name: String, val primary: Int, val background: Int, val transparent: Boolean)

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        // A launched activity can be idle before its first frame reaches the compositor.
        android.os.SystemClock.sleep(200)
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = File(context.getExternalFilesDir(null), "tag-ui").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
    }
}
