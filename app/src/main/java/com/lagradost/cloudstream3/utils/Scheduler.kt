package com.lagradost.cloudstream3.utils

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.BackupAPI
import com.lagradost.cloudstream3.syncproviders.BackupAPI.Companion.logHistoryChanged
import com.lagradost.cloudstream3.ui.home.HOME_BOOKMARK_VALUE_LIST
import com.lagradost.cloudstream3.ui.player.PLAYBACK_SPEED_KEY
import com.lagradost.cloudstream3.ui.player.RESIZE_MODE_KEY
import com.lagradost.cloudstream3.utils.BackupUtils.nonTransferableKeys

class Scheduler<INPUT>(
    private val throttleTimeMs: Long,
    private val onWork: (INPUT?) -> Unit,
    private val beforeWork: ((INPUT?) -> Unit)? = null,
    private val canWork: ((INPUT?) -> Boolean)? = null
) {
    companion object {
        var SCHEDULER_ID = 1

        // these will not run upload scheduler, however only `nonTransferableKeys` are not stored
        private val invalidUploadTriggerKeys = listOf(
            *nonTransferableKeys.toTypedArray(),
            VideoDownloadManager.KEY_DOWNLOAD_INFO,
            DOWNLOAD_HEADER_CACHE,
            PLAYBACK_SPEED_KEY,
            HOME_BOOKMARK_VALUE_LIST,
            RESIZE_MODE_KEY
        )

        fun createBackupScheduler() = Scheduler<BackupAPI.PreferencesSchedulerData<*>>(
            BackupAPI.UPLOAD_THROTTLE.inWholeMilliseconds,
            onWork = { input ->
                if (input == null) {
                    throw IllegalStateException()
                }

                AccountManager.BackupApis.forEach {
                    it.addToQueue(
                        input.storeKey,
                        input.source == BackupUtils.RestoreSource.SETTINGS
                    )
                }
            },
            beforeWork = {
                AccountManager.BackupApis.filter {
                    it.isActive == true
                }.forEach {
                    it.willQueueSoon = true
                }
            },
            canWork = { input ->
                if (input == null) {
                    throw IllegalStateException()
                }

                val hasSomeActiveManagers = AccountManager.BackupApis.any { it.isActive == true }
                if (!hasSomeActiveManagers) {
                    return@Scheduler false
                }

                val hasInvalidKey = invalidUploadTriggerKeys.contains(input.storeKey)
                if (hasInvalidKey) {
                    return@Scheduler false
                }

                val valueDidNotChange = input.oldValue == input.newValue
                if (valueDidNotChange) {
                    return@Scheduler false
                }

                input.syncPrefs.logHistoryChanged(input.storeKey, input.source)
                return@Scheduler true
            }
        )

        // Common usage is `val settingsManager = PreferenceManager.getDefaultSharedPreferences(this).attachListener().self`
        // which means it is mostly used for settings preferences, therefore we use `isSettings: Boolean = true`, be careful
        // if you need to directly access `context.getSharedPreferences` (without using DataStore) and dont forget to turn it off
        fun SharedPreferences.attachBackupListener(
            source: BackupUtils.RestoreSource = BackupUtils.RestoreSource.SETTINGS,
            syncPrefs: SharedPreferences
        ): BackupAPI.SharedPreferencesWithListener {
            val scheduler = createBackupScheduler()

            var lastValue = all
            registerOnSharedPreferenceChangeListener { sharedPreferences, storeKey ->
                scheduler.work(
                    BackupAPI.PreferencesSchedulerData(
                        syncPrefs,
                        storeKey,
                        lastValue[storeKey],
                        sharedPreferences.all[storeKey],
                        source
                    )
                )
                lastValue = sharedPreferences.all
            }

            return BackupAPI.SharedPreferencesWithListener(this, scheduler)
        }

        fun SharedPreferences.attachBackupListener(syncPrefs: SharedPreferences): BackupAPI.SharedPreferencesWithListener {
            return attachBackupListener(BackupUtils.RestoreSource.SETTINGS, syncPrefs)
        }
    }

    private val id = SCHEDULER_ID++
    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null

    fun work(input: INPUT? = null): Boolean {
        if (canWork?.invoke(input) == false) {
            // Log.d(LOG_KEY, "[$id] cannot schedule [${input}]")
            return false
        }

        Log.d(BackupAPI.LOG_KEY, "[$id] wants to schedule [${input}]")
        beforeWork?.invoke(input)
        throttle(input)

        return true
    }

    fun workNow(input: INPUT? = null): Boolean {
        if (canWork?.invoke(input) == false) {
            Log.d(BackupAPI.LOG_KEY, "[$id] cannot run immediate [${input}]")
            return false
        }


        Log.d(BackupAPI.LOG_KEY, "[$id] runs immediate [${input}]")
        beforeWork?.invoke(input)
        stop()
        onWork(input)

        return true
    }

    fun stop() {
        runnable?.let {
            handler.removeCallbacks(it)
            runnable = null
        }
    }

    private fun throttle(input: INPUT?) {
        stop()

        runnable = Runnable {
            Log.d(BackupAPI.LOG_KEY, "[$id] schedule success")
            onWork(input)
        }
        handler.postDelayed(runnable!!, throttleTimeMs)
    }
}