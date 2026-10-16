package io.legado.app.ui.highlight;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.RootMatchers.isPlatformPopup;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

/** Android APIs and actual UI only, so this also verifies the optimized delivery APK. */
@RunWith(AndroidJUnit4.class)
public class OptimizedHighlightTagTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    private int resource(String name, String type) {
        int value = context.getResources().getIdentifier(name, type, context.getPackageName());
        assertNotEquals("Missing " + name, 0, value);
        return value;
    }
    private int id(String name) { return resource(name, "id"); }
    private String string(String name) { return context.getString(resource(name, "string")); }

    private List<ContentValues> snapshot(SQLiteDatabase db, String table) {
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

    @Test public void globalLabelEditorRetainsDraftAndSavesCompatibleStyleJson() throws Exception {
        editorRoundTrip("");
    }

    @Test public void bookLabelEditorRetainsScopeAcrossRecreation() throws Exception {
        editorRoundTrip("fixture-book-tag-" + UUID.randomUUID());
    }

    private void editorRoundTrip(String bookUrl) throws Exception {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(
            context.getDatabasePath("legado.db").getPath(), null, SQLiteDatabase.OPEN_READWRITE);
        List<ContentValues> tags = snapshot(db, "highlight_tags");
        List<ContentValues> links = snapshot(db, "highlight_tag_links");
        List<ContentValues> orders = snapshot(db, "highlight_tag_orders");
        String name = "独立划线" + UUID.randomUUID().toString().substring(0, 4);
        int nextOrder = 0;
        for (ContentValues tag : tags) nextOrder = Math.max(nextOrder, tag.getAsInteger("sortOrder") + 1);
        ActivityScenario<Activity> scenario = null;
        try {
            Intent intent = new Intent().setClassName(context.getPackageName(),
                "io.legado.app.ui.highlight.HighlightTagManageActivity");
            if (!bookUrl.isEmpty()) intent.putExtra("bookUrl", bookUrl);
            scenario = ActivityScenario.launch(intent);
            onView(withId(id("tv_add"))).perform(click());
            onView(withId(id("et_name"))).inRoot(isDialog()).perform(replaceText(name), closeSoftKeyboard());
            onView(withId(id("sp_kind"))).inRoot(isDialog()).perform(scrollTo(), click());
            onData(allOf(is(instanceOf(String.class)), is(string("highlight_underline_wavy"))))
                .inRoot(isPlatformPopup()).perform(click());
            scenario.recreate();
            onView(withId(id("et_name"))).inRoot(isDialog()).check((view, error) -> {
                if (error != null) throw error;
                assertEquals(name, ((TextView) view).getText().toString());
            });
            screenshot("optimized-editor");
            onView(withId(id("btn_ok"))).inRoot(isDialog()).perform(click());
            screenshot("optimized-manager");
            onView(withId(id("btn_save"))).perform(click());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            try (Cursor cursor = db.rawQuery("SELECT style,sortOrder,bookUrl FROM highlight_tags WHERE name = ?",
                    new String[]{name})) {
                assertTrue("The real editor saved the new label", cursor.moveToFirst());
                JSONObject style = new JSONObject(cursor.getString(0));
                JSONObject underline = style.getJSONObject("underline");
                assertEquals("WAVY", underline.getString("kind"));
                assertTrue(underline.getDouble("width") > 0);
                assertEquals(nextOrder, cursor.getInt(1));
                assertEquals(bookUrl, cursor.getString(2));
            }
            assertEquals("Existing associations survive saving global settings",
                links, snapshot(db, "highlight_tag_links"));
        } finally {
            if (scenario != null) scenario.close();
            db.beginTransaction();
            try {
                db.delete("highlight_tag_links", null, null);
                db.delete("highlight_tags", null, null);
                for (ContentValues row : tags) db.insertOrThrow("highlight_tags", null, row);
                for (ContentValues row : links) db.insertOrThrow("highlight_tag_links", null, row);
                db.delete("highlight_tag_orders", null, null);
                for (ContentValues row : orders) db.insertOrThrow("highlight_tag_orders", null, row);
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); db.close(); }
        }
    }

    private void screenshot(String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(image);
        try {
            File directory = new File(context.getExternalFilesDir(null), "tag-ui");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
        } finally { image.recycle(); }
    }
}
