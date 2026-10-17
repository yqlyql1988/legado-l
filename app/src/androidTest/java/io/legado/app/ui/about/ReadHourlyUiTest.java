package io.legado.app.ui.about;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.RootMatchers.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

/** Exercises the real controls and deletion against debug and optimized APKs. */
@RunWith(AndroidJUnit4.class)
public class ReadHourlyUiTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String token = "时段验证 " + UUID.randomUUID();
    private final String device = "hourly-" + UUID.randomUUID();
    private final String first = token + "甲", second = token + "乙", old = token + "旧";
    private final LocalDate today = LocalDate.now();
    private final Map<String, Object> saved = new HashMap<>();
    private SQLiteDatabase db;

    private SharedPreferences prefs() {
        return context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }
    private int id(String name) {
        int value = context.getResources().getIdentifier(name, "id", context.getPackageName());
        assertTrue("Missing view " + name, value != 0);
        return value;
    }
    private String string(String name, Object... args) {
        return context.getString(context.getResources().getIdentifier(name, "string", context.getPackageName()), args);
    }
    private ActivityScenario<Activity> launch(String name) {
        return ActivityScenario.launch(new Intent().setClassName(context.getPackageName(),
            "io.legado.app.ui.about." + name).putExtra("query", token));
    }
    private String text(Activity activity, String name) {
        return ((TextView)activity.findViewById(id(name))).getText().toString();
    }
    private void await(ActivityScenario<Activity> scenario, String name, String expected) {
        String[] actual = {""};
        for (int i = 0; i < 120; i++) {
            boolean[] ready = {false};
            scenario.onActivity(activity -> {
                TextView view = activity.findViewById(id(name));
                actual[0] = view == null ? "missing" : view.getText().toString();
                ready[0] = view != null && view.length() > 0 &&
                    (expected == null || view.getText().toString().contains(expected));
            });
            if (ready[0]) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                return;
            }
            SystemClock.sleep(50);
        }
        try { screenshot("failure-" + name); } catch (Exception ignored) { }
        fail("Page did not load " + name + ": expected " + expected + ", actual " + actual[0]);
    }
    private long count(String title) {
        try (Cursor cursor = db.rawQuery("SELECT count(*) FROM readRecord WHERE bookName=?", new String[]{title})) {
            assertTrue(cursor.moveToFirst());
            return cursor.getLong(0);
        }
    }
    private void screenshot(String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(200);
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File folder = new File(context.getExternalFilesDir(null), "hourly-ui");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(folder, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
    }
    private View row(View view, String title) {
        if (view instanceof TextView && view.getId() == id("name") &&
            ((TextView)view).getText().toString().equals(title))
            return (View)((View)view.getParent()).getParent();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = row(group.getChildAt(i), title);
                if (found != null) return found;
            }
        }
        return null;
    }
    private void withRow(ActivityScenario<Activity> scenario, String title, Consumer<View> action) {
        scenario.onActivity(activity -> activity.findViewById(id("recycler_view")).scrollBy(0, -100000));
        for (int i = 0; i < 60; i++) {
            boolean[] found = {false};
            scenario.onActivity(activity -> {
                View list = activity.findViewById(id("recycler_view"));
                View item = row(list, title);
                if (item != null) {
                    found[0] = true;
                    action.accept(item);
                } else list.scrollBy(0, 100);
            });
            if (found[0]) return;
            SystemClock.sleep(50);
        }
        fail("Record not reachable by scrolling: " + title);
    }
    private void selectSpinner(String name, int index) {
        onView(withId(id(name))).inRoot(isDialog()).perform(new ViewAction() {
            @Override public Matcher<View> getConstraints() { return isAssignableFrom(Spinner.class); }
            @Override public String getDescription() { return "Choose spinner value " + index; }
            @Override public void perform(UiController controller, View view) {
                ((Spinner)view).setSelection(index);
                // Scope changes show/hide rows and resize the dialog window.
                controller.loopMainThreadForAtLeast(200);
            }
        });
    }
    private void openFilter(ActivityScenario<Activity> scenario) {
        scenario.onActivity(activity -> activity.findViewById(id("filter")).performClick());
    }
    private void confirm() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(200);
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click());
    }

    @Before public void before() {
        SharedPreferences.Editor editor = prefs().edit();
        for (String key : new String[]{"readStatisticsQualifiedOnly", "readStatisticsIntervalHours",
            "readStatisticsDayMinimum", "readStatisticsMonthMinimum", "readStatisticsYearMinimum",
            "readStatisticsAllMinimum", "readRecordUseDays", "readRecordShowSeconds"})
            saved.put(key, prefs().getAll().get(key));
        editor.putBoolean("readStatisticsQualifiedOnly", false).putInt("readStatisticsIntervalHours", 1)
            .putBoolean("readRecordUseDays", false).putBoolean("readRecordShowSeconds", true)
            .putLong("readStatisticsDayMinimum", 0).putLong("readStatisticsMonthMinimum", 0)
            .putLong("readStatisticsYearMinimum", 0).putLong("readStatisticsAllMinimum", 0).commit();
        try (ActivityScenario<Activity> initial = launch("ReadStatisticsActivity")) {
            await(initial, "tv_duration", null);
        }
        db = SQLiteDatabase.openDatabase(context.getDatabasePath("legado.db").getPath(), null, SQLiteDatabase.OPEN_READWRITE);
        for (Object[] values : new Object[][]{
            {device, first, "A", 900000L}, {device + "-tablet", first, "A", 60000L},
            {device, second, "B", 600000L}, {device, old, "C", 3600000L}
        }) db.execSQL("INSERT INTO readRecord(deviceId,bookName,author,readTime,lastRead) VALUES(?,?,?,?,1)", values);
        for (Object[] values : new Object[][]{
            {device, first, "A", today.toString(), 120000L},
            {device + "-tablet", first, "A", today.toString(), 60000L},
            {device, first, "A", today.minusDays(1).toString(), 300000L},
            {device, second, "B", today.toString(), 600000L}
        }) db.execSQL("INSERT INTO readRecordDaily(deviceId,bookName,author,date,readTime) VALUES(?,?,?,?,?)", values);
        for (Object[] values : new Object[][]{
            {device, first, "A", today.toString(), 9, 120000L},
            {device + "-tablet", first, "A", today.toString(), 9, 60000L},
            {device, first, "A", today.minusDays(1).toString(), 23, 300000L},
            {device, second, "B", today.toString(), 10, 600000L}
        }) db.execSQL("INSERT INTO readRecordHourly(deviceId,bookName,author,date,hour,readTime) VALUES(?,?,?,?,?,?)", values);
        db.execSQL("INSERT INTO readRecordHistory VALUES(?,?,?,?)", new Object[]{device, first, "A", 480000L});
    }
    @After public void after() {
        if (db != null) {
            for (String table : new String[]{"readRecordHourly", "readRecordDaily", "readRecordHistory", "readRecord"})
                db.execSQL("DELETE FROM " + table + " WHERE deviceId IN (?,?)", new Object[]{device, device + "-tablet"});
            db.close();
        }
        SharedPreferences.Editor editor = prefs().edit();
        for (Map.Entry<String, Object> entry : saved.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean)value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long)value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer)value);
            else editor.remove(entry.getKey());
        }
        assertTrue(editor.commit());
    }

    @Test public void opensTodayHourlySwitchesGraphAndKeepsCustomInterval() throws Exception {
        try (ActivityScenario<Activity> scenario = launch("ReadStatisticsActivity")) {
            await(scenario, "tv_duration", null);
            scenario.onActivity(activity -> {
                assertTrue(activity.findViewById(id("tab_day")).isSelected());
                assertEquals(string("read_statistics_day_period", today.getYear(), today.getMonthValue(), today.getDayOfMonth()),
                    text(activity, "tv_period"));
                assertEquals(View.VISIBLE, activity.findViewById(id("hour_details")).getVisibility());
                assertEquals(string("read_statistics_interval", 1), text(activity, "hour_interval"));
                assertEquals(View.GONE, activity.findViewById(id("hour_books")).getVisibility());
                assertEquals(string("read_statistics_expand_hour_books"), text(activity, "toggle_hour_books"));
                activity.findViewById(id("toggle_hour_books")).performClick();
                assertEquals(View.VISIBLE, activity.findViewById(id("hour_books")).getVisibility());
                assertTrue(((ViewGroup)activity.findViewById(id("hour_books"))).getChildCount() > 0);
                activity.findViewById(id("toggle_hour_books")).performClick();
                assertEquals(View.GONE, activity.findViewById(id("hour_books")).getVisibility());
                activity.findViewById(id("tab_month")).performClick();
                assertTrue(activity.findViewById(id("graph_trend")).isSelected());
                assertEquals(View.VISIBLE, activity.findViewById(id("calendar_panel")).getVisibility());
                assertEquals(View.GONE, activity.findViewById(id("day_books")).getVisibility());
                for (String period : new String[]{"tab_year", "tab_all"}) {
                    activity.findViewById(id(period)).performClick();
                    assertTrue(activity.findViewById(id("graph_trend")).isSelected());
                    assertEquals(View.GONE, activity.findViewById(id("hour_details")).getVisibility());
                    assertEquals(View.VISIBLE, activity.findViewById(id("chart")).getVisibility());
                }
                activity.findViewById(id("tab_day")).performClick();
                assertEquals(View.VISIBLE, activity.findViewById(id("hour_details")).getVisibility());
                activity.findViewById(id("tab_month")).performClick();
                assertTrue(activity.findViewById(id("graph_trend")).isSelected());
                activity.findViewById(id("graph_hours")).performClick();
                activity.findViewById(id("hour_interval")).performClick();
            });
            onData(equalTo(string("read_statistics_interval_custom"))).inRoot(isDialog()).perform(click());
            onView(isAssignableFrom(EditText.class)).inRoot(isDialog()).perform(replaceText("0"), closeSoftKeyboard());
            confirm();
            onView(isAssignableFrom(EditText.class)).inRoot(isDialog()).check(matches(hasErrorText(
                string("read_statistics_interval_invalid"))));
            onView(isAssignableFrom(EditText.class)).inRoot(isDialog()).perform(replaceText("5"), closeSoftKeyboard());
            confirm();
            assertEquals(5, prefs().getInt("readStatisticsIntervalHours", 0));
            scenario.onActivity(activity -> {
                assertEquals(string("read_statistics_interval", 5), text(activity, "hour_interval"));
                assertEquals(View.GONE, activity.findViewById(id("hour_books")).getVisibility());
                activity.findViewById(id("toggle_hour_books")).performClick();
                assertEquals(View.VISIBLE, activity.findViewById(id("hour_books")).getVisibility());
                assertEquals(string("read_statistics_collapse_hour_books"), text(activity, "toggle_hour_books"));
                assertTrue(text(activity, "tv_selected_hour").contains("10:00"));
                activity.findViewById(id("next_hour")).performClick();
                assertTrue(text(activity, "tv_selected_hour").contains("15:00"));
                activity.findViewById(id("previous_hour")).performClick();
                assertTrue(text(activity, "tv_selected_hour").contains("10:00"));
            });
            screenshot("hours-custom");
            scenario.recreate();
            await(scenario, "tv_duration", null);
            scenario.onActivity(activity -> {
                assertTrue(activity.findViewById(id("tab_month")).isSelected());
                assertEquals(string("read_statistics_interval", 5), text(activity, "hour_interval"));
                assertEquals(View.VISIBLE, activity.findViewById(id("hour_books")).getVisibility());
                assertEquals(string("read_statistics_collapse_hour_books"), text(activity, "toggle_hour_books"));
                for (String period : new String[]{"tab_year", "tab_all", "tab_day"}) {
                    activity.findViewById(id(period)).performClick();
                    assertEquals(View.VISIBLE, activity.findViewById(id("hour_details")).getVisibility());
                }
                activity.findViewById(id("previous_period")).performClick();
                assertEquals(string("read_statistics_day_period", today.minusDays(1).getYear(),
                    today.minusDays(1).getMonthValue(), today.minusDays(1).getDayOfMonth()), text(activity, "tv_period"));
            });
        }
        try (ActivityScenario<Activity> scenario = launch("ReadStatisticsActivity")) {
            await(scenario, "tv_duration", null);
            scenario.onActivity(activity -> {
                assertTrue(activity.findViewById(id("tab_day")).isSelected());
                assertEquals(View.GONE, activity.findViewById(id("hour_books")).getVisibility());
                assertEquals(string("read_statistics_expand_hour_books"), text(activity, "toggle_hour_books"));
            });
            screenshot("hours-default-day");
        }
    }

    @Test public void managementFiltersClearsHiddenSelectionsAndDeletesWholeBookHistory() throws Exception {
        try (ActivityScenario<Activity> scenario = launch("ReadRecordManageActivity")) {
            await(scenario, "count", "3本");
            withRow(scenario, first, item -> item.findViewById(id("delete")).performClick());
            onView(withId(android.R.id.message)).inRoot(isDialog()).check(matches(withText(containsString("全部阅读记录"))));
            onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click());
            assertEquals(2L, count(first));
            withRow(scenario, first, item -> item.findViewById(id("selected")).performClick());
            scenario.recreate();
            await(scenario, "count", "已选 1本");
            scenario.onActivity(activity -> activity.findViewById(id("select_all")).performClick());
            await(scenario, "count", "已选 3本");
            // Searching prunes hidden selections; clearing search never reselects them.
            onView(allOf(isAssignableFrom(EditText.class), isDescendantOfA(withId(id("search_view")))))
                .perform(replaceText(first), closeSoftKeyboard());
            await(scenario, "count", "筛选到 1本 · 已选 1本");
            onView(allOf(isAssignableFrom(EditText.class), isDescendantOfA(withId(id("search_view")))))
                .perform(replaceText(token), closeSoftKeyboard());
            await(scenario, "count", "筛选到 3本 · 已选 1本");
            openFilter(scenario);
            selectSpinner("range", 0);
            selectSpinner("hour", 9);
            selectSpinner("presets", 1);
            screenshot("manage-hour-filter");
            confirm();
            await(scenario, "count", "筛选到 1本 · 已选 0本");
            scenario.onActivity(activity -> {
                activity.findViewById(id("select_all")).performClick();
                activity.findViewById(id("delete_selected")).performClick();
            });
            onView(withId(android.R.id.message)).inRoot(isDialog()).check(matches(withText(containsString("各设备"))));
            confirm();
            await(scenario, "count", "筛选到 0本");
            assertEquals(0L, count(first));
            assertEquals(1L, count(second));
            for (String table : new String[]{"readRecordHourly", "readRecordDaily", "readRecordHistory"}) {
                try (Cursor cursor = db.rawQuery("SELECT count(*) FROM " + table + " WHERE bookName=?", new String[]{first})) {
                    assertTrue(cursor.moveToFirst());
                    assertEquals(0, cursor.getInt(0));
                }
            }
            openFilter(scenario);
            selectSpinner("range", 4);
            confirm();
            await(scenario, "count", "筛选到 2本");
            screenshot("manage-list");
            withRow(scenario, second, item -> item.findViewById(id("delete")).performClick());
            confirm();
            await(scenario, "count", "筛选到 1本");
            assertEquals(0L, count(second));
            assertEquals(1L, count(old));
        }
    }

    @Test public void filterValidatesBoundsBeforeApplyingAndSupportsEveryScope() {
        try (ActivityScenario<Activity> scenario = launch("ReadRecordManageActivity")) {
            await(scenario, "count", "3本");
            openFilter(scenario);
            onView(allOf(withId(id("hours")), isDescendantOfA(withId(id("minimum")))))
                .inRoot(isDialog()).perform(scrollTo(), replaceText("7"), closeSoftKeyboard());
            onView(withId(id("upper_enabled"))).inRoot(isDialog()).perform(scrollTo(), click());
            confirm();
            onView(allOf(withId(id("error")), isDescendantOfA(withId(id("maximum")))))
                .inRoot(isDialog()).perform(scrollTo()).check(matches(withText(string("read_record_manage_bounds_error"))));
            onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click());
            await(scenario, "count", "3本");
            for (int range : new int[]{1, 2, 3, 4}) {
                openFilter(scenario);
                selectSpinner("range", range);
                selectSpinner("presets", 1);
                confirm();
                await(scenario, "count", range == 4 ? "3本" : "2本");
            }
        }
    }
}
