package com.tadiwaprintbuddy.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tadiwaprintbuddy.app.TestApplication
import com.tadiwaprintbuddy.app.api.TadiwaApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class)
class AuthRepositoryTest {
    private lateinit var repository: AuthRepository
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("auth_repository_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        repository = AuthRepository.getTestInstance(context, prefs)
        server = MockWebServer()
        server.start()

        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val apiField = repository.javaClass.getDeclaredField("api").apply { isAccessible = true }
        apiField.set(repository, retrofit.create(TadiwaApi::class.java))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `registers then logs in and binds the local database to that account`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"username":"shop-owner","business_name":"Print Shop"}"""
        ))
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"access_token":"session-token","token_type":"bearer"}"""
        ))

        val result = repository.register("shop-owner", "password", "Print Shop")

        assertTrue(result.isSuccess)
        assertEquals("session-token", repository.getToken())
        assertTrue(repository.isBoundToAccount("shop-owner"))

        val registration = server.takeRequest()
        assertEquals("/api/v1/auth/register", registration.path)
        assertTrue(registration.body.readUtf8().contains("\"business_name\":\"Print Shop\""))
        val login = server.takeRequest()
        assertEquals("/api/v1/auth/login", login.path)
        assertTrue(login.body.readUtf8().contains("username=shop-owner"))
    }

    @Test
    fun `legacy token is resolved before allowing a different account to log in`() = runTest {
        repository.saveAuth(com.tadiwaprintbuddy.app.api.AuthResponse("legacy-token", "bearer"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"username":"original-owner","business_name":"Print Shop"}"""
        ))

        val result = repository.login("another-owner", "password")

        assertTrue(result.isFailure)
        assertTrue(repository.isBoundToAccount("original-owner"))
        assertEquals(1, server.requestCount)
        assertEquals("/api/v1/auth/me", server.takeRequest().path)
    }
}
