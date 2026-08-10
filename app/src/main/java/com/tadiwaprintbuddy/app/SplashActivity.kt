package com.tadiwaprintbuddy.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.tadiwaprintbuddy.app.databinding.ActivitySplashBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private var isAnimationFinished = false
    private var isNavigating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Make it edge-to-edge
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            insets
        }

        startAnimations()
    }

    override fun onResume() {
        super.onResume()
        // If animations finished while the lock screen was up, 
        // try to navigate now that we've returned.
        if (isAnimationFinished) {
            navigateToHome()
        }
    }

    private fun startAnimations() {
        lifecycleScope.launch {
            // Delay 100ms after mount
            delay(100)

            // Logo: fade in + scale 0.85 -> 1.0, 600ms
            binding.imgLogo.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(600)
                .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                .start()

            // Delay 400ms after logo starts
            delay(400)

            // Accent Line: fade in
            binding.accentLine.animate()
                .alpha(1f)
                .setDuration(400)
                .start()

            // App name: fade in, 400ms
            binding.textAppName.animate()
                .alpha(1f)
                .setDuration(400)
                .start()

            // Tagline: fade in
            delay(200)
            binding.textTagline.animate()
                .alpha(1f)
                .setDuration(400)
                .start()

            // Minimum duration check (1.5s total from start)
            delay(1000)

            isAnimationFinished = true
            navigateToHome()
        }
    }

    private fun navigateToHome() {
        if (isNavigating) return

        val securityManager = SecurityManager.getInstance(this)
        if (securityManager.isLockEnabled && !securityManager.isSessionValid()) {
            // Stay on splash or wait for security activity to handle it.
            // We don't want to start MainActivity in the background while locked.
            return
        }

        isNavigating = true
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, android.R.anim.fade_in, android.R.anim.fade_out)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }
}
