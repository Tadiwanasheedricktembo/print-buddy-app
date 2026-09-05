package com.tadiwaprintbuddy.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
        SecurityManager.getInstance(this).lockApp() // Guaranteed cold-start lock
        scheduleBackup()
        SyncManager.getInstance(this).scheduleSync()
        
        registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        registerScreenOffReceiver()

        if (BuildConfig.DEBUG) {
            runDatabaseIntegrityCheck()
        }
    }

    private fun registerScreenOffReceiver() {
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // Immediate lock on screen off for maximum security
                SecurityManager.getInstance(this@TadiwaPrintBuddyApp).lockApp()
            }
        }, filter)
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
        if (securityManager.isLockEnabled && !securityManager.isSessionValid()) {
            currentActivity?.let { activity ->
                if (activity !is SecurityActivity && !activity.isFinishing) {
                    val intent = Intent(activity, SecurityActivity::class.java)
                    // Use SINGLE_TOP and REORDER_TO_FRONT to avoid multiple instances 
                    // and prevent destroying the underlying activity stack.
                    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    activity.startActivity(intent)
                }
            }
        }
    }

    // ActivityLifecycleCallbacks
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        SecurityManager.getInstance(this).updateWindowSecurity(activity)
    }
    override fun onActivityStarted(activity: Activity) {
        currentActivity = activity
        checkSecurity()
    }
    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
        SecurityManager.getInstance(this).updateWindowSecurity(activity)
    }
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {
        if (currentActivity == activity) {
            // Don't null it out immediately, it might be an activity transition.
            // checkSecurity() needs a valid currentActivity to launch the lock screen.
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
