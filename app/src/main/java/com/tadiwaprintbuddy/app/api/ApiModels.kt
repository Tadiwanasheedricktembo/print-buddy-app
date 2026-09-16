package com.tadiwaprintbuddy.app.api

import com.google.gson.annotations.SerializedName

data class AuthRequest(
    val username: String,
    val password: String,
    val business_name: String? = null
)

data class AuthResponse(
    val access_token: String,
    val token_type: String
)

data class UserResponse(
    val username: String,
    val business_name: String?
)

data class SyncEventRequest(
    @SerializedName("entity_type") val entityType: String,
    @SerializedName("entity_sync_id") val entitySyncId: String,
    @SerializedName("operation") val operation: String,
    @SerializedName("data") val data: Map<String, Any?>,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("idempotency_key") val idempotencyKey: String,
    @SerializedName("server_updated_at") val serverUpdatedAt: String? = null,
    @SerializedName("server_id") val serverId: Int? = null
)

data class PushRequest(
    val events: List<SyncEventRequest>
)

data class PushResult(
    @SerializedName("sync_id") val syncId: String,
    @SerializedName("status") val status: String,
    @SerializedName("message") val message: String?
)

data class PushResponse(
    val results: List<PushResult>
)

data class PullRequest(
    @SerializedName("last_sync_timestamp") val lastSyncTimestamp: String? = null,
    @SerializedName("last_sync_id") val lastSyncId: Int? = null,
    @SerializedName("batch_size") val batchSize: Int = 100
)

data class PullResponse(
    val events: List<SyncEventRequest>,
    @SerializedName("next_cursor") val nextCursor: String?,
    @SerializedName("next_cursor_id") val nextCursorId: Int?
)
