package com.breakoverlay.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.breakoverlay.app.data.AppPreferences
import com.breakoverlay.app.data.PreferencesRepository
import com.breakoverlay.app.databinding.ActivityMainBinding
import com.breakoverlay.app.databinding.DialogAppLimitBinding
import com.breakoverlay.app.domain.AppSelectionPolicy
import com.breakoverlay.app.domain.DurationFormatter
import com.breakoverlay.app.domain.InstalledApp
import com.breakoverlay.app.domain.SettingRanges
import com.breakoverlay.app.notifications.NotificationHelper
import com.breakoverlay.app.overlay.BreakOverlayManager
import com.breakoverlay.app.overlay.TestOverlayState
import com.breakoverlay.app.service.MonitoringSnapshot
import com.breakoverlay.app.service.MonitoringStatusBus
import com.breakoverlay.app.service.ServiceController
import com.breakoverlay.app.ui.AppListAdapter
import com.breakoverlay.app.ui.AppRowModel
import com.breakoverlay.app.usage.DailyUsageSnapshot
import com.breakoverlay.app.usage.UsageStatsRepository
import com.breakoverlay.app.util.PermissionUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferencesRepository: PreferencesRepository
    private lateinit var usageStatsRepository: UsageStatsRepository
    private lateinit var appListRepository: com.breakoverlay.app.apps.AppListRepository
    private lateinit var appListAdapter: AppListAdapter

    private val selectedPackages = linkedSetOf<String>()
    private val allApps = mutableListOf<InstalledApp>()
    private val selectionMutex = Mutex()

    private var currentPreferences = AppPreferences()
    private var usageSnapshot: DailyUsageSnapshot? = null
    private var searchQuery = ""
    private var appsLoaded = false
    private var breakSettingsDirty = false
    private var applyingPreferences = false
    private var suppressMonitoringSwitch = false
    private var preferencesLoaded = false

    private var serviceRunning = false
    private var lastMonitoringSnapshot = MonitoringSnapshot()
    private var refreshJob: Job? = null
    private var previewOverlay: BreakOverlayManager? = null

    private var hasUsageAccess = false
    private var hasOverlayAccess = false
    private var notificationsAvailable = false
    private var batteryUnrestricted = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        refreshPermissionUi()
        if (!granted) {
            showSnackbar(getString(R.string.notifications_denied_message))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureSystemBars()

        preferencesRepository = PreferencesRepository(this)
        usageStatsRepository = UsageStatsRepository(this)
        appListRepository = com.breakoverlay.app.apps.AppListRepository(this)
        NotificationHelper(this).createChannels()

        setupRecyclerView()
        setupListeners()
        observePreferences()
        observeMonitoringStatus()

        applyingPreferences = true
        binding.scopeAllButton.isChecked = true
        applyingPreferences = false
        applyPreferences(currentPreferences)
        binding.footerVersionText.text = getString(
            R.string.footer_version_and_note,
            BuildConfig.VERSION_NAME,
            getString(R.string.footer_note),
        )
        refreshPermissionUi()
        renderAppList()
    }

    private fun configureSystemBars() {
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isLightTheme = nightMode != Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = isLightTheme
            isAppearanceLightNavigationBars = isLightTheme
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && isLightTheme) {
            // Pre-API 26 has no light navigation-bar icons; use a dark surface for contrast.
            window.navigationBarColor = ContextCompat.getColor(this, R.color.brand_primary_dark)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.decorView.setForceDarkAllowed(false)
        }
    }

    override fun onStart() {
        super.onStart()
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            loadApps()
            while (isActive) {
                refreshUsage()
                delay(USAGE_REFRESH_INTERVAL_MS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionUi()
        lifecycleScope.launch {
            try {
                val preferences = preferencesRepository.readPreferences()
                preferencesLoaded = true
                applyPreferences(preferences)
                refreshServiceRunningState()
                if (serviceRunning) {
                    ServiceController.refresh(this@MainActivity)
                } else {
                    maybeRestartMonitoring()
                }
                renderMonitoringCard(lastMonitoringSnapshot)
            } catch (error: Exception) {
                showSnackbar(getString(R.string.monitoring_toggle_failed))
                refreshServiceRunningState()
                renderMonitoringCard(lastMonitoringSnapshot)
            }
        }
    }

    override fun onStop() {
        refreshJob?.cancel()
        refreshJob = null
        super.onStop()
    }

    override fun onDestroy() {
        previewOverlay?.dismiss()
        previewOverlay = null
        TestOverlayState.setActive(false)
        super.onDestroy()
    }

    private fun setupRecyclerView() {
        appListAdapter = AppListAdapter(
            object : AppListAdapter.Listener {
                override fun onSelectionChanged(packageName: String, selected: Boolean) {
                    persistAppSelection(packageName, selected)
                }

                override fun onOpenLimit(packageName: String) {
                    val app = allApps.firstOrNull { it.packageName == packageName }
                    if (app != null) showLimitDialog(app)
                }
            },
        )
        binding.appsRecycler.layoutManager = LinearLayoutManager(this)
        binding.appsRecycler.adapter = appListAdapter
        binding.appsRecycler.itemAnimator?.changeDuration = 150L
    }

    private fun setupListeners() {
        binding.monitoringSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressMonitoringSwitch) return@setOnCheckedChangeListener
            if (!preferencesLoaded) {
                setMonitoringSwitchSilently(currentPreferences.monitoringEnabled)
                return@setOnCheckedChangeListener
            }
            if (isChecked) {
                attemptEnableMonitoring()
            } else {
                attemptDisableMonitoring()
            }
        }

        binding.testOverlayButton.setOnClickListener {
            showTestOverlay()
        }

        binding.overlayPermissionButton.setOnClickListener {
            openOverlaySettings()
        }
        binding.usagePermissionButton.setOnClickListener {
            openUsageAccessSettings()
        }
        binding.batteryPermissionButton.setOnClickListener {
            openBatteryOptimizationSettings()
        }
        binding.notificationPermissionButton.setOnClickListener {
            requestNotificationPermissionOrOpenSettings()
        }

        binding.breakIntervalInput.doAfterTextChanged {
            if (applyingPreferences) return@doAfterTextChanged
            breakSettingsDirty = true
            binding.breakIntervalLayout.error = null
        }
        binding.snoozeMinutesInput.doAfterTextChanged {
            if (applyingPreferences) return@doAfterTextChanged
            breakSettingsDirty = true
            binding.snoozeMinutesLayout.error = null
        }
        binding.soundSwitch.setOnCheckedChangeListener { _, _ ->
            if (!applyingPreferences) breakSettingsDirty = true
        }
        binding.scopeToggle.addOnButtonCheckedListener { _, _, isChecked ->
            if (!applyingPreferences && isChecked) {
                breakSettingsDirty = true
                updateScopeHint()
            }
        }
        binding.saveBreakSettingsButton.setOnClickListener {
            saveBreakSettings()
        }

        binding.appSearchInput.doAfterTextChanged { editable ->
            searchQuery = editable?.toString().orEmpty()
            renderAppList()
        }
        binding.selectAllAppsButton.setOnClickListener {
            selectAllApps()
        }
        binding.clearSelectionButton.setOnClickListener {
            clearAppSelection()
        }
    }

    private fun observePreferences() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                preferencesRepository.preferences.collect { preferences ->
                    preferencesLoaded = true
                    applyPreferences(preferences)
                }
            }
        }
    }

    private fun observeMonitoringStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MonitoringStatusBus.state.collect { snapshot ->
                    val wasRunning = serviceRunning
                    lastMonitoringSnapshot = snapshot
                    if (snapshot.serviceRunning) {
                        serviceRunning = true
                    } else if (wasRunning) {
                        serviceRunning = ServiceController.isRunning(this@MainActivity)
                    }
                    renderMonitoringCard(snapshot)
                }
            }
        }
    }

    private fun applyPreferences(preferences: AppPreferences) {
        currentPreferences = preferences
        selectedPackages.clear()
        selectedPackages.addAll(preferences.selectedPackages)

        if (!breakSettingsDirty) {
            applyingPreferences = true
            val intervalText = preferences.breakIntervalMinutes.toString()
            if (binding.breakIntervalInput.text?.toString() != intervalText) {
                binding.breakIntervalInput.setText(intervalText)
            }
            val snoozeText = preferences.snoozeMinutes.toString()
            if (binding.snoozeMinutesInput.text?.toString() != snoozeText) {
                binding.snoozeMinutesInput.setText(snoozeText)
            }
            binding.soundSwitch.isChecked = preferences.soundEnabled
            binding.scopeToggle.check(
                if (preferences.monitorAllApps) {
                    binding.scopeAllButton.id
                } else {
                    binding.scopeSelectedButton.id
                },
            )
            applyingPreferences = false
        }

        setMonitoringSwitchSilently(preferences.monitoringEnabled)
        renderAppList()
        renderMonitoringCard(lastMonitoringSnapshot)
    }

    private suspend fun loadApps() {
        if (!appsLoaded) {
            binding.appsEmptyText.isVisible = true
            binding.appsEmptyText.text = getString(R.string.apps_loading)
        }
        val loaded = appListRepository.loadLaunchableApps()
        allApps.clear()
        allApps.addAll(loaded)
        appsLoaded = true
        renderAppList()
        renderMonitoringCard(lastMonitoringSnapshot)
    }

    private suspend fun refreshUsage() {
        val wasRunning = serviceRunning
        refreshServiceRunningState()
        if (wasRunning != serviceRunning) {
            renderMonitoringCard(lastMonitoringSnapshot)
        }
        val accessNow = PermissionUtils.hasUsageAccess(this)
        if (accessNow != hasUsageAccess) {
            refreshPermissionUi()
        }
        if (!accessNow) {
            usageSnapshot = null
            renderAppList()
            return
        }
        usageSnapshot = usageStatsRepository.loadTodayUsage()
        renderAppList()
    }

    private fun renderAppList() {
        val snapshot = usageSnapshot
        val usageAvailable = hasUsageAccess && snapshot?.available == true
        val unavailableTextRes = when {
            !hasUsageAccess -> R.string.app_usage_unavailable
            snapshot == null -> R.string.app_usage_loading
            !snapshot.available -> R.string.app_usage_error
            else -> R.string.app_usage_unavailable
        }
        val rows = AppSelectionPolicy.filterAndSort(
            items = allApps,
            selectedPackages = selectedPackages,
            query = searchQuery,
            packageOf = { it.packageName },
            labelOf = { it.label },
        ).map { app ->
            val usageMs = if (usageAvailable) {
                snapshot?.reconstruction?.packageUsageMs(app.packageName) ?: 0L
            } else {
                0L
            }
            AppRowModel(
                app = app,
                usageMs = usageMs,
                usageEstimated = usageAvailable &&
                    snapshot?.reconstruction?.isEstimated(app.packageName) == true,
                usageAvailable = usageAvailable,
                unavailableUsageTextRes = unavailableTextRes,
                limit = currentPreferences.dailyLimits[app.packageName],
                selected = app.packageName in selectedPackages,
            )
        }
        appListAdapter.submitList(rows)

        binding.appsEmptyText.text = when {
            !appsLoaded -> getString(R.string.apps_loading)
            allApps.isEmpty() -> getString(R.string.apps_none_found)
            rows.isEmpty() -> getString(R.string.apps_empty)
            else -> ""
        }
        binding.appsEmptyText.isVisible = !appsLoaded || rows.isEmpty()

        val selectedCount = selectedPackages.size
        binding.selectedAppsCountText.text = resources.getQuantityString(
            R.plurals.selected_apps_count,
            selectedCount,
            selectedCount,
        )
        updateScopeHint()
        updateUsageNote()
    }

    private fun updateScopeHint() {
        val allAppsSelected = binding.scopeAllButton.isChecked
        binding.scopeHintText.text = when {
            allAppsSelected -> getString(R.string.scope_all_description)
            selectedPackages.isEmpty() -> getString(R.string.scope_selected_empty_description)
            else -> resources.getQuantityString(
                R.plurals.scope_selected_description,
                selectedPackages.size,
                selectedPackages.size,
            )
        }
    }

    private fun updateUsageNote() {
        binding.appUsageNote.text = when {
            !hasUsageAccess -> getString(R.string.usage_estimate_note_permission)
            usageSnapshot?.available == false -> getString(R.string.usage_unavailable_note)
            else -> getString(R.string.usage_estimate_note)
        }
    }

    private fun saveBreakSettings() {
        val interval = SettingRanges.parsePositiveInt(
            binding.breakIntervalInput.text?.toString().orEmpty(),
        )
        val snooze = SettingRanges.parsePositiveInt(
            binding.snoozeMinutesInput.text?.toString().orEmpty(),
        )
        var valid = true
        if (!SettingRanges.isValidBreakInterval(interval)) {
            binding.breakIntervalLayout.error = getString(R.string.invalid_break_interval)
            binding.breakIntervalInput.requestFocus()
            valid = false
        } else {
            binding.breakIntervalLayout.error = null
        }
        if (!SettingRanges.isValidSnooze(snooze)) {
            binding.snoozeMinutesLayout.error = getString(R.string.invalid_snooze)
            if (valid) binding.snoozeMinutesInput.requestFocus()
            valid = false
        } else {
            binding.snoozeMinutesLayout.error = null
        }
        if (!valid || interval == null || snooze == null) return

        val monitorAllApps = binding.scopeAllButton.isChecked
        val soundEnabled = binding.soundSwitch.isChecked
        lifecycleScope.launch {
            try {
                preferencesRepository.saveBreakSettings(
                    intervalMinutes = interval,
                    snoozeMinutes = snooze,
                    monitorAllApps = monitorAllApps,
                    soundEnabled = soundEnabled,
                )
                breakSettingsDirty = false
                ServiceController.refresh(this@MainActivity)
                showSnackbar(getString(R.string.break_settings_saved))
            } catch (error: Exception) {
                showSnackbar(getString(R.string.break_settings_save_failed))
            }
        }
    }

    private fun attemptEnableMonitoring() {
        val missingUsage = !hasUsageAccess
        val missingOverlay = !hasOverlayAccess
        if (missingUsage || missingOverlay) {
            setMonitoringSwitchSilently(false)
            showPermissionRequiredDialog(missingUsage, missingOverlay)
            return
        }
        if (!currentPreferences.monitorAllApps && selectedPackages.isEmpty()) {
            setMonitoringSwitchSilently(false)
            showSelectAppsFirstDialog()
            return
        }

        val previous = currentPreferences
        currentPreferences = previous.copy(monitoringEnabled = true)
        serviceRunning = true
        setMonitoringSwitchSilently(true)
        renderMonitoringCard(lastMonitoringSnapshot)

        lifecycleScope.launch {
            try {
                preferencesRepository.clearBreakSchedule()
                preferencesRepository.setMonitoringEnabled(true)
                val outcome = ServiceController.start(this@MainActivity)
                if (!outcome.started) {
                    rollbackMonitoringEnable(previous)
                    showStartFailureDialog()
                    return@launch
                }
                showSnackbar(getString(R.string.monitoring_enabled_message))
                requestNotificationPermissionIfUseful()
            } catch (error: Exception) {
                try {
                    preferencesRepository.setMonitoringEnabled(false)
                } catch (_: Exception) {
                    // The UI below still explains that monitoring did not start.
                }
                currentPreferences = previous.copy(monitoringEnabled = false)
                serviceRunning = false
                setMonitoringSwitchSilently(false)
                renderMonitoringCard(lastMonitoringSnapshot)
                showSnackbar(getString(R.string.monitoring_toggle_failed))
            }
        }
    }

    private suspend fun rollbackMonitoringEnable(previous: AppPreferences) {
        try {
            preferencesRepository.setMonitoringEnabled(false)
        } catch (_: Exception) {
            // Startup already failed; keep the switch off and explain the failure.
        }
        currentPreferences = previous.copy(monitoringEnabled = false)
        serviceRunning = false
        setMonitoringSwitchSilently(false)
        renderMonitoringCard(lastMonitoringSnapshot)
    }

    private fun attemptDisableMonitoring() {
        val previous = currentPreferences
        currentPreferences = previous.copy(monitoringEnabled = false)
        serviceRunning = false
        setMonitoringSwitchSilently(false)
        renderMonitoringCard(lastMonitoringSnapshot)

        lifecycleScope.launch {
            try {
                preferencesRepository.setMonitoringEnabled(false)
            } catch (error: Exception) {
                currentPreferences = previous.copy(monitoringEnabled = true)
                setMonitoringSwitchSilently(true)
                refreshServiceRunningState()
                renderMonitoringCard(lastMonitoringSnapshot)
                showSnackbar(getString(R.string.monitoring_toggle_failed))
                return@launch
            }
            try {
                preferencesRepository.clearBreakSchedule()
            } catch (_: Exception) {
                // The service also clears the schedule when it observes monitoring disabled.
            }
            ServiceController.stop(this@MainActivity)
            MonitoringStatusBus.markStopped(monitoringEnabled = false)
            showSnackbar(getString(R.string.monitoring_disabled_message))
        }
    }

    private fun maybeRestartMonitoring() {
        if (!preferencesLoaded || serviceRunning) return
        if (!currentPreferences.monitoringEnabled) return
        if (!hasUsageAccess || !hasOverlayAccess) return
        if (!currentPreferences.monitorAllApps && selectedPackages.isEmpty()) return

        lifecycleScope.launch {
            val outcome = ServiceController.start(this@MainActivity)
            if (outcome.started) {
                serviceRunning = true
                renderMonitoringCard(lastMonitoringSnapshot)
            } else {
                serviceRunning = false
                renderMonitoringCard(lastMonitoringSnapshot)
            }
        }
    }

    private fun refreshServiceRunningState() {
        val actual = ServiceController.isRunning(this)
        serviceRunning = actual
        if (!actual && lastMonitoringSnapshot.serviceRunning) {
            MonitoringStatusBus.markStopped(currentPreferences.monitoringEnabled)
        }
    }

    private fun setMonitoringSwitchSilently(checked: Boolean) {
        suppressMonitoringSwitch = true
        binding.monitoringSwitch.isChecked = checked
        suppressMonitoringSwitch = false
    }

    private fun persistAppSelection(packageName: String, selected: Boolean) {
        if (selected) selectedPackages += packageName else selectedPackages -= packageName
        renderAppList()
        lifecycleScope.launch {
            try {
                selectionMutex.withLock {
                    preferencesRepository.setSelected(packageName, selected)
                }
            } catch (error: Exception) {
                showSnackbar(getString(R.string.app_selection_save_failed))
                reloadPreferences()
            }
        }
    }

    private fun selectAllApps() {
        if (allApps.isEmpty()) {
            showSnackbar(getString(R.string.apps_none_found))
            return
        }
        val packages = allApps.mapTo(linkedSetOf()) { it.packageName }
        selectedPackages.clear()
        selectedPackages.addAll(packages)
        renderAppList()
        lifecycleScope.launch {
            try {
                selectionMutex.withLock {
                    preferencesRepository.replaceSelectedPackages(packages)
                }
                showSnackbar(getString(R.string.apps_all_selected))
            } catch (error: Exception) {
                showSnackbar(getString(R.string.app_selection_save_failed))
                reloadPreferences()
            }
        }
    }

    private fun clearAppSelection() {
        selectedPackages.clear()
        renderAppList()
        lifecycleScope.launch {
            try {
                selectionMutex.withLock {
                    preferencesRepository.replaceSelectedPackages(emptySet())
                }
                showSnackbar(getString(R.string.apps_selection_cleared))
            } catch (error: Exception) {
                showSnackbar(getString(R.string.app_selection_save_failed))
                reloadPreferences()
            }
        }
    }

    private fun reloadPreferences() {
        lifecycleScope.launch {
            try {
                val preferences = preferencesRepository.readPreferences()
                preferencesLoaded = true
                applyPreferences(preferences)
            } catch (_: Exception) {
                // The previously displayed state remains; the snackbar already reported failure.
            }
        }
    }

    private fun showLimitDialog(app: InstalledApp) {
        val existing = currentPreferences.dailyLimits[app.packageName]
        val dialogBinding = DialogAppLimitBinding.inflate(layoutInflater)
        dialogBinding.dialogAppIcon.setImageDrawable(
            app.icon ?: ContextCompat.getDrawable(this, R.mipmap.ic_launcher),
        )
        dialogBinding.dialogAppLabel.text = app.label
        dialogBinding.dialogTodayUsage.text = todayUsageText(app.packageName)

        val initialEnabled = existing?.enabled ?: true
        dialogBinding.dialogLimitSwitch.isChecked = initialEnabled
        dialogBinding.dialogLimitMinutesInput.setText(
            (existing?.minutes ?: DEFAULT_LIMIT_MINUTES).toString(),
        )
        setLimitInputEnabled(dialogBinding, initialEnabled)
        dialogBinding.dialogLimitSwitch.setOnCheckedChangeListener { _, checked ->
            setLimitInputEnabled(dialogBinding, checked)
            if (checked && dialogBinding.dialogLimitMinutesInput.text.isNullOrBlank()) {
                dialogBinding.dialogLimitMinutesInput.setText(DEFAULT_LIMIT_MINUTES.toString())
            }
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_limit_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
        if (existing != null) {
            builder.setNeutralButton(R.string.action_remove_limit, null)
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                if (!dialogBinding.dialogLimitSwitch.isChecked) {
                    if (existing == null) {
                        dialog.dismiss()
                    } else {
                        dialog.dismiss()
                        persistDailyLimit(
                            packageName = app.packageName,
                            minutes = existing.minutes,
                            enabled = false,
                        )
                    }
                    return@setOnClickListener
                }
                val minutes = SettingRanges.parsePositiveInt(
                    dialogBinding.dialogLimitMinutesInput.text?.toString().orEmpty(),
                )
                if (!SettingRanges.isValidDailyLimit(minutes)) {
                    dialogBinding.dialogLimitMinutesLayout.error =
                        getString(R.string.dialog_limit_invalid)
                    return@setOnClickListener
                }
                dialogBinding.dialogLimitMinutesLayout.error = null
                dialog.dismiss()
                persistDailyLimit(
                    packageName = app.packageName,
                    minutes = minutes ?: DEFAULT_LIMIT_MINUTES,
                    enabled = true,
                )
            }
            if (existing != null) {
                dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                    dialog.dismiss()
                    removeDailyLimit(app.packageName)
                }
            }
        }
        dialog.show()
    }

    private fun setLimitInputEnabled(binding: DialogAppLimitBinding, enabled: Boolean) {
        binding.dialogLimitMinutesInput.isEnabled = enabled
        binding.dialogLimitMinutesLayout.isEnabled = enabled
        binding.dialogLimitMinutesLayout.alpha = if (enabled) 1f else 0.55f
    }

    private fun todayUsageText(packageName: String): String {
        if (!hasUsageAccess) {
            return getString(R.string.dialog_today_usage_unavailable)
        }
        val snapshot = usageSnapshot
        if (snapshot == null || !snapshot.available) {
            return getString(R.string.app_usage_error)
        }
        val used = snapshot.reconstruction.packageUsageMs(packageName)
        return if (snapshot.reconstruction.isEstimated(packageName)) {
            getString(
                R.string.app_usage_today_estimated_caption,
                DurationFormatter.compact(used),
            )
        } else {
            getString(
                R.string.app_usage_today_caption,
                DurationFormatter.compact(used),
            )
        }
    }

    private fun persistDailyLimit(packageName: String, minutes: Int, enabled: Boolean) {
        selectedPackages += packageName
        renderAppList()
        lifecycleScope.launch {
            try {
                preferencesRepository.saveDailyLimit(packageName, minutes, enabled)
                showSnackbar(
                    getString(
                        if (enabled) R.string.daily_limit_saved else R.string.daily_limit_disabled,
                    ),
                )
                ServiceController.refresh(this@MainActivity)
            } catch (error: Exception) {
                showSnackbar(getString(R.string.daily_limit_save_failed))
                reloadPreferences()
            }
        }
    }

    private fun removeDailyLimit(packageName: String) {
        lifecycleScope.launch {
            try {
                preferencesRepository.removeDailyLimit(packageName)
                showSnackbar(getString(R.string.daily_limit_removed))
            } catch (error: Exception) {
                showSnackbar(getString(R.string.daily_limit_save_failed))
                reloadPreferences()
            }
        }
    }

    private fun showTestOverlay() {
        if (!hasOverlayAccess) {
            showOverlayPermissionDialog()
            return
        }
        if (!preferencesLoaded) return
        if (previewOverlay != null) return

        val manager = BreakOverlayManager(
            context = this,
            soundEnabled = currentPreferences.soundEnabled,
            snoozeDurationMinutes = currentPreferences.snoozeMinutes,
            listener = object : BreakOverlayManager.SnoozeListener {
                override fun onSnooze(testOverlay: Boolean) {
                    if (testOverlay) {
                        previewOverlay = null
                        TestOverlayState.setActive(false)
                    }
                }
            },
        )
        TestOverlayState.setActive(true)
        previewOverlay = manager
        when (manager.show(test = true)) {
            BreakOverlayManager.ShowResult.SHOWN -> Unit
            BreakOverlayManager.ShowResult.NO_OVERLAY_PERMISSION -> {
                cleanupPreviewOverlay()
                hasOverlayAccess = false
                showOverlayPermissionDialog()
            }
            BreakOverlayManager.ShowResult.ALREADY_VISIBLE -> {
                cleanupPreviewOverlay()
                showSnackbar(getString(R.string.overlay_test_already_visible))
            }
            BreakOverlayManager.ShowResult.WINDOW_ADD_FAILED -> {
                cleanupPreviewOverlay()
                showSnackbar(getString(R.string.overlay_test_failed))
            }
        }
    }

    private fun cleanupPreviewOverlay() {
        previewOverlay?.dismiss()
        previewOverlay = null
        TestOverlayState.setActive(false)
    }

    private fun refreshPermissionUi() {
        hasUsageAccess = PermissionUtils.hasUsageAccess(this)
        hasOverlayAccess = PermissionUtils.canDrawOverlays(this)
        notificationsAvailable = PermissionUtils.effectivelyCanNotify(this)
        batteryUnrestricted = PermissionUtils.isIgnoringBatteryOptimizations(this)

        setBadge(
            binding.overlayPermissionStatus,
            getString(if (hasOverlayAccess) R.string.status_granted else R.string.status_missing),
            if (hasOverlayAccess) BadgeTone.SUCCESS else BadgeTone.WARNING,
        )
        binding.overlayPermissionButton.text = getString(
            if (hasOverlayAccess) R.string.action_manage else R.string.action_grant,
        )

        setBadge(
            binding.usagePermissionStatus,
            getString(if (hasUsageAccess) R.string.status_granted else R.string.status_missing),
            if (hasUsageAccess) BadgeTone.SUCCESS else BadgeTone.WARNING,
        )

        val postPermission = PermissionUtils.hasPostNotificationPermission(this)
        val appNotificationsEnabled = PermissionUtils.areNotificationsEnabled(this)
        val channelDisabled = PermissionUtils.gentleNotificationChannelDisabled(this)
        when {
            !postPermission || !appNotificationsEnabled -> setBadge(
                binding.notificationPermissionStatus,
                getString(R.string.status_off),
                BadgeTone.WARNING,
            )
            channelDisabled -> setBadge(
                binding.notificationPermissionStatus,
                getString(R.string.status_channel_off),
                BadgeTone.WARNING,
            )
            else -> setBadge(
                binding.notificationPermissionStatus,
                getString(R.string.status_allowed),
                BadgeTone.SUCCESS,
            )
        }
        binding.notificationPermissionButton.text = getString(
            if (notificationsAvailable) R.string.action_manage else R.string.action_allow,
        )

        setBadge(
            binding.batteryPermissionStatus,
            getString(
                if (batteryUnrestricted) {
                    R.string.permission_battery_unrestricted
                } else {
                    R.string.permission_battery_optimized
                },
            ),
            if (batteryUnrestricted) BadgeTone.SUCCESS else BadgeTone.NEUTRAL,
        )

        renderMonitoringCard(lastMonitoringSnapshot)
        renderAppList()
    }

    private fun renderMonitoringCard(snapshot: MonitoringSnapshot) {
        val enabled = currentPreferences.monitoringEnabled
        setMonitoringSwitchSilently(enabled)

        val criticalReady = hasUsageAccess && hasOverlayAccess
        val statusText: String
        val detailText: String
        val dotColor: Int
        when {
            !enabled -> {
                statusText = getString(R.string.monitoring_status_off)
                detailText = getString(R.string.monitoring_detail_off)
                dotColor = R.color.status_off_dot
            }
            !serviceRunning -> {
                statusText = getString(R.string.monitoring_status_stopped)
                detailText = getString(R.string.monitoring_detail_stopped)
                dotColor = R.color.status_warning_dot
            }
            !criticalReady -> {
                statusText = getString(R.string.monitoring_status_partial)
                detailText = getString(R.string.monitoring_detail_partial)
                dotColor = R.color.status_warning_dot
            }
            else -> {
                statusText = getString(R.string.monitoring_status_active)
                detailText = getString(
                    if (snapshot.screenOn) {
                        R.string.monitoring_detail_active
                    } else {
                        R.string.monitoring_detail_screen_off
                    },
                )
                dotColor = R.color.status_active_dot
            }
        }
        binding.monitoringStatusText.text = statusText
        binding.monitoringDetailText.text = detailText
        binding.monitoringStatusDot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, dotColor),
        )

        when {
            !enabled -> setBadge(
                binding.headerStatusBadge,
                getString(R.string.status_off),
                BadgeTone.NEUTRAL,
            )
            serviceRunning && criticalReady -> setBadge(
                binding.headerStatusBadge,
                getString(R.string.status_active),
                BadgeTone.SUCCESS,
            )
            else -> setBadge(
                binding.headerStatusBadge,
                getString(R.string.status_needs_attention),
                BadgeTone.WARNING,
            )
        }

        renderLiveValues(snapshot, enabled)

        val warning = currentWarningText(enabled)
        binding.monitoringWarningText.text = warning.orEmpty()
        binding.monitoringWarningText.isVisible = warning != null
    }

    private fun renderLiveValues(snapshot: MonitoringSnapshot, enabled: Boolean) {
        if (!enabled || !serviceRunning) {
            binding.activeAppValue.text = getString(R.string.value_none)
            binding.sessionDurationValue.text = getString(R.string.value_dash)
            binding.nextBreakValue.text = getString(R.string.value_waiting_for_app)
            return
        }

        binding.activeAppValue.text = snapshot.activePackage?.let { labelForPackage(it) }
            ?: getString(R.string.value_none)
        binding.sessionDurationValue.text = if (snapshot.sessionActive) {
            DurationFormatter.compact(snapshot.continuousSessionMs)
        } else {
            getString(R.string.value_dash)
        }

        binding.nextBreakValue.text = when {
            snapshot.overlayShowing -> getString(R.string.value_break_in_progress)
            !snapshot.screenOn -> getString(R.string.value_screen_off)
            snapshot.sessionActive && snapshot.isAccruing -> {
                val remaining = snapshot.remainingActiveMs ?: 0L
                if (remaining <= 0L) {
                    getString(R.string.next_break_due)
                } else {
                    val estimated = snapshot.estimatedNextBreakWallMs
                    if (estimated != null) {
                        val time = DateFormat.getTimeFormat(this).format(Date(estimated))
                        getString(
                            R.string.next_break_value,
                            DurationFormatter.countdown(remaining),
                            time,
                        )
                    } else {
                        getString(
                            R.string.next_break_remaining_only,
                            DurationFormatter.countdown(remaining),
                        )
                    }
                }
            }
            snapshot.sessionActive -> getString(R.string.value_paused)
            else -> getString(R.string.value_waiting_for_app)
        }
    }

    private fun currentWarningText(enabled: Boolean): String? {
        return when {
            !hasUsageAccess -> getString(R.string.warning_usage_access_missing)
            !hasOverlayAccess -> getString(R.string.warning_overlay_missing)
            enabled && !serviceRunning -> getString(R.string.warning_service_stopped)
            enabled && !notificationsAvailable ->
                getString(R.string.warning_notifications_disabled)
            enabled && !currentPreferences.monitorAllApps && selectedPackages.isEmpty() ->
                getString(R.string.warning_selected_scope_empty)
            enabled && serviceRunning -> lastMonitoringSnapshot.statusNote
                ?.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun labelForPackage(packageName: String): String =
        allApps.firstOrNull { it.packageName == packageName }?.label
            ?: appListRepository.appLabel(packageName)

    private fun showPermissionRequiredDialog(missingUsage: Boolean, missingOverlay: Boolean) {
        val labels = when {
            missingUsage && missingOverlay -> getString(R.string.permission_label_both)
            missingUsage -> getString(R.string.permission_label_usage)
            else -> getString(R.string.permission_label_overlay)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.permission_needed_title)
            .setMessage(getString(R.string.monitoring_requires_permissions, labels))
            .setPositiveButton(R.string.action_open_settings) { _, _ ->
                if (missingOverlay) openOverlaySettings() else openUsageAccessSettings()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showSelectAppsFirstDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_apps_first_title)
            .setMessage(R.string.select_apps_first_message)
            .setPositiveButton(R.string.action_open) { _, _ ->
                binding.dashboardScroll.post {
                    binding.dashboardScroll.smoothScrollTo(
                        0,
                        (binding.appsCard.top - dp(16)).coerceAtLeast(0),
                    )
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showStartFailureDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.start_failed_title)
            .setMessage(R.string.monitoring_start_failed_message)
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    private fun showOverlayPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.permission_needed_title)
            .setMessage(R.string.overlay_test_permission_needed)
            .setPositiveButton(R.string.action_open_settings) { _, _ -> openOverlaySettings() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun requestNotificationPermissionIfUseful() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionUtils.hasPostNotificationPermission(this)
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestNotificationPermissionOrOpenSettings() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionUtils.hasPostNotificationPermission(this)
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings()
        }
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        openSystemScreen(intent)
    }

    private fun openUsageAccessSettings() {
        openSystemScreen(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun openBatteryOptimizationSettings() {
        openSystemScreen(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun openNotificationSettings() {
        val primary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            appDetailsIntent()
        }
        openSystemScreen(primary)
    }

    private fun openSystemScreen(primary: Intent) {
        try {
            startActivity(primary)
        } catch (_: ActivityNotFoundException) {
            openAppDetailsFallback()
        } catch (_: SecurityException) {
            openAppDetailsFallback()
        } catch (_: RuntimeException) {
            openAppDetailsFallback()
        }
    }

    private fun openAppDetailsFallback() {
        try {
            startActivity(appDetailsIntent())
        } catch (_: RuntimeException) {
            showSnackbar(getString(R.string.open_settings_failed))
        }
    }

    private fun appDetailsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:$packageName"),
    )

    private fun setBadge(view: TextView, text: String, tone: BadgeTone) {
        view.text = text
        val background = when (tone) {
            BadgeTone.SUCCESS -> R.drawable.bg_badge_success
            BadgeTone.WARNING -> R.drawable.bg_badge_warning
            BadgeTone.ERROR -> R.drawable.bg_badge_error
            BadgeTone.NEUTRAL -> R.drawable.bg_badge_neutral
        }
        val textColor = when (tone) {
            BadgeTone.SUCCESS -> R.color.badge_success_text
            BadgeTone.WARNING -> R.color.badge_warning_text
            BadgeTone.ERROR -> R.color.badge_error_text
            BadgeTone.NEUTRAL -> R.color.badge_neutral_text
        }
        view.setBackgroundResource(background)
        view.setTextColor(ContextCompat.getColor(this, textColor))
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.dashboardScroll, message, Snackbar.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private enum class BadgeTone {
        SUCCESS,
        WARNING,
        ERROR,
        NEUTRAL,
    }

    private companion object {
        const val DEFAULT_LIMIT_MINUTES = 30
        const val USAGE_REFRESH_INTERVAL_MS = 30_000L
    }
}
