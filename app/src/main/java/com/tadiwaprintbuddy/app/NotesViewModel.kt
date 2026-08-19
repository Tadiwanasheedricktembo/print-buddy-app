package com.tadiwaprintbuddy.app

import androidx.lifecycle.*
import com.tadiwaprintbuddy.app.data.Note
import com.tadiwaprintbuddy.app.data.NoteRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModel(private val repository: NoteRepository) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortNewestFirst = MutableStateFlow(true)
    val sortNewestFirst: StateFlow<Boolean> = _sortNewestFirst.asStateFlow()

    val notes: StateFlow<List<Note>> = combine(
        _searchQuery,
        _sortNewestFirst
    ) { query, newestFirst ->
        Pair(query, newestFirst)
    }.flatMapLatest { (query, newestFirst) ->
        if (query.isBlank()) {
            repository.getAllNotes(newestFirst)
        } else {
            repository.searchNotes(query)
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun onSortOrderChanged(newestFirst: Boolean) {
        _sortNewestFirst.value = newestFirst
    }

    fun deleteNote(note: Note) {
        viewModelScope.launch {
            repository.deleteNote(note)
        }
    }

    fun saveNote(title: String, content: String, noteId: Int? = null) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (noteId == null) {
                repository.insertNote(Note(title = title, content = content, createdAt = now, updatedAt = now))
            } else {
                val existing = repository.getNoteById(noteId)
                if (existing != null) {
                    repository.updateNote(existing.copy(title = title, content = content, updatedAt = now))
                }
            }
        }
    }

    suspend fun getNoteById(id: Int): Note? {
        return repository.getNoteById(id)
    }
}

class NotesViewModelFactory(private val repository: NoteRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(NotesViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return NotesViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
