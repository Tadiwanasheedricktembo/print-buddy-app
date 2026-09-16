package com.tadiwaprintbuddy.app.data

import androidx.room.*

@Dao
interface DeferredSyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeferred(event: DeferredSync): Long

    @Query("SELECT * FROM deferred_sync ORDER BY createdAt ASC")
    suspend fun getAllDeferred(): List<DeferredSync>

    @Delete
    suspend fun deleteDeferred(event: DeferredSync): Int

    @Query("DELETE FROM deferred_sync WHERE entitySyncId = :syncId")
    suspend fun deleteBySyncId(syncId: String): Int
}
