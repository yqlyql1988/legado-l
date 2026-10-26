package io.legado.app.ui.book.tag;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

/** Data-changing UI checks are restricted to a separate private QA application. */
@RunWith(AndroidJUnit4.class)
public class BookTagUiTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String prefix = "tag-ui-" + UUID.randomUUID();
    private SQLiteDatabase db;
    private ActivityScenario<Activity> scenario;
    private SharedPreferences preferences;
    private String bookUrl = prefix + "-0";
    private File localBook;

    private int id(String name) {
        int result = context.getResources().getIdentifier(name, "id", context.getPackageName());
        assertTrue(name, result != 0);
        return result;
    }

    private String text(String name) {
        return context.getString(context.getResources().getIdentifier(name, "string", context.getPackageName()));
    }

    private void await(BooleanSupplier condition) {
        long end = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < end) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(50);
        }
        assertTrue("Timed out waiting for book tags", condition.getAsBoolean());
    }

    private void launch(String activity) {
        if (scenario != null) scenario.close();
        Intent intent = new Intent().setClassName(context.getPackageName(), activity);
        if (activity.endsWith("BookInfoActivity") || activity.endsWith("BookInfoEditActivity")) {
            intent.putExtra("bookUrl", bookUrl);
        }
        scenario = ActivityScenario.launch(intent);
        boolean landscape = InstrumentationRegistry.getArguments()
                .getString("tagVariant", "").contains("landscape");
        scenario.onActivity(screen -> screen.setRequestedOrientation(landscape
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(250);
    }

    private void insertBook(int index, String kind, String tags) throws Exception {
        Object book = Class.forName("io.legado.app.data.entities.Book").getConstructor().newInstance();
        for (String[] value : new String[][] {
                {"bookUrl", prefix + "-" + index}, {"name", "标签界面示例" + index},
                {"author", prefix}, {"origin", "https://tag-ui.invalid"},
                {"tocUrl", "https://tag-ui.invalid/toc"},
                {"coverUrl", "file:///tag-ui-cover"}, {"kind", kind}, {"customTag", tags},
                {"intro", "书源标签和我的标签可以合并查看，个人标签在换源后保留。"}
        }) {
            Field field = book.getClass().getDeclaredField(value[0]);
            field.setAccessible(true);
            field.set(book, value[1]);
        }
        ContentValues row = new ContentValues();
        try (Cursor columns = db.rawQuery("PRAGMA table_info(books)", null)) {
            while (columns.moveToNext()) {
                String name = columns.getString(1);
                Field field = book.getClass().getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(book);
                String key = "`" + name + "`";
                if (value == null) row.putNull(key);
                else if (value instanceof Boolean) row.put(key, (Boolean) value ? 1 : 0);
                else if (value instanceof Number) row.put(key, ((Number) value).longValue());
                else row.put(key, (String) value);
            }
        }
        db.insertOrThrow("books", null, row);
    }

    private String column(String column) {
        try (Cursor result = db.rawQuery("SELECT " + column + " FROM books WHERE bookUrl=?",
                new String[]{prefix + "-0"})) {
            assertTrue(result.moveToFirst());
            return result.getString(0);
        }
    }

    private void screenshot(String name) throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        String variant = args.getString("tagVariant", "light");
        File folder = new File(context.getExternalFilesDir(null), "book-tag-ui");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        SystemClock.sleep(250);
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        try (FileOutputStream stream = new FileOutputStream(new File(folder, name + "-" + variant + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally { bitmap.recycle(); }
    }

    @Before public void setup() throws Exception {
        assertTrue("Use a separate QA package", context.getPackageName().endsWith(".taguiqa"));
        preferences = context.getSharedPreferences(context.getPackageName() + "_preferences", 0);
        Bundle args = InstrumentationRegistry.getArguments();
        preferences.edit().putString("language", "zh")
                .putString("themeMode", args.getString("tagTheme", "1"))
                .putInt("fontScale", Integer.parseInt(args.getString("tagFontScale", "10")))
                .remove("bookTagOverviewSort").remove("bookTagOverviewFilter")
                .remove("bookInfoTagFilter").commit();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                Class<?> config = Class.forName("io.legado.app.help.config.ThemeConfig");
                config.getMethod("applyDayNightInit", Context.class)
                        .invoke(config.getField("INSTANCE").get(null), context);
            } catch (Exception error) { throw new RuntimeException(error); }
        });
        Object database = Class.forName("io.legado.app.data.AppDatabaseKt")
                .getMethod("getAppDb").invoke(null);
        Object helper = Class.forName("androidx.room.RoomDatabase")
                .getMethod("getOpenHelper").invoke(database);
        Class.forName("androidx.sqlite.db.SupportSQLiteOpenHelper")
                .getMethod("getWritableDatabase").invoke(helper);
        db = SQLiteDatabase.openDatabase(context.getDatabasePath("legado.db").getPath(),
                null, SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING);
        insertBook(0, "总攻,主攻", "总攻,已读");
        insertBook(1, "总攻,主攻", "已读");
        insertBook(2, "总攻", null);
    }

    private void choose(String name) {
        onView(allOf(withText(name), isDescendantOfA(withId(id("recycler_view"))))).perform(click());
    }

    private void firstTag(String expected) {
        await(() -> {
            final String[] name = {null};
            scenario.onActivity(activity -> {
                android.view.ViewGroup recycler = activity.findViewById(id("recycler_view"));
                if (recycler.getChildCount() > 0) {
                    TextView label = recycler.getChildAt(0).findViewById(id("tv_name"));
                    if (label != null) name[0] = label.getText().toString();
                }
            });
            return expected.equals(name[0]);
        });
    }

    @Test public void managementRemembersSortAndFilterWithoutDroppingSharedTags() throws Exception {
        launch("io.legado.app.ui.book.tag.BookTagActivity");
        firstTag("总攻");
        onView(allOf(withText(text("book_tag_manage")), isDescendantOfA(withId(id("toolbar")))))
                .check(matches(isDisplayed()));
        screenshot("management");
        onView(withId(id("menu_sort"))).perform(click());
        choose(text("book_tag_sort_user"));
        firstTag("已读");
        assertEquals("USER", preferences.getString("bookTagOverviewSort", ""));
        onView(withId(id("menu_filter"))).perform(click());
        choose(text("book_my_tags"));
        onView(withText("主攻")).check(doesNotExist());
        onView(withText("总攻")).check(matches(isDisplayed()));
        launch("io.legado.app.ui.book.tag.BookTagActivity");
        firstTag("已读");
        onView(withId(id("menu_sort")))
                .check(matches(withContentDescription(text("book_tag_sort_user"))));
        onView(withText("主攻")).check(doesNotExist());
        assertEquals("USER", preferences.getString("bookTagOverviewFilter", ""));
        onView(withId(id("menu_filter"))).perform(click());
        choose(text("book_source_tags"));
        onView(withText("已读")).check(doesNotExist());
        onView(withText("总攻")).check(matches(isDisplayed()));
        onView(withText("主攻")).check(matches(isDisplayed()));
        onView(withId(id("menu_filter"))).perform(click());
        choose(text("book_tag_filter_all"));
        screenshot("management-my-order");
    }

    private void openEditor() {
        onView(withContentDescription(text("book_custom_tags_edit"))).perform(scrollTo(), click());
        onView(withId(id("tie_custom_tag"))).check(matches(isDisplayed()));
    }

    private void toggle(String name) {
        onView(allOf(withText(name), isDescendantOfA(withId(id("lb_common_tag"))))).perform(click());
    }

    private int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void updateDetail(String kind, String customTag) {
        ContentValues values = new ContentValues();
        values.put("kind", kind);
        values.put("customTag", customTag);
        db.update("books", values, "bookUrl=?", new String[]{bookUrl});
    }

    private void waitForLabels(int count) {
        await(() -> {
            final boolean[] ready = {false};
            scenario.onActivity(screen -> {
                android.view.ViewGroup labels = screen.findViewById(id("lb_kind"));
                ready[0] = labels.getChildCount() == count && labels.getHeight() > 0
                        && !labels.isLayoutRequested();
            });
            return ready[0];
        });
    }

    private void assertActionGeometry() {
        scenario.onActivity(screen -> {
            android.view.ViewGroup labels = screen.findViewById(id("lb_kind"));
            android.widget.ImageButton action = screen.findViewById(id("ib_edit_tags"));
            android.view.ViewGroup header = (android.view.ViewGroup) action.getParent();
            int[] origin = new int[2], bar = new int[2], button = new int[2];
            header.getLocationOnScreen(origin);
            labels.getLocationOnScreen(bar);
            action.getLocationOnScreen(button);
            assertEquals("Labels stay centered without counting the action",
                    origin[0] + header.getWidth() / 2, bar[0] + labels.getWidth() / 2, 1);
            assertTrue("Entire touch target stays in its container", action.getLeft() >= 0
                    && action.getRight() <= header.getWidth());
            assertTrue("Retain a large target within the available margin",
                    action.getWidth() >= dp(14) && action.getWidth() <= dp(28));
            assertEquals(dp(28), action.getHeight());
            assertEquals(android.widget.ImageView.ScaleType.FIT_CENTER, action.getScaleType());
            assertEquals(dp(14), action.getWidth() - action.getPaddingLeft() - action.getPaddingRight());
            if (labels.getChildCount() == 0) {
                assertEquals(bar[0] + labels.getWidth() / 2,
                        button[0] + action.getPaddingLeft() + dp(14) / 2, 1);
                return;
            }
            android.view.View last = labels.getChildAt(labels.getChildCount() - 1);
            int[] tail = new int[2];
            last.getLocationOnScreen(tail);
            assertTrue("The edit action never intercepts tag taps",
                    button[0] >= tail[0] + last.getWidth());
            assertEquals("Action uses the existing end margin with a 2dp visual gap",
                    tail[0] + last.getWidth() + dp(2), button[0] + action.getPaddingLeft(), 1);
            assertEquals("Action stays on the last label row",
                    tail[1] + last.getHeight() / 2, button[1] + action.getHeight() / 2, 1);
        });
    }

    @Test public void fullRowKeepsItsOriginalWidthAndNeverWrapsTheAction() throws Exception {
        updateDetail(new String(new char[80]).replace(String.valueOf((char) 0), "总攻"), null);
        launch("io.legado.app.ui.book.info.BookInfoActivity");
        waitForLabels(1);
        final int[] before = new int[6];
        scenario.onActivity(screen -> {
            android.view.ViewGroup labels = screen.findViewById(id("lb_kind"));
            android.view.View last = labels.getChildAt(0);
            android.view.View action = screen.findViewById(id("ib_edit_tags"));
            android.view.ViewGroup header = (android.view.ViewGroup) action.getParent();
            boolean landscape = context.getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            assertEquals("Use the original full label width", header.getWidth() - dp(landscape ? 32 : 36),
                    labels.getWidth(), 1);
            assertTrue("The label actually fills the row", last.getWidth() >= labels.getWidth() - dp(2) - 1);
            before[0] = labels.getWidth();
            before[1] = labels.getHeight();
            before[2] = last.getLeft();
            before[3] = last.getTop();
            before[4] = last.getWidth();
            before[5] = last.getHeight();
            action.setVisibility(android.view.View.GONE);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        scenario.onActivity(screen -> {
            android.view.ViewGroup labels = screen.findViewById(id("lb_kind"));
            android.view.View last = labels.getChildAt(0);
            assertArrayEquals("Showing the action never changes label layout", before,
                    new int[]{labels.getWidth(), labels.getHeight(), last.getLeft(), last.getTop(),
                            last.getWidth(), last.getHeight()});
            screen.findViewById(id("ib_edit_tags")).setVisibility(android.view.View.VISIBLE);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertActionGeometry();
        onView(withId(id("ib_edit_tags"))).perform(scrollTo());
        screenshot("detail-full-row");
        openEditor();
        onView(withId(id("tie_custom_tag"))).check(matches(withText("")));
    }

    @Test public void multilineAndEmptyTagsKeepAnIndependentEditAction() throws Exception {
        StringBuilder tags = new StringBuilder("总攻,主攻");
        for (int index = 1; index <= 16; index++) tags.append(",示例标签").append(index);
        updateDetail(tags.toString(), "已读");
        preferences.edit().putString("bookInfoTagFilter", "SOURCE").commit();
        launch("io.legado.app.ui.book.info.BookInfoActivity");
        waitForLabels(19);
        onView(allOf(withText("已读"), isDescendantOfA(withId(id("lb_kind")))))
                .check(matches(isDisplayed()));
        scenario.onActivity(screen -> {
            android.view.ViewGroup labels = screen.findViewById(id("lb_kind"));
            assertTrue("Test multiple rows", labels.getChildAt(0).getTop()
                    < labels.getChildAt(labels.getChildCount() - 1).getTop());
        });
        assertActionGeometry();
        onView(withId(id("ib_edit_tags"))).perform(scrollTo());
        screenshot("detail-multiline");
        updateDetail(null, null);
        launch("io.legado.app.ui.book.info.BookInfoActivity");
        waitForLabels(0);
        assertActionGeometry();
        screenshot("detail-empty");
        openEditor();
        onView(withId(id("tie_custom_tag"))).check(matches(withText("")));
    }

    @Test public void wordCountAndLocalSizeUseFilledMetadataWithoutBecomingPersonalTags() throws Exception {
        localBook = new File(context.getFilesDir(), prefix + ".txt");
        try (FileOutputStream stream = new FileOutputStream(localBook)) {
            stream.write(new byte[2048]);
        }
        ContentValues values = new ContentValues();
        bookUrl = localBook.getAbsolutePath();
        values.put("bookUrl", bookUrl);
        values.put("origin", "loc_book");
        values.put("type", 256);
        values.put("wordCount", "12万字");
        db.update("books", values, "bookUrl=?", new String[]{prefix + "-0"});
        launch("io.legado.app.ui.book.info.BookInfoActivity");
        waitForLabels(5);
        onView(withId(id("lb_kind"))).check((view, error) -> {
            if (error != null) throw error;
            android.view.ViewGroup labels = (android.view.ViewGroup) view;
            assertEquals("Metadata, source and shared tags keep solid backgrounds",
                    "AccentBgTextView", labels.getChildAt(0).getClass().getSimpleName());
            assertEquals("File size also keeps a solid background",
                    "AccentBgTextView", labels.getChildAt(3).getClass().getSimpleName());
            assertEquals("Only personal tags use a light background",
                    "AccentTonalBgTextView", labels.getChildAt(4).getClass().getSimpleName());
            assertNull(labels.getChildAt(0).getContentDescription());
            assertNull(labels.getChildAt(3).getContentDescription());
        });
        assertActionGeometry();
        screenshot("detail-metadata");
        openEditor();
        onView(withId(id("tie_custom_tag"))).check(matches(withText("总攻,已读")));
    }

    @Test public void detailEditingPreservesSourceTagsAndCancelLeavesSavedTagsUntouched() throws Exception {
        launch("io.legado.app.ui.book.info.BookInfoActivity");
        onView(allOf(withText("总攻"), isDescendantOfA(withId(id("lb_kind")))))
                .check(matches(withContentDescription("总攻，书源与我的标签")));
        onView(allOf(withText("主攻"), isDescendantOfA(withId(id("lb_kind")))))
                .check(matches(withContentDescription("主攻，仅书源标签")));
        onView(allOf(withText("已读"), isDescendantOfA(withId(id("lb_kind")))))
                .check(matches(withContentDescription("已读，仅我的标签")));
        screenshot("detail");
        openEditor();
        toggle("主攻");
        onView(withId(id("btn_cancel"))).perform(click());
        assertEquals("总攻,已读", column("customTag"));
        openEditor();
        onView(withId(id("tie_common_tag_search"))).perform(replaceText("攻"));
        androidx.test.espresso.Espresso.closeSoftKeyboard();
        onView(allOf(withText("主攻"), isDescendantOfA(withId(id("lb_common_tag")))))
                .check(matches(isDisplayed()));
        onView(allOf(withText("总攻"), isDescendantOfA(withId(id("lb_common_tag")))))
                .check(matches(isDisplayed()));
        toggle("主攻");
        screenshot("editor");
        onView(withId(id("btn_ok"))).perform(click());
        await(() -> column("customTag").contains("主攻"));
        assertEquals("总攻,主攻", column("kind"));
        openEditor();
        toggle("总攻");
        onView(withId(id("btn_ok"))).perform(click());
        await(() -> !column("customTag").contains("总攻"));
        assertEquals("总攻,主攻", column("kind"));
        onView(allOf(withText("总攻"), isDescendantOfA(withId(id("lb_kind")))))
                .check(matches(withContentDescription("总攻，仅书源标签")));
    }

    @Test public void editorCollapsesLongCandidatesAndKeepsDraftAfterRecreation() throws Exception {
        StringBuilder sourceTags = new StringBuilder("总攻,主攻");
        for (int index = 1; index <= 64; index++) sourceTags.append(",示例标签").append(index);
        ContentValues values = new ContentValues();
        values.put("kind", sourceTags.toString());
        db.update("books", values, "bookUrl=?", new String[]{prefix + "-0"});
        launch("io.legado.app.ui.book.info.BookInfoActivity");
        openEditor();
        onView(withId(id("tv_common_tag_expand"))).perform(scrollTo()).check(matches(isDisplayed()));
        onView(withId(id("lb_common_tag"))).check((view, error) -> {
            if (error != null) throw error;
            android.view.ViewGroup labels = (android.view.ViewGroup) view;
            java.util.Set<Integer> rows = new java.util.HashSet<>();
            int visible = 0;
            for (int index = 0; index < labels.getChildCount(); index++) {
                android.view.View child = labels.getChildAt(index);
                if (child.getVisibility() == android.view.View.VISIBLE) {
                    rows.add(child.getTop());
                    visible++;
                }
            }
            assertEquals(3, rows.size());
            assertTrue("Remaining candidates must be hidden", visible < labels.getChildCount());
        });
        screenshot("editor-collapsed");
        onView(withId(id("tv_common_tag_expand"))).perform(scrollTo(), click());
        onView(withId(id("tv_common_tag_expand"))).check(matches(withText(text("book_tag_existing_collapse"))));
        screenshot("editor-expanded");
        onView(withId(id("tie_custom_tag"))).perform(scrollTo(), replaceText("总攻,收藏"));
        androidx.test.espresso.Espresso.closeSoftKeyboard();
        scenario.recreate();
        onView(withId(id("tie_custom_tag"))).check(matches(withText("总攻,收藏")));
        onView(withId(id("btn_ok"))).perform(click());
        await(() -> "总攻,收藏".equals(column("customTag")));
        launch("io.legado.app.ui.book.info.edit.BookInfoEditActivity");
        onView(withId(id("tie_custom_tag"))).perform(scrollTo()).check(matches(withText("总攻,收藏")));
        await(() -> {
            final boolean[] ready = {false};
            scenario.onActivity(screen -> {
                android.view.ViewGroup labels = screen.findViewById(id("lb_common_tag"));
                ready[0] = labels.getChildCount() >= 67 && labels.getHeight() > 0 && !labels.isLayoutRequested();
            });
            return ready[0];
        });
        onView(withId(id("lb_common_tag"))).perform(scrollTo()).check((view, error) -> {
            if (error != null) throw error;
            android.view.ViewGroup labels = (android.view.ViewGroup) view;
            java.util.Set<Integer> rows = new java.util.HashSet<>();
            for (int index = 0; index < labels.getChildCount(); index++) {
                android.view.View child = labels.getChildAt(index);
                if (child.getVisibility() == android.view.View.VISIBLE) rows.add(child.getTop());
            }
            assertTrue("Full editor also limits candidates to three rows", rows.size() > 0 && rows.size() <= 3);
        });
        screenshot("full-editor");
    }

    @After public void cleanup() {
        if (scenario != null) scenario.close();
        if (localBook != null) assertTrue(localBook.delete());
        if (db != null) {
            db.delete("books", "author=?", new String[]{prefix});
            db.close();
        }
    }
}
