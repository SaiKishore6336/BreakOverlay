package com.breakoverlay.app.overlay

import android.app.KeyguardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import com.breakoverlay.app.R
import com.breakoverlay.app.databinding.OverlayBreakBinding
import com.breakoverlay.app.domain.DurationFormatter
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

/**
 * Adds a single full-screen application overlay. Only supported system flags are used:
 * an overlay cannot cover the secure keyguard, permission dialogs, or secure windows.
 */
class BreakOverlayManager(
    context: Context,
    private val soundEnabled: Boolean,
    private val snoozeDurationMinutes: Int,
    private val listener: SnoozeListener,
) {
    interface SnoozeListener {
        fun onSnooze(testOverlay: Boolean)
    }

    enum class ShowResult {
        SHOWN,
        NO_OVERLAY_PERMISSION,
        ALREADY_VISIBLE,
        WINDOW_ADD_FAILED,
    }

    private val appContext = context.applicationContext
    private val themedContext = ContextThemeWrapper(
        appContext,
        R.style.Theme_BreakOverlay_Overlay,
    )
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var binding: OverlayBreakBinding? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var soundPlayer: BreakSoundPlayer? = null
    private var testOverlay = false
    private var countdownRunning = false
    private var countdownDeadlineElapsed = 0L
    private var lastDisplayedSecond = -1
    private var added = false

    private val titleStages = arrayOf(
        R.string.overlay_title_stage_1,
        R.string.overlay_title_stage_2,
        R.string.overlay_title_stage_3,
    )

    private val countdownTicker = object : Runnable {
        override fun run() {
            updateCountdown()
        }
    }

    @Suppress("DEPRECATION")
    fun show(test: Boolean = false): ShowResult {
        if (!Settings.canDrawOverlays(appContext)) {
            return ShowResult.NO_OVERLAY_PERMISSION
        }
        synchronized(activeManagerLock) {
            if (ACTIVE_MANAGER.get() != null) return ShowResult.ALREADY_VISIBLE
            ACTIVE_MANAGER.set(this)
        }

        testOverlay = test
        val overlayBinding = OverlayBreakBinding.inflate(
            LayoutInflater.from(themedContext),
        )
        binding = overlayBinding
        val view = overlayBinding.root

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        layoutParams = params

        view.isFocusableInTouchMode = true
        view.requestFocus()
        view.setOnKeyListener { _, keyCode, _ ->
            keyCode == KeyEvent.KEYCODE_BACK
        }

        overlayBinding.overlayEyebrow.text = appContext.getString(
            if (test) R.string.overlay_test_eyebrow else R.string.overlay_eyebrow,
        )
        overlayBinding.overlayTitle.text = ""
        overlayBinding.overlayTitle.contentDescription =
            appContext.getString(R.string.overlay_title_full)
        overlayBinding.overlaySnoozeButton.isEnabled = false
        overlayBinding.overlaySnoozeButton.text = appContext.getString(
            R.string.overlay_snooze_locked,
            COUNTDOWN_SECONDS,
        )
        overlayBinding.overlaySnoozeButton.setOnClickListener {
            if (!it.isEnabled) return@setOnClickListener
            dismiss()
            listener.onSnooze(testOverlay)
        }

        try {
            windowManager.addView(view, params)
            added = true
            view.post {
                if (!added) return@post
                view.requestFocus()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    view.windowInsetsController?.apply {
                        hide(WindowInsets.Type.statusBars())
                        systemBarsBehavior =
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                }
            }
        } catch (security: SecurityException) {
            Log.w(TAG, "Overlay permission rejected by WindowManager", security)
            cleanupAfterFailedAdd()
            return ShowResult.NO_OVERLAY_PERMISSION
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to add break overlay", error)
            cleanupAfterFailedAdd()
            return ShowResult.WINDOW_ADD_FAILED
        }

        if (soundEnabled) {
            soundPlayer = BreakSoundPlayer(themedContext).also { it.play() }
        }
        startTitleAnimation()
        startCountdown()
        return ShowResult.SHOWN
    }

    fun dismiss() {
        if (!added && binding == null) return
        handler.removeCallbacksAndMessages(null)
        countdownRunning = false
        binding?.overlayTitle?.animate()?.cancel()
        binding?.overlayProgress?.clearAnimation()
        soundPlayer?.release()
        soundPlayer = null

        val view = binding?.root
        if (added && view != null) {
            try {
                windowManager.removeView(view)
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Overlay view was already detached", error)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not remove overlay view", error)
            }
        }
        added = false
        binding = null
        layoutParams = null
        synchronized(activeManagerLock) {
            if (ACTIVE_MANAGER.get() === this) {
                ACTIVE_MANAGER.set(null)
            }
        }
    }

    fun isShowing(): Boolean = added

    private fun startTitleAnimation() {
        val view = binding ?: return
        showTitleStage(0)
        handler.postDelayed({ showTitleStage(1) }, TITLE_STAGE_DELAY_MS)
        handler.postDelayed(
            { showTitleStage(2) },
            TITLE_STAGE_DELAY_MS * 2,
        )
    }

    private fun showTitleStage(stage: Int) {
        val view = binding ?: return
        val title = view.overlayTitle
        title.animate().cancel()
        title.alpha = 0f
        title.translationY = 14f
        title.text = appContext.getString(titleStages[stage])
        title.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(TITLE_FADE_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun startCountdown() {
        countdownRunning = true
        countdownDeadlineElapsed = SystemClock.elapsedRealtime() + COUNTDOWN_MS
        lastDisplayedSecond = -1
        updateCountdown()
    }

    /**
     * The countdown is derived from a monotonic deadline. A single ticker reschedules itself;
     * there is no independent progress animation that can drift from the text.
     */
    private fun updateCountdown() {
        if (!countdownRunning) return
        val view = binding ?: return
        val remaining = (countdownDeadlineElapsed - SystemClock.elapsedRealtime())
            .coerceAtLeast(0L)
        val displayedSeconds = ceil(remaining / 1000.0).toInt()

        if (displayedSeconds != lastDisplayedSecond) {
            lastDisplayedSecond = displayedSeconds
            val progress = (displayedSeconds * 1000)
                .coerceIn(0, COUNTDOWN_SECONDS * 1000)
            view.overlayProgress.max = COUNTDOWN_SECONDS * 1000
            view.overlayProgress.progress = progress

            if (displayedSeconds > 0) {
                view.overlayCountdown.text = appContext.getString(
                    R.string.overlay_countdown,
                    displayedSeconds,
                )
                view.overlaySnoozeButton.text = appContext.getString(
                    R.string.overlay_snooze_locked,
                    displayedSeconds,
                )
                view.overlayGuidance.text = appContext.getString(
                    if (displayedSeconds >= 5) {
                        R.string.overlay_guidance_distance
                    } else {
                        R.string.overlay_guidance_blink
                    },
                )
            } else {
                view.overlayCountdown.text =
                    appContext.getString(R.string.overlay_countdown_complete)
                view.overlayGuidance.text =
                    appContext.getString(R.string.overlay_guidance_complete)
                view.overlaySnoozeButton.text = appContext.getString(
                    R.string.overlay_snooze_button,
                    snoozeDurationMinutes,
                )
                view.overlaySnoozeButton.isEnabled = true
            }
        }

        if (remaining <= 0L) {
            countdownRunning = false
            return
        }
        val delay = (remaining % 1000L).coerceAtLeast(50L)
        handler.postDelayed(countdownTicker, delay)
    }

    private fun cleanupAfterFailedAdd() {
        handler.removeCallbacksAndMessages(null)
        soundPlayer?.release()
        soundPlayer = null
        binding = null
        layoutParams = null
        added = false
        synchronized(activeManagerLock) {
            if (ACTIVE_MANAGER.get() === this) {
                ACTIVE_MANAGER.set(null)
            }
        }
    }

    companion object {
        private const val TAG = "BreakOverlayManager"
        private const val COUNTDOWN_SECONDS = 8
        private const val COUNTDOWN_MS = COUNTDOWN_SECONDS * 1000L
        private const val TITLE_STAGE_DELAY_MS = 350L
        private const val TITLE_FADE_MS = 180L

        private val activeManagerLock = Any()
        private val ACTIVE_MANAGER = AtomicReference<BreakOverlayManager?>(null)
    }
}
