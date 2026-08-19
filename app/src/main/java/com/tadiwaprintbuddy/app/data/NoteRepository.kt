package com.tadiwaprintbuddy.app.data

import kotlinx.coroutines.flow.Flow

class NoteRepository(private val noteDao: NoteDao) {
    fun getAllNotes(newestFirst: Boolean): Flow<List<Note>> {
        return if (newestFirst) {
            noteDao.getAllNotesNewestFirst()
        } else {
            noteDao.getAllNotesOldestFirst()
        }
    }

    fun searchNotes(query: String): Flow<List<Note>> {
        return noteDao.searchNotes(query)
    }

    suspend fun insertNote(note: Note): Long {
        return noteDao.insertNote(note)
    }

    suspend fun updateNote(note: Note): Int {
        return noteDao.updateNote(note)
    }

    suspend fun deleteNote(note: Note): Int {
        return noteDao.deleteNote(note)
    }

    suspend fun getNoteById(id: Int): Note? {
        return noteDao.getNoteById(id)
    }
}
