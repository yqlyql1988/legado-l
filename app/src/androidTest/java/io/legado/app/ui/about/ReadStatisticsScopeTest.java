package io.legado.app.ui.about;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
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
        ActivityScenario<Activity> scenario = ActivityScenario.launch(new Intent().setClassName(context.getPackageName(),
            "io.legado.app.ui.about.ReadStatisticsActivity"));
        await(scenario);
        scenario.onActivity(activity -> {
            activity.findViewById(id("tab_month")).performClick();
            activity.findViewById(id("graph_trend")).performClick();
        });
        return scenario;
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
        assertEquals(books + "本", text(activity, "tv_books"));
        assertEquals(days + "天", text(activity, "tv_days"));
        assertEquals(duration(average), text(activity, "tv_average"));
        assertEquals("读过的书", text(activity, "tv_books_label"));
        assertEquals("已记录天数", text(activity, "tv_days_label"));
        assertEquals("明细日均", text(activity, "tv_average_label"));
    }
    private float characterSize(TextView view, boolean digit) {
        Spanned text = (Spanned)view.getText();
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i)) != digit) continue;
            float size = view.getTextSize();
            for (RelativeSizeSpan span : text.getSpans(i, i + 1, RelativeSizeSpan.class))
                size *= span.getSizeChange();
            return size;
        }
        throw new AssertionError("Missing number or unit in " + text);
    }
    private void assertMetricsAligned(Activity activity) {
        TextView first = activity.findViewById(id("tv_days"));
        TextView firstLabel = activity.findViewById(id("tv_days_label"));
        int[] firstPosition = new int[2], firstLabelPosition = new int[2];
        first.getLocationOnScreen(firstPosition);
        firstLabel.getLocationOnScreen(firstLabelPosition);
        for (String name : new String[]{"tv_days", "tv_books", "tv_average"}) {
            TextView value = activity.findViewById(id(name));
            TextView label = activity.findViewById(id(name + "_label"));
            int[] position = new int[2], labelPosition = new int[2];
            value.getLocationOnScreen(position);
            label.getLocationOnScreen(labelPosition);
            assertEquals("Different number size: " + name, characterSize(first, true), characterSize(value, true), .01f);
            assertEquals("Different unit size: " + name, characterSize(first, false), characterSize(value, false), .01f);
            assertEquals("Different label size: " + name, firstLabel.getTextSize(), label.getTextSize(), .01f);
            assertEquals("Different value top: " + name, firstPosition[1], position[1]);
            assertEquals("Different value height: " + name, first.getHeight(), value.getHeight());
            assertEquals("Different value baseline: " + name, first.getLayout().getLineBaseline(0), value.getLayout().getLineBaseline(0));
            assertEquals("Different label top: " + name, firstLabelPosition[1], labelPosition[1]);
            assertEquals("Different label height: " + name, firstLabel.getHeight(), label.getHeight());
            assertEquals("Different label baseline: " + name, firstLabel.getBaseline(), label.getBaseline());
            assertEquals("Column left edges differ: " + name, position[0], labelPosition[0]);
            assertEquals("Column widths differ: " + name, value.getWidth(), label.getWidth());
            assertTrue("Value overlaps label: " + name, position[1] + value.getHeight() <= labelPosition[1]);
            for (TextView view : new TextView[]{value, label}) {
                assertEquals("Text truncated: " + name, view.length(), view.getLayout().getLineEnd(view.getLineCount() - 1));
                assertTrue("Text height clipped: " + name,
                    view.getLayout().getHeight() <= view.getHeight() - view.getCompoundPaddingTop() - view.getCompoundPaddingBottom());
                for (int line = 0; line < view.getLineCount(); line++)
                    assertTrue("Text width clipped: " + name,
                        view.getLayout().getLineWidth(line) <= view.getWidth() - view.getCompoundPaddingLeft() - view.getCompoundPaddingRight() + 1);
            }
            assertEquals("Accessibility lost label: " + name,
                value.getText() + "\n" + label.getText(), value.getContentDescription().toString());
        }
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
        SystemClock.sleep(150);
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
            .putLong("readStatisticsDayMinimum", minimum).putLong("readStatisticsMonthMinimum", minimum)
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

    @Test public void selectionSurvivesRecreationAndReopeningAndEmptyScopeShowsZero() throws Exception {
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
                assertEquals(today + " · " + duration(minimum), text(activity, "tv_selected_day"));
            });
            screenshot("metrics-zero");
            scenario.onActivity(activity -> {
                assertMetricsAligned(activity);
                activity.findViewById(id("scope_all")).performClick();
                assertFalse(text(activity, "tv_duration").equals("0秒"));
            });
        }
    }

    @Test public void zeroThresholdGivesIdenticalStatisticsInBothScopes() {
        prefs().edit().putLong("readStatisticsDayMinimum", 0).putLong("readStatisticsMonthMinimum", 0).putLong("readStatisticsYearMinimum", 0)
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

    @Test public void everyRankingFollowsScopeAndDailyThresholdIsIndependent() {
        long before = query("SELECT sum(readTime) FROM readRecord");
        try (ActivityScenario<Activity> scenario = launch()) {
            await(scenario);
            scenario.onActivity(activity -> {
                for (String tab : new String[]{"tab_month", "tab_year", "tab_all"}) {
                    activity.findViewById(id(tab)).performClick();
                    activity.findViewById(id("scope_all")).performClick();
                    String allCount = text(activity, "tv_rank_scope");
                    int allBooks = Integer.parseInt(text(activity, "tv_books").split("本")[0]);
                    assertEquals("全部 · " + allBooks + "本", allCount);
                    assertTrue(allBooks >= (tab.equals("tab_all") ? 3 : 2));
                    RecyclerView ranking = activity.findViewById(id("recycler_view"));
                    assertEquals(allBooks + 1, ranking.getAdapter().getItemCount());
                    activity.findViewById(id("scope_qualified")).performClick();
                    int qualifiedBooks = tab.equals("tab_all") ? 2 : 1;
                    assertEquals("达标 · " + qualifiedBooks + "本", text(activity, "tv_rank_scope"));
                    assertEquals(qualifiedBooks + 1, ranking.getAdapter().getItemCount());
                    assertEquals(View.GONE, activity.findViewById(id("tv_empty")).getVisibility());
                    activity.findViewById(id("scope_all")).performClick();
                    assertEquals(allCount, text(activity, "tv_rank_scope"));
                }
                activity.findViewById(id("tab_month")).performClick();
                assertTrue(text(activity, "tv_day_rank_scope").startsWith("全部 · "));
                activity.findViewById(id("scope_qualified")).performClick();
                assertEquals("达标 · 1本", text(activity, "tv_day_rank_scope"));
                assertEquals(today + " · " + duration(minimum), text(activity, "tv_selected_day"));
            });
        }
        prefs().edit().putLong("readStatisticsMonthMinimum", Long.MAX_VALUE)
            .putLong("readStatisticsDayMinimum", minimum / 2).commit();
        try (ActivityScenario<Activity> scenario = launch()) {
            await(scenario);
            scenario.onActivity(activity -> {
                assertEquals("达标 · 0本", text(activity, "tv_rank_scope"));
                assertEquals("达标 · 2本", text(activity, "tv_day_rank_scope"));
                assertEquals(today + " · " + duration(minimum + minimum / 2), text(activity, "tv_selected_day"));
                LinearLayout rows = activity.findViewById(id("day_books"));
                assertEquals(2, rows.getChildCount());
                assertEquals("作者乙", ((TextView)rows.getChildAt(1).findViewById(id("tv_author"))).getText().toString());
                activity.findViewById(id("scope_all")).performClick();
                assertEquals(View.GONE, activity.findViewById(id("tv_empty")).getVisibility());
            });
        }
        assertEquals(before, query("SELECT sum(readTime) FROM readRecord"));
    }

    @Test public void hierarchyKeepsLongDurationsAndOriginalTimeDisplayOptions() throws Exception {
        long longTime = 1234L * 3600000 + 56 * 60000 + 7 * 1000;
        db.execSQL("UPDATE readRecord SET readTime=? WHERE deviceId=? AND author=?",
            new Object[]{longTime, device, "作者甲"});
        db.execSQL("UPDATE readRecordDaily SET readTime=? WHERE deviceId=? AND author=?",
            new Object[]{longTime, device, "作者甲"});
        try (ActivityScenario<Activity> scenario = launch()) {
            await(scenario);
            scenario.onActivity(activity -> {
                activity.findViewById(id("tab_all")).performClick();
                activity.findViewById(id("scope_qualified")).performClick();
                assertEquals(duration(longTime), text(activity, "tv_average"));
                TextView value = activity.findViewById(id("tv_duration"));
                assertTrue(value.getText() instanceof Spanned);
                assertTrue(((Spanned)value.getText()).getSpans(0, value.length(), RelativeSizeSpan.class).length > 1);
                assertTrue(value.getText().toString().contains("秒"));
                assertTrue(activity.findViewById(id("summary_divider")).getHeight() > 0);
                TextView days = activity.findViewById(id("tv_days"));
                TextView books = activity.findViewById(id("tv_books"));
                TextView average = activity.findViewById(id("tv_average"));
                assertEquals(days.getTop(), books.getTop());
                assertEquals(days.getTop(), average.getTop());
                assertTrue(days.getRight() <= books.getLeft() && books.getRight() <= average.getLeft());
                assertTrue(value.getLineCount() <= 2);
            });
            screenshot("hierarchy-long-seconds");
            scenario.onActivity(activity -> {
                TextView value = activity.findViewById(id("tv_duration"));
                assertEquals("Duration text clipped", value.length(),
                    value.getLayout().getLineEnd(value.getLineCount() - 1));
                assertMetricsAligned(activity);
                assertEquals("Days label wrapped", 1, ((TextView)activity.findViewById(id("tv_days_label"))).getLineCount());
                assertEquals("Books label wrapped", 1, ((TextView)activity.findViewById(id("tv_books_label"))).getLineCount());
                assertEquals("Average label wrapped", 1, ((TextView)activity.findViewById(id("tv_average_label"))).getLineCount());
            });
        }
        prefs().edit().putBoolean("readRecordUseDays", true).putBoolean("readRecordShowSeconds", false).commit();
        try (ActivityScenario<Activity> scenario = launch()) {
            await(scenario);
            scenario.onActivity(activity -> {
                activity.findViewById(id("tab_all")).performClick();
                String value = text(activity, "tv_duration");
                assertTrue(value.contains("天"));
                assertFalse(value.contains("秒"));
            });
            screenshot("hierarchy-long-days");
            scenario.onActivity(this::assertMetricsAligned);
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
                scenario.onActivity(activity -> {
                    activity.findViewById(id("scope_qualified")).performClick();
                    assertTrue(activity.findViewById(id("scope_qualified")).isSelected());
                    assertSummary(activity, minimum, 1, 1, minimum);
                });
                screenshot("scope-" + theme + "-qualified");
                scenario.onActivity(this::assertMetricsAligned);
            }
        }
    }
}
