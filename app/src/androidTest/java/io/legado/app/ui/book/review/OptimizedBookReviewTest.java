package io.legado.app.ui.book.review;

import android.app.Activity;
import android.content.Context;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.RatingBar;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import static org.hamcrest.Matchers.allOf;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

/** Android APIs and UI only: also runs against the actual R8 optimized delivery APK. */
@RunWith(AndroidJUnit4.class)
public class OptimizedBookReviewTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String name = "书评示例-" + UUID.randomUUID().toString().substring(0, 6);
    private final String author = "验证作者";
    private final String url = "review-smoke:" + UUID.randomUUID();
    private ActivityScenario<Activity> scenario;
    private SQLiteDatabase database;
    private final List<String> reviewIds = new ArrayList<>();
    private final List<String> extraBookNames = new ArrayList<>();
    private String bookDraftKey;

    private int id(String name) {
        int result = context.getResources().getIdentifier(name, "id", context.getPackageName());
        assertNotEquals("Missing resource " + name, 0, result);
        return result;
    }

    private String string(String name) {
        return context.getString(context.getResources().getIdentifier(name, "string", context.getPackageName()));
    }

    private SQLiteDatabase db() {
        if (database == null) database = SQLiteDatabase.openDatabase(
            context.getDatabasePath("legado.db").getPath(), null, SQLiteDatabase.OPEN_READWRITE);
        return database;
    }

    private void launch(String activity, boolean withBook) {
        if (scenario != null) scenario.close();
        Intent intent = new Intent().setClassName(context.getPackageName(), activity);
        if (withBook) intent.putExtra("bookName", name).putExtra("bookAuthor", author)
            .putExtra("bookUrl", url);
        scenario = ActivityScenario.launch(intent);
    }

    private void editor() {
        launch("io.legado.app.ui.book.review.BookReviewEditActivity", true);
        await(() -> {
            boolean[] ready = {false};
            scenario.onActivity(activity -> {
                TextView title = activity.findViewById(id("tv_book"));
                ready[0] = title != null && title.getText().toString().contains(name);
            });
            return ready[0];
        });
    }

    private int count() {
        try (Cursor cursor = db().rawQuery("select count(*) from book_reviews where bookName = ?",
                new String[]{name})) {
            assertTrue(cursor.moveToFirst());
            return cursor.getInt(0);
        }
    }

    private String reviewId(String content) {
        try (Cursor cursor = db().rawQuery("select id from book_reviews where bookName = ? and content = ?",
                new String[]{name, content})) {
            assertTrue(cursor.moveToFirst());
            String result = cursor.getString(0);
            reviewIds.add(result);
            return result;
        }
    }

    private void write(String content, float score) {
        editor();
        scenario.onActivity(activity -> {
            ((RatingBar) activity.findViewById(id("rating_bar"))).setRating(score);
            ((EditText) activity.findViewById(id("et_content"))).setText(content);
        });
        // Use the actual editor's key, including its JSON formatting, for stale-draft checks.
        for (Map.Entry<String, ?> entry : context.getSharedPreferences("bookReviewDrafts", 0).getAll().entrySet()) {
            try {
                if (!entry.getKey().startsWith("edit:") && entry.getValue() instanceof String &&
                        new JSONObject((String) entry.getValue()).optString("bookName").equals(name)) {
                    bookDraftKey = entry.getKey();
                }
            } catch (org.json.JSONException ignored) { }
        }
        onView(withId(id("menu_save"))).perform(click());
        await(() -> {
            try (Cursor cursor = db().rawQuery("select id from book_reviews where bookName = ? and content = ?",
                    new String[]{name, content})) {
                return cursor.moveToFirst();
            }
        });
        reviewId(content);
    }

    private void deleteFromHistory(String content) {
        onView(allOf(withId(id("review_more")),
            withParent(withParent(hasDescendant(withText(content)))))).perform(click());
        SystemClock.sleep(350);
        onView(withText(string("delete"))).perform(click());
    }

    private void deleteFromEditor() {
        androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu(context);
        SystemClock.sleep(400); // The custom overflow popup animates outside Espresso's idle tracking.
        onView(withText(string("book_review_delete"))).perform(click());
    }

    private void addShelfBook() {
        ContentValues values = new ContentValues();
        values.put("bookUrl", url);
        values.put("name", name);
        values.put("author", author);
        values.put("origin", "https://review-test.invalid");
        values.put("intro", "这是简介。书评应始终在简介之前，可以反复展开和收起。");
        db().insertOrThrow("books", null, values);
    }

    private void information() {
        if (scenario != null) scenario.close();
        scenario = ActivityScenario.launch(new Intent().setClassName(context.getPackageName(),
            "io.legado.app.ui.book.info.BookInfoActivity").putExtra("bookUrl", url));
    }

    private void await(BooleanSupplier condition) {
        long until = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < until) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(50);
        }
        assertTrue("Timed out waiting for review screen", condition.getAsBoolean());
    }

    private void screenshot(String file) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(2500); // Allow activity transitions and save toasts to finish.
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        try (FileOutputStream stream = new FileOutputStream(new File(context.getExternalFilesDir(null), file))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally { bitmap.recycle(); }
    }

    @After public void cleanup() {
        if (scenario != null) scenario.close();
        db().delete("book_reviews", "bookName = ?", new String[]{name});
        for (String extraName : extraBookNames) {
            db().delete("book_reviews", "bookName = ?", new String[]{extraName});
        }
        for (String reviewId : reviewIds) {
            db().delete("book_review_deletions", "id = ?", new String[]{reviewId});
            context.getSharedPreferences("bookReviewDrafts", 0).edit().remove("edit:" + reviewId).commit();
        }
        db().delete("books", "bookUrl = ?", new String[]{url});
        db().delete("books", "bookUrl = ?", new String[]{url + "-current"});
        db().delete("searchBooks", "bookUrl = ?", new String[]{url});
        db().delete("book_sources", "bookSourceUrl = ?", new String[]{url + "-source"});
        android.content.SharedPreferences preferences = context.getSharedPreferences("bookReviewDrafts", 0);
        android.content.SharedPreferences.Editor cleanup = preferences.edit();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            try {
                if (entry.getValue() instanceof String &&
                        new JSONObject((String) entry.getValue()).optString("bookName").equals(name)) {
                    cleanup.remove(entry.getKey());
                }
            } catch (org.json.JSONException ignored) { }
        }
        cleanup.commit();
        database.close();
    }

    @Test public void writeEditDateFollowUpAndArchiveSurviveReopening() throws Exception {
        editor();
        scenario.onActivity(activity -> {
            ((RatingBar) activity.findViewById(id("rating_bar"))).setRating(4.5f);
            ((EditText) activity.findViewById(id("et_content"))).setText("初读：人物鲜活，结尾回味很久。");
        });
        onView(withId(id("tv_date"))).perform(scrollTo(), click());
        onView(isAssignableFrom(DatePicker.class)).perform(new androidx.test.espresso.ViewAction() {
            @Override public org.hamcrest.Matcher<View> getConstraints() { return isAssignableFrom(DatePicker.class); }
            @Override public String getDescription() { return "Set review date"; }
            @Override public void perform(androidx.test.espresso.UiController ui, View view) {
                ((DatePicker) view).updateDate(2026, 9, 14);
                ui.loopMainThreadUntilIdle();
            }
        });
        onView(withId(android.R.id.button1)).perform(click());
        screenshot("review-editor.png");
        onView(withId(id("menu_save"))).perform(click());
        await(() -> count() == 1);
        String initialId;
        try (Cursor cursor = db().rawQuery("select id, rating, reviewDate, isFollowUp from book_reviews where bookName = ?",
                new String[]{name})) {
            assertTrue(cursor.moveToFirst());
            initialId = cursor.getString(0);
            assertEquals(9, cursor.getInt(1));
            assertEquals("2026-10-14", cursor.getString(2));
            assertEquals(0, cursor.getInt(3));
        }
        editor();
        scenario.onActivity(activity -> {
            assertEquals(4.5f, ((RatingBar) activity.findViewById(id("rating_bar"))).getRating(), 0f);
            ((EditText) activity.findViewById(id("et_content"))).setText("重读追评：这次更能理解主角的选择。");
        });
        onView(withId(id("menu_save"))).perform(click());
        await(() -> count() == 2);
        launch("io.legado.app.ui.book.review.BookReviewActivity", true);
        onView(withText("初读：人物鲜活，结尾回味很久。")).check(matches(isDisplayed()));
        onView(withText("重读追评：这次更能理解主角的选择。")).check(matches(isDisplayed()));
        screenshot("review-history.png");
        scenario.close();
        Intent edit = new Intent().setClassName(context.getPackageName(),
            "io.legado.app.ui.book.review.BookReviewEditActivity").putExtra("reviewId", initialId);
        scenario = ActivityScenario.launch(edit);
        await(() -> {
            boolean[] ready = {false};
            scenario.onActivity(activity -> ready[0] = ((TextView) activity.findViewById(id("tv_book")))
                .getText().toString().contains(name));
            return ready[0];
        });
        scenario.onActivity(activity -> ((EditText) activity.findViewById(id("et_content")))
            .setText("编辑初评：人物鲜活，值得重读。"));
        onView(withId(id("menu_save"))).perform(click());
        await(() -> {
            try (Cursor cursor = db().rawQuery("select content from book_reviews where id = ?", new String[]{initialId})) {
                return cursor.moveToFirst() && cursor.getString(0).startsWith("编辑初评");
            }
        });
        launch("io.legado.app.ui.book.review.BookReviewActivity", false);
        onView(withId(id("menu_search"))).perform(click());
        onView(withId(id("search_src_text"))).perform(replaceText(name), closeSoftKeyboard());
        onView(allOf(withId(id("tv_title")), withText(name))).check(matches(isDisplayed()));
        onView(withText(author)).check(matches(isDisplayed()));
        screenshot("review-archive.png");
        assertEquals(2, count());
    }

    @Test public void informationPanelCollapsesAndRestoresItsState() throws Exception {
        editor(); // Initialize the app database before inserting an isolated fixture.
        scenario.onActivity(activity -> {
            ((RatingBar) activity.findViewById(id("rating_bar"))).setRating(4f);
            ((EditText) activity.findViewById(id("et_content"))).setText("折叠测试：保留评分和日期。");
        });
        onView(withId(id("menu_save"))).perform(click());
        await(() -> count() == 1);
        addShelfBook();
        information();
        await(() -> {
            boolean[] ready = {false};
            scenario.onActivity(activity -> {
                TextView content = activity.findViewById(id("tv_review_content"));
                ready[0] = content != null && content.getText().toString().startsWith("折叠测试");
            });
            return ready[0];
        });
        onView(withId(id("tv_review_toggle"))).perform(scrollTo());
        screenshot("review-info-expanded.png");
        onView(withId(id("tv_review_toggle"))).perform(click());
        scenario.onActivity(activity -> {
            assertEquals(View.GONE, activity.findViewById(id("ll_review_details")).getVisibility());
            assertEquals(View.VISIBLE, activity.findViewById(id("tv_review_date")).getVisibility());
        });
        screenshot("review-info-collapsed.png");
        scenario.recreate();
        await(() -> {
            boolean[] ready = {false};
            scenario.onActivity(activity -> {
                TextView date = activity.findViewById(id("tv_review_date"));
                ready[0] = date != null && date.getVisibility() == View.VISIBLE;
            });
            return ready[0];
        });
        scenario.onActivity(activity -> assertEquals(View.GONE,
            activity.findViewById(id("ll_review_details")).getVisibility()));
        onView(withId(id("tv_review_toggle"))).perform(scrollTo(), click());
        scenario.onActivity(activity -> assertEquals(View.VISIBLE,
            activity.findViewById(id("ll_review_details")).getVisibility()));
    }

    @Test public void deleteConfirmsBeforePromotingTextFollowUpAndReturningToUnreviewed() throws Exception {
        write("待删除初评", 4.5f);
        String initialId = reviewId("待删除初评");
        write("保留的文字追评", 0f);
        String followId = reviewId("保留的文字追评");
        ContentValues legacy = new ContentValues();
        legacy.putNull("rating"); // Preserve the old-backup text-only promotion regression.
        db().update("book_reviews", legacy, "id = ?", new String[]{followId});
        addShelfBook();
        launch("io.legado.app.ui.book.review.BookReviewActivity", true);
        onView(withText("待删除初评")).check(matches(isDisplayed()));
        deleteFromHistory("待删除初评");
        assertEquals(2, count()); // Merely opening the confirmation does not delete.
        screenshot("review-delete-confirm.png");
        onView(withId(android.R.id.button2)).perform(click());
        assertEquals(2, count());
        deleteFromHistory("待删除初评");
        onView(withId(android.R.id.button1)).perform(click());
        await(() -> count() == 1);
        try (Cursor cursor = db().rawQuery(
            "select isFollowUp, rating, content from book_reviews where id = ?", new String[]{followId})) {
            assertTrue(cursor.moveToFirst());
            assertEquals(0, cursor.getInt(0));
            assertTrue(cursor.isNull(1));
            assertEquals("保留的文字追评", cursor.getString(2));
        }
        onView(withText(string("book_review_initial"))).check(matches(isDisplayed()));
        onView(withText("保留的文字追评")).perform(click());
        onView(withId(id("et_content"))).perform(scrollTo(), replaceText("递补为初评后仍可修改文字"), closeSoftKeyboard());
        onView(withId(id("menu_save"))).perform(click());
        await(() -> {
            try (Cursor cursor = db().rawQuery("select content from book_reviews where id = ?", new String[]{followId})) {
                return cursor.moveToFirst() && cursor.getString(0).equals("递补为初评后仍可修改文字");
            }
        });
        deleteFromHistory("递补为初评后仍可修改文字");
        onView(withId(android.R.id.button1)).perform(click());
        await(() -> count() == 0);
        onView(withText(string("book_review_none"))).check(matches(isDisplayed()));
        information();
        await(() -> {
            boolean[] ready = {false};
            scenario.onActivity(activity -> {
                TextView rating = activity.findViewById(id("tv_review_rating"));
                ready[0] = rating != null && rating.getText().toString().equals(string("book_review_none"));
            });
            return ready[0];
        });
        scenario.onActivity(activity -> {
            assertEquals(View.GONE, activity.findViewById(id("tv_review_date")).getVisibility());
            assertEquals(View.GONE, activity.findViewById(id("ll_review_details")).getVisibility());
            View card = activity.findViewById(id("review_card"));
            assertTrue("Collapsed empty review bar should be compact",
                card.getHeight() <= Math.ceil(50 * context.getResources().getDisplayMetrics().density));
        });
        onView(withId(id("tv_review_toggle"))).perform(scrollTo());
        screenshot("review-unreviewed.png");
    }

    @Test public void editorDeleteCanBeCancelledAndDeletedDraftDoesNotReturn() throws Exception {
        write("编辑页删除测试", 4f);
        String initialId = reviewId("编辑页删除测试");
        String key = bookDraftKey;
        assertNotNull("The editor must persist its actual draft before saving", key);
        JSONObject draft = new JSONObject();
        draft.put("id", initialId).put("bookName", name).put("bookAuthor", author)
            .put("bookUrl", url).put("rating", 8).put("content", "不应复活的旧草稿")
            .put("reviewDate", "2026-10-15").put("isFollowUp", false)
            .put("createdAt", 1).put("updatedAt", 1);
        context.getSharedPreferences("bookReviewDrafts", 0).edit().putString(key, draft.toString()).commit();
        if (scenario != null) scenario.close();
        scenario = ActivityScenario.launch(new Intent().setClassName(context.getPackageName(),
            "io.legado.app.ui.book.review.BookReviewEditActivity").putExtra("reviewId", initialId));
        onView(withText(name)).check(matches(isDisplayed()));
        deleteFromEditor();
        onView(withId(android.R.id.button2)).perform(click());
        assertEquals(1, count());
        deleteFromEditor();
        onView(withId(android.R.id.button1)).perform(click());
        await(() -> count() == 0);
        editor();
        scenario.onActivity(activity -> {
            assertEquals("", ((EditText) activity.findViewById(id("et_content"))).getText().toString());
            assertEquals(0f, ((RatingBar) activity.findViewById(id("rating_bar"))).getRating(), 0f);
        });
        assertEquals(0, count());
        assertFalse(context.getSharedPreferences("bookReviewDrafts", 0).contains(key));
    }

    private void archive() {
        launch("io.legado.app.ui.book.review.BookReviewActivity", false);
        onView(withId(id("menu_search"))).perform(click());
        onView(withId(id("search_src_text"))).perform(replaceText(name), closeSoftKeyboard());
        onView(allOf(withId(id("tv_title")), withText(name))).check(matches(isDisplayed()));
    }

    private void archiveReview(String title, int rating, String date) {
        String reviewId = UUID.randomUUID().toString();
        ContentValues values = new ContentValues();
        values.put("id", reviewId);
        values.put("bookName", title);
        values.put("bookAuthor", author);
        values.put("bookUrl", url);
        values.put("rating", rating);
        values.put("content", "归档排序和长书名验证");
        values.put("reviewDate", date);
        values.put("isFollowUp", 0);
        values.put("createdAt", 1);
        values.put("updatedAt", 1);
        db().insertOrThrow("book_reviews", null, values);
        extraBookNames.add(title);
        reviewIds.add(reviewId);
    }

    private String firstArchiveTitle() {
        String[] title = {""};
        scenario.onActivity(activity -> {
            android.view.ViewGroup list = activity.findViewById(id("recycler_view"));
            if (list.getChildCount() > 0) {
                title[0] = ((TextView) list.getChildAt(0).findViewById(id("tv_title"))).getText().toString();
            }
        });
        return title[0];
    }

    @Test public void archiveSortingPersistsAndLongTitlesUseAtMostTwoLines() throws Exception {
        editor();
        android.content.SharedPreferences preferences =
            context.getSharedPreferences(context.getPackageName() + "_preferences", 0);
        String key = "bookReviewArchiveSort";
        String previous = preferences.getString(key, null);
        String recent = name + "-sort-" + new String(new char[100]).replace("\0", "长书名");
        String high = name + "-sort-高评分";
        try {
            preferences.edit().putString(key, "recent").commit();
            archiveReview(recent, 6, "2026-10-15");
            archiveReview(high, 10, "2026-10-14");
            launch("io.legado.app.ui.book.review.BookReviewActivity", false);
            onView(withId(id("menu_search"))).perform(click());
            onView(withId(id("search_src_text"))).perform(replaceText(name), closeSoftKeyboard());
            await(() -> firstArchiveTitle().equals(recent));
            scenario.onActivity(activity -> {
                android.view.ViewGroup list = activity.findViewById(id("recycler_view"));
                TextView title = list.getChildAt(0).findViewById(id("tv_title"));
                assertEquals(2, title.getMaxLines());
                assertEquals(2, title.getLineCount());
                assertEquals(android.text.TextUtils.TruncateAt.END, title.getEllipsize());
                assertTrue(title.getLayout().getEllipsisCount(1) > 0);
                RatingBar stars = list.getChildAt(0).findViewById(id("review_rating_stars"));
                assertEquals(3f, stars.getRating(), 0f);
            });
            screenshot("review-archive-two-lines.png");
            onView(withId(id("tv_sort"))).perform(click());
            SystemClock.sleep(350);
            onView(withText(string("book_review_sort_rating"))).perform(click());
            await(() -> firstArchiveTitle().equals(high));
            scenario.recreate();
            await(() -> firstArchiveTitle().equals(high));
            onView(withId(id("tv_sort"))).check(matches(withText(string("book_review_sort_rating"))));
            screenshot("review-archive-rating-sort.png");
            onView(withId(id("tv_sort"))).perform(click());
            SystemClock.sleep(350);
            onView(withText(string("book_review_sort_recent"))).perform(click());
            await(() -> firstArchiveTitle().equals(recent));
        } finally {
            if (previous == null) preferences.edit().remove(key).commit();
            else preferences.edit().putString(key, previous).commit();
        }
    }

    private void assertResumed(String activityName) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            java.util.Collection<Activity> activities = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED);
            assertEquals(1, activities.size());
            Activity activity = activities.iterator().next();
            assertEquals(activityName, activity.getClass().getName());
            if (activityName.endsWith("BookReviewActivity")) {
                assertFalse("Back from details must return directly to the archive",
                    activity.getIntent().hasExtra("bookName"));
            }
        });
    }

    @Test public void archiveClickKeepsHistoryAndLongPressOpensDetailsWithoutExtraClick() throws Exception {
        write("点击查看书评，长按查看详情", 4f);
        addShelfBook();
        archive();
        onView(allOf(withId(id("tv_title")), withText(name))).perform(click());
        onView(withId(id("tv_write"))).check(matches(isDisplayed()));
        onView(withText(string("book_review_initial"))).check(matches(isDisplayed()));
        androidx.test.espresso.Espresso.pressBack();
        onView(allOf(withId(id("tv_title")), withText(name))).perform(longClick());
        onView(allOf(withId(id("tv_name")), withText(name))).check(matches(isDisplayed()));
        assertResumed("io.legado.app.ui.book.info.BookInfoActivity");
        screenshot("review-long-press-info.png");
        androidx.test.espresso.Espresso.pressBack();
        onView(allOf(withId(id("tv_title")), withText(name))).check(matches(isDisplayed()));
        assertResumed("io.legado.app.ui.book.review.BookReviewActivity");
        assertEquals(1, count());
    }

    @Test public void archiveLongPressUsesCurrentBookAfterSourceChange() {
        write("换源前写的书评", 4.5f);
        addShelfBook();
        ContentValues changed = new ContentValues();
        changed.put("bookUrl", url + "-current");
        changed.put("originName", "换源后的书源");
        db().update("books", changed, "bookUrl = ?", new String[]{url});
        // An old URL can now identify another book; name and author must win.
        ContentValues old = new ContentValues();
        old.put("bookUrl", url);
        old.put("name", name + "-另一部");
        old.put("author", "另一位作者");
        old.put("origin", "https://review-test.invalid");
        db().insertOrThrow("books", null, old);
        archive();
        onView(allOf(withId(id("tv_title")), withText(name))).perform(longClick());
        onView(allOf(withId(id("tv_name")), withText(name))).check(matches(isDisplayed()));
        onView(withId(id("tv_origin"))).check(matches(withText(
            org.hamcrest.Matchers.containsString("换源后的书源"))));
        assertEquals(1, count());
    }

    @Test public void archiveLongPressOpensSearchCacheForBookOutsideShelf() {
        write("移出书架后保留的书评", 4f);
        ContentValues source = new ContentValues();
        source.put("bookSourceUrl", url + "-source");
        source.put("bookSourceName", "缓存测试书源");
        source.put("bookSourceType", 0);
        source.put("lastUpdateTime", 0);
        source.put("respondTime", 0);
        source.put("weight", 0);
        db().insertOrThrow("book_sources", null, source);
        ContentValues cached = new ContentValues();
        cached.put("bookUrl", url);
        cached.put("name", name);
        cached.put("author", author);
        cached.put("origin", url + "-source");
        cached.put("originName", "缓存测试书源");
        cached.put("type", 0);
        cached.put("tocUrl", "");
        cached.put("time", System.currentTimeMillis());
        cached.put("originOrder", 0);
        db().insertOrThrow("searchBooks", null, cached);
        archive();
        onView(allOf(withId(id("tv_title")), withText(name))).perform(longClick());
        onView(allOf(withId(id("tv_name")), withText(name))).check(matches(isDisplayed()));
        onView(withId(id("tv_shelf"))).check(matches(withText(string("add_to_bookshelf"))));
        try (Cursor cursor = db().rawQuery("select count(*) from books where name = ?",
                new String[]{name})) {
            assertTrue(cursor.moveToFirst());
            assertEquals("Opening details must not add the book to the shelf", 0, cursor.getInt(0));
        }
        assertEquals(1, count());
    }

    @Test public void followUpPrefillsPreviousScoreAndSavesItUnlessChanged() throws Exception {
        write("上次评分四星半", 4.5f);
        editor();
        scenario.onActivity(activity -> {
            assertEquals(4.5f, ((RatingBar) activity.findViewById(id("rating_bar"))).getRating(), 0f);
            ((EditText) activity.findViewById(id("et_content"))).setText("这次只写文字");
        });
        scenario.recreate();
        await(() -> {
            boolean[] ready = {false};
            scenario.onActivity(activity -> ready[0] =
                ((TextView) activity.findViewById(id("tv_book"))).getText().toString().equals(name));
            return ready[0];
        });
        scenario.onActivity(activity ->
            assertEquals(4.5f, ((RatingBar) activity.findViewById(id("rating_bar"))).getRating(), 0f));
        screenshot("review-follow-up-rating.png");
        onView(withId(id("menu_save"))).perform(click());
        await(() -> count() == 2);
        String inheritedId = reviewId("这次只写文字");
        try (Cursor cursor = db().rawQuery("select rating from book_reviews where id = ?",
                new String[]{inheritedId})) {
            assertTrue(cursor.moveToFirst());
            assertEquals(9, cursor.getInt(0));
        }
        write("重新改成三星", 3f);
        editor();
        scenario.onActivity(activity -> {
            RatingBar rating = activity.findViewById(id("rating_bar"));
            assertEquals(3f, rating.getRating(), 0f);
            rating.setRating(5f);
        });
        onView(withId(id("tv_clear_rating"))).perform(scrollTo(), click());
        scenario.onActivity(activity -> {
            assertEquals(3f, ((RatingBar) activity.findViewById(id("rating_bar"))).getRating(), 0f);
            ((EditText) activity.findViewById(id("et_content"))).setText("恢复上次评分后保存");
        });
        onView(withId(id("menu_save"))).perform(click());
        await(() -> count() == 4);
        String resetId = reviewId("恢复上次评分后保存");
        try (Cursor cursor = db().rawQuery("select rating from book_reviews where id = ?",
                new String[]{resetId})) {
            assertTrue(cursor.moveToFirst());
            assertEquals(6, cursor.getInt(0));
        }
    }
}
