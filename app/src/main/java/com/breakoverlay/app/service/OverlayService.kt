package com.breakoverlay.app.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.breakoverlay.app.R
import com.breakoverlay.app.apps.AppListRepository
import com.breakoverlay.app.data.AppPreferences
import com.breakoverlay.app.data.PreferencesRepository
import com.breakoverlay.app.domain.DurationFormatter
import com.breakoverlay.app.domain.LimitAlertPolicy
import com.breakoverlay.app.domain.LocalDay
import com.breakoverlay.app.domain.MonitoringReadiness
import com.breakoverlay.app.notifications.NotificationHelper
import com.breakoverlay.app.overlay.BreakOverlayManager
import com.breakoverlay.app.overlay.TestOverlayState
import com.breakoverlay.app.scheduler.BreakScheduleRecovery
import com.breakoverlay.app.scheduler.BreakScheduler
import com.breakoverlay.app.scheduler.BreakSchedulerTick
import com.breakoverlay.app.usage.UsageStatsRepository
import com.breakoverlay.app.util.BootUtils
import com.breakoverlay.app.util.PermissionUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground monitoring service. It owns exactly one scheduler and one monitor loop.
 *
 * Android may defer foreground-service work under Doze, App Standby, vendor task managers
 * and battery restrictions. Timing is timestamp-based and recovery is best-effort; exact
 * real-time reminders are not guaranteed by the platform.
 */
class OverlayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)
    private val schedulerLock = Any()

    private lateinit var preferencesRepository: PreferencesRepository
    private lateinit var usageStatsRepository: UsageStatsRepository
    private lateinit var appListRepository: AppListRepository
    private lateinit var notificationHelper: NotificationHelper

    @Volatile
    private var latestPreferences: AppPreferences? = null

    private var preferencesJob: Job? = null
    private var monitorJob: Job? = null
    private var scheduler: BreakScheduler? = null
    private var bootCount = 0

    private var receiverRegistered = false
    private var foregroundStarted = false
    private var lastConfiguredInterval = -1
    private var lastConfiguredSnooze = -1

    private var lastSchedulePersistElapsed = 0L
    private var lastPersistedSessionActive = false
    private var lastNotificationUpdateElapsed = 0L
    private var lastNotificationSignature = ""
    private var lastLimitCheckWall = 0L
    private var lastOverlayAttemptElapsed = 0L
    private var lastBlockedNotificationElapsed = -1L
    private var lastOverlayError: String? = null

    @Volatile
    private var overlayShowing = false

    @Volatile
    private var overlayManager: BreakOverlayManager? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            wakeSignal.trySend(Unit)
        }
    }

    private val snoozeListener = object : BreakOverlayManager.SnoozeListener {
        override fun onSnooze(testOverlay: Boolean) {
            if (testOverlay) return
            overlayManager = null
            overlayShowing = false
            serviceScope.launch {
                val nowWall = System.currentTimeMillis()
                synchronized(schedulerLock) {
                    scheduler?.onSnooze(nowWall)
                }
                persistSchedule(force = true)
                wakeSignal.trySend(Unit)
                updateServiceNotification(force = true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationHelper = NotificationHelper(this)
        notificationHelper.createChannels()
        if (!enterForeground()) {
            stopSelf()
            return
        }

        preferencesRepository = PreferencesRepository(this)
        usageStatsRepository = UsageStatsRepository(this)
        appListRepository = AppListRepository(this)
        bootCount = BootUtils.currentBootCount(this)

        registerScreenReceiver()
        observePreferences()
        serviceScope.launch {
            TestOverlayState.active.collect { previewActive ->
                // Reset on both edges. On entry, preview display time must never be charged
                // as monitored-app use. On exit, this discards any interval that might have
                // elapsed before the monitor loop observed the preview state.
                synchronized(schedulerLock) {
                    scheduler?.resetAccrualBoundary()
                }
                if (previewActive) wakeSignal.trySend(Unit)
            }
        }
        monitorJob = serviceScope.launch { monitorLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ServiceController.ACTION_REFRESH) {
            wakeSignal.trySend(Unit)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Monitoring is user-controlled; removing the dashboard task does not disable it.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        preferencesJob?.cancel()

        if (latestPreferences?.monitoringEnabled == true) {
            persistScheduleBlockingForDestroy()
        }

        if (receiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (_: IllegalArgumentException) {
                // Already unregistered during teardown.
            }
            receiverRegistered = false
        }

        try {
            overlayManager?.dismiss()
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not remove overlay during service teardown", error)
        }
        overlayManager = null
        overlayShowing = false

        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            notificationHelper.cancelServiceNotification()
            foregroundStarted = false
        }

        MonitoringStatusBus.markStopped(latestPreferences?.monitoringEnabled ?: false)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun enterForeground(): Boolean {
        return try {
            val notification = notificationHelper.buildServiceNotification(
                getString(R.string.service_notification_title),
                getString(R.string.service_notification_starting),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NotificationHelper.SERVICE_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(
                    NotificationHelper.SERVICE_NOTIFICATION_ID,
                    notification,
                )
            }
            foregroundStarted = true
            true
        } catch (security: SecurityException) {
            Log.e(TAG, "Foreground-service permission is missing", security)
            false
        } catch (error: RuntimeException) {
            Log.e(TAG, "Foreground service could not enter the foreground", error)
            false
        }
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            ContextCompat.registerReceiver(
                this,
                screenReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        } catch (error: RuntimeException) {
            // Polling still detects screen state; the receiver is an optimization.
            Log.w(TAG, "Screen-state receiver could not be registered", error)
        }
    }

    private fun observePreferences() {
        preferencesJob = serviceScope.launch {
            try {
                preferencesRepository.preferences.collect { prefs ->
                    latestPreferences = prefs
                    if (!prefs.monitoringEnabled) {
                        stopSelf()
                        return@collect
                    }
                    wakeSignal.trySend(Unit)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RuntimeException) {
                Log.e(TAG, "Preference observer failed", error)
            }
        }
    }

    private suspend fun monitorLoop() {
        var schedulerInitialized = false
        MonitoringStatusBus.update {
            it.copy(
                serviceRunning = true,
                monitoringEnabled = latestPreferences?.monitoringEnabled ?: false,
                lastUpdatedWallMs = System.currentTimeMillis(),
            )
        }

        while (currentCoroutineContext().isActive) {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val prefs = latestPreferences
                    ?: preferencesRepository.readPreferences().also { latestPreferences = it }

                if (!prefs.monitoringEnabled) {
                    preferencesRepository.clearBreakSchedule()
                    stopSelf()
                    return
                }

                if (!schedulerInitialized) {
                    initializeScheduler(prefs)
                    schedulerInitialized = true
                }
                configureScheduler(prefs)

                val nowWall = System.currentTimeMillis()
                val nowElapsed = SystemClock.elapsedRealtime()
                val actualScreenOn = PermissionUtils.isScreenOn(this)
                val deviceLocked = PermissionUtils.isDeviceLocked(this)
                val usableScreen = actualScreenOn && !deviceLocked
                val usageAccess = PermissionUtils.hasUsageAccess(this)
                val overlayAccess = PermissionUtils.canDrawOverlays(this)
                val notificationsAvailable = PermissionUtils.effectivelyCanNotify(this)

                if (overlayShowing && !overlayAccess) {
                    cancelOverlayBecausePermissionWasRevoked()
                }

                if (overlayAccess && lastOverlayError == getString(R.string.service_error_overlay_permission)) {
                    // The permission was restored after a blocked attempt. Do not keep showing
                    // the old permission error while waiting for the next break.
                    lastOverlayError = null
                }

                if (TestOverlayState.active.value) {
                    // A diagnostic preview owns the screen. Freeze the scheduler instead of
                    // charging preview time, resetting the real session, or triggering a
                    // second overlay. The next real tick starts a fresh accrual boundary.
                    synchronized(schedulerLock) {
                        scheduler?.resetAccrualBoundary()
                    }
                    MonitoringStatusBus.update {
                        it.copy(
                            serviceRunning = true,
                            monitoringEnabled = prefs.monitoringEnabled,
                            activePackage = null,
                            isAccruing = false,
                            estimatedNextBreakWallMs = null,
                            overlayShowing = false,
                            screenOn = usableScreen,
                            usageAccessGranted = usageAccess,
                            overlayAccessGranted = overlayAccess,
                            notificationsAvailable = notificationsAvailable,
                            statusNote = getString(R.string.warning_overlay_preview_active),
                            lastUpdatedWallMs = nowWall,
                        )
                    }
                    delay(PREVIEW_POLL_MS)
                    continue
                }

                val foregroundPackage = if (usageAccess && usableScreen) {
                    usageStatsRepository.currentForegroundPackage(nowWall)
                } else {
                    null
                }
                val isEligible = usageAccess &&
                    usableScreen &&
                    foregroundPackage != null &&
                    !appListRepository.excludedFromBreakMonitoring(foregroundPackage) &&
                    prefs.isMonitored(foregroundPackage)

                val tick = synchronized(schedulerLock) {
                    scheduler?.tick(
                        nowElapsedMs = nowElapsed,
                        nowWallMs = nowWall,
                        screenOn = usableScreen,
                        eligibleForeground = isEligible,
                    )
                } ?: return

                if (tick.shouldTriggerBreak && !overlayShowing) {
                    maybeShowBreakOverlay(prefs, nowElapsed, nowWall)
                }

                persistScheduleIfNeeded(tick, nowElapsed, nowWall)

                val readiness = MonitoringReadiness.evaluate(
                    usageAccessGranted = usageAccess,
                    overlayGranted = overlayAccess,
                    notificationsGranted = notificationsAvailable,
                    batteryOptimizationIgnored = PermissionUtils.isIgnoringBatteryOptimizations(this),
                    monitoringEnabled = prefs.monitoringEnabled,
                )
                val note = buildStatusNote(
                    usageAccess = usageAccess,
                    overlayAccess = overlayAccess,
                    notificationsAvailable = notificationsAvailable,
                    readiness = readiness,
                ) ?: lastOverlayError

                MonitoringStatusBus.update {
                    it.copy(
                        serviceRunning = true,
                        monitoringEnabled = prefs.monitoringEnabled,
                        activePackage = if (isEligible) foregroundPackage else null,
                        sessionActive = tick.sessionActive,
                        isAccruing = tick.isAccruing,
                        continuousSessionMs = tick.continuousSessionMs,
                        remainingActiveMs = if (tick.sessionActive) {
                            tick.remainingActiveMs
                        } else {
                            null
                        },
                        estimatedNextBreakWallMs = if (tick.isAccruing) {
                            tick.estimatedDueAtWallMs
                        } else {
                            null
                        },
                        overlayShowing = overlayShowing,
                        screenOn = usableScreen,
                        usageAccessGranted = usageAccess,
                        overlayAccessGranted = overlayAccess,
                        notificationsAvailable = notificationsAvailable,
                        statusNote = note,
                        lastUpdatedWallMs = nowWall,
                    )
                }

                updateServiceNotification(
                    usageAccess = usageAccess,
                    overlayAccess = overlayAccess,
                    tick = tick,
                    force = false,
                )

                if (
                    usageAccess &&
                    usableScreen &&
                    nowWall - lastLimitCheckWall >= LIMIT_CHECK_INTERVAL_MS
                ) {
                    lastLimitCheckWall = nowWall
                    checkDailyLimits(prefs, nowWall)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RuntimeException) {
                Log.e(TAG, "Monitoring iteration failed", error)
                lastOverlayError = getString(R.string.service_error_generic)
                MonitoringStatusBus.update {
                    it.copy(
                        serviceRunning = true,
                        statusNote = getString(R.string.service_error_generic),
                        lastUpdatedWallMs = System.currentTimeMillis(),
                    )
                }
                delay(5_000L)
            }

            val screenOn = PermissionUtils.isScreenOn(this)
            val waitMs = if (screenOn) ACTIVE_POLL_MS else SCREEN_OFF_POLL_MS
            val elapsedThisLoop = SystemClock.elapsedRealtime() - startedAt
            val boundedWait = (waitMs - elapsedThisLoop).coerceAtLeast(50L)
            withTimeoutOrNull(boundedWait) {
                wakeSignal.receive()
            }
        }
    }

    private suspend fun initializeScheduler(prefs: AppPreferences) {
        if (scheduler != null) return
        bootCount = BootUtils.currentBootCount(this)
        val persisted = preferencesRepository.readBreakSchedule()
        val restored = BreakScheduleRecovery.restore(persisted, bootCount)
        val schedulerInstance = BreakScheduler(
            intervalMinutes = prefs.breakIntervalMinutes,
            snoozeMinutes = prefs.snoozeMinutes,
            initialAccumulatedActiveMs = restored.accumulatedActiveMs,
            initialSnoozeExtensionMs = restored.snoozeExtensionMs,
            initialLastDueAtWallMs = restored.lastDueAtWallMs,
            initialSessionActive = restored.sessionActive,
        )
        lastPersistedSessionActive = restored.sessionActive
        synchronized(schedulerLock) {
            scheduler = schedulerInstance
        }
        lastConfiguredInterval = prefs.breakIntervalMinutes
        lastConfiguredSnooze = prefs.snoozeMinutes
        if (restored.shouldClearPersistence) {
            preferencesRepository.clearBreakSchedule()
        }
    }

    private fun configureScheduler(prefs: AppPreferences) {
        if (
            lastConfiguredInterval == prefs.breakIntervalMinutes &&
            lastConfiguredSnooze == prefs.snoozeMinutes
        ) {
            return
        }
        synchronized(schedulerLock) {
            scheduler?.configure(prefs.breakIntervalMinutes, prefs.snoozeMinutes)
        }
        lastConfiguredInterval = prefs.breakIntervalMinutes
        lastConfiguredSnooze = prefs.snoozeMinutes
    }

    private suspend fun maybeShowBreakOverlay(
        prefs: AppPreferences,
        nowElapsed: Long,
        nowWall: Long,
    ) {
        if (!PermissionUtils.isScreenOn(this) || PermissionUtils.isDeviceLocked(this)) return
        if (!PermissionUtils.canDrawOverlays(this)) {
            notifyOverlayBlocked(nowElapsed)
            return
        }
        if (nowElapsed - lastOverlayAttemptElapsed < OVERLAY_RETRY_INTERVAL_MS) return
        lastOverlayAttemptElapsed = nowElapsed

        val result = withContext(Dispatchers.Main.immediate) {
            val manager = BreakOverlayManager(
                context = this@OverlayService,
                soundEnabled = prefs.soundEnabled,
                snoozeDurationMinutes = prefs.snoozeMinutes,
                listener = snoozeListener,
            )
            overlayManager = manager
            val showResult = manager.show(test = false)
            if (showResult != BreakOverlayManager.ShowResult.SHOWN) {
                if (overlayManager === manager) overlayManager = null
            }
            showResult
        }

        when (result) {
            BreakOverlayManager.ShowResult.SHOWN -> {
                val marked = synchronized(schedulerLock) {
                    scheduler?.markOverlayShown(nowWall) == true
                }
                if (!marked) {
                    withContext(Dispatchers.Main.immediate) {
                        overlayManager?.dismiss()
                    }
                    overlayManager = null
                    return
                }
                overlayShowing = true
                lastOverlayError = null
                persistSchedule(force = true)
                updateServiceNotification(force = true)
            }

            BreakOverlayManager.ShowResult.NO_OVERLAY_PERMISSION -> {
                notifyOverlayBlocked(nowElapsed)
            }

            BreakOverlayManager.ShowResult.ALREADY_VISIBLE -> {
                // A test overlay or a previous window owns the screen. Retry after it closes.
                lastOverlayError = null
            }

            BreakOverlayManager.ShowResult.WINDOW_ADD_FAILED -> {
                lastOverlayError = getString(R.string.service_error_overlay_failed)
                Log.e(TAG, "WindowManager rejected the break overlay")
            }
        }
    }

    private fun notifyOverlayBlocked(nowElapsed: Long) {
        if (
            lastBlockedNotificationElapsed < 0L ||
            nowElapsed - lastBlockedNotificationElapsed >= BLOCKED_NOTIFICATION_INTERVAL_MS
        ) {
            lastBlockedNotificationElapsed = nowElapsed
            notificationHelper.showOverlayPermissionNeeded()
        }
        lastOverlayError = getString(R.string.service_error_overlay_permission)
    }

    private suspend fun cancelOverlayBecausePermissionWasRevoked() {
        withContext(Dispatchers.Main.immediate) {
            overlayManager?.dismiss()
        }
        overlayManager = null
        overlayShowing = false
        synchronized(schedulerLock) {
            scheduler?.onOverlayCancelled()
        }
        lastOverlayError = getString(R.string.service_error_overlay_permission)
        persistSchedule(force = true)
        Log.w(TAG, "Overlay permission was revoked; active overlay was removed")
    }

    private suspend fun persistScheduleIfNeeded(
        tick: BreakSchedulerTick,
        nowElapsed: Long,
        nowWall: Long,
    ) {
        val sessionStateChanged = lastPersistedSessionActive != tick.sessionActive
        if (
            nowElapsed - lastSchedulePersistElapsed >= SCHEDULE_PERSIST_INTERVAL_MS ||
            sessionStateChanged
        ) {
            persistSchedule(
                force = false,
                nowElapsed = nowElapsed,
                nowWall = nowWall,
                sessionActive = tick.sessionActive,
            )
        }
    }

    private suspend fun persistSchedule(
        force: Boolean,
        nowElapsed: Long = SystemClock.elapsedRealtime(),
        nowWall: Long = System.currentTimeMillis(),
        sessionActive: Boolean? = null,
    ) {
        if (!force && nowElapsed - lastSchedulePersistElapsed < SCHEDULE_PERSIST_INTERVAL_MS) {
            return
        }
        val snapshot = synchronized(schedulerLock) {
            scheduler?.snapshot(bootCount, nowElapsed, nowWall)
        } ?: return
        try {
            preferencesRepository.saveBreakSchedule(snapshot)
            lastSchedulePersistElapsed = nowElapsed
            lastPersistedSessionActive = sessionActive ?: snapshot.wasActive
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not persist break schedule", error)
        }
    }

    private fun persistScheduleBlockingForDestroy() {
        val snapshot = synchronized(schedulerLock) {
            scheduler?.snapshot(
                bootCount,
                SystemClock.elapsedRealtime(),
                System.currentTimeMillis(),
            )
        } ?: return
        try {
            runBlocking(Dispatchers.IO) {
                withTimeout(1_500L) {
                    preferencesRepository.saveBreakSchedule(snapshot)
                }
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not persist schedule during service teardown", error)
        }
    }

    private suspend fun checkDailyLimits(prefs: AppPreferences, nowWall: Long) {
        val enabledLimits = prefs.dailyLimits.filterValues { it.enabled }
        if (enabledLimits.isEmpty()) return
        if (!PermissionUtils.effectivelyCanNotify(this)) return

        val usage = usageStatsRepository.loadTodayUsage()
        if (!usage.available) return
        val localDayKey = LocalDay.key(nowWall)
        val freshPrefs = preferencesRepository.readPreferences()
        for ((packageName, limit) in enabledLimits) {
            val usedMs = usage.reconstruction.packageUsageMs(packageName)
            if (
                LimitAlertPolicy.shouldAlert(
                    localDayKey = localDayKey,
                    packageName = packageName,
                    usedMs = usedMs,
                    limitMinutes = limit.minutes,
                    alertedKeys = freshPrefs.notifiedLimitKeys,
                )
            ) {
                val label = appListRepository.appLabel(packageName)
                val estimated = usage.reconstruction.isEstimated(packageName)
                val posted = notificationHelper.showScreenLimitReached(
                    packageName = packageName,
                    appLabel = label,
                    usedMs = usedMs,
                    limitMinutes = limit.minutes,
                    estimated = estimated,
                )
                if (posted) {
                    preferencesRepository.markLimitAlerted(localDayKey, packageName)
                }
            }
        }
    }

    private fun buildStatusNote(
        usageAccess: Boolean,
        overlayAccess: Boolean,
        notificationsAvailable: Boolean,
        readiness: MonitoringReadiness,
    ): String? = when {
        !usageAccess -> getString(R.string.warning_usage_access_missing)
        !overlayAccess -> getString(R.string.warning_overlay_missing)
        !notificationsAvailable -> getString(R.string.warning_notifications_disabled)
        readiness.missingCritical.isNotEmpty() ->
            getString(R.string.warning_permissions_missing)
        else -> null
    }

    private fun updateServiceNotification(
        usageAccess: Boolean? = null,
        overlayAccess: Boolean? = null,
        tick: BreakSchedulerTick? = null,
        force: Boolean,
    ) {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (!force && nowElapsed - lastNotificationUpdateElapsed < NOTIFICATION_UPDATE_INTERVAL_MS) {
            return
        }

        val currentUsageAccess = usageAccess ?: PermissionUtils.hasUsageAccess(this)
        val currentOverlayAccess = overlayAccess ?: PermissionUtils.canDrawOverlays(this)
        val title: String
        val text: String
        when {
            !currentUsageAccess -> {
                title = getString(R.string.service_notification_permission_title)
                text = getString(R.string.warning_usage_access_missing)
            }

            !currentOverlayAccess -> {
                title = getString(R.string.service_notification_permission_title)
                text = getString(R.string.warning_overlay_missing)
            }

            overlayShowing -> {
                title = getString(R.string.service_notification_overlay_title)
                text = getString(R.string.service_notification_overlay_active)
            }

            tick != null && tick.isAccruing && tick.sessionActive -> {
                title = getString(R.string.service_notification_title)
                text = getString(
                    R.string.service_notification_next_break,
                    DurationFormatter.countdown(tick.remainingActiveMs),
                )
            }

            tick != null && tick.sessionActive -> {
                title = getString(R.string.service_notification_title)
                text = getString(R.string.service_notification_paused)
            }

            else -> {
                title = getString(R.string.service_notification_title)
                text = getString(R.string.service_notification_waiting)
            }
        }

        val signature = "$title|$text"
        if (!force && signature == lastNotificationSignature) return
        lastNotificationSignature = signature
        lastNotificationUpdateElapsed = nowElapsed
        notificationHelper.updateServiceNotification(title, text)
    }

    companion object {
        private const val TAG = "OverlayService"
        private const val ACTIVE_POLL_MS = 5_000L
        private const val SCREEN_OFF_POLL_MS = 60_000L
        private const val PREVIEW_POLL_MS = 1_000L
        private const val SCHEDULE_PERSIST_INTERVAL_MS = 10_000L
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 15_000L
        private const val LIMIT_CHECK_INTERVAL_MS = 30_000L
        private const val OVERLAY_RETRY_INTERVAL_MS = 15_000L
        private const val BLOCKED_NOTIFICATION_INTERVAL_MS = 10L * 60_000L
    }
}
