package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordBook
import io.legado.app.data.entities.ReadRecordShow
import io.legado.app.data.entities.ReadRecordAuthors
import io.legado.app.data.entities.ReadRecordDaily
import io.legado.app.data.entities.ReadRecordDailyShow
import io.legado.app.data.entities.ReadRecordHistory
import io.legado.app.data.entities.ReadRecordHourly
import io.legado.app.data.entities.ReadRecordHourlyShow
import io.legado.app.data.entities.ReadStatisticsState
import io.legado.app.data.entities.mergeRestoredReadRecord
import io.legado.app.help.book.ReadDayDuration
import io.legado.app.help.book.ReadHourDuration
import io.legado.app.help.book.isReadStatisticsDate
import io.legado.app.help.book.mergeReadStatistics
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadRecordDao {

    @get:Query("select * from readRecord")
    val all: List<ReadRecord>

    @Query(
        """select distinct bookName, case when author = '' then coalesce(resolvedAuthor, '') else author end as author from readRecord
            order by bookName collate localized, author collate localized"""
    )
    fun flowBooks(): Flow<List<ReadRecordBook>>

    @get:Query(
        """
        select history.bookName, sum(history.readTime) as readTime,
            max(history.lastRead) as lastRead,
            case when history.author = '' then coalesce(history.resolvedAuthor, '') else history.author end as author,
            snapshot.lastChapterTitle, snapshot.lastChapterIndex,
            snapshot.lastChapterPos, snapshot.coverUrl
        from readRecord history
        join readRecord snapshot on snapshot.rowid = (select rowid from readRecord
                where bookName = history.bookName and
                    (case when author = '' then coalesce(resolvedAuthor, '') else author end) =
                    (case when history.author = '' then coalesce(history.resolvedAuthor, '') else history.author end)
                order by lastRead desc, deviceId, author limit 1)
        group by history.bookName,
            case when history.author = '' then coalesce(history.resolvedAuthor, '') else history.author end
        order by history.bookName collate localized, author collate localized"""
    )
    val allShow: List<ReadRecordShow>

    @get:Query("select sum(readTime) from readRecord")
    val allTime: Long

    @Query(
        """
        select history.bookName, sum(history.readTime) as readTime,
            max(history.lastRead) as lastRead,
            case when history.author = '' then coalesce(history.resolvedAuthor, '') else history.author end as author,
            snapshot.lastChapterTitle, snapshot.lastChapterIndex,
            snapshot.lastChapterPos, snapshot.coverUrl
        from readRecord history
        join readRecord snapshot on snapshot.rowid = (select rowid from readRecord
                where bookName = history.bookName and
                    (case when author = '' then coalesce(resolvedAuthor, '') else author end) =
                    (case when history.author = '' then coalesce(history.resolvedAuthor, '') else history.author end)
                order by lastRead desc, deviceId, author limit 1)
        group by history.bookName,
            case when history.author = '' then coalesce(history.resolvedAuthor, '') else history.author end
        having history.bookName like '%' || :searchKey || '%'
            or (case when history.author = '' then coalesce(history.resolvedAuthor, '') else history.author end) like '%' || :searchKey || '%'
        order by history.bookName collate localized, author collate localized"""
    )
    fun search(searchKey: String): List<ReadRecordShow>

    @Query("select readTime from readRecord where deviceId = :deviceId and bookName = :bookName and author = :author")
    fun getReadTime(deviceId: String, bookName: String, author: String): Long?

    @Query("select * from readRecord where deviceId = :deviceId and bookName = :bookName and author = :author")
    fun getRecord(deviceId: String, bookName: String, author: String): ReadRecord?

    @Query("""update readRecord set coverUrl = :coverUrl
        where deviceId = :deviceId and bookName = :bookName and author = :author and coverUrl = :expected""")
    fun updateCoverIfUnchanged(deviceId: String, bookName: String, author: String, expected: String, coverUrl: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg readRecord: ReadRecord)

    @Update
    fun update(vararg record: ReadRecord)

    @Delete
    fun delete(vararg record: ReadRecord)

    @Query("delete from readRecord")
    fun clearRecordsOnly()

    @Query("""delete from readRecord where bookName = :bookName and
        (case when author = '' then coalesce(resolvedAuthor, '') else author end) = :author""")
    fun deleteRecordsByBook(bookName: String, author: String)

    @Transaction
    fun clear() {
        clearHourly()
        clearDaily()
        clearHistory()
        clearRecordsOnly()
    }

    @Transaction
    fun deleteByBook(bookName: String, author: String) {
        getRecordsByEffectiveAuthor(bookName, author).forEach {
            deleteHourly(it.deviceId, it.bookName, it.author)
            deleteDaily(it.deviceId, it.bookName, it.author)
            deleteHistory(it.deviceId, it.bookName, it.author)
        }
        deleteRecordsByBook(bookName, author)
    }

    @Transaction
    fun deleteBooks(books: List<ReadRecordBook>) {
        books.distinct().forEach { deleteByBook(it.bookName, it.author) }
    }

    @Query("select * from readRecord where bookName = :bookName and author = :author")
    fun getRecords(bookName: String, author: String): List<ReadRecord>

    @Query("""select * from readRecord where bookName = :bookName and
        (case when author = '' then coalesce(resolvedAuthor, '') else author end) = :author""")
    fun getRecordsByEffectiveAuthor(bookName: String, author: String): List<ReadRecord>

    /** Move every device's account together, including explicitly resolved legacy authors. */
    @Transaction
    fun updateBookMetadata(oldName: String, oldAuthor: String, newName: String, newAuthor: String) {
        if (oldName == newName && oldAuthor == newAuthor) return
        val originals = getRecordsByEffectiveAuthor(oldName, oldAuthor)
        if (originals.isEmpty()) return
        val destination = getRecordsByEffectiveAuthor(newName, newAuthor)
        val daily = (originals + destination).flatMap { getDaily(it.deviceId, it.bookName, it.author) }
        val hourly = (originals + destination).flatMap { getHourly(it.deviceId, it.bookName, it.author) }
        val histories = (originals + destination).mapNotNull {
            getHistory(it.deviceId, it.bookName, it.author)?.let { time ->
                ReadRecordHistory(it.deviceId, it.bookName, it.author, time)
            }
        }
        (originals + destination).forEach { deleteDaily(it.deviceId, it.bookName, it.author) }
        (originals + destination).forEach { deleteHourly(it.deviceId, it.bookName, it.author) }
        (originals + destination).forEach { deleteHistory(it.deviceId, it.bookName, it.author) }
        // Remove all selected rows first: a resolved blank-author row may share the new PK.
        delete(*(originals + destination).toTypedArray())
        (destination + originals).groupBy { it.deviceId }.values.forEach { records ->
            val renamed = records.map {
                it.copy(bookName = newName, author = newAuthor, resolvedAuthor = null)
            }
            val merged = renamed.reduce { current, incoming ->
                mergeRestoredReadRecord(current, incoming, localDevice = true).copy(
                    readTime = Math.addExact(current.readTime, incoming.readTime),
                )
            }
            insert(merged)
        }
        daily.groupBy { it.deviceId to it.date }.values.forEach { rows ->
            insertDaily(rows.first().copy(
                bookName = newName, author = newAuthor,
                readTime = rows.fold(0L) { total, row -> Math.addExact(total, row.readTime) },
            ))
        }
        hourly.groupBy { Triple(it.deviceId, it.date, it.hour) }.values.forEach { rows ->
            insertHourly(rows.first().copy(
                bookName = newName, author = newAuthor,
                readTime = rows.fold(0L) { total, row -> Math.addExact(total, row.readTime) },
            ))
        }
        histories.groupBy { it.deviceId }.values.forEach { rows ->
            insertHistory(ReadRecordHistory(rows.first().deviceId, newName, newAuthor,
                rows.fold(0L) { total, row -> Math.addExact(total, row.readTime) }))
        }
    }

    /** A user removes an obsolete label; the legacy row's undivided duration stays intact. */
    @Transaction
    fun removeLegacyAuthor(bookName: String, author: String, removedAuthor: String) {
        val authors = ReadRecordAuthors.decode(author)
        if (!ReadRecordAuthors.isCombined(author) || authors.size < 2 || removedAuthor !in authors) return
        val remaining = (authors - removedAuthor).reduce(ReadRecordAuthors::merge)
        updateBookMetadata(bookName, author, bookName, remaining)
    }

    /** The first subsequent reading supplies the author for this title's unknown history. */
    @Transaction
    fun resolveUnknownAuthor(bookName: String, author: String) {
        if (author.isBlank() || ReadRecordAuthors.isCombined(author)) return
        assignUnknownAuthor(bookName, author)
    }

    @Query("""update readRecord set resolvedAuthor = :author
        where bookName = :bookName and author = '' and resolvedAuthor is null""")
    fun assignUnknownAuthor(bookName: String, author: String)

    @Query("select * from readRecordDaily")
    fun allDaily(): List<ReadRecordDaily>

    @Query("select * from readRecordHistory")
    fun allHistory(): List<ReadRecordHistory>

    @Query("select readTime from readRecordHistory where deviceId = :deviceId and bookName = :bookName and author = :author")
    fun getHistory(deviceId: String, bookName: String, author: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertHistory(vararg history: ReadRecordHistory)

    @Query("delete from readRecordHistory")
    fun clearHistory()

    @Query("delete from readRecordHistory where deviceId = :deviceId and bookName = :bookName and author = :author")
    fun deleteHistory(deviceId: String, bookName: String, author: String)

    @Query(
        """select daily.bookName,
            case when history.author = '' then coalesce(history.resolvedAuthor, '') else history.author end as author,
            daily.date, sum(daily.readTime) as readTime
        from readRecordDaily daily join readRecord history
            on daily.deviceId = history.deviceId and daily.bookName = history.bookName and daily.author = history.author
        group by daily.bookName,
            case when history.author = '' then coalesce(history.resolvedAuthor, '') else history.author end, daily.date"""
    )
    fun dailyShow(): List<ReadRecordDailyShow>

    @Query("select * from readRecordDaily where deviceId = :deviceId and bookName = :bookName and author = :author")
    fun getDaily(deviceId: String, bookName: String, author: String): List<ReadRecordDaily>

    @Query("select readTime from readRecordDaily where deviceId = :deviceId and bookName = :bookName and author = :author and date = :date")
    fun getDailyTime(deviceId: String, bookName: String, author: String, date: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertDaily(vararg daily: ReadRecordDaily)

    @Query("delete from readRecordDaily")
    fun clearDaily()

    @Query("delete from readRecordDaily where deviceId = :deviceId and bookName = :bookName and author = :author")
    fun deleteDaily(deviceId: String, bookName: String, author: String)

    @Query("select * from readRecordHourly")
    fun allHourly(): List<ReadRecordHourly>

    @Query("""select hourly.bookName,
        case when history.author = '' then coalesce(history.resolvedAuthor, '') else history.author end as author,
        hourly.date, hourly.hour, sum(hourly.readTime) as readTime
        from readRecordHourly hourly join readRecord history
        on hourly.deviceId = history.deviceId and hourly.bookName = history.bookName and hourly.author = history.author
        group by hourly.bookName,
        case when history.author = '' then coalesce(history.resolvedAuthor, '') else history.author end,
        hourly.date, hourly.hour""")
    fun hourlyShow(): List<ReadRecordHourlyShow>

    @Query("select * from readRecordHourly where deviceId = :deviceId and bookName = :bookName and author = :author")
    fun getHourly(deviceId: String, bookName: String, author: String): List<ReadRecordHourly>

    @Query("""select readTime from readRecordHourly where deviceId = :deviceId and bookName = :bookName
        and author = :author and date = :date and hour = :hour""")
    fun getHourlyTime(deviceId: String, bookName: String, author: String, date: String, hour: Int): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertHourly(vararg hourly: ReadRecordHourly)

    @Query("delete from readRecordHourly")
    fun clearHourly()

    @Query("delete from readRecordHourly where deviceId = :deviceId and bookName = :bookName and author = :author")
    fun deleteHourly(deviceId: String, bookName: String, author: String)

    @Transaction
    fun addHourly(record: ReadRecord, intervals: List<ReadHourDuration>) {
        intervals.forEach { interval ->
            val existing = getHourlyTime(record.deviceId, record.bookName, record.author, interval.date, interval.hour) ?: 0L
            insertHourly(ReadRecordHourly(record.deviceId, record.bookName, record.author, interval.date,
                interval.hour, Math.addExact(existing, interval.duration)))
        }
    }

    @Query("select * from readStatisticsState")
    fun statisticsStates(): List<ReadStatisticsState>

    @Query("select * from readStatisticsState where deviceId = :deviceId")
    fun statisticsState(deviceId: String): ReadStatisticsState?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun ensureStatisticsState(state: ReadStatisticsState)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertStatisticsState(state: ReadStatisticsState)

    @Transaction
    fun addDaily(record: ReadRecord, intervals: List<ReadDayDuration>, intervalEnd: Long = record.lastRead) {
        if (intervals.isEmpty()) return
        intervals.forEach { interval ->
            val existing = getDailyTime(record.deviceId, record.bookName, record.author, interval.date) ?: 0L
            val total = Math.addExact(existing, interval.duration)
            insertDaily(ReadRecordDaily(record.deviceId, record.bookName, record.author, interval.date, total))
        }
        ensureStatisticsState(ReadStatisticsState(
            record.deviceId, (intervalEnd - intervals.sumOf { it.duration }).coerceAtLeast(0),
        ))
    }

    @Transaction
    fun restoreStatistics(
        records: List<ReadRecord>,
        daily: List<ReadRecordDaily>?,
        states: List<ReadStatisticsState>?,
        localDeviceId: String,
        history: List<ReadRecordHistory>? = null,
        hourly: List<ReadRecordHourly>? = null,
    ) {
        val incomingHourly = hourly.orEmpty().map {
            if (it.deviceId.isBlank()) it.copy(deviceId = localDeviceId) else it
        }.filter { it.readTime > 0 && it.hour in 0..23 && isReadStatisticsDate(it.date) }
            .groupBy { Triple(it.deviceId, it.bookName, it.author) }
        val incomingHistory = history.orEmpty().map {
            if (it.deviceId.isBlank()) it.copy(deviceId = localDeviceId) else it
        }.filter { it.readTime >= 0 }.groupBy { Triple(it.deviceId, it.bookName, it.author) }
        val incomingDaily = daily?.map {
            if (it.deviceId.isBlank()) it.copy(deviceId = localDeviceId) else it
        }?.filter { it.readTime > 0 && isReadStatisticsDate(it.date) }
            ?.groupBy { Triple(it.deviceId, it.bookName, it.author) }
        records.forEach { incoming ->
            val current = getRecord(incoming.deviceId, incoming.bookName, incoming.author)
            val restored = mergeReadStatistics(
                current, incoming,
                getDaily(incoming.deviceId, incoming.bookName, incoming.author),
                if (daily == null) null else incomingDaily?.get(Triple(incoming.deviceId, incoming.bookName, incoming.author)).orEmpty(),
                incoming.deviceId == localDeviceId,
                getHistory(incoming.deviceId, incoming.bookName, incoming.author),
                incomingHistory[Triple(incoming.deviceId, incoming.bookName, incoming.author)]?.maxOf { it.readTime },
                getHourly(incoming.deviceId, incoming.bookName, incoming.author),
                incomingHourly[Triple(incoming.deviceId, incoming.bookName, incoming.author)],
            )
            insert(restored.record)
            insertDaily(*restored.daily.toTypedArray())
            insertHourly(*restored.hourly.toTypedArray())
            restored.historyTime?.let {
                insertHistory(ReadRecordHistory(incoming.deviceId, incoming.bookName, incoming.author, it))
            }
        }
        states.orEmpty().forEach { state ->
            val normalized = if (state.deviceId.isBlank()) state.copy(deviceId = localDeviceId) else state
            if (normalized.startedAt > 0) {
                val current = statisticsState(normalized.deviceId)
                insertStatisticsState(normalized.copy(
                    startedAt = minOf(current?.startedAt ?: Long.MAX_VALUE, normalized.startedAt),
                ))
            }
        }
    }
}
