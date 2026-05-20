package com.tadiwaprintbuddy.app

import android.content.Context
import android.content.SharedPreferences
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.TimeUnit

class SecurityManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("security_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_BACKGROUND_TIME = "background_time"
        private const val KEY_IS_LOCKED = "is_locked"
        private val SESSION_TIMEOUT = TimeUnit.MINUTES.toMillis(5)
        
        @Volatile
        private var instance: SecurityManager? = null

        fun getInstance(context: Context): SecurityManager {
            return instance ?: synchronized(this) {
                instance ?: SecurityManager(context.applicationContext).also { instance = it }
            }
        }
    }

    var isBiometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, value).apply()

    private var backgroundTime: Long
        get() = prefs.getLong(KEY_BACKGROUND_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_BACKGROUND_TIME, value).apply()

    private var isLocked: Boolean
        get() = prefs.getBoolean(KEY_IS_LOCKED, true)
        set(value) = prefs.edit().putBoolean(KEY_IS_LOCKED, value).apply()

    fun isSessionValid(): Boolean {
        if (!isBiometricEnabled) return true
        if (isLocked) return false
        val currentTime = System.currentTimeMillis()
        val bgTime = backgroundTime
        if (bgTime == 0L) return true
        return (currentTime - bgTime) < SESSION_TIMEOUT
    }

    fun onAppBackgrounded() {
        backgroundTime = System.currentTimeMillis()
    }

    fun onAuthSuccess() {
        isLocked = false
        backgroundTime = 0L
    }

    fun lockApp() {
        isLocked = true
    }

    fun canAuthenticate(): Int {
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onError(errString.toString())
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onAuthSuccess()
                    onSuccess()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }
}
