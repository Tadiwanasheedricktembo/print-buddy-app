package com.tadiwaprintbuddy.app

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tadiwaprintbuddy.app.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class)
class NotesFeatureTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var noteDao: NoteDao
    private lateinit var repository: NoteRepository
    private lateinit var viewModel: NotesViewModel

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        noteDao = db.noteDao()
        repository = NoteRepository(noteDao)
        viewModel = NotesViewModel(repository)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `test note creation and retrieval`() = runBlocking {
        viewModel.saveNote("Title 1", "Content of note 1")

        val notesList = repository.getAllNotes(true).first()
        assertEquals(1, notesList.size)
        val created = notesList.first()
        assertEquals("Title 1", created.title)
        assertEquals("Content of note 1", created.content)
        assertTrue(created.createdAt > 0)
        assertTrue(created.updatedAt > 0)
        assertNotNull(created.syncId)
        assertNull(created.deletedAt)
    }

    @Test
    fun `test note edit and update`() = runBlocking {
        viewModel.saveNote("Original Title", "Original Content")
        val original = repository.getAllNotes(true).first().first()

        viewModel.saveNote("Updated Title", "Updated Content", original.id)

        val updated = repository.getNoteById(original.id)
        assertNotNull(updated)
        assertEquals("Updated Title", updated!!.title)
        assertEquals("Updated Content", updated.content)
        assertEquals(original.createdAt, updated.createdAt)
        assertTrue(updated.updatedAt >= original.updatedAt)
    }

    @Test
    fun `test note deletion`() = runBlocking {
        viewModel.saveNote("To Delete", "Delete Content")
        val created = repository.getAllNotes(true).first().first()

        viewModel.deleteNote(created)

        val activeNotes = repository.getAllNotes(true).first()
        assertTrue(activeNotes.isEmpty())

        val fetchedDirectly = repository.getNoteById(created.id)
        assertNull(fetchedDirectly)
    }

    @Test
    fun `test multiple notes and search with sorting`() = runBlocking {
        viewModel.saveNote("Alpha Note", "First item in list", null)
        Thread.sleep(10)
        viewModel.saveNote("Beta Note", "Second item in list", null)
        Thread.sleep(10)
        viewModel.saveNote("Gamma Special Note", "Third item in list", null)

        val newestFirst = repository.getAllNotes(true).first()
        assertEquals(3, newestFirst.size)
        assertEquals("Gamma Special Note", newestFirst[0].title)
        assertEquals("Beta Note", newestFirst[1].title)
        assertEquals("Alpha Note", newestFirst[2].title)

        val oldestFirst = repository.getAllNotes(false).first()
        assertEquals(3, oldestFirst.size)
        assertEquals("Alpha Note", oldestFirst[0].title)
        assertEquals("Beta Note", oldestFirst[1].title)
        assertEquals("Gamma Special Note", oldestFirst[2].title)

        val searchNewest = repository.searchNotes("Special", true).first()
        assertEquals(1, searchNewest.size)
        assertEquals("Gamma Special Note", searchNewest[0].title)

        val searchOldest = repository.searchNotes("Note", false).first()
        assertEquals(3, searchOldest.size)
        assertEquals("Alpha Note", searchOldest[0].title)
    }

    @Test
    fun `test notes backup and restore lifecycle end to end`() = runBlocking {
        // 1. Setup database on disk for BackupUtils
        val diskDbPath = context.getDatabasePath("print_database")
        diskDbPath.parentFile?.mkdirs()

        val diskDb = Room.databaseBuilder(context, AppDatabase::class.java, "print_database")
            .allowMainThreadQueries()
            .build()

        val diskNoteDao = diskDb.noteDao()
        val diskRepository = NoteRepository(diskNoteDao)

        // 2. Create representative note dataset
        diskRepository.insertNote(Note(title = "Normal Note", content = "Simple text note", createdAt = 1000L, updatedAt = 1000L))
        diskRepository.insertNote(Note(title = "Special Chars & Long Content Note", content = "Line 1: ₹1,500.00\nLine 2: ~!@#$%^&*()_+{}|:\"<>?`-=[]\\;',./", createdAt = 2000L, updatedAt = 2000L))
        val editId = diskRepository.insertNote(Note(title = "Draft Note", content = "Before edit", createdAt = 3000L, updatedAt = 3000L)).toInt()
        diskRepository.updateNote(Note(id = editId, title = "Edited Note Title", content = "After edit content", createdAt = 3000L, updatedAt = 4000L))
        val deleteId = diskRepository.insertNote(Note(title = "Temporary Note", content = "To be deleted", createdAt = 5000L, updatedAt = 5000L)).toInt()
        diskRepository.deleteNote(Note(id = deleteId, title = "Temporary Note", content = "To be deleted", createdAt = 5000L, updatedAt = 5000L))

        diskDb.close()

        // 3. Create zip backup
        val backupFile = File(context.cacheDir, "notes_test_backup.zip")
        val backupSuccess = BackupUtils.createZipBackupToFile(context, backupFile)
        assertTrue("Backup should succeed", backupSuccess)
        assertTrue("Backup file must exist", backupFile.exists() && backupFile.length() > 0)

        // 4. Clear/corrupt database state
        context.getDatabasePath("print_database").delete()
        context.getDatabasePath("print_database-shm").delete()
        context.getDatabasePath("print_database-wal").delete()

        // 5. Restore backup from zip
        val restoreSuccess = BackupUtils.restoreZipBackup(context, Uri.fromFile(backupFile))
        assertTrue("Restore should succeed", restoreSuccess)

        // 6. Verify restored database contents
        val restoredDb = Room.databaseBuilder(context, AppDatabase::class.java, "print_database")
            .allowMainThreadQueries()
            .build()
        val restoredDao = restoredDb.noteDao()
        val restoredRepository = NoteRepository(restoredDao)

        val restoredNotes = restoredRepository.getAllNotes(false).first()
        assertEquals(3, restoredNotes.size) // 3 active notes, 1 soft-deleted excluded

        assertEquals("Normal Note", restoredNotes[0].title)
        assertEquals("Simple text note", restoredNotes[0].content)

        assertEquals("Special Chars & Long Content Note", restoredNotes[1].title)
        assertTrue(restoredNotes[1].content.contains("₹1,500.00"))

        assertEquals("Edited Note Title", restoredNotes[2].title)
        assertEquals("After edit content", restoredNotes[2].content)

        val deletedCheck = restoredDao.getNoteById(deleteId)
        assertNull("Deleted note must not be returned in active notes query", deletedCheck)

        restoredDb.close()
    }
}
