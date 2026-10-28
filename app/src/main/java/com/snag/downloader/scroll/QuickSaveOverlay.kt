package com.snag.downloader.scroll

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import com.snag.downloader.R
import kotlin.math.abs

/**
 * Draggable floating save button. The default style is the maroon/gold crown; the
 * low-profile style is a smaller black button with a white crown.
 */
internal class QuickSaveOverlay(
    private val context: Context,
    private val lowProfile: Boolean,
    private val opacity: Float,
    private val onSave: () -> Unit,
) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val size = dp(if (lowProfile) 46 else 58)
    private val margin = dp(if (lowProfile) 10 else 14)
    private val touchSlop = dp(8)
    private val handler = Handler(Looper.getMainLooper())
    private var view: FrameLayout? = null
    private var icon: ImageView? = null
    private var spinner: ProgressBar? = null
    private var baseIconColor = if (lowProfile) WHITE else GOLD
    private var pendingState: CaptureFeedbackState? = null

    fun attach() {
        if (view != null || !Settings.canDrawOverlays(context)) return
        val wm = windowManager ?: return
        val metrics = context.resources.displayMetrics
        val layout = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (metrics.widthPixels - size - margin).coerceAtLeast(0)
            y = (metrics.heightPixels * 0.45f).toInt()
        }

        val root = FrameLayout(context).apply {
            elevation = dp(if (lowProfile) 3 else 8).toFloat()
            alpha = opacity.coerceIn(0.35f, 1f)
            contentDescription = "Save copied TikTok link"
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (lowProfile) BLACK else MAROON)
                setStroke(
                    dp(if (lowProfile) 1 else 2),
                    if (lowProfile) 0x40FFFFFF else GOLD,
                )
            }
            setOnClickListener { onSave() }
        }

        val crown = ImageView(context).apply {
            setImageResource(R.drawable.ic_crown)
            setColorFilter(baseIconColor)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val inset = dp(if (lowProfile) 11 else 14)
            setPadding(inset, inset, inset, inset)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(crown, FrameLayout.LayoutParams(-1, -1))

        val progress = ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleSmall,
        ).apply {
            indeterminateTintList = ColorStateList.valueOf(baseIconColor)
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val spinnerSize = dp(if (lowProfile) 20 else 24)
        root.addView(
            progress,
            FrameLayout.LayoutParams(spinnerSize, spinnerSize, Gravity.CENTER),
        )

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragged = false

        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = layout.x
                    startY = layout.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) dragged = true
                    if (dragged) {
                        layout.x = (startX + dx.toInt()).coerceIn(0, metrics.widthPixels - size)
                        layout.y = (startY + dy.toInt()).coerceIn(0, metrics.heightPixels - size)
                        runCatching { wm.updateViewLayout(view, layout) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) root.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        runCatching {
            wm.addView(root, layout)
            view = root
            icon = crown
            spinner = progress
            pendingState?.let(::render)
            pendingState = null
        }
    }

    fun render(state: CaptureFeedbackState) {
        if (view == null) {
            pendingState = state
            return
        }
        val root = view ?: return
        val crown = icon ?: return
        val progress = spinner ?: return
        handler.removeCallbacksAndMessages(null)
        root.animate().cancel()

        when (state) {
            CaptureFeedbackState.Capturing -> {
                progress.visibility = View.VISIBLE
                crown.alpha = 0f
                root.animate()
                    .scaleX(0.92f)
                    .scaleY(0.92f)
                    .setDuration(180)
                    .start()
            }
            CaptureFeedbackState.Success -> {
                progress.visibility = View.GONE
                crown.alpha = 1f
                crown.setColorFilter(SUCCESS)
                root.scaleX = 0.92f
                root.scaleY = 0.92f
                root.animate()
                    .scaleX(1.18f)
                    .scaleY(1.18f)
                    .setDuration(150)
                    .withEndAction {
                        root.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(180)
                            .withEndAction {
                                handler.postDelayed({
                                    crown.setColorFilter(baseIconColor)
                                }, 550L)
                            }
                            .start()
                    }
                    .start()
            }
            is CaptureFeedbackState.Error -> {
                progress.visibility = View.GONE
                crown.alpha = 1f
                crown.setColorFilter(ERROR)
                root.animate()
                    .rotation(-7f)
                    .setDuration(70)
                    .withEndAction {
                        root.animate()
                            .rotation(7f)
                            .setDuration(70)
                            .withEndAction {
                                root.animate()
                                    .rotation(0f)
                                    .setDuration(90)
                                    .withEndAction {
                                        handler.postDelayed({
                                            crown.setColorFilter(baseIconColor)
                                        }, 550L)
                                    }
                                    .start()
                            }
                            .start()
                    }
                    .start()
            }
        }
    }

    fun detach() {
        handler.removeCallbacksAndMessages(null)
        val current = view ?: return
        runCatching { windowManager?.removeView(current) }
        view = null
        icon = null
        spinner = null
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val MAROON = 0xFF6E1B2A.toInt()
        const val GOLD = 0xFFD8B56A.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF0B0B0B.toInt()
        const val SUCCESS = 0xFF7FD39B.toInt()
        const val ERROR = 0xFFFF8A80.toInt()
    }
}
