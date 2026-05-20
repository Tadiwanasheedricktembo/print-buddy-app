package com.tadiwaprintbuddy.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tadiwaprintbuddy.app.databinding.ActivitySecurityBinding

class SecurityActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySecurityBinding
    private lateinit var securityManager: SecurityManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySecurityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        securityManager = SecurityManager.getInstance(this)

        binding.btnAuthenticate.setOnClickListener {
            showBiometricPrompt()
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishAffinity()
            }
        })

        showBiometricPrompt()
    }

    private fun showBiometricPrompt() {
        securityManager.authenticate(
            activity = this,
            title = getString(R.string.app_locked),
            subtitle = getString(R.string.authenticate_to_continue),
            onSuccess = {
                finish()
            },
            onError = { error ->
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            }
        )
    }
}
