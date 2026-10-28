package com.snag.downloader.scroll

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.snag.downloader.R
import kotlin.math.abs

/**
 * Draggable crown overlay. A tap is a save action; dragging repositions the button.
 * The window is deliberately not focusable so TikTok keeps keyboard and touch focus.
 */
internal class QuickSaveOverlay(
    private val context: Context,
    private val onSave: () -> Unit,
) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val size = dp(58)
    private val margin = dp(14)
    private val touchSlop = dp(8)
    private var view: View? = null

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
            elevation = dp(8).toFloat()
            contentDescription = "Save copied TikTok link"
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF6E1B2A.toInt())
                setStroke(dp(2), 0xFFB08A45.toInt())
            }
            setOnClickListener { onSave() }
        }
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_crown)
            setColorFilter(0xFFD8B56A.toInt())
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(14), dp(14), dp(14), dp(14))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(icon, FrameLayout.LayoutParams(-1, -1))

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
        }
    }

    fun detach() {
        val current = view ?: return
        runCatching { windowManager?.removeView(current) }
        view = null
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
