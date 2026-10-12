package com.tadiwaprintbuddy.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tadiwaprintbuddy.app.api.AuthResponse
import com.tadiwaprintbuddy.app.api.AuthRequest
import com.tadiwaprintbuddy.app.api.UserResponse
import com.tadiwaprintbuddy.app.api.NetworkConfig
import com.tadiwaprintbuddy.app.api.TadiwaApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class AuthRepository private constructor(context: Context, private val testingPrefs: SharedPreferences? = null) {
    private val masterKey = if (testingPrefs == null) {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    } else null

    private val prefs = testingPrefs ?: EncryptedSharedPreferences.create(
        context,
        "auth_prefs",
        masterKey!!,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _isLoggedIn = MutableStateFlow(getToken() != null)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn

    private val api = Retrofit.Builder()
        .baseUrl(NetworkConfig.BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(TadiwaApi::class.java)

    companion object {
        private const val SYNC_ACCOUNT_USERNAME = "sync_account_username"

        @Volatile
        private var INSTANCE: AuthRepository? = null

        fun getInstance(context: Context): AuthRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AuthRepository(context).also { INSTANCE = it }
            }
        }

        fun getTestInstance(context: Context, prefs: SharedPreferences): AuthRepository {
            return AuthRepository(context, prefs)
        }
    }

    @Synchronized
    fun saveAuth(response: AuthResponse, username: String? = null) {
        val boundUsername = prefs.getString(SYNC_ACCOUNT_USERNAME, null)
        if (username != null && boundUsername != null && boundUsername != username) {
            throw IllegalStateException("This local database is already linked to another cloud account")
        }
        val editor = prefs.edit().putString("access_token", response.access_token)
        if (username != null) editor.putString(SYNC_ACCOUNT_USERNAME, username)
        editor.apply()
        _isLoggedIn.value = true
    }

    fun getToken(): String? {
        return prefs.getString("access_token", null)
    }

    fun getAuthHeader(): String? {
        val token = getToken() ?: return null
        return "Bearer $token"
    }

    fun logout() {
        prefs.edit().remove("access_token").apply()
        _isLoggedIn.value = false
    }

    fun isBoundToAccount(username: String): Boolean {
        val boundUsername = prefs.getString(SYNC_ACCOUNT_USERNAME, null)
        return boundUsername == null || boundUsername == username
    }

    suspend fun ensureAccountBinding(): Result<Unit> {
        val token = getToken() ?: return Result.success(Unit)
        if (prefs.getString(SYNC_ACCOUNT_USERNAME, null) != null) return Result.success(Unit)
        return try {
            val response = api.getMe("Bearer $token")
            if (response.code() == 401) {
                logout()
                return Result.failure(AuthenticationRequiredException("Cloud session expired; sign in again"))
            }
            if (!response.isSuccessful) {
                return Result.failure(Exception("Unable to verify the cloud account: HTTP ${response.code()}"))
            }
            val user: UserResponse = response.body()
                ?: return Result.failure(Exception("Cloud account response was empty"))
            prefs.edit().putString(SYNC_ACCOUNT_USERNAME, user.username).apply()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun register(username: String, password: String, businessName: String? = null): Result<AuthResponse> {
        val binding = ensureAccountBinding()
        if (binding.isFailure) return Result.failure(binding.exceptionOrNull()!!)
        if (!isBoundToAccount(username)) {
            return Result.failure(IllegalStateException("This local database is already linked to another cloud account"))
        }
        return try {
            val registration = api.register(AuthRequest(username, password, businessName))
            if (!registration.isSuccessful) {
                return Result.failure(Exception("Registration failed: ${registration.code()}"))
            }
            login(username, password)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun login(username: String, password: String): Result<AuthResponse> {
        val binding = ensureAccountBinding()
        if (binding.isFailure) return Result.failure(binding.exceptionOrNull()!!)
        if (!isBoundToAccount(username)) {
            return Result.failure(IllegalStateException("This local database is already linked to another cloud account"))
        }
        return try {
            val response = api.login(username, password)
            if (response.isSuccessful && response.body() != null) {
                val auth = response.body()!!
                saveAuth(auth, username)
                Result.success(auth)
            } else {
                Result.failure(Exception("Login failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

}
