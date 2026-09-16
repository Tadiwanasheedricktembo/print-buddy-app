package com.tadiwaprintbuddy.app.data

enum class SyncStatus {
    LOCAL_ONLY,
    PENDING_UPLOAD,
    SYNCING,
    SYNCED,
    FAILED,
    CONFLICT
}
