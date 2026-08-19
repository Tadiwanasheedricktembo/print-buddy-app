package com.tadiwaprintbuddy.app

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.Note
import com.tadiwaprintbuddy.app.data.NoteRepository
import com.tadiwaprintbuddy.app.databinding.ActivityNotesBinding
import kotlinx.coroutines.launch

class NotesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotesBinding
    private val viewModel: NotesViewModel by viewModels {
        val database = AppDatabase.getDatabase(this)
        // I need to add noteDao to AppDatabase
        NotesViewModelFactory(NoteRepository(database.noteDao()))
    }
    private lateinit var adapter: NotesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupSearch()
        setupFab()
        observeViewModel()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun setupRecyclerView() {
        adapter = NotesAdapter(
            onNoteClick = { note ->
                val intent = Intent(this, EditNoteActivity::class.java)
                intent.putExtra(EditNoteActivity.EXTRA_NOTE_ID, note.id)
                startActivity(intent)
            },
            onNoteLongClick = { note ->
                showDeleteConfirmation(note)
            }
        )
        binding.recyclerNotes.layoutManager = LinearLayoutManager(this)
        binding.recyclerNotes.adapter = adapter
    }

    private fun setupSearch() {
        binding.editSearchNotes.addTextChangedListener { text ->
            viewModel.onSearchQueryChanged(text?.toString().orEmpty())
            binding.btnClearSearch.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        binding.btnClearSearch.setOnClickListener {
            binding.editSearchNotes.text.clear()
        }
    }

    private fun setupFab() {
        binding.fabAddNote.setOnClickListener {
            startActivity(Intent(this, EditNoteActivity::class.java))
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.notes.collect { notes ->
                    adapter.submitList(notes)
                    binding.layoutEmptyState.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun showDeleteConfirmation(note: Note) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_note)
            .setMessage(R.string.delete_note_confirm)
            .setPositiveButton(R.string.delete_note) { _, _ ->
                viewModel.deleteNote(note)
                Toast.makeText(this, R.string.note_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_notes, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sort_newest -> {
                viewModel.onSortOrderChanged(true)
                true
            }
            R.id.action_sort_oldest -> {
                viewModel.onSortOrderChanged(false)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
