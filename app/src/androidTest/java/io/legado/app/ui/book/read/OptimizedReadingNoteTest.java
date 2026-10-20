package io.legado.app.ui.book.read;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Uses kept backup entities, Android APIs and the real reader, including in the optimized APK. */
@RunWith(AndroidJUnit4.class)
public class OptimizedReadingNoteTest {
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
                else if (value instanceof String) row.put(column, (String) value);
                else throw new AssertionError("Unexpected persisted field: " + name);
            }
        }
        db.insertOrThrow(table, null, row);
    }

    @Test public void optimizedReaderRepairsBothTypesAndKeepsBackupFieldNames() throws Exception {
        verifyReader(false);
    }

    @Test public void optimizedReaderReopensLegacyBookmarkContainingChapterTitle() throws Exception {
        verifyReader(true);
    }

    private void verifyReader(boolean titleInBookmark) throws Exception {
        try (ActivityScenario<Activity> startup = ActivityScenario.launch(
                new Intent().setClassName(context.getPackageName(),
                    "io.legado.app.ui.highlight.HighlightTagManageActivity"))) { }
        SQLiteDatabase db = SQLiteDatabase.openDatabase(context.getDatabasePath("legado.db").getPath(),
            null, SQLiteDatabase.OPEN_READWRITE);
        String suffix = UUID.randomUUID().toString();
        String url = "https://example.invalid/notes/" + suffix;
        String sourceUrl = "https://example.invalid/source/" + suffix;
        String name = "Optimized notes " + suffix;
        String quote = "唯一的优化包定位原文";
        long time = System.currentTimeMillis();
        File cache = null;
        ActivityScenario<Activity> scenario = null;
        try {
            assertEquals(123, db.getVersion());
            Object book = entity("Book");
            set(book, "bookUrl", url);
            set(book, "name", name);
            set(book, "origin", sourceUrl);
            set(book, "tocUrl", url + "/toc");
            set(book, "totalChapterNum", 1);
            set(book, "canUpdate", false);
            insert(db, "books", book);
            Object source = entity("BookSource");
            set(source, "bookSourceUrl", sourceUrl);
            insert(db, "book_sources", source);
            Object chapter = entity("BookChapter");
            set(chapter, "bookUrl", url);
            set(chapter, "url", url + "/new");
            set(chapter, "title", "第1章 新章名");
            insert(db, "chapters", chapter);
            Object highlight = entity("BookHighlight");
            set(highlight, "time", time);
            set(highlight, "bookUrl", url);
            set(highlight, "bookName", name);
            set(highlight, "chapterUrl", url + "/old?key=expired");
            set(highlight, "chapterName", "第一章 旧章名");
            set(highlight, "chapterPos", 8);
            set(highlight, "chapterPosEnd", 8 + quote.length());
            set(highlight, "bookText", quote);
            set(highlight, "style", "{\"fill\":-256}");
            set(highlight, "note", "Keep memo");
            insert(db, "highlights", highlight);
            Object bookmark = entity("Bookmark");
            set(bookmark, "time", time + 1);
            set(bookmark, "bookName", name);
            set(bookmark, "chapterName", titleInBookmark ? "第1章 新章名" : "第一章 旧章名");
            set(bookmark, "chapterPos", 8);
            String bookmarkQuote = titleInBookmark ? "第1章 新章名\n" + quote : quote;
            set(bookmark, "bookText", bookmarkQuote);
            set(bookmark, "content", "Keep bookmark");
            insert(db, "bookmarks", bookmark);
            String folder = (String) book.getClass().getMethod("getFolderName").invoke(book);
            String filename = (String) chapter.getClass().getMethod("getFileName", String.class).invoke(chapter, "nb");
            cache = new File(context.getExternalFilesDir(null), "book_cache/" + folder);
            assertTrue(cache.isDirectory() || cache.mkdirs());
            Files.write(new File(cache, filename).toPath(),
                ("修文时插入的新正文，把原来偏移向后移动。\n这里是" + quote + "。").getBytes(StandardCharsets.UTF_8));
            scenario = ActivityScenario.launch(new Intent().setClassName(context.getPackageName(),
                "io.legado.app.ui.book.read.ReadBookActivity")
                .putExtra("bookUrl", url).putExtra("inBookshelf", true)
                .putExtra("index", 0).putExtra("chapterPos", 8)
                .putExtra("highlightLayoutTitleLength", 0)
                .putExtra("readingNoteKey", (titleInBookmark ? "bookmark:" : "highlight:") +
                    (titleInBookmark ? time + 1 : time))
                .putExtra("highlightAnchorText", titleInBookmark ? bookmarkQuote : quote));
            await(() -> {
                try (Cursor cursor = db.rawQuery("SELECT count(*) FROM reading_note_locations WHERE noteTime IN (?,?) AND verified=1",
                        new String[]{Long.toString(time), Long.toString(time + 1)})) {
                    return cursor.moveToFirst() && cursor.getInt(0) == 2;
                }
            });
            await(() -> {
                try (Cursor cursor = db.rawQuery("SELECT durChapterPos FROM books WHERE bookUrl=?",
                        new String[]{url})) {
                    return cursor.moveToFirst() && cursor.getInt(0) > 0;
                }
            });
            try (Cursor cursor = db.rawQuery("SELECT bookText,content FROM bookmarks WHERE time=?",
                    new String[]{Long.toString(time + 1)})) {
                assertTrue(cursor.moveToFirst());
                assertEquals(bookmarkQuote, cursor.getString(0));
                assertEquals("Keep bookmark", cursor.getString(1));
            }
            try (Cursor cursor = db.rawQuery("SELECT chapterUrl,chapterPos,bookText,note FROM highlights WHERE time=?",
                    new String[]{Long.toString(time)})) {
                assertTrue(cursor.moveToFirst());
                assertEquals(url + "/new", cursor.getString(0));
                assertTrue(cursor.getInt(1) > 8);
                assertEquals(quote, cursor.getString(2));
                assertEquals("Keep memo", cursor.getString(3));
            }
            for (String field : new String[]{"kind", "noteTime", "bookUrl", "chapterUrl", "quote",
                    "prefix", "suffix", "verified", "updatedAt"}) {
                assertNotNull(entity("ReadingNoteLocation").getClass().getDeclaredField(field));
            }
            assertNotNull(entity("ReadingNoteSource").getClass().getDeclaredField("workId"));
        } finally {
            if (scenario != null) scenario.close();
            db.delete("reading_note_locations", "noteTime IN (?,?)",
                new String[]{Long.toString(time), Long.toString(time + 1)});
            db.delete("highlights", "time=?", new String[]{Long.toString(time)});
            db.delete("bookmarks", "time=?", new String[]{Long.toString(time + 1)});
            db.delete("chapters", "bookUrl=?", new String[]{url});
            db.delete("books", "bookUrl=?", new String[]{url});
            db.delete("book_sources", "bookSourceUrl=?", new String[]{sourceUrl});
            db.close();
            if (cache != null && cache.isDirectory()) {
                File[] files = cache.listFiles();
                if (files != null) for (File file : files) assertTrue(file.delete());
                assertTrue(cache.delete());
            }
        }
    }

    private void await(BooleanSupplier condition) {
        long end = SystemClock.uptimeMillis() + 20000;
        do {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(60);
        } while (SystemClock.uptimeMillis() < end);
        fail("Optimized note fixture was not ready");
    }
}
