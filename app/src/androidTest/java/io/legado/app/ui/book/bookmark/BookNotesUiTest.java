package io.legado.app.ui.book.bookmark;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.lifecycle.Lifecycle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.Espresso.closeSoftKeyboard;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.Espresso.pressBackUnconditionally;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static org.junit.Assert.*;

/** Uses SQL, manifest activity names and resource IDs, so it also verifies optimized releases. */
@RunWith(AndroidJUnit4.class)
public class BookNotesUiTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private SQLiteDatabase db;
    private String author, bookUrl, tag;
    private long time;
    private int id(String name) {
        int id = context.getResources().getIdentifier(name, "id", context.getPackageName());
        assertTrue("Missing ID: " + name, id != 0);
        return id;
    }
    private String text(String name, Object... args) {
        int id = context.getResources().getIdentifier(name, "string", context.getPackageName());
        assertTrue("Missing string: " + name, id != 0);
        return context.getString(id, args);
    }
    private Intent intent(boolean detail) {
        Intent intent = new Intent().setClassName(context.getPackageName(),
            "io.legado.app.ui.book.bookmark.AllBookmarkActivity");
        if (detail) intent.putExtra("bookUrl", bookUrl);
        return intent;
    }
    private void insert(String table, ContentValues values) {
        try (Cursor columns = db.rawQuery("PRAGMA table_info(`" + table + "`)", null)) {
            while (columns.moveToNext()) {
                String name = columns.getString(columns.getColumnIndexOrThrow("name"));
                String type = columns.getString(columns.getColumnIndexOrThrow("type"));
                boolean required = columns.getInt(columns.getColumnIndexOrThrow("notnull")) != 0;
                boolean hasDefault = !columns.isNull(columns.getColumnIndexOrThrow("dflt_value"));
                if (!values.containsKey(name) && required && !hasDefault) {
                    if (type.contains("INT")) values.put(name, 0);
                    else if (type.contains("REAL")) values.put(name, 0.0);
                    else values.put(name, "");
                }
            }
        }
        List<String> columns = new ArrayList<>(values.keySet());
        List<String> quoted = new ArrayList<>();
        Object[] arguments = new Object[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            quoted.add("`" + columns.get(i) + "`");
            arguments[i] = values.get(columns.get(i));
        }
        db.execSQL("INSERT INTO `" + table + "` (" + String.join(",", quoted) + ") VALUES (" +
            String.join(",", Collections.nCopies(columns.size(), "?")) + ")", arguments);
    }
    @Before public void seed() {
        try (ActivityScenario<Activity> initial = ActivityScenario.launch(intent(false))) {
            initial.onActivity(activity -> assertNotNull(activity.findViewById(id("tv_summary"))));
            // Keep the first activity alive until Room finishes opening a fresh installation.
            File file = context.getDatabasePath("legado.db");
            for (int attempt = 0; attempt < 120 && db == null; attempt++) {
                if (file.exists()) {
                    SQLiteDatabase candidate = SQLiteDatabase.openDatabase(file.getPath(),
                        null, SQLiteDatabase.OPEN_READWRITE);
                    try (Cursor tables = candidate.rawQuery(
                        "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN " +
                        "('books','chapters','bookmarks','highlight_tags')", null)) {
                        if (tables.moveToFirst() && tables.getInt(0) == 4) db = candidate;
                    }
                    if (db == null) candidate.close();
                }
                if (db == null) SystemClock.sleep(50);
            }
            assertNotNull("Room did not initialize its tables", db);
        }
        author = "书签界面验证-" + UUID.randomUUID();
        bookUrl = "book-notes-fixture://" + UUID.randomUUID();
        tag = "book-notes-label-" + UUID.randomUUID();
        db.setForeignKeyConstraintsEnabled(true);
        time = System.currentTimeMillis() + (System.nanoTime() & 0xfffff);
        while (count("bookmarks", "time IN (?,?)", "" + time, "" + (time + 1)) > 0 ||
               count("highlights", "time IN (?,?)", "" + time, "" + (time + 1)) > 0) time += 2;
        db.beginTransaction();
        try {
            ContentValues book = new ContentValues();
            book.put("bookUrl", bookUrl); book.put("name", "长夜书店"); book.put("author", author);
            book.put("type", 8); book.put("totalChapterNum", 3);
            book.put("durChapterIndex", 2); book.put("durChapterPos", 678);
            book.put("durChapterTitle", "第14章 上次阅读位置");
            insert("books", book);
            for (int i = 0; i < 3; i++) {
                ContentValues chapter = new ContentValues();
                chapter.put("bookUrl", bookUrl); chapter.put("url", bookUrl + "/chapter/" + i);
                chapter.put("index", i); chapter.put("title", "第" + (12 + i) + "章 雨夜归人");
                insert("chapters", chapter);
            }
            ContentValues bookmark = new ContentValues();
            bookmark.put("time", time); bookmark.put("bookName", "长夜书店");
            bookmark.put("bookAuthor", author); bookmark.put("chapterIndex", 1);
            bookmark.put("chapterPos", 45); bookmark.put("chapterName", "第13章 窗边旧信");
            bookmark.put("bookText", "他在靠窗的位置坐下，打开那本夹着旧车票的书。");
            bookmark.put("content", "旧车票第一次出现，后面回来对照。");
            insert("bookmarks", bookmark);
            for (int i = 0; i < 2; i++) {
                ContentValues highlight = new ContentValues();
                highlight.put("time", time + i); highlight.put("bookUrl", bookUrl);
                highlight.put("bookName", "长夜书店"); highlight.put("bookAuthor", author);
                highlight.put("chapterUrl", bookUrl + "/chapter/" + i);
                highlight.put("chapterIndex", i); highlight.put("chapterPos", 80);
                highlight.put("chapterPosEnd", 100); highlight.put("layoutTitleLength", 10);
                highlight.put("chapterName", i == 0 ? "第12章 雨夜归人" : "第13章 窗边旧信");
                highlight.put("bookText", i == 0 ?
                    "雨停的时候，书店还亮着灯。那些没有说出口的话，最终都藏进了翻过的书页里。" :
                    "“有些告别不必赶在天亮以前。”她说完，把门口的伞又往里挪了一点。");
                highlight.put("note", i == 0 ? "喜欢这一句，把书店写成了一个可以暂时停靠的地方。" : "");
                highlight.put("style", "{\"fill\":1358954496}");
                insert("highlights", highlight);
            }
            ContentValues label = new ContentValues();
            label.put("id", tag); label.put("name", "伏笔"); label.put("bookUrl", bookUrl);
            label.put("style", "{}"); label.put("sortOrder", 0); label.put("note", "完整标签备注");
            insert("highlight_tags", label);
            ContentValues link = new ContentValues();
            link.put("highlightTime", time); link.put("tagId", tag);
            insert("highlight_tag_links", link);
            ContentValues orphan = new ContentValues(bookmark);
            orphan.put("time", time + 2); orphan.put("bookName", "山海来信");
            orphan.put("bookText", "河流从不催促岸边的树。");
            insert("bookmarks", orphan);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    @After public void cleanup() {
        if (db != null) {
            db.execSQL("DELETE FROM book_review_deletions WHERE id IN (SELECT id FROM book_reviews WHERE bookAuthor=?)",
                new Object[]{author});
            db.delete("book_review_deletions", "id LIKE ?", new String[]{tag + "-review%"});
            db.delete("book_reviews", "bookAuthor = ?", new String[]{author});
            db.delete("highlight_tags", "id = ?", new String[]{tag});
            db.delete("highlights", "bookAuthor = ?", new String[]{author});
            db.delete("bookmarks", "bookAuthor = ?", new String[]{author});
            db.delete("books", "bookUrl = ?", new String[]{bookUrl});
            db.close();
        }
    }
    private int count(String table, String where, String... args) {
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM `" + table + "` WHERE " + where, args)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getInt(0);
        }
    }
    private void awaitSummary(ActivityScenario<Activity> scenario, String expectedText) {
        for (int i = 0; i < 120; i++) {
            boolean[] loaded = {false};
            scenario.onActivity(activity -> {
                TextView summary = activity.findViewById(id("tv_summary"));
                loaded[0] = expectedText.contentEquals(summary.getText());
            });
            if (loaded[0]) return;
            SystemClock.sleep(50);
        }
        fail("Expected summary: " + expectedText);
    }
    private void awaitCount(ActivityScenario<Activity> scenario, int expected) {
        awaitSummary(scenario, text("book_notes_count", expected));
    }
    private EditText input(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = input(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
    private void query(Activity activity, String value, boolean show) {
        View search = activity.findViewById(id("search_view"));
        if (show) search.setVisibility(View.VISIBLE);
        EditText field = input(search);
        assertNotNull(field);
        field.setText(value);
    }
    private void screenshot(String name) throws Exception {
        instrumentation.waitForIdleSync();
        SystemClock.sleep(350); // Allow the first rendered frame to reach the screenshot surface.
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File folder = new File(context.getExternalFilesDir(null), "book-notes-ui");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        try (FileOutputStream stream = new FileOutputStream(new File(folder, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally { bitmap.recycle(); }
    }
    private void seedReview(String suffix, String name, String content, String date, int score) {
        ContentValues review = new ContentValues();
        review.put("id", tag + "-review-" + suffix);
        review.put("bookName", name); review.put("bookAuthor", author);
        review.put("bookUrl", bookUrl); review.put("rating", score);
        review.put("content", content); review.put("reviewDate", date);
        review.put("isFollowUp", suffix.equals("initial") ? 0 : 1);
        review.put("createdAt", time + 10); review.put("updatedAt", time + 10);
        insert("book_reviews", review);
    }

    @Test public void reviewSummaryTabSearchAndRecreationUseExistingHistory() throws Exception {
        seedReview("initial", "长夜书店", "初读更喜欢雨夜书店的温柔。", "2026-10-01", 8);
        seedReview("follow", "长夜书店", "重读才看懂那些安静的段落。", "2026-10-18", 9);
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent(true))) {
            awaitCount(scenario, 3);
            onView(withText("重读才看懂那些安静的段落。")).check(matches(isDisplayed()));
            screenshot("review-summary");
            onView(withText(text("book_notes_tab_review", 2))).perform(click());
            awaitSummary(scenario, text("book_review_count", 2));
            onView(withId(id("tv_tag_filter"))).check(matches(withEffectiveVisibility(Visibility.GONE)));
            onView(withId(id("tv_sort"))).check(matches(withEffectiveVisibility(Visibility.GONE)));
            onView(withId(id("batch_actions"))).check(matches(withEffectiveVisibility(Visibility.GONE)));
            screenshot("review-history");
            scenario.onActivity(activity -> query(activity, "初读", true));
            awaitSummary(scenario, text("book_review_count", 1));
            closeSoftKeyboard();
            scenario.recreate();
            awaitSummary(scenario, text("book_review_count", 1));
            onView(withText(text("book_notes_tab_review", 2))).check(matches(isSelected()));
            onView(withText("初读更喜欢雨夜书店的温柔。")).check(matches(isDisplayed()));
            toolbarBack();
            awaitSummary(scenario, text("book_review_count", 2));
            toolbarBack();
            scenario.onActivity(activity -> query(activity, author, false));
            awaitSummary(scenario, text("book_notes_summary", 2, 2, 2, 2));
        }
    }

    @Test public void archiveIncludesReviewOnlyBookAndSearchesItsReviewBody() throws Exception {
        seedReview("initial", "寻常的光", "关于家人的那些段落，值得反复回味。", "2026-10-18", 10);
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent(false))) {
            scenario.onActivity(activity -> query(activity, "关于家人的那些段落", false));
            awaitSummary(scenario, text("book_notes_summary", 1, 0, 0, 1));
            onView(withText("寻常的光")).perform(click());
            awaitCount(scenario, 0);
            onView(withText("关于家人的那些段落，值得反复回味。")).check(matches(isDisplayed()));
            onView(withId(id("tv_continue"))).check(matches(withEffectiveVisibility(Visibility.GONE)));
            onView(withText(text("book_notes_tab_review", 1))).perform(click());
            awaitSummary(scenario, text("book_review_count", 1));
            screenshot("review-only");
            toolbarBack();
            awaitSummary(scenario, text("book_notes_summary", 1, 0, 0, 1));
        }
    }

    @Test public void newFollowUpReturnsToSameTabWithInheritedRatingAndUpdatedCount() throws Exception {
        seedReview("initial", "长夜书店", "最初的书评", "2026-10-01", 9);
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent(true))) {
            awaitCount(scenario, 3);
            onView(withText(text("book_notes_tab_review", 1))).perform(click());
            awaitSummary(scenario, text("book_review_count", 1));
            onView(withId(id("tv_write_review"))).perform(click());
            onView(withId(id("et_content"))).perform(scrollTo(), replaceText("从阅读笔记直接写下新的追评。"));
            closeSoftKeyboard();
            onView(withId(id("menu_save"))).perform(click());
            awaitSummary(scenario, text("book_review_count", 2));
            assertEquals(2, count("book_reviews", "bookAuthor=? AND rating=9", author));
            assertEquals(1, count("book_reviews", "bookAuthor=? AND content=?", author, "从阅读笔记直接写下新的追评。"));
            onView(withText(text("book_notes_tab_review", 2))).check(matches(isSelected()));
            onView(withText("从阅读笔记直接写下新的追评。")).check(matches(isDisplayed()));
        }
    }

    @Test public void deletingReviewKeepsNotesProgressAndRefreshesAllSummary() throws Exception {
        seedReview("initial", "长夜书店", "即将删除的书评", "2026-10-18", 9);
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent(true))) {
            awaitCount(scenario, 3);
            onView(withText(text("book_notes_tab_review", 1))).perform(click());
            awaitSummary(scenario, text("book_review_count", 1));
            onView(withId(id("review_more"))).perform(click());
            onView(withText(text("delete"))).perform(click());
            onView(withId(android.R.id.button1)).perform(click());
            awaitSummary(scenario, text("book_review_count", 0));
            assertEquals(0, count("book_reviews", "bookAuthor=?", author));
            assertEquals(2, count("highlights", "bookAuthor=?", author));
            assertEquals(1, count("books", "bookUrl=? AND durChapterIndex=2 AND durChapterPos=678", bookUrl));
            onView(withText(text("book_notes_tab_all", 3))).perform(click());
            awaitCount(scenario, 3);
            onView(withId(id("tv_add"))).check(matches(withText(text("book_review_write"))))
                .check(matches(isDisplayed()));
        }
    }
    @Test public void archiveGroupsBooksAndOldExportActionsStayAvailable() throws Exception {
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent(false))) {
            scenario.onActivity(activity -> query(activity, author, false));
            awaitSummary(scenario, text("book_notes_summary", 2, 2, 2, 0));
            onView(withText(text("book_notes_book_count", 1, 2, 0))).check(matches(isDisplayed()));
            screenshot("archive");
            openActionBarOverflowOrOptionsMenu(context);
            onView(withText(text("export"))).check(matches(isDisplayed()));
            onView(withText(text("export_md"))).check(matches(isDisplayed()));
            onView(withText(text("book_notes_export_markdown"))).check(matches(isDisplayed()));
        }
    }
    private void toolbarBack() {
        onView(withContentDescription(text("back"))).perform(click());
    }
    private void assertListPosition(Activity activity, String name, int top) {
        ViewGroup list = activity.findViewById(id("recycler_view"));
        View first = list.getChildAt(0);
        assertNotNull(first);
        assertEquals(name, ((TextView) first.findViewById(id("tv_name"))).getText().toString());
        assertEquals(top, first.getTop());
    }
    @Test public void toolbarAndSystemBackReturnToArchiveBeforeFinishing() throws Exception {
        for (boolean toolbar : new boolean[]{true, false}) {
            try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent(false))) {
                scenario.onActivity(activity -> query(activity, author, false));
                awaitSummary(scenario, text("book_notes_summary", 2, 2, 2, 0));
                onView(withText("长夜书店")).perform(click());
                awaitCount(scenario, 3);
                if (toolbar) toolbarBack(); else pressBack();
                awaitSummary(scenario, text("book_notes_summary", 2, 2, 2, 0));
                scenario.onActivity(activity -> {
                    assertFalse(activity.isFinishing());
                    assertEquals(View.GONE, activity.findViewById(id("book_heading")).getVisibility());
                });
                if (toolbar) toolbarBack(); else pressBackUnconditionally();
                for (int i = 0; i < 100 && scenario.getState() != Lifecycle.State.DESTROYED; i++)
                    SystemClock.sleep(50);
                assertEquals(Lifecycle.State.DESTROYED, scenario.getState());
            }
        }
    }
    @Test public void returnRestoresBookListSearchAndScrollAfterRecreation() throws Exception {
        for (int i = 0; i < 20; i++) {
            ContentValues bookmark = new ContentValues();
            bookmark.put("time", time + 100 + i);
            bookmark.put("bookName", "返回位置验证-" + i);
            bookmark.put("bookAuthor", author);
            bookmark.put("bookText", "用于验证书籍列表返回位置");
            insert("bookmarks", bookmark);
        }
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent(false))) {
            scenario.onActivity(activity -> query(activity, author, true));
            awaitSummary(scenario, text("book_notes_summary", 22, 22, 2, 0));
            closeSoftKeyboard();
            for (int i = 0; i < 6; i++)
                onView(withId(id("recycler_view"))).perform(swipeUp());
            AtomicReference<String> firstName = new AtomicReference<>();
            int[] top = {0};
            scenario.onActivity(activity -> {
                ViewGroup list = activity.findViewById(id("recycler_view"));
                View first = list.getChildAt(0);
                firstName.set(((TextView) first.findViewById(id("tv_name"))).getText().toString());
                top[0] = first.getTop();
                assertNotEquals("返回位置验证-19", firstName.get());
            });
            onView(withText("长夜书店")).perform(click());
            awaitCount(scenario, 3);
            scenario.recreate();
            awaitCount(scenario, 3);
            toolbarBack();
            awaitSummary(scenario, text("book_notes_summary", 22, 22, 2, 0));
            instrumentation.waitForIdleSync();
            scenario.onActivity(activity -> {
                View search = activity.findViewById(id("search_view"));
                assertEquals(View.VISIBLE, search.getVisibility());
                assertEquals(author, input(search).getText().toString());
                assertListPosition(activity, firstName.get(), top[0]);
            });
            scenario.recreate();
            awaitSummary(scenario, text("book_notes_summary", 22, 22, 2, 0));
            instrumentation.waitForIdleSync();
            scenario.onActivity(activity -> assertListPosition(activity, firstName.get(), top[0]));
            screenshot("navigation-restored");
        }
    }
    @Test public void directBookEntryStaysOnArchiveAfterBackAndRecreation() throws Exception {
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent(true))) {
            awaitCount(scenario, 3);
            toolbarBack();
            onView(withId(id("book_heading"))).check(matches(withEffectiveVisibility(Visibility.GONE)));
            scenario.recreate();
            onView(withId(id("book_heading"))).check(matches(withEffectiveVisibility(Visibility.GONE)));
            scenario.onActivity(activity -> assertFalse(activity.isFinishing()));
        }
    }
    @Test public void toolbarBackExitsBatchAndSearchBeforeReturningToBooks() throws Exception {
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent(false))) {
            scenario.onActivity(activity -> query(activity, author, false));
            awaitSummary(scenario, text("book_notes_summary", 2, 2, 2, 0));
            onView(withText("长夜书店")).perform(click());
            awaitCount(scenario, 3);
            scenario.onActivity(activity -> activity.findViewById(id("note_body")).performLongClick());
            onView(withId(id("batch_actions"))).check(matches(isDisplayed()));
            toolbarBack();
            onView(withId(id("batch_actions"))).check(matches(withEffectiveVisibility(Visibility.GONE)));
            onView(withId(id("book_heading"))).check(matches(isDisplayed()));
            scenario.onActivity(activity -> query(activity, "停靠", true));
            awaitCount(scenario, 1);
            closeSoftKeyboard();
            toolbarBack();
            awaitCount(scenario, 3);
            onView(withId(id("search_view"))).check(matches(withEffectiveVisibility(Visibility.GONE)));
            onView(withId(id("book_heading"))).check(matches(isDisplayed()));
            toolbarBack();
            awaitSummary(scenario, text("book_notes_summary", 2, 2, 2, 0));
        }
    }
    @Test public void continueReadingRestoresSavedProgressWhileItemClickCarriesPosition() throws Exception {
        AtomicReference<Intent> opened = new AtomicReference<>();
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (intent.getComponent() != null &&
                    intent.getComponent().getClassName().equals("io.legado.app.ui.book.read.ReadBookActivity")) {
                    opened.set(new Intent(intent));
                    return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
                }
                return null;
            }
        };
        instrumentation.addMonitor(monitor);
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent(true))) {
            awaitCount(scenario, 3);
            onView(withId(id("tv_continue"))).perform(click());
            for (int i = 0; i < 100 && opened.get() == null; i++) SystemClock.sleep(50);
            Intent resume = opened.getAndSet(null);
            assertNotNull(resume); assertEquals(bookUrl, resume.getStringExtra("bookUrl"));
            assertFalse(resume.hasExtra("index"));
            assertFalse(resume.hasExtra("chapterPos"));
            assertFalse(resume.hasExtra("highlightLayoutTitleLength"));
            assertEquals(1, count("books", "bookUrl=? AND durChapterIndex=2 AND durChapterPos=678", bookUrl));
            scenario.onActivity(activity -> {
                activity.findViewById(id("note_body")).performClick();
            });
            for (int i = 0; i < 100 && opened.get() == null; i++) SystemClock.sleep(50);
            Intent jump = opened.get();
            assertNotNull(jump); assertEquals(0, jump.getIntExtra("index", -1));
            assertEquals(80, jump.getIntExtra("chapterPos", -1));
            assertEquals(10, jump.getIntExtra("highlightLayoutTitleLength", -1));
        } finally { instrumentation.removeMonitor(monitor); }
    }
    @Test public void classificationTagAndSearchSurviveRecreation() throws Exception {
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent(true))) {
            awaitCount(scenario, 3);
            screenshot("book");
            onView(withText(text("book_notes_tab_highlight", 2))).perform(click());
            awaitCount(scenario, 2);
            onView(withId(id("tv_tag_filter"))).perform(click());
            onView(withText("伏笔")).inRoot(isDialog()).perform(click());
            awaitCount(scenario, 1);
            scenario.onActivity(activity -> {
                query(activity, "停靠", true);
            });
            awaitCount(scenario, 1);
            scenario.recreate();
            awaitCount(scenario, 1);
            scenario.onActivity(activity -> {
                assertEquals("停靠", input(activity.findViewById(id("search_view"))).getText().toString());
            });
            onView(withText(text("book_notes_tab_highlight", 2))).check(matches(isSelected()));
            screenshot("filtered");
        }
    }
    @Test public void deletingSameTimeBookmarkAndHighlightKeepsOtherBookAndTagDefinition() throws Exception {
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent(true))) {
            awaitCount(scenario, 3);
            scenario.onActivity(activity -> {
                activity.findViewById(id("note_body")).performLongClick();
            });
            onView(withId(id("btn_select_all"))).perform(click());
            onView(withId(id("btn_delete"))).perform(click());
            onView(withId(android.R.id.button1)).perform(click());
            for (int i = 0; i < 100 && count("highlights", "bookUrl=?", bookUrl) != 0; i++) SystemClock.sleep(50);
            assertEquals(0, count("highlights", "bookUrl=?", bookUrl));
            assertEquals(0, count("bookmarks", "bookAuthor=? AND bookName=?", author, "长夜书店"));
            assertEquals(1, count("bookmarks", "bookAuthor=? AND bookName=?", author, "山海来信"));
            assertEquals(1, count("highlight_tags", "id=?", tag));
            assertEquals(0, count("highlight_tag_links", "tagId=?", tag));
            assertEquals(1, count("books", "bookUrl=? AND durChapterIndex=2 AND durChapterPos=678", bookUrl));
        }
    }
    @Test public void missingBookShowsReadableEditableArchiveWithoutResumeButton() throws Exception {
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent(false))) {
            scenario.onActivity(activity -> query(activity, author, false));
            awaitSummary(scenario, text("book_notes_summary", 2, 2, 2, 0));
            onView(withText("山海来信")).perform(click());
            awaitCount(scenario, 1);
            onView(withId(id("tv_continue"))).check(matches(withEffectiveVisibility(Visibility.GONE)));
            onView(withText("河流从不催促岸边的树。")).check(matches(isDisplayed()));
        }
    }

    @Test public void originalExportFormatsAndCombinedExportWorkThroughExistingFolderPicker() throws Exception {
        File folder = new File(context.getCacheDir(), "notes-picker-" + UUID.randomUUID());
        assertTrue(folder.mkdirs());
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (intent.getComponent() != null &&
                    intent.getComponent().getClassName().equals("io.legado.app.ui.file.HandleFileActivity")) {
                    return new Instrumentation.ActivityResult(Activity.RESULT_OK,
                        new Intent().setData(Uri.fromFile(folder)));
                }
                return null;
            }
        };
        instrumentation.addMonitor(monitor);
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent(true))) {
            awaitCount(scenario, 3);
            for (String action : new String[]{"export", "export_md", "book_notes_export_markdown"}) {
                openActionBarOverflowOrOptionsMenu(context);
                onView(withText(text(action))).perform(click());
                boolean found = false;
                for (int attempt = 0; attempt < 120; attempt++) {
                    File[] files = folder.listFiles((dir, name) ->
                        action.equals("export") ? name.endsWith(".json") :
                        action.equals("export_md") ? name.startsWith("bookmark-") && name.endsWith(".md") :
                        name.startsWith("notes-") && name.endsWith(".md"));
                    if (files != null && files.length == 1 && files[0].length() > 0) {
                        String content = new String(Files.readAllBytes(files[0].toPath()), StandardCharsets.UTF_8);
                        if (action.equals("export")) {
                            JSONArray array = new JSONArray(content);
                            for (int i = 0; i < array.length(); i++) {
                                JSONObject item = array.getJSONObject(i);
                                if (item.optLong("time") != time) continue;
                                assertEquals("长夜书店", item.getString("bookName"));
                                assertEquals(author, item.getString("bookAuthor"));
                                assertEquals(1, item.getInt("chapterIndex"));
                                assertEquals(45, item.getInt("chapterPos"));
                                assertEquals("旧车票第一次出现，后面回来对照。", item.getString("content"));
                                found = true;
                            }
                        } else {
                            found = content.contains("旧车票第一次出现，后面回来对照。");
                            if (found && !action.equals("export_md")) {
                                assertTrue(content.contains("那些没有说出口的话"));
                                assertTrue(content.contains("标签：伏笔"));
                                assertTrue(content.contains("暂时停靠的地方"));
                            }
                        }
                        if (found) break;
                    }
                    SystemClock.sleep(50);
                }
                assertTrue("Export action did not create expected data: " + action, found);
            }
            SystemClock.sleep(2500); // Let export toasts finish before the following theme screenshots.
        } finally {
            instrumentation.removeMonitor(monitor);
            for (File file : folder.listFiles()) assertTrue(file.delete());
            assertTrue(folder.delete());
        }
    }

    private void applyTheme(String mode) {
        instrumentation.runOnMainSync(() -> {
            context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE)
                .edit().putString("themeMode", mode).commit();
            try {
                Class<?> type = Class.forName("io.legado.app.help.config.ThemeConfig");
                type.getMethod("applyDayNightInit", Context.class).invoke(type.getField("INSTANCE").get(null), context);
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
    }

    @Test public void nightEinkAndLandscapeKeepClassificationAndReadingActionsVisible() throws Exception {
        seedReview("initial", "长夜书店", "夜间、墨水屏和横屏下也能查看书评摘要。", "2026-10-18", 9);
        SharedPreferences prefs = context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
        String original = prefs.getString("themeMode", "0");
        try {
            for (String mode : new String[]{"2", "3", "1"}) {
                applyTheme(mode);
                try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent(true))) {
                    awaitCount(scenario, 3);
                    if (mode.equals("1")) {
                        scenario.onActivity(activity -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
                        for (int i = 0; i < 100; i++) {
                            boolean[] landscape = {false};
                            scenario.onActivity(activity -> landscape[0] =
                                activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
                            if (landscape[0]) break;
                            SystemClock.sleep(50);
                        }
                    }
                    onView(withId(id("tv_continue"))).check(matches(isDisplayed()));
                    onView(withText(text("book_notes_tab_highlight", 2))).check(matches(isDisplayed()));
                    screenshot(mode.equals("2") ? "book-night" : mode.equals("3") ? "book-eink" : "book-landscape");
                }
            }
        } finally { applyTheme(original); }
    }
}
