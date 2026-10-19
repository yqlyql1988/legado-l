package io.legado.app.ui.highlight;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.widget.CheckBox;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.UUID;
import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.*;

/** Also runs against the shrunk release without depending on obfuscated application classes. */
@RunWith(AndroidJUnit4.class)
public class OptimizedBookmarkTagTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    private Object entity(String name) throws Exception {
        return Class.forName("io.legado.app.data.entities." + name).getConstructor().newInstance();
    }

    private void set(Object entity, String name, Object value) throws Exception {
        Field field = entity.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(entity, value);
    }

    private void insert(SQLiteDatabase db, String table, Object entity) throws Exception {
        ContentValues row = new ContentValues();
        try (Cursor columns = db.rawQuery("PRAGMA table_info(`" + table + "`)", null)) {
            while (columns.moveToNext()) {
                String name = columns.getString(1);
                Field field = entity.getClass().getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(entity);
                String column = "`" + name + "`";
                if (value == null) row.putNull(column);
                else if (value instanceof Boolean) row.put(column, (Boolean) value ? 1 : 0);
                else if (value instanceof Number) row.put(column, ((Number) value).longValue());
                else row.put(column, (String) value);
            }
        }
        db.insertOrThrow(table, null, row);
    }

    private int id(String name) {
        int value = context.getResources().getIdentifier(name, "id", context.getPackageName());
        assertTrue(name, value != 0);
        return value;
    }

    private void awaitText(String text) {
        Throwable last = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            try { onView(withText(text)).check(matches(isDisplayed())); return; }
            catch (AssertionError | androidx.test.espresso.NoMatchingViewException error) {
                last = error; SystemClock.sleep(50);
            }
        }
        throw new AssertionError("Missing text: " + text, last);
    }

    private void screenshot(String name) throws Exception {
        File folder = new File(context.getExternalFilesDir(null), "bookmark-tags-ui");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        try (FileOutputStream stream = new FileOutputStream(new File(folder, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        } finally { bitmap.recycle(); }
    }

    @Test public void releaseKeepsTagFieldsAndSharedPickerInteractions() throws Exception {
        try (ActivityScenario<Activity> startup = ActivityScenario.launch(new Intent().setClassName(
                context.getPackageName(), "io.legado.app.ui.highlight.HighlightTagManageActivity"))) { }
        SQLiteDatabase db = SQLiteDatabase.openDatabase(context.getDatabasePath("legado.db").getPath(),
                null, SQLiteDatabase.OPEN_READWRITE);
        String token = UUID.randomUUID().toString();
        String url = "bookmark-tags-release-" + token;
        String bookName = "标签验证-" + token;
        String first = "待回看" + token.substring(0, 8);
        String second = "伏笔" + token.substring(0, 8);
        String created = "重点" + token.substring(0, 8);
        long time = System.currentTimeMillis();
        ActivityScenario<Activity> scenario = null;
        try {
            assertEquals(123, db.getVersion());
            Object book = entity("Book");
            set(book, "bookUrl", url);
            set(book, "name", bookName);
            set(book, "author", "验证");
            insert(db, "books", book);
            Object bookmark = entity("Bookmark");
            set(bookmark, "time", time);
            set(bookmark, "bookName", bookName);
            set(bookmark, "bookAuthor", "验证");
            set(bookmark, "chapterName", "第一章");
            set(bookmark, "bookText", "书签和标注共用标签，常用标签可以各自设置。");
            set(bookmark, "content", "稍后回看这一段");
            insert(db, "bookmarks", bookmark);
            for (int index = 0; index < 2; index++) {
                Object tag = entity("HighlightTag");
                set(tag, "id", token + index);
                set(tag, "name", index == 0 ? first : second);
                set(tag, "common", false);
                set(tag, "bookmarkCommon", index == 0);
                set(tag, "bookmarkSortOrder", index);
                insert(db, "highlight_tags", tag);
            }
            db.execSQL("INSERT INTO bookmark_tag_links(bookmarkTime,tagId) VALUES(?,?)",
                    new Object[]{time, token + "0"});
            scenario = ActivityScenario.launch(new Intent().setClassName(context.getPackageName(),
                    "io.legado.app.ui.book.bookmark.AllBookmarkActivity").putExtra("bookUrl", url));
            awaitText(first);
            onView(withId(id("btn_more"))).perform(click());
            onView(withText("标签")).perform(click());
            onView(withText("标签库")).perform(click());
            onView(withHint("搜索标签名称或说明")).perform(replaceText(second));
            closeSoftKeyboard();
            onView(allOf(withText(second), isAssignableFrom(CheckBox.class))).perform(click());
            onView(withHint("搜索标签名称或说明")).perform(replaceText(""));
            closeSoftKeyboard();
            onView(withText("本书标签")).perform(click());
            onView(allOf(withText(first), isAssignableFrom(CheckBox.class))).check(matches(isChecked()));
            onView(allOf(withText(second), isAssignableFrom(CheckBox.class))).check(matches(isChecked()));
            screenshot("release-picker-bottom-actions");
            onView(withText("＋新建")).perform(click());
            onView(withId(id("et_name"))).perform(replaceText(created));
            closeSoftKeyboard();
            onView(withText("保存")).perform(click());
            onView(allOf(withText(created), isAssignableFrom(CheckBox.class))).check(matches(isChecked()));
            onView(withText("确定")).perform(click());
            awaitText(created);
            try (Cursor links = db.rawQuery("SELECT count(*) FROM bookmark_tag_links WHERE bookmarkTime=?",
                    new String[]{String.valueOf(time)})) {
                assertTrue(links.moveToFirst());
                assertEquals(3, links.getInt(0));
            }
            screenshot("release-notes-compact-labels");
        } finally {
            if (scenario != null) scenario.close();
            db.delete("bookmark_tag_links", "bookmarkTime=?", new String[]{String.valueOf(time)});
            db.delete("bookmarks", "time=?", new String[]{String.valueOf(time)});
            db.delete("bookmark_tag_orders", "bookUrl=?", new String[]{url});
            db.delete("highlight_tags", "id IN (?,?) OR name=?", new String[]{token + "0", token + "1", created});
            db.delete("books", "bookUrl=?", new String[]{url});
            db.close();
        }
    }
}
