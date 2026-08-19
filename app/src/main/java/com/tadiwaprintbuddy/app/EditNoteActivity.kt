package com.tadiwaprintbuddy.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.NoteRepository
import com.tadiwaprintbuddy.app.databinding.ActivityEditNoteBinding
import kotlinx.coroutines.launch

class EditNoteActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTE_ID = "extra_note_id"
    }

    private lateinit var binding: ActivityEditNoteBinding
    private val viewModel: NotesViewModel by viewModels {
        val database = AppDatabase.getDatabase(this)
        NotesViewModelFactory(NoteRepository(database.noteDao()))
    }
    private var noteId: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditNoteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        noteId = intent.getIntExtra(EXTRA_NOTE_ID, -1).takeIf { it != -1 }

        setupToolbar()
        setupSaveButton()

        if (noteId != null) {
            loadNote(noteId!!)
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        if (noteId != null) {
            binding.toolbar.title = getString(R.string.edit_note)
        } else {
            binding.toolbar.title = getString(R.string.new_note)
        }
    }

    private fun setupSaveButton() {
        binding.btnSaveNote.setOnClickListener {
            val title = binding.editNoteTitle.text.toString().trim()
            val content = binding.editNoteContent.text.toString().trim()

            if (title.isEmpty()) {
                Toast.makeText(this, R.string.error_empty_title, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            viewModel.saveNote(title, content, noteId)
            Toast.makeText(this, R.string.note_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun loadNote(id: Int) {
        lifecycleScope.launch {
            val note = viewModel.getNoteById(id)
            if (note != null) {
                binding.editNoteTitle.setText(note.title)
                binding.editNoteContent.setText(note.content)
            }
        }
    }
}
