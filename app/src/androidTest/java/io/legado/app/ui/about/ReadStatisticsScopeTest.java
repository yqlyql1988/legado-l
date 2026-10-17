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
import android.widget.LinearLayout;
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
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import static org.junit.Assert.*;

/** Also runs against the optimized APK without depending on its renamed classes. */
@RunWith(AndroidJUnit4.class)
public class ReadStatisticsScopeTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String device = "scope-" + UUID.randomUUID();
    private final String title = "统计范围验证 " + device;
    private final String today = LocalDate.now().toString();
    private final Map<String, Object> saved = new HashMap<>();
    private SQLiteDatabase db;
    private long minimum;

    private SharedPreferences prefs() {
        return context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
    }
    private int id(String name) {
        int value = context.getResources().getIdentifier(name, "id", context.getPackageName());
        assertTrue("Missing view " + name, value != 0);
        return value;
    }
    private long query(String sql) {
        try (Cursor cursor = db.rawQuery(sql, null)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getLong(0);
        }
    }
    private ActivityScenario<Activity> launch() {
        return ActivityScenario.launch(new Intent().setClassName(context.getPackageName(),
            "io.legado.app.ui.about.ReadStatisticsActivity"));
    }
    private void await(ActivityScenario<Activity> scenario) {
        for (int i = 0; i < 100; i++) {
            boolean[] ready = {false};
            scenario.onActivity(activity ->
                ready[0] = ((TextView)activity.findViewById(id("tv_duration"))).length() > 0);
            if (ready[0]) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                return;
            }
            SystemClock.sleep(50);
        }
        fail("Statistics did not load");
    }
    private String text(Activity activity, String name) {
        return ((TextView)activity.findViewById(id(name))).getText().toString();
    }
    private String duration(long time) {
        long hours = time / 3600000, minutes = time / 60000 % 60, seconds = time / 1000 % 60;
        String result = (hours > 0 ? hours + "小时" : "") +
            (minutes > 0 ? minutes + "分钟" : "") + (seconds > 0 ? seconds + "秒" : "");
        return result.isEmpty() ? "0秒" : result;
    }
    private String[] totals(Activity activity) {
        return new String[]{text(activity, "tv_duration"), text(activity, "tv_books"),
            text(activity, "tv_days"), text(activity, "tv_average")};
    }
    private void assertSummary(Activity activity, long time, int books, int days, long average) {
        assertEquals(duration(time), text(activity, "tv_duration"));
        assertEquals(books + "本\n读过的书", text(activity, "tv_books"));
        assertEquals(days + "天\n已记录天数", text(activity, "tv_days"));
        assertEquals(duration(average) + "\n明细日均", text(activity, "tv_average"));
    }
    private void applyTheme() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                Class<?> type = Class.forName("io.legado.app.help.config.ThemeConfig");
                type.getMethod("applyDayNightInit", Context.class).invoke(type.getField("INSTANCE").get(null), context);
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
    }
    private void screenshot(String name) throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Bitmap image = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(image);
        File folder = new File(context.getExternalFilesDir(null), "statistics-scope");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        try (FileOutputStream stream = new FileOutputStream(new File(folder, name + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally { image.recycle(); }
    }

    @Before public void before() {
        for (String key : new String[]{"readStatisticsQualifiedOnly", "readStatisticsDayMinimum",
            "readStatisticsMonthMinimum", "readStatisticsYearMinimum", "readStatisticsAllMinimum",
            "readRecordUseDays", "readRecordShowSeconds", "themeMode", "colorPrimaryNight",
            "colorAccentNight", "colorBackgroundNight", "colorBottomBackgroundNight"})
            saved.put(key, prefs().getAll().get(key));
        db = SQLiteDatabase.openDatabase(context.getDatabasePath("legado.db").getPath(), null,
            SQLiteDatabase.OPEN_READWRITE);
        // Exclude existing user books from the qualified result without changing their records.
        long baseline = Math.max(query("SELECT coalesce(sum(readTime),0) FROM readRecord"),
            query("SELECT coalesce(sum(readTime),0) FROM readRecordDaily"));
        minimum = (baseline / 60000 + 10) * 60000;
        for (Object[] row : new Object[][]{
            {device, title, "作者甲", minimum * 2, System.currentTimeMillis()},
            {device, title, "作者乙", minimum / 2, System.currentTimeMillis()},
            {device, title + "历史", "作者丙", minimum * 3, System.currentTimeMillis()}
        }) db.execSQL("INSERT INTO readRecord(deviceId,bookName,author,readTime,lastRead) VALUES(?,?,?,?,?)", row);
        db.execSQL("INSERT INTO readRecordDaily(deviceId,bookName,author,date,readTime) VALUES(?,?,?,?,?)",
            new Object[]{device, title, "作者甲", today, minimum});
        db.execSQL("INSERT INTO readRecordDaily(deviceId,bookName,author,date,readTime) VALUES(?,?,?,?,?)",
            new Object[]{device, title, "作者乙", today, minimum / 2});
        assertTrue(prefs().edit().putBoolean("readStatisticsQualifiedOnly", false)
            .putBoolean("readRecordUseDays", false).putBoolean("readRecordShowSeconds", true)
            .putLong("readStatisticsDayMinimum", 0).putLong("readStatisticsMonthMinimum", minimum)
            .putLong("readStatisticsYearMinimum", minimum).putLong("readStatisticsAllMinimum", minimum).commit());
    }
    @After public void after() {
        if (db != null) {
            db.execSQL("DELETE FROM readRecordDaily WHERE deviceId=?", new Object[]{device});
            db.execSQL("DELETE FROM readRecord WHERE deviceId=?", new Object[]{device});
            db.close();
        }
        SharedPreferences.Editor editor = prefs().edit();
        for (Map.Entry<String, Object> entry : saved.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean)value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long)value);
            else if (value instanceof String) editor.putString(entry.getKey(), (String)value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer)value);
            else editor.remove(entry.getKey());
        }
        assertTrue(editor.commit());
        applyTheme();
    }

    @Test public void switchRecomputesEveryPeriodAndCalendarWithoutChangingRecords() {
        long before = query("SELECT sum(readTime) FROM readRecord");
        try (ActivityScenario<Activity> scenario = launch()) {
            await(scenario);
            String[][] all = {null};
            scenario.onActivity(activity -> {
                assertTrue(activity.findViewById(id("scope_all")).isSelected());
                all[0] = totals(activity);
                activity.findViewById(id("scope_qualified")).performClick();
                assertTrue(activity.findViewById(id("scope_qualified")).isSelected());
                assertSummary(activity, minimum, 1, 1, minimum);
                assertEquals(today + " · " + duration(minimum), text(activity, "tv_selected_day"));
                LinearLayout rows = activity.findViewById(id("day_books"));
                assertEquals(1, rows.getChildCount());
                assertEquals(title, ((TextView)rows.getChildAt(0).findViewById(id("tv_book_name"))).getText().toString());
                assertEquals("作者甲", ((TextView)rows.getChildAt(0).findViewById(id("tv_author"))).getText().toString());
                activity.findViewById(id("tab_year")).performClick();
                assertSummary(activity, minimum, 1, 1, minimum);
                activity.findViewById(id("tab_all")).performClick();
                assertSummary(activity, minimum * 5, 2, 1, minimum);
                assertTrue(text(activity, "tv_history").contains(duration(minimum * 4)));
                activity.findViewById(id("tab_month")).performClick();
                activity.findViewById(id("scope_all")).performClick();
                assertArrayEquals(all[0], totals(activity));
            });
        }
        assertEquals(before, query("SELECT sum(readTime) FROM readRecord"));
    }

    @Test public void selectionSurvivesRecreationAndReopeningAndEmptyScopeShowsZero() {
        try (ActivityScenario<Activity> scenario = launch()) {
            await(scenario);
            scenario.onActivity(activity -> activity.findViewById(id("scope_qualified")).performClick());
            assertTrue(prefs().getBoolean("readStatisticsQualifiedOnly", false));
            scenario.recreate();
            await(scenario);
            scenario.onActivity(activity -> {
                assertTrue(activity.findViewById(id("scope_qualified")).isSelected());
                assertSummary(activity, minimum, 1, 1, minimum);
            });
        }
        prefs().edit().putLong("readStatisticsMonthMinimum", Long.MAX_VALUE).commit();
        try (ActivityScenario<Activity> scenario = launch()) {
            await(scenario);
            scenario.onActivity(activity -> {
                assertTrue(activity.findViewById(id("scope_qualified")).isSelected());
                assertSummary(activity, 0, 0, 0, 0);
                assertEquals(View.VISIBLE, activity.findViewById(id("tv_empty")).getVisibility());
                assertEquals(today + " · 0秒", text(activity, "tv_selected_day"));
                activity.findViewById(id("scope_all")).performClick();
                assertFalse(text(activity, "tv_duration").equals("0秒"));
            });
        }
    }

    @Test public void zeroThresholdGivesIdenticalStatisticsInBothScopes() {
        prefs().edit().putLong("readStatisticsMonthMinimum", 0).putLong("readStatisticsYearMinimum", 0)
            .putLong("readStatisticsAllMinimum", 0).commit();
        try (ActivityScenario<Activity> scenario = launch()) {
            await(scenario);
            scenario.onActivity(activity -> {
                for (String tab : new String[]{"tab_month", "tab_year", "tab_all"}) {
                    activity.findViewById(id(tab)).performClick();
                    activity.findViewById(id("scope_all")).performClick();
                    String[] all = totals(activity);
                    activity.findViewById(id("scope_qualified")).performClick();
                    assertArrayEquals(all, totals(activity));
                }
            });
        }
    }

    @Test public void segmentedSwitchFitsTheTitleLineInLightDarkAndCustomThemes() throws Exception {
        for (String theme : new String[]{"light", "dark", "custom"}) {
            SharedPreferences.Editor editor = prefs().edit().putBoolean("readStatisticsQualifiedOnly", false)
                .putString("themeMode", theme.equals("light") ? "1" : "2");
            if (theme.equals("custom")) editor.putInt("colorPrimaryNight", 0xff10212f)
                .putInt("colorAccentNight", 0xff80cbc4).putInt("colorBackgroundNight", 0xff0b1821)
                .putInt("colorBottomBackgroundNight", 0xff10212f);
            editor.commit();
            applyTheme();
            try (ActivityScenario<Activity> scenario = launch()) {
                await(scenario);
                scenario.onActivity(activity -> {
                    TextView label = activity.findViewById(id("tv_duration_label"));
                    View scope = activity.findViewById(id("statistics_scope"));
                    assertEquals("阅读时长", label.getText().toString());
                    assertSame(label.getParent(), scope.getParent());
                    assertTrue("Title and switch overlap", label.getRight() <= scope.getLeft());
                    assertTrue("Switch exceeds card", scope.getRight() <= ((View)scope.getParent()).getWidth());
                    for (String name : new String[]{"scope_all", "scope_qualified"}) {
                        TextView button = activity.findViewById(id(name));
                        assertEquals(1, button.getLineCount());
                        assertTrue("Scope text clipped", button.getPaint().measureText(button.getText().toString())
                            <= button.getWidth() - button.getPaddingLeft() - button.getPaddingRight() + 1);
                        assertNotNull(button.getContentDescription());
                    }
                });
                screenshot("scope-" + theme + "-all");
                scenario.onActivity(activity -> activity.findViewById(id("scope_qualified")).performClick());
                screenshot("scope-" + theme + "-qualified");
            }
        }
    }
}
