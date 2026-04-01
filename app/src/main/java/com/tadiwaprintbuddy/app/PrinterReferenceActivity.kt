package com.tadiwaprintbuddy.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.PrinterReference
import com.tadiwaprintbuddy.app.data.PrintRepository
import com.tadiwaprintbuddy.app.databinding.ActivityPrinterReferenceBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PrinterReferenceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrinterReferenceBinding
    private lateinit var repository: PrintRepository
    private lateinit var adapter: PrinterReferenceAdapter
    private var latestTmpUri: Uri? = null
    private var latestTmpFilePath: String? = null
    private var references: List<PrinterReference> = emptyList()

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { isSuccess ->
        if (isSuccess) {
            latestTmpUri?.let { uri ->
                showAddReferenceDialog(uri)
            }
        }
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                copyImageToInternalStorage(uri)
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            showImageSourceDialog()
        } else {
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    private val viewPhotoLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            loadReferences()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrinterReferenceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val database = AppDatabase.getDatabase(this)
        repository = PrintRepository(database.printDao())

        adapter = PrinterReferenceAdapter(references) { position ->
            val intent = Intent(this, ViewPhotoActivity::class.java).apply {
                putParcelableArrayListExtra("REFERENCES", ArrayList(references))
                putExtra("POSITION", position)
            }
            viewPhotoLauncher.launch(intent)
        }
        binding.recyclerPrinterReferences.layoutManager = LinearLayoutManager(this)
        binding.recyclerPrinterReferences.adapter = adapter

        binding.fabAddReference.setOnClickListener {
            showImageSourceDialog()
        }

        loadReferences()
    }

    private fun showImageSourceDialog() {
        val options = arrayOf("Take Photo", "Choose from Gallery", "Cancel")
        AlertDialog.Builder(this)
            .setTitle("Add Reference")
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> requestCameraPermission()
                    1 -> launchGallery()
                    2 -> dialog.dismiss()
                }
            }
            .show()
    }

    private fun requestCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                launchCamera()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun launchCamera() {
        val tmpFile = createTmpFile()
        latestTmpFilePath = tmpFile.absolutePath
        latestTmpUri = FileProvider.getUriForFile(this, "com.tadiwaprintbuddy.app.fileprovider", tmpFile)
        latestTmpUri?.let {
            takePictureLauncher.launch(it)
        }
    }

    private fun launchGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        pickImageLauncher.launch(intent)
    }

    private fun createTmpFile(): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir)
    }

    private fun copyImageToInternalStorage(uri: Uri) {
        lifecycleScope.launch {
            val tmpFile = withContext(Dispatchers.IO) {
                val inputStream: InputStream? = contentResolver.openInputStream(uri)
                val file = createTmpFile()
                val outputStream = FileOutputStream(file)
                inputStream?.copyTo(outputStream)
                inputStream?.close()
                outputStream.close()
                file
            }
            latestTmpFilePath = tmpFile.absolutePath
            showAddReferenceDialog(Uri.fromFile(tmpFile))
        }
    }

    private fun showAddReferenceDialog(imageUri: Uri) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_reference, null)
        val editTitle = dialogView.findViewById<EditText>(R.id.editTitle)
        val editNotes = dialogView.findViewById<EditText>(R.id.editNotes)

        AlertDialog.Builder(this)
            .setTitle("Add Reference")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val title = editTitle.text.toString()
                val notes = editNotes.text.toString()

                if (title.isNotBlank()) {
                    lifecycleScope.launch {
                        val reference = PrinterReference(
                            title = title,
                            notes = notes,
                            imagePath = latestTmpFilePath!!,
                            timestamp = System.currentTimeMillis()
                        )
                        repository.addPrinterReference(reference)
                        loadReferences()
                    }
                } else {
                    Toast.makeText(this, "Title cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadReferences() {
        lifecycleScope.launch {
            references = repository.getAllPrinterReferences()
            adapter.updateReferences(references)
        }
    }
}
