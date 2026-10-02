package com.light.lightcamera

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class ScreenOverlayView(
    context: Context,
    private val listener: OverlayListener,
) : LinearLayout(context) {

    interface OverlayListener {
        fun onPauseClicked()
        fun onResumeClicked()
        fun onStopClicked()
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val layoutParams = WindowManager.LayoutParams().apply {
        type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        format = android.graphics.PixelFormat.TRANSLUCENT
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        gravity = Gravity.TOP or Gravity.END

        x = dpToPx(12)
        y = dpToPx(48)
    }

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var isPaused = false
    private var isExpanded = false

    private val redDot: View
    private val timerTextView: TextView
    private val pauseButton: ImageButton
    private val stopButton: ImageButton
    private val expandButton: ImageButton
    private val controlsLayout: LinearLayout

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))

        // Dark rounded pill background
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(20).toFloat()
            setColor(Color.parseColor("#EE1A1A1A"))
            setStroke(dpToPx(1), Color.parseColor("#50FFFFFF"))
        }

        // Red recording dot indicator
        redDot = View(context).apply {
            val size = dpToPx(8)
            layoutParams = LayoutParams(size, size).apply {
                marginEnd = dpToPx(6)
                marginStart = dpToPx(2)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.RED)
            }
        }
        addView(redDot)

        // Timer text view
        timerTextView = TextView(context).apply {
            text = "00:00"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dpToPx(8)
            }
            visibility = View.GONE
        }
        addView(timerTextView)

        // Inner controls layout
        controlsLayout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }

        // Larger button touch targets (36dp x 36dp with generous padding) for easy clicking
        val buttonSize = dpToPx(36)
        val buttonPadding = dpToPx(7)

        // Pause/Resume button
        pauseButton = ImageButton(context).apply {
            setImageResource(R.drawable.ic_pause)
            setColorFilter(Color.WHITE)
            background = createButtonBackground()
            setPadding(buttonPadding, buttonPadding, buttonPadding, buttonPadding)
            layoutParams = LayoutParams(buttonSize, buttonSize).apply {
                marginEnd = dpToPx(4)
            }
            contentDescription = context.getString(R.string.pause_screen_record)
            setOnClickListener {
                if (isPaused) {
                    isPaused = false
                    setImageResource(R.drawable.ic_pause)
                    contentDescription = context.getString(R.string.pause_screen_record)
                    listener.onResumeClicked()
                } else {
                    isPaused = true
                    setImageResource(R.drawable.ic_play_arrow)
                    contentDescription = context.getString(R.string.resume_screen_record)
                    listener.onPauseClicked()
                }
            }
        }
        controlsLayout.addView(pauseButton)

        // Stop button
        stopButton = ImageButton(context).apply {
            setImageResource(R.drawable.ic_stop)
            setColorFilter(Color.parseColor("#FF5252"))
            background = createButtonBackground()
            setPadding(buttonPadding, buttonPadding, buttonPadding, buttonPadding)
            layoutParams = LayoutParams(buttonSize, buttonSize).apply {
                marginEnd = dpToPx(4)
            }
            contentDescription = context.getString(R.string.stop_screen_record)
            setOnClickListener {
                listener.onStopClicked()
            }
        }
        controlsLayout.addView(stopButton)

        addView(controlsLayout)

        // Minimize / Expand toggle button
        expandButton = ImageButton(context).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(Color.LTGRAY)
            background = createButtonBackground()
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            layoutParams = LayoutParams(buttonSize, buttonSize)
            rotation = 0f
            contentDescription = context.getString(R.string.app_name)
            setOnClickListener {
                toggleExpand()
            }
        }
        addView(expandButton)

        setupTouchDrag()
    }

    private fun createButtonBackground(): RippleDrawable {
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        val content = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#20FFFFFF"))
        }
        return RippleDrawable(
            ColorStateList.valueOf(Color.parseColor("#40FFFFFF")),
            content,
            mask
        )
    }

    private fun toggleExpand() {
        isExpanded = !isExpanded
        if (isExpanded) {
            controlsLayout.visibility = View.VISIBLE
            timerTextView.visibility = View.VISIBLE
            expandButton.rotation = 180f
        } else {
            controlsLayout.visibility = View.GONE
            timerTextView.visibility = View.GONE
            expandButton.rotation = 0f
        }
        updateWindow()
    }

    private fun setupTouchDrag() {
        setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        layoutParams.x = initialX - dx.toInt()
                        layoutParams.y = initialY + dy.toInt()
                        updateWindow()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        v.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (!isExpanded) {
            toggleExpand()
        }
        return true
    }

    fun updateTimerText(formattedTime: String) {
        timerTextView.text = formattedTime
    }

    fun showOverlay() {
        if (parent == null) {
            windowManager.addView(this, layoutParams)
        }
    }

    fun removeOverlay() {
        if (parent != null) {
            windowManager.removeView(this)
        }
    }

    private fun updateWindow() {
        if (parent != null) {
            windowManager.updateViewLayout(this, layoutParams)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
