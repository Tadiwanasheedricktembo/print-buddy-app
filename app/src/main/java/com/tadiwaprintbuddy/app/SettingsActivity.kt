package com.tadiwaprintbuddy.app

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var themeManager: ThemeManager
    private lateinit var securityManager: SecurityManager
    private lateinit var rateManager: PrintRateManager

    private val backupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let { performBackup(it) }
    }

    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { performRestore(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        themeManager = ThemeManager(this)
        securityManager = SecurityManager.getInstance(this)
        rateManager = PrintRateManager(this)

        setupUI()
        loadSettings()
    }

    private fun setupUI() {
        // Theme Selection
        binding.toggleGroupTheme.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val theme = when (checkedId) {
                    R.id.btnThemeLight -> ThemeManager.THEME_LIGHT
                    R.id.btnThemeDark -> ThemeManager.THEME_DARK
                    else -> ThemeManager.THEME_SYSTEM
                }
                themeManager.selectedTheme = theme
            }
        }

        binding.switchEnableAppLock.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked && !securityManager.isLockEnabled) {
                // Enabling: Set up PIN first
                showPinSetupDialog(
                    onSuccess = {
                        securityManager.isLockEnabled = true
                        loadSettings()
                    },
                    onCancel = {
                        binding.switchEnableAppLock.isChecked = false
                    }
                )
            } else if (!isChecked && securityManager.isLockEnabled) {
                // Disabling: Require authentication
                showVerifyPinDialog(
                    title = "Disable App Lock",
                    onSuccess = {
                        securityManager.isLockEnabled = false
                        securityManager.isBiometricEnabled = false
                        loadSettings()
                    },
                    onCancel = {
                        binding.switchEnableAppLock.isChecked = true
                    }
                )
            }
        }

        binding.btnChangePin.setOnClickListener {
            showVerifyPinDialog(
                title = "Verify Current PIN",
                onSuccess = {
                    showPinSetupDialog(
                        isChange = true,
                        onSuccess = {
                            Toast.makeText(this, "PIN changed successfully", Toast.LENGTH_SHORT).show()
                        },
                        onCancel = {}
                    )
                },
                onCancel = {}
            )
        }

        binding.switchEnableBiometrics.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!securityManager.isLockEnabled) {
                    Toast.makeText(this, "Enable App Lock (PIN) first", Toast.LENGTH_LONG).show()
                    binding.switchEnableBiometrics.isChecked = false
                    return@setOnCheckedChangeListener
                }
                val canAuth = securityManager.canAuthenticate()
                if (canAuth != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
                    Toast.makeText(this, getString(R.string.auth_error_unavailable), Toast.LENGTH_LONG).show()
                    binding.switchEnableBiometrics.isChecked = false
                } else {
                    securityManager.isBiometricEnabled = true
                }
            } else {
                securityManager.isBiometricEnabled = false
            }
        }

        // Timeout Spinner
        val timeoutOptions = arrayOf(
            getString(R.string.timeout_immediate),
            getString(R.string.timeout_1min),
            getString(R.string.timeout_5min),
            getString(R.string.timeout_15min)
        )
        val timeoutValues = arrayOf(0L, 60_000L, 300_000L, 900_000L)
        
        val timeoutAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, timeoutOptions)
        binding.spinnerTimeout.setAdapter(timeoutAdapter)
        binding.spinnerTimeout.setOnItemClickListener { _, _, position, _ ->
            securityManager.lockTimeout = timeoutValues[position]
        }

        binding.btnLockNow.setOnClickListener {
            securityManager.lockApp()
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_HOME)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        }

        binding.btnBackup.setOnClickListener {
            val fileName = "PrintBuddy_Backup_${System.currentTimeMillis()}.zip"
            backupLauncher.launch(fileName)
        }

        binding.btnRestore.setOnClickListener {
            restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }

        binding.btnSaveSettings.setOnClickListener {
            saveAllSettings()
        }
    }

    private fun loadSettings() {
        // Load Theme
        val currentTheme = themeManager.selectedTheme
        val buttonId = when (currentTheme) {
            ThemeManager.THEME_LIGHT -> R.id.btnThemeLight
            ThemeManager.THEME_DARK -> R.id.btnThemeDark
            else -> R.id.btnThemeSystem
        }
        binding.toggleGroupTheme.check(buttonId)

        binding.switchEnableAppLock.isChecked = securityManager.isLockEnabled
        binding.btnChangePin.visibility = if (securityManager.isLockEnabled) View.VISIBLE else View.GONE
        
        binding.switchEnableBiometrics.isEnabled = securityManager.isLockEnabled
        binding.switchEnableBiometrics.isChecked = securityManager.isBiometricEnabled
        
        // Timeout
        val timeoutValue = securityManager.lockTimeout
        val timeoutIndex = when (timeoutValue) {
            0L -> 0
            60_000L -> 1
            300_000L -> 2
            else -> 3
        }
        val options = arrayOf(
            getString(R.string.timeout_immediate),
            getString(R.string.timeout_1min),
            getString(R.string.timeout_5min),
            getString(R.string.timeout_15min)
        )
        binding.spinnerTimeout.setText(options[timeoutIndex], false)
        
        // Load Rates
        binding.editDefaultBwRate.setText(rateManager.bwRate.toString())
        binding.editDefaultColorRate.setText(rateManager.colorRate.toString())
    }

    private fun showPinSetupDialog(isChange: Boolean = false, onSuccess: () -> Unit, onCancel: () -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_pin_setup, null)
        val editPin = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editPin)
        val editConfirmPin = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editConfirmPin)
        val txtTitle = dialogView.findViewById<android.widget.TextView>(R.id.txtPinTitle)
        
        if (isChange) {
            txtTitle.text = getString(R.string.change_app_pin)
        }

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton("Save", null) // Overridden below
            .setNegativeButton("Cancel") { d, _ -> 
                onCancel()
                d.dismiss()
            }
            .create()
            .apply {
                show()
                getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val pin = editPin.text.toString()
                    val confirm = editConfirmPin.text.toString()
                    
                    if (pin.length < 4 || pin.length > 6) {
                        editPin.error = getString(R.string.pin_too_short)
                        return@setOnClickListener
                    }
                    if (pin != confirm) {
                        editConfirmPin.error = getString(R.string.pin_mismatch)
                        return@setOnClickListener
                    }
                    
                    securityManager.setPin(pin)
                    onSuccess()
                    dismiss()
                }
            }
    }

    private fun showVerifyPinDialog(title: String, onSuccess: () -> Unit, onCancel: () -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_pin_setup, null)
        val editPin = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editPin)
        val layoutConfirm = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.layoutConfirmPin)
        
        // Hide confirm field for verification
        layoutConfirm.visibility = View.GONE
        
        val txtTitle = dialogView.findViewById<android.widget.TextView>(R.id.txtPinTitle)
        txtTitle.text = title
        
        val txtDesc = dialogView.findViewById<android.widget.TextView>(R.id.txtPinDescription)
        txtDesc.text = "Enter your current App PIN to continue."

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton("Verify", null)
            .setNegativeButton("Cancel") { d, _ -> 
                onCancel()
                d.dismiss()
            }
            .create()
            .apply {
                show()
                getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val pin = editPin.text.toString()
                    if (securityManager.verifyPin(pin)) {
                        onSuccess()
                        dismiss()
                    } else {
                        editPin.error = getString(R.string.incorrect_pin)
                    }
                }
            }
    }

    private fun saveAllSettings() {
        securityManager.isBiometricEnabled = binding.switchEnableBiometrics.isChecked
        
        // Save Rates
        val bwRate = binding.editDefaultBwRate.text.toString().toDoubleOrNull() ?: PrintRateManager.DEFAULT_BW_RATE
        val colorRate = binding.editDefaultColorRate.text.toString().toDoubleOrNull() ?: PrintRateManager.DEFAULT_COLOR_RATE
        rateManager.bwRate = bwRate
        rateManager.colorRate = colorRate
        
        Toast.makeText(this, "Settings saved successfully", Toast.LENGTH_SHORT).show()
    }

    private fun performBackup(uri: Uri) {
        lifecycleScope.launch {
            val success = BackupUtils.createZipBackup(this@SettingsActivity, uri)
            if (success) {
                Toast.makeText(this@SettingsActivity, "Backup successful!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@SettingsActivity, "Backup failed!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun performRestore(uri: Uri) {
        lifecycleScope.launch {
            val success = BackupUtils.restoreZipBackup(this@SettingsActivity, uri)
            if (success) {
                Toast.makeText(this@SettingsActivity, "Restore successful! Restarting...", Toast.LENGTH_LONG).show()
                
                // Give the toast a moment to show, then kill the process to force a clean reload
                kotlinx.coroutines.delay(1500)
                
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                val mainIntent = Intent.makeRestartActivityTask(intent?.component)
                startActivity(mainIntent)
                Runtime.getRuntime().exit(0)
            } else {
                Toast.makeText(this@SettingsActivity, "Restore failed! Ensure you selected a valid .zip backup.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
