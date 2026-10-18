package io.legado.app.ui.highlight;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

/** The same public-API tests verify the isolated debug app and optimized delivery APK. */
@RunWith(AndroidJUnit4.class)
public class HighlightTagLibraryUiTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String token = UUID.randomUUID().toString();
    private final String book = "library-a-" + token;
    private final String otherBook = "library-b-" + token;
    private final String name = "标签" + token.substring(0, 6);
    private SQLiteDatabase db;
    private List<ContentValues> tags, links, orders;
    private ActivityScenario<Activity> scenario;

    private int id(String name) {
        int value = context.getResources().getIdentifier(name, "id", context.getPackageName());
        assertNotEquals(name, 0, value);
        return value;
    }
    private String text(String name, Object... args) {
        return context.getString(context.getResources().getIdentifier(name, "string", context.getPackageName()), args);
    }
    private List<ContentValues> snapshot(String table) {
        List<ContentValues> rows = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT * FROM " + table, null)) {
            while (cursor.moveToNext()) {
                ContentValues row = new ContentValues();
                for (int i = 0; i < cursor.getColumnCount(); i++) {
                    if (cursor.getType(i) == Cursor.FIELD_TYPE_INTEGER)
                        row.put(cursor.getColumnName(i), cursor.getLong(i));
                    else row.put(cursor.getColumnName(i), cursor.getString(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }
    @Before public void before() {
        db = SQLiteDatabase.openDatabase(context.getDatabasePath("legado.db").getPath(),
            null, SQLiteDatabase.OPEN_READWRITE);
        tags = snapshot("highlight_tags");
        links = snapshot("highlight_tag_links");
        orders = snapshot("highlight_tag_orders");
    }
    @After public void after() {
        if (scenario != null) scenario.close();
        db.beginTransaction();
        try {
            db.delete("highlight_tag_links", null, null);
            db.delete("highlight_tags", null, null);
            db.delete("highlight_tag_orders", null, null);
            for (ContentValues row : tags) db.insertOrThrow("highlight_tags", null, row);
            for (ContentValues row : links) db.insertOrThrow("highlight_tag_links", null, row);
            for (ContentValues row : orders) db.insertOrThrow("highlight_tag_orders", null, row);
            db.delete("highlights", "bookUrl IN (?,?)", new String[]{book, otherBook});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); db.close(); }
    }
    private void insertTag(String owner, boolean common) {
        db.execSQL("INSERT INTO highlight_tags (id,name,style,sortOrder,bookUrl,note,common) VALUES (?,?,?,999,?,?,?)",
            new Object[]{token, name, "{\"bold\":true}", owner, "标签说明", common ? 1 : 0});
    }
    private long insertHighlight(String owner) {
        long time = System.currentTimeMillis() + 100000;
        db.execSQL("INSERT INTO highlights (time,bookUrl,chapterUrl,bookName,bookAuthor,"
                + "chapterIndex,chapterPos,chapterPosEnd,layoutTitleLength,chapterName,bookText,style,note)"
                + " VALUES (?,?,'chapter','Library','Author',0,0,2,0,'Chapter','ab','{}','标注笔记')",
            new Object[]{time, owner});
        db.execSQL("INSERT INTO highlight_tag_links VALUES (?,?)", new Object[]{time, token});
        return time;
    }
    private void launch() {
        scenario = ActivityScenario.launch(new Intent().setClassName(context.getPackageName(),
            "io.legado.app.ui.highlight.HighlightTagManageActivity").putExtra("bookUrl", book));
    }
    private long scalar(String sql, String... args) {
        try (Cursor cursor = db.rawQuery(sql, args)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getLong(0);
        }
    }
    private void await(BooleanSupplier condition) {
        for (int n = 0; n < 60 && !condition.getAsBoolean(); n++) android.os.SystemClock.sleep(100);
        assertTrue("The saved transaction must become visible", condition.getAsBoolean());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private void save() { onView(withId(id("btn_save"))).perform(click()); }
    private void addSearch() {
        onView(withId(id("tv_add"))).perform(click());
        onView(withId(id("et_tag_search"))).inRoot(isDialog()).perform(replaceText(name), closeSoftKeyboard());
    }
    private void openLibrary() {
        openActionBarOverflowOrOptionsMenu(context);
        onView(withText(text("highlight_tag_library"))).perform(click());
        onView(withId(id("et_library_search"))).perform(replaceText(name), closeSoftKeyboard());
    }
    private void screenshot(String label) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        android.os.SystemClock.sleep(200);
        Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(image);
        try {
            File folder = new File(context.getExternalFilesDir(null), "tag-library-ui");
            assertTrue(folder.isDirectory() || folder.mkdirs());
            try (FileOutputStream output = new FileOutputStream(new File(folder, label + ".png"))) {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
        } finally { image.recycle(); }
    }

    @Test public void commonStarSurvivesRecreationAndKeepsOriginalLabelIdentity() throws Exception {
        insertTag(book, false);
        launch();
        onView(withContentDescription(text("highlight_tag_mark_common", name))).perform(click());
        scenario.recreate();
        onView(withContentDescription(text("highlight_tag_unmark_common", name))).check(matches(isSelected()));
        assertEquals(0, scalar("SELECT common FROM highlight_tags WHERE id=?", token));
        screenshot("common-manager");
        save();
        await(() -> scalar("SELECT common FROM highlight_tags WHERE id=?", token) == 1);
        try (Cursor cursor = db.rawQuery("SELECT bookUrl,note FROM highlight_tags WHERE id=?", new String[]{token})) {
            assertTrue(cursor.moveToFirst());
            assertEquals(book, cursor.getString(0));
            assertEquals("标签说明", cursor.getString(1));
        }
    }

    @Test public void hidingCommonLabelKeepsStarAndAnnotationThenCanBeAddedBack() throws Exception {
        insertTag("", true);
        long time = insertHighlight(book);
        launch();
        onView(allOf(withText(name), isDescendantOfA(withId(id("recycler_view"))))).perform(click());
        onView(withId(id("btn_delete"))).inRoot(isDialog()).perform(scrollTo(), click());
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click());
        onView(allOf(withText(name), isDescendantOfA(withId(id("recycler_view"))))).check(doesNotExist());
        save();
        await(() -> scalar("SELECT count(*) FROM highlight_tag_orders WHERE bookUrl=? AND tagId=? AND hidden=1", book, token) == 1);
        assertEquals(1, scalar("SELECT common FROM highlight_tags WHERE id=?", token));
        assertEquals(1, scalar("SELECT count(*) FROM highlight_tag_links WHERE highlightTime=? AND tagId=?", "" + time, token));
        scenario.close();
        launch();
        addSearch();
        onView(withId(id("btn_create_tag"))).inRoot(isDialog()).check(matches(not(isDisplayed())));
        onView(withText(text("highlight_tag_select_existing"))).inRoot(isDialog()).check(matches(isDisplayed()));
        screenshot("add-existing");
        onView(withId(id("tv_status"))).inRoot(isDialog()).perform(click());
        onView(withContentDescription(text("highlight_tag_unmark_common", name))).check(matches(isSelected()));
        save();
        await(() -> scalar("SELECT count(*) FROM highlight_tag_orders WHERE bookUrl=? AND tagId=? AND hidden=0", book, token) == 1);
        assertEquals(1, scalar("SELECT common FROM highlight_tags WHERE id=?", token));
    }

    @Test public void unifiedSearchReusesOtherBooksLabelAndLocatesAlreadyAddedLabel() throws Exception {
        insertTag(otherBook, false);
        launch();
        addSearch();
        onView(withId(id("btn_create_tag"))).inRoot(isDialog()).check(matches(not(isDisplayed())));
        onView(withId(id("tv_status"))).inRoot(isDialog()).perform(click());
        scenario.recreate();
        onView(withText(name)).check(matches(isDisplayed()));
        addSearch();
        onView(withText(text("highlight_tag_already_added"))).inRoot(isDialog()).check(matches(isDisplayed()));
        screenshot("already-added");
        onView(withId(id("tv_status"))).inRoot(isDialog()).perform(click());
        save();
        await(() -> scalar("SELECT count(*) FROM highlight_tag_orders WHERE bookUrl=? AND tagId=? AND hidden=0", book, token) == 1);
        assertEquals(tags.size() + 1, scalar("SELECT count(*) FROM highlight_tags"));
        assertEquals(0, scalar("SELECT common FROM highlight_tags WHERE id=?", token));
    }

    @Test public void libraryPermanentDeletionShowsAffectedCountsAndKeepsOriginalAnnotations() throws Exception {
        insertTag(book, false);
        long time = insertHighlight(book);
        launch();
        openLibrary();
        screenshot("library");
        onView(withId(id("btn_more"))).perform(click());
        onView(withText(text("highlight_tag_delete_library"))).perform(click());
        onView(withText(text("highlight_tag_delete_library_hint", name, 1, 1)))
            .inRoot(isDialog()).check(matches(isDisplayed()));
        screenshot("delete-confirmation");
        onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click());
        assertEquals(1, scalar("SELECT count(*) FROM highlight_tags WHERE id=?", token));
        onView(withId(id("btn_more"))).perform(click());
        onView(withText(text("highlight_tag_delete_library"))).perform(click());
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click());
        save();
        await(() -> scalar("SELECT count(*) FROM highlight_tags WHERE id=?", token) == 0);
        assertEquals(0, scalar("SELECT count(*) FROM highlight_tag_links WHERE highlightTime=?", "" + time));
        try (Cursor cursor = db.rawQuery("SELECT note,style FROM highlights WHERE time=?", new String[]{"" + time})) {
            assertTrue(cursor.moveToFirst());
            assertEquals("标注笔记", cursor.getString(0));
            assertEquals("{}", cursor.getString(1));
        }
    }

    @Test public void explicitlySwitchingSameNameLabelThenRenamingKeepsOtherBooksAssociations() {
        insertTag(book, false);
        long time = insertHighlight(book);
        String shared = token + "-shared";
        db.execSQL("INSERT INTO highlight_tags (id,name,style,sortOrder,bookUrl,note,common)"
            + " VALUES (?,?,?,998,?,'常用同名标签',1)",
            new Object[]{shared, name, "{\"italic\":true}", otherBook});
        long otherTime = time + 1;
        db.execSQL("INSERT INTO highlights SELECT ?,?,chapterUrl,bookName,bookAuthor,"
            + "chapterIndex,chapterPos,chapterPosEnd,layoutTitleLength,chapterName,bookText,style,note"
            + " FROM highlights WHERE time=?", new Object[]{otherTime, otherBook, time});
        db.execSQL("INSERT INTO highlight_tag_links VALUES (?,?)", new Object[]{otherTime, token});
        launch();
        addSearch();
        onView(allOf(withId(id("tv_status")),
            withParent(hasDescendant(withText("★ " + name))))).inRoot(isDialog()).perform(click());
        onView(withText(text("highlight_tag_use_existing_hint", name))).inRoot(isDialog()).check(matches(isDisplayed()));
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click());
        onView(allOf(withText(name), isDescendantOfA(withId(id("recycler_view"))))).perform(click());
        onView(withId(id("et_name"))).inRoot(isDialog()).perform(replaceText(name + "新"), closeSoftKeyboard());
        onView(withId(id("btn_ok"))).inRoot(isDialog()).perform(click());
        save();
        await(() -> scalar("SELECT count(*) FROM highlight_tag_links WHERE highlightTime=? AND tagId=?",
            "" + time, shared) == 1);
        assertEquals(0, scalar("SELECT count(*) FROM highlight_tag_links WHERE highlightTime=? AND tagId=?", "" + time, token));
        assertEquals(1, scalar("SELECT count(*) FROM highlight_tag_links WHERE highlightTime=? AND tagId=?", "" + otherTime, token));
        assertEquals(1, scalar("SELECT count(*) FROM highlight_tags WHERE id=?", token));
    }

    @Test public void discardingStarChangeLeavesStoredLabelsLinksAndBookDisplayUntouched() {
        insertTag(book, false);
        List<ContentValues> before = snapshot("highlight_tags");
        launch();
        onView(withContentDescription(text("highlight_tag_mark_common", name))).perform(click());
        onView(withContentDescription(text("back"))).perform(click());
        onView(withText(text("highlight_tag_discard"))).inRoot(isDialog()).perform(click());
        assertEquals(before, snapshot("highlight_tags"));
        assertEquals(links, snapshot("highlight_tag_links"));
        assertEquals(orders, snapshot("highlight_tag_orders"));
    }

    @Test public void switchingThenDeletingOldLibraryEntryKeepsTheChosenAssociation() {
        insertTag(book, false);
        long time = insertHighlight(book);
        String shared = token + "-shared";
        db.execSQL("INSERT INTO highlight_tags (id,name,style,sortOrder,bookUrl,note,common)"
            + " VALUES (?,?,?,998,?,'常用同名标签',1)",
            new Object[]{shared, name, "{\"italic\":true}", otherBook});
        launch();
        addSearch();
        onView(allOf(withId(id("tv_status")),
            withParent(hasDescendant(withText("★ " + name))))).inRoot(isDialog()).perform(click());
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click());
        openLibrary();
        onView(allOf(withId(id("btn_more")),
            withParent(hasDescendant(withText("标签说明"))))).perform(click());
        onView(withText(text("highlight_tag_delete_library"))).perform(click());
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click());
        save();
        await(() -> scalar("SELECT count(*) FROM highlight_tags WHERE id=?", token) == 0);
        assertEquals(1, scalar("SELECT count(*) FROM highlight_tag_links WHERE highlightTime=? AND tagId=?", "" + time, shared));
        assertEquals(1, scalar("SELECT count(*) FROM highlights WHERE time=? AND note='标注笔记'", "" + time));
    }
}
