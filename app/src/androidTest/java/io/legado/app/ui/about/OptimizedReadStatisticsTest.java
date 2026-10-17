package io.legado.app.ui.about;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.RootMatchers.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;
import static org.hamcrest.Matchers.allOf;

/** Uses Android UI and SQL so the same checks run against the optimized release APK. */
@RunWith(AndroidJUnit4.class)
public class OptimizedReadStatisticsTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private int id(String name) {
        int value = context.getResources().getIdentifier(name, "id", context.getPackageName());
        assertTrue("Missing view: " + name, value != 0);
        return value;
    }
    private Intent intent(String activity) {
        return new Intent().setClassName(context.getPackageName(), "io.legado.app.ui.about." + activity);
    }
    private String string(String name, Object... args) {
        int value = context.getResources().getIdentifier(name, "string", context.getPackageName());
        assertTrue("Missing string: " + name, value != 0);
        return context.getString(value, args);
    }
    private void checkStatisticsHelp(String expectedDate, String screenshotName) throws Exception {
        String explanation = string("read_statistics_coverage", expectedDate);
        onView(withText(explanation)).check(doesNotExist());
        openActionBarOverflowOrOptionsMenu(context);
        onView(withText(string("read_statistics_help"))).inRoot(isPlatformPopup()).perform(click());
        onView(withText(explanation)).inRoot(isDialog()).check(matches(isDisplayed()));
        screenshot(screenshotName);
        onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click());
        onView(withText(explanation)).check(doesNotExist());
    }
    private void screenshot(String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(150);
        Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(image);
        File folder = new File(context.getExternalFilesDir(null), "statistics-ui");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        try (FileOutputStream stream = new FileOutputStream(new File(folder, name + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally { image.recycle(); }
    }
    private void await(ActivityScenario<Activity> scenario, String viewName) {
        for (int i = 0; i < 100; i++) {
            boolean[] ready = {false};
            scenario.onActivity(activity -> {
                TextView view = activity.findViewById(id(viewName));
                ready[0] = view != null && view.getText().length() > 0;
            });
            if (ready[0]) return;
            SystemClock.sleep(50);
        }
        fail("Page did not load");
    }
    private SharedPreferences prefs() {
        return context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }
    private Map<String, Object> savePrefs(String... keys) {
        Map<String, Object> saved = new HashMap<>();
        for (String key : keys) saved.put(key, prefs().getAll().get(key));
        return saved;
    }
    private void restorePrefs(Map<String, Object> values) {
        SharedPreferences.Editor editor = prefs().edit();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getValue() instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) entry.getValue());
            else if (entry.getValue() instanceof String) editor.putString(entry.getKey(), (String) entry.getValue());
            else if (entry.getValue() instanceof Long) editor.putLong(entry.getKey(), (Long) entry.getValue());
            else editor.remove(entry.getKey());
        }
        assertTrue(editor.commit());
    }
    private void applyTheme() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                Class<?> type = Class.forName("io.legado.app.help.config.ThemeConfig");
                type.getMethod("applyDayNightInit", Context.class).invoke(type.getField("INSTANCE").get(null), context);
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
    }

    @Test public void recordCardKeepsSearchAndOpensStatisticsInBothLayouts() throws Exception {
        Map<String, Object> saved = savePrefs("readRecordSimpleLayout", "readRecordFixedCard");
        try {
            for (boolean simple : new boolean[]{true, false}) {
                prefs().edit().putBoolean("readRecordSimpleLayout", simple).putBoolean("readRecordFixedCard", true).commit();
                android.app.Instrumentation.ActivityMonitor monitor = InstrumentationRegistry.getInstrumentation()
                    .addMonitor("io.legado.app.ui.about.ReadStatisticsActivity", null, false);
                try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent("ReadRecordActivity"))) {
                    await(scenario, "tv_year_duration");
                    screenshot(simple ? "record-simple" : "record-enhanced");
                    scenario.onActivity(activity -> {
                        View title = activity.findViewById(id("title_bar"));
                        assertNotNull(title.findViewById(id("search_view")));
                        View entry = activity.findViewById(id(simple ? "compact_summary" : "enhanced_summary"));
                        assertTrue(entry.isClickable());
                        entry.performClick();
                    });
                    Activity stats = monitor.waitForActivityWithTimeout(5000);
                    assertNotNull(stats);
                    InstrumentationRegistry.getInstrumentation().runOnMainSync(stats::finish);
                } finally { InstrumentationRegistry.getInstrumentation().removeMonitor(monitor); }
            }
        } finally { restorePrefs(saved); }
    }

    @Test public void optimizedStatisticsRetainsHistoryAndRendersInteractiveCalendar() throws Exception {
        String device = "statistics-release-" + UUID.randomUUID();
        LocalDate today = LocalDate.now();
        LocalDate firstDetail = today.minusMonths(2).withDayOfMonth(1);
        Map<String, Object> saved = savePrefs("readRecordUseDays", "readRecordShowSeconds", "themeMode",
            "readStatisticsDayMinimum", "readStatisticsMonthMinimum",
            "readStatisticsYearMinimum", "readStatisticsAllMinimum");
        // Open the real activity once so Room creates/migrates the database first.
        try (ActivityScenario<Activity> initial = ActivityScenario.launch(intent("ReadStatisticsActivity"))) {
            await(initial, "tv_duration");
        }
        SQLiteDatabase db = SQLiteDatabase.openDatabase(context.getDatabasePath("legado.db").getPath(),
            null, SQLiteDatabase.OPEN_READWRITE);
        try {
            assertEquals(116, db.getVersion());
            // Include device start dates and existing details, just like the real coverage calculation.
            try (android.database.Cursor cursor = db.rawQuery(
                "SELECT startedAt FROM readStatisticsState WHERE startedAt > 0", null)) {
                while (cursor.moveToNext()) {
                    LocalDate date = java.time.Instant.ofEpochMilli(cursor.getLong(0))
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
                    if (date.isBefore(firstDetail)) firstDetail = date;
                }
            }
            try (android.database.Cursor cursor = db.rawQuery("SELECT MIN(date) FROM readRecordDaily", null)) {
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    LocalDate date = LocalDate.parse(cursor.getString(0));
                    if (date.isBefore(firstDetail)) firstDetail = date;
                }
            }
            db.execSQL("INSERT INTO readRecord(deviceId,bookName,author,readTime,lastRead) VALUES(?,?,?,?,?)",
                new Object[]{device, "山茶文具店", "小川糸", 7200000L, System.currentTimeMillis()});
            db.execSQL("INSERT INTO readRecord(deviceId,bookName,author,readTime,lastRead) VALUES(?,?,?,?,?)",
                new Object[]{device, "昨日的世界", "茨威格", 3600000L, System.currentTimeMillis()});
            db.execSQL("INSERT INTO readRecordDaily(deviceId,bookName,author,date,readTime) VALUES(?,?,?,?,?)",
                new Object[]{device, "山茶文具店", "小川糸", today.toString(), 1800000L});
            db.execSQL("INSERT INTO readRecordDaily(deviceId,bookName,author,date,readTime) VALUES(?,?,?,?,?)",
                new Object[]{device, "山茶文具店", "小川糸", firstDetail.toString(), 600000L});
            prefs().edit().putBoolean("readRecordUseDays", false).putBoolean("readRecordShowSeconds", false)
                .putLong("readStatisticsDayMinimum", 0).putLong("readStatisticsMonthMinimum", 0)
                .putLong("readStatisticsYearMinimum", 0).putLong("readStatisticsAllMinimum", 0).commit();
            for (String theme : new String[]{"1", "2"}) {
                prefs().edit().putString("themeMode", theme).commit();
                applyTheme();
                String suffix = theme.equals("1") ? "light" : "dark";
                try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent("ReadStatisticsActivity"))) {
                    await(scenario, "tv_duration");
                    scenario.onActivity(activity -> {
                        assertTrue(activity.findViewById(id("tab_month")).isSelected());
                        assertTrue(dayCell(activity, today).isSelected());
                        assertEquals(View.VISIBLE, activity.findViewById(id("day_books")).getVisibility());
                        activity.findViewById(id("tab_all")).performClick();
                        assertEquals(View.VISIBLE, activity.findViewById(id("tv_history")).getVisibility());
                        assertTrue(((TextView)activity.findViewById(id("tv_history"))).getText().toString().contains("历史未分期"));
                        assertNotNull(activity.findViewById(id("chart")));
                        View header = activity.findViewById(id("tab_all"));
                        View content = activity.findViewById(id("recycler_view"));
                        ViewGroupRoot.assertWidth(header, content);
                    });
                    screenshot("all-" + suffix);
                    checkStatisticsHelp(firstDetail.toString(), "help-" + suffix);
                    scenario.onActivity(activity -> activity.findViewById(id("tab_year")).performClick());
                    screenshot("year-" + suffix);
                    checkStatisticsHelp(firstDetail.toString(), "help-year-" + suffix);
                    scenario.onActivity(activity -> {
                        activity.findViewById(id("tab_month")).performClick();
                        assertEquals(View.GONE, activity.findViewById(id("chart")).getVisibility());
                        GridLayout grid = activity.findViewById(id("calendar_grid"));
                        assertTrue(grid.getChildCount() >= 7 + today.lengthOfMonth());
                        for (int i = 0; i < grid.getChildCount(); i++) {
                            View cell = grid.getChildAt(i);
                            if (cell.getContentDescription() != null && cell.getContentDescription().toString().startsWith(today.toString())) {
                                assertTrue(cell.isSelected());
                                assertTrue(cell.isEnabled());
                            }
                        }
                        LinearLayout rows = activity.findViewById(id("day_books"));
                        boolean found = false;
                        for (int i = 0; i < rows.getChildCount(); i++) {
                            TextView title = rows.getChildAt(i).findViewById(id("tv_book_name"));
                            if (title != null && title.getText().toString().equals("山茶文具店")) found = true;
                        }
                        assertTrue("Today's book must be clickable", found);
                    });
                    screenshot("month-" + suffix);
                    scenario.onActivity(activity -> activity.findViewById(id("recycler_view")).scrollBy(0, 350));
                    screenshot("calendar-" + suffix);
                    scenario.recreate();
                    await(scenario, "tv_selected_day");
                    checkStatisticsHelp(firstDetail.toString(), "help-month-" + suffix);
                    scenario.onActivity(activity -> assertTrue(((TextView)activity.findViewById(id("tv_selected_day")))
                        .getText().toString().startsWith(today.toString())));
                }
            }
        } finally {
            db.execSQL("DELETE FROM readRecordDaily WHERE deviceId=?", new Object[]{device});
            db.execSQL("DELETE FROM readRecord WHERE deviceId=?", new Object[]{device});
            db.close();
            restorePrefs(saved);
            applyTheme();
        }
    }

    private View dayCell(Activity activity, LocalDate date) {
        GridLayout grid = activity.findViewById(id("calendar_grid"));
        for (int i = 0; i < grid.getChildCount(); i++) {
            View cell = grid.getChildAt(i);
            if (cell.getContentDescription() != null &&
                cell.getContentDescription().toString().startsWith(date.toString())) return cell;
        }
        throw new AssertionError("Date missing: " + date);
    }

    @Test public void calendarDefaultsToTodayAndTogglesWithoutChangingFutureDates() throws Exception {
        LocalDate today = LocalDate.now();
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent("ReadStatisticsActivity"))) {
            await(scenario, "tv_duration");
            scenario.onActivity(activity -> {
                assertTrue(activity.findViewById(id("tab_month")).isSelected());
                assertTrue(dayCell(activity, today).isSelected());
                dayCell(activity, today).performClick();
                assertFalse(dayCell(activity, today).isSelected());
                assertEquals(View.GONE, activity.findViewById(id("tv_selected_day")).getVisibility());
                assertEquals(View.GONE, activity.findViewById(id("day_books")).getVisibility());
                if (today.getDayOfMonth() < today.lengthOfMonth()) {
                    View future = dayCell(activity, today.plusDays(1));
                    assertFalse(future.isEnabled());
                    assertEquals(.35f, future.getAlpha(), .001f);
                }
            });
            scenario.recreate();
            await(scenario, "tv_duration");
            scenario.onActivity(activity -> {
                assertEquals(View.GONE, activity.findViewById(id("day_books")).getVisibility());
                assertFalse(dayCell(activity, today).isSelected());
                LocalDate other = today.withDayOfMonth(1);
                dayCell(activity, other).performClick();
                assertTrue(dayCell(activity, other).isSelected());
                assertEquals(View.VISIBLE, activity.findViewById(id("day_books")).getVisibility());
                if (!other.equals(today)) {
                    dayCell(activity, today).performClick();
                    assertFalse(dayCell(activity, other).isSelected());
                    assertTrue(dayCell(activity, today).isSelected());
                }
                View previous = activity.findViewById(id("previous_period"));
                if (previous.isEnabled()) {
                    previous.performClick();
                    assertEquals(View.GONE, activity.findViewById(id("day_books")).getVisibility());
                    GridLayout grid = activity.findViewById(id("calendar_grid"));
                    for (int i = 0; i < grid.getChildCount(); i++) assertFalse(grid.getChildAt(i).isSelected());
                    activity.findViewById(id("next_period")).performClick();
                    assertTrue(dayCell(activity, today).isSelected());
                }
            });
            screenshot("calendar-toggle");
        }
    }

    private void openRankingSettings() {
        openActionBarOverflowOrOptionsMenu(context);
        onView(withText(string("read_statistics_ranking_settings"))).inRoot(isPlatformPopup()).perform(click());
        onView(withText(string("read_statistics_ranking_explanation"))).inRoot(isDialog()).check(matches(isDisplayed()));
    }

    private void enterMinimum(String row, String value) {
        onView(allOf(withId(id("minimum")), isDescendantOfA(withId(id(row)))))
            .inRoot(isDialog()).perform(scrollTo(), replaceText(value),
                androidx.test.espresso.action.ViewActions.closeSoftKeyboard());
    }

    @Test public void rankingSettingsOnlyHideBooksAndSaveAllFourThresholds() throws Exception {
        String[] keys = {"readStatisticsDayMinimum", "readStatisticsMonthMinimum",
            "readStatisticsYearMinimum", "readStatisticsAllMinimum"};
        Map<String, Object> saved = savePrefs(keys);
        String device = "ranking-ui-" + UUID.randomUUID();
        String title = "排行验证 " + device;
        LocalDate today = LocalDate.now();
        try (ActivityScenario<Activity> initial = ActivityScenario.launch(intent("ReadStatisticsActivity"))) {
            await(initial, "tv_duration");
        }
        SQLiteDatabase db = SQLiteDatabase.openDatabase(context.getDatabasePath("legado.db").getPath(),
            null, SQLiteDatabase.OPEN_READWRITE);
        try {
            db.execSQL("INSERT INTO readRecord(deviceId,bookName,author,readTime,lastRead) VALUES(?,?,?,?,?)",
                new Object[]{device, title, "排行作者", 7200000L, System.currentTimeMillis()});
            db.execSQL("INSERT INTO readRecordDaily(deviceId,bookName,author,date,readTime) VALUES(?,?,?,?,?)",
                new Object[]{device, title, "排行作者", today.toString(), 600000L});
            prefs().edit().putLong(keys[0], 0).putLong(keys[1], 0).putLong(keys[2], 0).putLong(keys[3], 0).commit();
            for (String theme : new String[]{"1", "2"}) {
                Map<String, Object> themeBefore = savePrefs("themeMode");
                prefs().edit().putString("themeMode", theme).commit();
                applyTheme();
                try (ActivityScenario<Activity> scenario = ActivityScenario.launch(intent("ReadStatisticsActivity"))) {
                    await(scenario, "tv_duration");
                    String[] totals = new String[4];
                    scenario.onActivity(activity -> {
                        for (int i = 0; i < totals.length; i++)
                            totals[i] = ((TextView)activity.findViewById(id(new String[]{"tv_duration","tv_days","tv_books","tv_average"}[i]))).getText().toString();
                    });
                    openRankingSettings();
                    enterMinimum("day_minimum", "11");
                    enterMinimum("month_minimum", "12");
                    enterMinimum("year_minimum", "13");
                    enterMinimum("all_minimum", "14");
                    screenshot(theme.equals("1") ? "ranking-settings-light" : "ranking-settings-dark");
                    onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click());
                    for (String key : keys) assertEquals(0L, prefs().getLong(key, 0));
                    openRankingSettings();
                    enterMinimum("day_minimum", "11");
                    enterMinimum("month_minimum", "12");
                    enterMinimum("year_minimum", "13");
                    enterMinimum("all_minimum", "14");
                    onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click());
                    for (int i = 0; i < keys.length; i++) assertEquals((11L + i) * 60000, prefs().getLong(keys[i], 0));
                    scenario.onActivity(activity -> {
                        LinearLayout rows = activity.findViewById(id("day_books"));
                        for (int i = 0; i < rows.getChildCount(); i++) {
                            TextView name = rows.getChildAt(i).findViewById(id("tv_book_name"));
                            assertTrue(name == null || !title.equals(name.getText().toString()));
                        }
                        for (int i = 0; i < totals.length; i++)
                            assertEquals(totals[i], ((TextView)activity.findViewById(id(new String[]{"tv_duration","tv_days","tv_books","tv_average"}[i]))).getText().toString());
                    });
                    // At the exact daily threshold the book remains visible.
                    openRankingSettings();
                    enterMinimum("day_minimum", "10");
                    onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click());
                    scenario.onActivity(activity -> {
                        LinearLayout rows = activity.findViewById(id("day_books"));
                        boolean found = false;
                        for (int i = 0; i < rows.getChildCount(); i++) {
                            TextView name = rows.getChildAt(i).findViewById(id("tv_book_name"));
                            if (name != null && title.equals(name.getText().toString())) found = true;
                        }
                        assertTrue(found);
                    });
                    // Invalid input cannot partially save the other fields.
                    openRankingSettings();
                    enterMinimum("day_minimum", "");
                    enterMinimum("month_minimum", "9223372036854775807");
                    onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click());
                    onView(withId(android.R.id.button1)).inRoot(isDialog()).check(matches(isDisplayed()));
                    assertEquals(600000L, prefs().getLong(keys[0], 0));
                    assertEquals(720000L, prefs().getLong(keys[1], 0));
                    onView(withId(android.R.id.button2)).inRoot(isDialog()).perform(click());
                    for (int index : new int[]{1, 2, 3}) {
                        prefs().edit().putLong(keys[0], 0).putLong(keys[1], 0)
                            .putLong(keys[2], 0).putLong(keys[3], 0).commit();
                        scenario.recreate();
                        await(scenario, "tv_duration");
                        String tab = new String[]{"", "tab_month", "tab_year", "tab_all"}[index];
                        String[] before = new String[4];
                        scenario.onActivity(activity -> {
                            activity.findViewById(id(tab)).performClick();
                            assertTrue(((RecyclerView)activity.findViewById(id("recycler_view"))).getAdapter().getItemCount() >= 2);
                            for (int i = 0; i < before.length; i++)
                                before[i] = ((TextView)activity.findViewById(id(new String[]{"tv_duration","tv_days","tv_books","tv_average"}[i]))).getText().toString();
                        });
                        prefs().edit().putLong(keys[index], Long.MAX_VALUE).commit();
                        scenario.recreate();
                        await(scenario, "tv_duration");
                        scenario.onActivity(activity -> {
                            assertTrue(activity.findViewById(id(tab)).isSelected());
                            assertEquals(1, ((RecyclerView)activity.findViewById(id("recycler_view"))).getAdapter().getItemCount());
                            assertEquals(string("read_statistics_filtered_empty"),
                                ((TextView)activity.findViewById(id("tv_empty"))).getText().toString());
                            for (int i = 0; i < before.length; i++)
                                assertEquals(before[i], ((TextView)activity.findViewById(id(new String[]{"tv_duration","tv_days","tv_books","tv_average"}[i]))).getText().toString());
                            if (index == 1) {
                                assertEquals(View.VISIBLE, activity.findViewById(id("day_books")).getVisibility());
                                activity.findViewById(id("tab_year")).performClick();
                            } else activity.findViewById(id("tab_month")).performClick();
                            assertTrue(((RecyclerView)activity.findViewById(id("recycler_view"))).getAdapter().getItemCount() >= 2);
                        });
                    }
                } finally {
                    prefs().edit().putLong(keys[0], 0).putLong(keys[1], 0).putLong(keys[2], 0).putLong(keys[3], 0).commit();
                    restorePrefs(themeBefore);
                    applyTheme();
                }
            }
        } finally {
            db.execSQL("DELETE FROM readRecordDaily WHERE deviceId=?", new Object[]{device});
            db.execSQL("DELETE FROM readRecord WHERE deviceId=?", new Object[]{device});
            db.close();
            restorePrefs(saved);
        }
    }

    private static class ViewGroupRoot {
        static void assertWidth(View view, View content) {
            View root = view;
            while (root.getParent() instanceof View && root.getParent() != content) root = (View)root.getParent();
            assertEquals("Statistics header must fill the screen width", content.getWidth(), root.getWidth());
        }
    }
}
