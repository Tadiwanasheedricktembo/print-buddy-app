package com.tadiwaprintbuddy.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun getAllNotesNewestFirst(): Flow<List<Note>>

    @Query("SELECT * FROM notes ORDER BY createdAt ASC")
    fun getAllNotesOldestFirst(): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%'")
    fun searchNotes(query: String): Flow<List<Note>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNoteInternal(note: Note): Long

    @Update
    suspend fun updateNoteInternal(note: Note): Int

    @Delete
    suspend fun deleteNoteInternal(note: Note): Int

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
        val rows = deleteNoteInternal(note)
        insertSyncEvent(SyncOutbox(entityType = "NOTE", entitySyncId = note.syncId, operation = "DELETE"))
        return rows
    }

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNoteById(id: Int): Note?
}
