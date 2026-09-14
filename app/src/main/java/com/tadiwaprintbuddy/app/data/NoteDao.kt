package com.tadiwaprintbuddy.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE (deletedAt IS NULL) ORDER BY createdAt DESC")
    fun getAllNotesNewestFirst(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE (deletedAt IS NULL) ORDER BY createdAt ASC")
    fun getAllNotesOldestFirst(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%') AND (deletedAt IS NULL)")
    fun searchNotes(query: String): Flow<List<Note>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNoteInternal(note: Note): Long

    @Update
    suspend fun updateNoteInternal(note: Note): Int

    @Query("UPDATE notes SET deletedAt = :deletedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markNoteDeletedInternal(id: Int, deletedAt: Long, updatedAt: Long): Int

    @Insert
    suspend fun insertSyncEvent(entry: SyncOutbox): Long

    @Transaction
    suspend fun insertNoteWithSync(note: Note): Long {
        val id = insertNoteInternal(note)
        insertSyncEvent(SyncOutbox(entityType = "NOTE", entitySyncId = note.syncId, operation = "CREATE"))
        return id
    }

    @Transaction
    suspend fun updateNoteWithSync(note: Note): Int {
        val rows = updateNoteInternal(note)
        insertSyncEvent(SyncOutbox(entityType = "NOTE", entitySyncId = note.syncId, operation = "UPDATE"))
        return rows
    }

    @Transaction
    suspend fun deleteNoteWithSync(note: Note): Int {
        val now = System.currentTimeMillis()
        val rows = markNoteDeletedInternal(note.id, now, now)
        insertSyncEvent(SyncOutbox(entityType = "NOTE", entitySyncId = note.syncId, operation = "DELETE"))
        return rows
    }

    @Query("SELECT * FROM notes WHERE id = :id AND (deletedAt IS NULL)")
    suspend fun getNoteById(id: Int): Note?
}
