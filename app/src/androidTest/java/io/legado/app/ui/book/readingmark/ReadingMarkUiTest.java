package io.legado.app.ui.book.readingmark;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.hamcrest.Matcher;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

/** Uses public Android APIs and actual controls, including against the optimized release APK. */
@RunWith(AndroidJUnit4.class)
public class ReadingMarkUiTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String name = "阅读标记示例-" + UUID.randomUUID().toString().substring(0, 6);
    private final String url = "reading-mark-test:" + UUID.randomUUID();
    private final String author = "验证作者";
    private ActivityScenario<Activity> scenario;
    private SQLiteDatabase db;

    private int id(String key) {
        int result = context.getResources().getIdentifier(key, "id", context.getPackageName());
        assertNotEquals(key, 0, result);
        return result;
    }
    private String string(String key) {
        return context.getString(context.getResources().getIdentifier(key, "string", context.getPackageName()));
    }
    private void launch(String activity) {
        if (scenario != null) scenario.close();
        scenario = ActivityScenario.launch(new Intent().setClassName(context.getPackageName(),
            "io.legado.app." + activity).putExtra("bookUrl", url));
    }
    private void await(BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + 12000;
        while (SystemClock.uptimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(60);
        }
        assertTrue("Reading mark UI did not reach the expected state", condition.getAsBoolean());
    }
    private void ready(String key) {
        await(() -> {
            try {
                onView(withId(id(key))).check(matches(isEnabled()));
                return true;
            } catch (AssertionError | RuntimeException error) { return false; }
        });
    }
    private void spinner(String key, int position) {
        onView(withId(id(key))).perform(new ViewAction() {
            public Matcher<View> getConstraints() { return isAssignableFrom(Spinner.class); }
            public String getDescription() { return "Choose a reading status or chapter"; }
            public void perform(UiController controller, View view) {
                ((Spinner)view).setSelection(position);
                controller.loopMainThreadUntilIdle();
            }
        });
    }
    private void reason(String value) {
        onView(withId(id("mark_reason"))).perform(new ViewAction() {
            public Matcher<View> getConstraints() { return isAssignableFrom(EditText.class); }
            public String getDescription() { return "Enter an optional abandonment reason"; }
            public void perform(UiController controller, View view) {
                ((EditText)view).setText(value);
                controller.loopMainThreadUntilIdle();
            }
        });
    }
    private String field(String column) {
        try (Cursor cursor = db.rawQuery("select " + column +
            " from book_reading_marks where bookName = ? and bookAuthor = ?", new String[]{name, author})) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }
    private int skippedCount() {
        try { return new JSONArray(field("skipped")).length(); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private void information() {
        launch("ui.book.info.BookInfoActivity");
        ready("reading_mark");
        await(() -> {
            boolean[] loaded = {false};
            scenario.onActivity(activity -> loaded[0] =
                ((TextView)activity.findViewById(id("tv_name"))).getText().toString().equals(name));
            return loaded[0];
        });
    }
    private void editor() {
        onView(withId(id("reading_mark"))).perform(scrollTo(), click());
        ready("mark_save");
    }
    private void applyTheme(android.content.SharedPreferences prefs, String mode) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            if (mode == null) prefs.edit().remove("themeMode").commit();
            else prefs.edit().putString("themeMode", mode).commit();
            try {
                // Exercise the same kept theme entry point as the app's theme selector.
                Class<?> theme = context.getClassLoader().loadClass("io.legado.app.help.config.ThemeConfig");
                theme.getMethod("applyDayNightInit", Context.class).invoke(theme.getField("INSTANCE").get(null), context);
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private void screenshot(String key) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(300);
        Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(image);
        File folder = new File(context.getExternalFilesDir(null), "reading-mark-ui");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        try (FileOutputStream stream = new FileOutputStream(new File(folder, key + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally { image.recycle(); }
    }

    @Before public void setup() {
        launch("ui.about.ReadRecordActivity");
        ready("tv_reading_time");
        // Wait for the first Room query, not just inflation of an initially empty summary.
        await(() -> {
            boolean[] loaded = {false};
            scenario.onActivity(activity -> loaded[0] =
                ((TextView)activity.findViewById(id("tv_reading_time"))).length() > 0);
            return loaded[0];
        });
        scenario.close();
        scenario = null;
        db = SQLiteDatabase.openDatabase(context.getDatabasePath("legado.db").getPath(),
            null, SQLiteDatabase.OPEN_READWRITE);
        ContentValues book = new ContentValues();
        book.put("bookUrl", url); book.put("name", name); book.put("author", author);
        book.put("origin", "https://reading-mark-test.invalid");
        book.put("intro", "阅读标记与现有页面统一，支持记录弃读位置、读完和跳过未看的章节。");
        book.put("durChapterTitle", "第8章 · 测试章节");
        book.put("durChapterIndex", 7); book.put("durChapterPos", 345);
        book.put("totalChapterNum", 12);
        db.insertOrThrow("books", null, book);
        for (int index = 0; index < 12; index++) {
            ContentValues chapter = new ContentValues();
            chapter.put("bookUrl", url); chapter.put("url", url + "/c" + index);
            chapter.put("title", "第" + (index + 1) + "章 · 测试章节"); chapter.put("`index`", index);
            chapter.put("baseUrl", url); chapter.put("isVolume", 0); chapter.put("isVip", 0); chapter.put("isPay", 0);
            db.insertOrThrow("chapters", null, chapter);
        }
    }

    @After public void cleanup() {
        if (scenario != null) scenario.close();
        if (db != null) {
            db.delete("book_reading_marks", "bookName = ?", new String[]{name});
            db.delete("readRecord", "bookName = ?", new String[]{name});
            db.delete("chapters", "bookUrl = ?", new String[]{url});
            db.delete("books", "bookUrl = ?", new String[]{url});
            db.close();
        }
    }

    @Test public void compactInformationRowAbandonmentRangeRecreationAndCompletion() throws Exception {
        information();
        onView(withId(id("reading_mark_summary"))).check(matches(withText(string("reading_mark_unmarked"))));
        scenario.onActivity(activity -> {
            View mark = activity.findViewById(id("reading_mark"));
            View review = activity.findViewById(id("book_review"));
            assertSame(mark.getParent(), review.getParent());
            assertTrue(mark.getBottom() <= review.getTop());
            assertTrue(mark.getHeight() >= 48 * context.getResources().getDisplayMetrics().density);
            assertTrue(((TextView)activity.findViewById(id("reading_mark_summary"))).getTextSize()
                <= 14 * context.getResources().getDisplayMetrics().scaledDensity);
        });
        screenshot("information-unmarked");
        editor();
        spinner("mark_status", 2);
        reason("剧情重复，先放一放");
        onView(withId(id("mark_choose_range"))).perform(scrollTo(), click());
        spinner("mark_from", 1); spinner("mark_to", 3);
        onView(withId(id("mark_add_range"))).perform(scrollTo(), click());
        scenario.recreate();
        ready("mark_save");
        onView(withId(id("mark_reason"))).check(matches(withText("剧情重复，先放一放")));
        screenshot("editor-restored");
        onView(withId(id("mark_save"))).perform(click());
        await(() -> "abandoned".equals(field("status")));
        assertEquals(3, skippedCount());
        assertEquals(345, new JSONObject(field("position")).getInt("pos"));
        editor();
        spinner("mark_status", 3);
        onView(withId(id("mark_save"))).perform(click());
        await(() -> "finished".equals(field("status")));
        assertEquals(3, skippedCount());
        assertTrue(field("position").contains("第12章"));
        try (Cursor cursor = db.rawQuery("select durChapterIndex,durChapterPos from books where bookUrl=?",
            new String[]{url})) {
            assertTrue(cursor.moveToFirst()); assertEquals(7, cursor.getInt(0)); assertEquals(345, cursor.getInt(1));
        }
        screenshot("information-finished");
    }

    @Test public void catalogLongPressMarksAndUnmarksWithoutChangingReadingProgress() throws Exception {
        launch("ui.book.toc.TocActivity");
        ready("tv_current_chapter_info");
        await(() -> {
            try {
                onView(withId(id("iv_chapter_top"))).perform(click());
                onView(allOf(withId(id("tv_chapter_name")), withText("第2章 · 测试章节")))
                    .check(matches(isDisplayed()));
                return true;
            } catch (AssertionError | RuntimeException error) { return false; }
        });
        onView(allOf(withId(id("tv_chapter_name")), withText("第2章 · 测试章节"))).perform(longClick());
        onView(withText(string("reading_mark_skip_one"))).perform(click());
        await(() -> field("skipped") != null && skippedCount() == 1);
        assertEquals("unmarked", field("status"));
        openActionBarOverflowOrOptionsMenu(context);
        onView(withText(string("reading_mark_only_skipped"))).perform(click());
        onView(allOf(withId(id("tv_chapter_name")), withText("第2章 · 测试章节"))).check(matches(isDisplayed()));
        screenshot("catalog-only-skipped");
        scenario.recreate();
        ready("tv_current_chapter_info");
        await(() -> {
            try {
                onView(allOf(withId(id("tv_chapter_name")), withText("第2章 · 测试章节")))
                    .check(matches(isDisplayed()));
                return true;
            } catch (AssertionError | RuntimeException error) { return false; }
        });
        onView(allOf(withId(id("tv_chapter_name")), withText("第2章 · 测试章节"))).perform(longClick());
        onView(withText(string("reading_mark_unskip_one"))).perform(click());
        await(() -> skippedCount() == 0);
        onView(withId(id("toc_reading_mark_empty"))).check(matches(isDisplayed()));
    }

    @Test public void cancelDiscardsNewStatusAndRange() {
        information();
        editor();
        spinner("mark_status", 3);
        onView(withId(id("mark_cancel"))).perform(click());
        assertNull(field("status"));
        onView(withId(id("reading_mark_summary"))).check(matches(withText(string("reading_mark_unmarked"))));
    }

    @Test public void nightThemeUsesExistingPaletteForInformationAndDialog() throws Exception {
        android.content.SharedPreferences prefs = context.getSharedPreferences(context.getPackageName() + "_preferences", 0);
        String old = prefs.getString("themeMode", null);
        try {
            applyTheme(prefs, "1");
            information();
            int[] lightText = {0};
            scenario.onActivity(activity -> lightText[0] =
                ((TextView)activity.findViewById(id("reading_mark_summary"))).getCurrentTextColor());
            scenario.close();
            scenario = null;
            applyTheme(prefs, "2");
            information();
            scenario.onActivity(activity -> {
                int darkText = ((TextView)activity.findViewById(id("reading_mark_summary"))).getCurrentTextColor();
                assertTrue("Night mode must use the existing lighter text palette",
                    Color.luminance(darkText) > Color.luminance(lightText[0]));
            });
            screenshot("information-night");
            editor();
            spinner("mark_status", 2);
            onView(withId(id("mark_position"))).check((view, error) -> {
                if (error != null) throw error;
                assertTrue(Color.luminance(((TextView)view).getCurrentTextColor()) > 0.5f);
            });
            screenshot("editor-night");
        } finally {
            if (scenario != null) { scenario.close(); scenario = null; }
            applyTheme(prefs, old);
        }
    }
}
