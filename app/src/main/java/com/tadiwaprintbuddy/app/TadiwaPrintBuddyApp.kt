package com.tadiwaprintbuddy.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.DebugTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class TadiwaPrintBuddyApp : Application(), Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver {
    
    private var currentActivity: Activity? = null

    override fun onCreate() {
        super<Application>.onCreate()
        ThemeManager(this).applyTheme()
        SecurityManager.getInstance(this).lockApp()
        scheduleBackup()
        
        registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        if (BuildConfig.DEBUG) {
            runDatabaseIntegrityCheck()
        }
    }

    private fun runDatabaseIntegrityCheck() {
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(this@TadiwaPrintBuddyApp)
                val integrityDao = db.integrityCheckDao()
                
                val duplicateCount = integrityDao.getDuplicateCustomerCount()
                if (duplicateCount > 0) {
                    Log.e(DebugTags.DATABASE_INTEGRITY, "⚠️  $duplicateCount duplicate customer sets detected!")
                    val duplicates = integrityDao.getDuplicateCustomers()
                    duplicates.forEach { 
                        Log.e(DebugTags.DATABASE_INTEGRITY, "  - ${it.customerName}: ${it.cnt} entries")
                    }
                }
                
                val orphanedCount = integrityDao.getOrphanedSettlementCount()
                if (orphanedCount > 0) {
                    Log.e(DebugTags.DATABASE_INTEGRITY, "⚠️  $orphanedCount orphaned settlement records detected!")
                }
                
                val mismatches = integrityDao.getBalanceMismatches()
                if (mismatches.isNotEmpty()) {
                    Log.e(DebugTags.DATABASE_INTEGRITY, "⚠️  ${mismatches.size} balance mismatches detected!")
                    mismatches.forEach {
                        Log.e(DebugTags.DATABASE_INTEGRITY, 
                            "  - ${it.customerName}: orders=₹${it.order_debt} vs settlement=₹${it.settlement_balance}")
                    }
                }

                if (duplicateCount == 0 && orphanedCount == 0 && mismatches.isEmpty()) {
                    Log.i(DebugTags.DATABASE_INTEGRITY, "✅ Database integrity check passed.")
                }
            } catch (e: Exception) {
                Log.e(DebugTags.DATABASE_INTEGRITY, "Failed to run integrity check", e)
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        checkSecurity()
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        SecurityManager.getInstance(this).onAppBackgrounded()
    }

    private fun checkSecurity() {
        val securityManager = SecurityManager.getInstance(this)
        if (securityManager.isBiometricEnabled && !securityManager.isSessionValid()) {
            currentActivity?.let { activity ->
                if (activity !is SecurityActivity) {
                    val intent = Intent(activity, SecurityActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
            }
        }
    }

    // ActivityLifecycleCallbacks
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {
        currentActivity = activity
    }
    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
    }
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {
        if (currentActivity == activity) {
            currentActivity = null
        }
    }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    private fun scheduleBackup() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(true)
            .build()

        val backupRequest = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "daily_backup",
            ExistingPeriodicWorkPolicy.KEEP,
            backupRequest
        )
    }
}
