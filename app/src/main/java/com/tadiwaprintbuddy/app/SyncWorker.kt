package com.tadiwaprintbuddy.app

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tadiwaprintbuddy.app.data.DebugTags
import com.tadiwaprintbuddy.app.data.NetworkSyncRepository
import com.tadiwaprintbuddy.app.data.AuthenticationRequiredException

class SyncWorker(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Log.i(DebugTags.SYNC_PROCESS, "SyncWorker: Starting background synchronization cycle")
        
        val repository = NetworkSyncRepository.getInstance(applicationContext)
        
        return try {
            // 1. Initial ID backfill if needed
            SyncManager.getInstance(applicationContext).initializeSyncIds()
            
            // 2. Push local changes
            val pushResult = repository.pushPendingChanges()
            if (pushResult.isFailure) {
                Log.e(DebugTags.SYNC_PROCESS, "SyncWorker: Push failed", pushResult.exceptionOrNull())
                if (pushResult.exceptionOrNull() is AuthenticationRequiredException) {
                    return Result.failure()
                }
            }
            
            // 3. Pull cloud changes
            val pullResult = repository.pullChanges()
            if (pullResult.isFailure) {
                Log.e(DebugTags.SYNC_PROCESS, "SyncWorker: Pull failed", pullResult.exceptionOrNull())
                if (pullResult.exceptionOrNull() is AuthenticationRequiredException) {
                    return Result.failure()
                }
            }
            
            if (pushResult.isFailure || pullResult.isFailure) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (e: Exception) {
            Log.e(DebugTags.SYNC_PROCESS, "SyncWorker: Cycle failed", e)
            Result.retry()
        }
    }
}
