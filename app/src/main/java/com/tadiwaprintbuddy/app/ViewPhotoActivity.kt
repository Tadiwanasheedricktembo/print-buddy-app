package com.tadiwaprintbuddy.app

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.PrinterReference
import com.tadiwaprintbuddy.app.data.PrintRepository
import com.tadiwaprintbuddy.app.databinding.ActivityViewPhotoBinding
import kotlinx.coroutines.launch
import java.io.File

class ViewPhotoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityViewPhotoBinding
    private lateinit var repository: PrintRepository
    private lateinit var references: List<PrinterReference>
    private var currentPosition: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewPhotoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val database = AppDatabase.getDatabase(this)
        repository = PrintRepository(database.printDao())

        references = intent.getParcelableArrayListExtra("REFERENCES") ?: emptyList()
        currentPosition = intent.getIntExtra("POSITION", -1)

        if (references.isNotEmpty()) {
            val adapter = FullScreenPhotoAdapter(references)
            binding.viewPager.adapter = adapter
            binding.viewPager.setCurrentItem(currentPosition, false)

            updateUiForPosition(currentPosition)

            binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    currentPosition = position
                    updateUiForPosition(position)
                }
            })
        }

        binding.buttonDelete.setOnClickListener {
            showDeleteConfirmationDialog()
        }
    }

    private fun updateUiForPosition(position: Int) {
        val reference = references[position]
        binding.textTitleFull.text = reference.title
        binding.textNotesFull.text = reference.notes
    }

    private fun showDeleteConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Delete Reference")
            .setMessage("Are you sure you want to delete this reference?")
            .setPositiveButton("Delete") { _, _ ->
                deleteReference()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteReference() {
        if (currentPosition != -1) {
            lifecycleScope.launch {
                val referenceToDelete = references[currentPosition]
                try {
                    val file = File(referenceToDelete.imagePath)
                    if (file.exists()) {
                        file.delete()
                    }
                } catch (e: Exception) {
                    // Handle exception
                }

                repository.deletePrinterReference(referenceToDelete)
                setResult(Activity.RESULT_OK)
                finish()
            }
        }
    }
}
