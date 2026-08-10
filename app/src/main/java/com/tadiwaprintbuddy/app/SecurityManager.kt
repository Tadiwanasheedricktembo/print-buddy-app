package com.tadiwaprintbuddy.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class SecurityManager(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "secure_security_prefs",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        private const val KEY_LOCK_ENABLED = "app_lock_enabled"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PIN_LENGTH = "pin_length"
        private const val KEY_BACKGROUND_TIME = "background_time"
        private const val KEY_IS_LOCKED = "is_locked"
        private const val KEY_LOCK_TIMEOUT = "lock_timeout_ms"
        
        @Volatile
        private var instance: SecurityManager? = null

        fun getInstance(context: Context): SecurityManager {
            return instance ?: synchronized(this) {
                instance ?: SecurityManager(context.applicationContext).also { instance = it }
            }
        }
    }

    var isLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCK_ENABLED, value).apply()

    var isBiometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, value).apply()

    var lockTimeout: Long
        get() = prefs.getLong(KEY_LOCK_TIMEOUT, 0L)
        set(value) = prefs.edit().putLong(KEY_LOCK_TIMEOUT, value).apply()

    private var backgroundTime: Long
        get() = prefs.getLong(KEY_BACKGROUND_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_BACKGROUND_TIME, value).apply()

    private var isLocked: Boolean
        get() = prefs.getBoolean(KEY_IS_LOCKED, true)
        set(value) = prefs.edit().putBoolean(KEY_IS_LOCKED, value).apply()

    fun hasPin(): Boolean = prefs.contains(KEY_PIN_HASH)

    fun setPin(pin: String) {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        val hash = hashPin(pin, saltBase64)
        
        prefs.edit()
            .putString(KEY_PIN_HASH, hash)
            .putString(KEY_PIN_SALT, saltBase64)
            .putInt(KEY_PIN_LENGTH, pin.length)
            .apply()
    }

    fun getPinLength(): Int = prefs.getInt(KEY_PIN_LENGTH, 4)

    fun verifyPin(pin: String): Boolean {
        val storedHash = prefs.getString(KEY_PIN_HASH, null) ?: return false
        val storedSalt = prefs.getString(KEY_PIN_SALT, null) ?: return false
        return hashPin(pin, storedSalt) == storedHash
    }

    private fun hashPin(pin: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt.toByteArray())
        val bytes = md.digest(pin.toByteArray())
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun isSessionValid(): Boolean {
        if (!isLockEnabled) return true
        if (isLocked) return false
        
        val currentTime = System.currentTimeMillis()
        val bgTime = backgroundTime
        if (bgTime == 0L) return true // Changed from false to true to prevent locking immediately on first background if not yet set
        
        val timeout = lockTimeout
        if (timeout == 0L) return false // Immediate lock
        
        return (currentTime - bgTime) < timeout
    }

    fun onAppBackgrounded() {
        if (!isLocked) {
            backgroundTime = System.currentTimeMillis()
        }
    }

    fun onAuthSuccess() {
        isLocked = false
        backgroundTime = 0L
    }

    fun lockApp() {
        isLocked = true
        backgroundTime = 0L
    }

    fun updateWindowSecurity(activity: android.app.Activity) {
        if (isLockEnabled && !isSessionValid()) {
            activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    fun canAuthenticate(): Int {
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
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
                    if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON && errorCode != BiometricPrompt.ERROR_USER_CANCELED) {
                         onError(errString.toString())
                    }
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
            .setNegativeButtonText(context.getString(android.R.string.cancel))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }
}
