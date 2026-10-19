package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.legado.app.data.entities.ReadingNoteLocation
import io.legado.app.data.entities.ReadingNoteSource

@Dao
interface ReadingNoteDao {
    @Query("SELECT * FROM reading_note_locations")
    fun locations(): List<ReadingNoteLocation>

    @Query("SELECT * FROM reading_note_locations WHERE bookUrl = :bookUrl")
    fun locationsForBook(bookUrl: String): List<ReadingNoteLocation>

    @Query("SELECT * FROM reading_note_locations WHERE kind = :kind AND noteTime = :time")
    fun locationsForNote(kind: String, time: Long): List<ReadingNoteLocation>

    @Query("SELECT * FROM reading_note_sources")
    fun sources(): List<ReadingNoteSource>

    @Upsert
    fun saveLocations(locations: List<ReadingNoteLocation>)

    @Upsert
    fun saveSources(sources: List<ReadingNoteSource>)

    @Query("DELETE FROM reading_note_locations WHERE kind = :kind AND noteTime IN (:times)")
    fun deleteLocations(kind: String, times: List<Long>)

    @Query("DELETE FROM reading_note_sources WHERE bookUrl IN (:urls)")
    fun deleteSources(urls: List<String>)
}
