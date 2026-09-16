package com.tadiwaprintbuddy.app.api

import retrofit2.Response
import retrofit2.http.*

interface TadiwaApi {
    @POST("api/v1/auth/register")
    suspend fun register(@Body request: AuthRequest): Response<UserResponse>

    @FormUrlEncoded
    @POST("api/v1/auth/login")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String
    ): Response<AuthResponse>

    @GET("api/v1/auth/me")
    suspend fun getMe(@Header("Authorization") token: String): Response<UserResponse>

    @POST("api/v1/sync/push")
    suspend fun pushSync(
        @Header("Authorization") token: String,
        @Body request: PushRequest
    ): Response<PushResponse>

    @POST("api/v1/sync/pull")
    suspend fun pullSync(
        @Header("Authorization") token: String,
        @Body request: PullRequest
    ): Response<PullResponse>
}
