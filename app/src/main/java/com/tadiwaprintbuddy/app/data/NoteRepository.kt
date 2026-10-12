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

    fun searchNotes(query: String, newestFirst: Boolean = true): Flow<List<Note>> {
        return if (newestFirst) {
            noteDao.searchNotesNewestFirst(query)
        } else {
            noteDao.searchNotesOldestFirst(query)
        }
    }

    suspend fun insertNote(note: Note): Long {
        return noteDao.insertNoteWithSync(note)
    }

    suspend fun updateNote(note: Note): Int {
        return noteDao.updateNoteWithSync(note)
    }

    suspend fun deleteNote(note: Note): Int {
        return noteDao.deleteNoteWithSync(note)
    }

    suspend fun getNoteById(id: Int): Note? {
        return noteDao.getNoteById(id)
    }
}
