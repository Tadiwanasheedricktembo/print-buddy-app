package com.tadiwaprintbuddy.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tadiwaprintbuddy.app.api.AuthResponse
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

    fun saveAuth(response: AuthResponse) {
        prefs.edit().putString("access_token", response.access_token).apply()
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

    suspend fun login(username: String, password: String): Result<AuthResponse> {
        return try {
            val response = api.login(username, password)
            if (response.isSuccessful && response.body() != null) {
                val auth = response.body()!!
                saveAuth(auth)
                Result.success(auth)
            } else {
                Result.failure(Exception("Login failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
