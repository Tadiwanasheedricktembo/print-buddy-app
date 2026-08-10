package com.tadiwaprintbuddy.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tadiwaprintbuddy.app.databinding.ActivitySecurityBinding

class SecurityActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySecurityBinding
    private lateinit var securityManager: SecurityManager
    private var isAuthenticating = false
    private var enteredPin = ""
    private lateinit var dotViews: List<ImageView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        securityManager = SecurityManager.getInstance(this)
        securityManager.updateWindowSecurity(this)
        
        binding = ActivitySecurityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Support up to 6 dots if needed, but requirements say 4-6.
        // We'll dynamically show dots based on the expected length if known, or just show 6.
        dotViews = listOf(
            binding.dot1, binding.dot2, binding.dot3, 
            binding.dot4, binding.dot5, binding.dot6
        )
        
        setupUI()
        setupNumericPad()
        
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishAffinity()
            }
        })
    }

    private fun setupUI() {
        val expectedLength = if (securityManager.hasPin()) securityManager.getPinLength() else 4
        
        // Adjust dot visibility
        dotViews.forEachIndexed { index, imageView ->
            imageView.visibility = if (index < expectedLength) View.VISIBLE else View.GONE
        }

        binding.btnBiometric.setOnClickListener {
            showBiometricPrompt()
        }
        
        // Show biometric if enabled and available
        val canAuth = securityManager.canAuthenticate()
        binding.btnBiometric.visibility = if (securityManager.isBiometricEnabled && 
            canAuth == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) View.VISIBLE else View.INVISIBLE
    }

    private fun setupNumericPad() {
        val buttons = listOf(
            binding.btn1, binding.btn2, binding.btn3,
            binding.btn4, binding.btn5, binding.btn6,
            binding.btn7, binding.btn8, binding.btn9,
            binding.btn0
        )

        val expectedLength = if (securityManager.hasPin()) securityManager.getPinLength() else 4

        buttons.forEach { btn ->
            btn.setOnClickListener {
                if (enteredPin.length < expectedLength) {
                    enteredPin += btn.text
                    updatePinDots()
                    if (enteredPin.length == expectedLength) {
                        verifyPin()
                    }
                }
            }
        }

        binding.btnDelete.setOnClickListener {
            if (enteredPin.isNotEmpty()) {
                enteredPin = enteredPin.dropLast(1)
                updatePinDots()
            }
        }
    }

    private fun updatePinDots() {
        dotViews.forEachIndexed { index, imageView ->
            if (index < enteredPin.length) {
                imageView.setImageResource(R.drawable.ic_pin_dot_filled)
                imageView.imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.brand_primary)
                )
            } else {
                imageView.setImageResource(R.drawable.ic_pin_dot_empty)
                imageView.imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.text_disabled)
                )
            }
        }
    }

    private fun verifyPin() {
        if (securityManager.verifyPin(enteredPin)) {
            securityManager.onAuthSuccess()
            securityManager.updateWindowSecurity(this)
            finish()
        } else {
            Toast.makeText(this, getString(R.string.incorrect_pin), Toast.LENGTH_SHORT).show()
            enteredPin = ""
            updatePinDots()
            // Vibrate or animate if needed
            binding.layoutPinDots.startAnimation(
                android.view.animation.AnimationUtils.loadAnimation(this, R.anim.shake)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (securityManager.isBiometricEnabled && !isAuthenticating) {
            showBiometricPrompt()
        }
    }

    override fun onPause() {
        super.onPause()
        // Reset state to allow prompt again if resumed
        isAuthenticating = false
    }

    private fun showBiometricPrompt() {
        val canAuth = securityManager.canAuthenticate()
        if (canAuth != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
            return
        }

        isAuthenticating = true
        securityManager.authenticate(
            activity = this,
            title = getString(R.string.app_locked),
            subtitle = getString(R.string.authenticate_to_continue),
            onSuccess = {
                isAuthenticating = false
                securityManager.updateWindowSecurity(this)
                finish()
            },
            onError = { error ->
                isAuthenticating = false
                // Only toast for actual errors, not cancellation
                if (error.isNotEmpty()) {
                    Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}
