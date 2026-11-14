package com.example.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.JarvisOrchestratorState
import com.example.service.JarvisRuntimeSnapshot
import com.example.ui.theme.JarvisCyan
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * Guaranteed In-App Compose Bottom-Right Siri Pill rendered inside MainActivity's Scaffold Box
 * whenever JARVIS is WAKING_BRAIN, ONLINE, or EXECUTING.
 */
@Composable
fun BottomRightSiriPill(
    snapshot: JarvisRuntimeSnapshot,
    onCloseToSleep: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (snapshot.state == JarvisOrchestratorState.SLEEPING) return

    val infiniteTransition = rememberInfiniteTransition(label = "siriOrbPulse")
    val orbScale by infiniteTransition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orbScale"
    )

    val pillText = formatConcisePillStatus(snapshot)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .height(44.dp)
            .widthIn(min = 150.dp, max = 270.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF0A0A0E))
            .border(1.5.dp, JarvisCyan, RoundedCornerShape(24.dp))
            .padding(horizontal = 14.dp)
            .testTag("bottom_right_siri_pill")
    ) {
        // Left: Pulsing Cyan Orb
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .scale(orbScale)
                    .clip(CircleShape)
                    .background(JarvisCyan.copy(alpha = 0.30f))
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(JarvisCyan)
            )
        }

        // Center: Concise Status Text ("Listening...", "Opening WhatsApp...", "Playing Shiv Tandav...")
        Text(
            text = pillText,
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )

        // Right: Close "×" button to put JARVIS back to SLEEPING
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .clickable(onClick = onCloseToSleep)
                .testTag("siri_pill_close_button")
        ) {
            Text(
                text = "×",
                color = TextSecondary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

fun formatConcisePillStatus(snapshot: JarvisRuntimeSnapshot): String {
    return when (snapshot.state) {
        JarvisOrchestratorState.SLEEPING -> "Sleeping"
        JarvisOrchestratorState.RECORDING -> "Recording..."
        JarvisOrchestratorState.TRANSCRIBING -> "Transcribing..."
        JarvisOrchestratorState.WAKING_BRAIN -> "Waking..."
        JarvisOrchestratorState.EXECUTING -> {
            val fb = snapshot.lastActionFeedback
            when {
                fb.startsWith("Opening ", ignoreCase = true) ||
                    fb.startsWith("Playing ", ignoreCase = true) ||
                    fb.startsWith("Messaging ", ignoreCase = true) ||
                    fb.startsWith("Calling ", ignoreCase = true) -> fb
                fb.isNotBlank() -> fb
                else -> "Executing..."
            }
        }
        JarvisOrchestratorState.ONLINE -> {
            val fb = snapshot.lastActionFeedback
            if (fb.startsWith("Listening", ignoreCase = true) ||
                fb.startsWith("Brain ONLINE", ignoreCase = true) ||
                fb.isBlank()
            ) {
                "Listening... (${snapshot.remainingWakeSeconds}s)"
            } else {
                fb
            }
        }
    }
}

/**
 * System-Wide Bottom-Right Floating Pill Overlay (`WindowManager`) that stays visible on top of
 * WhatsApp, YouTube, or any other app when `Settings.canDrawOverlays(context)` is granted.
 */
class JarvisFloatingPillOverlay(
    private val context: Context,
    private val onCloseClicked: () -> Unit
) {
    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private var rootView: LinearLayout? = null
    private var statusTextView: TextView? = null
    private var orbAnimator: ObjectAnimator? = null
    private var isAdded = false

    fun showOrUpdate(customText: String) {
        if (!Settings.canDrawOverlays(appContext)) {
            hide()
            return
        }
        val clean = customText.trim().ifEmpty { "Recording..." }
        if (!isAdded) {
            showInternal(clean)
        } else {
            statusTextView?.text = clean
        }
    }

    fun updateOrShow(snapshot: JarvisRuntimeSnapshot) {
        if (snapshot.state == JarvisOrchestratorState.SLEEPING) {
            hide()
            return
        }
        showOrUpdate(formatConcisePillStatus(snapshot))
    }

    private fun showInternal(initialText: String) {
        val wm = windowManager ?: return
        if (isAdded) return

        try {
            val pillLayout = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val padH = dpToPx(14f)
                val padV = dpToPx(8f)
                setPadding(padH, padV, padH, padV)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(24f).toFloat()
                    setColor(AndroidColor.parseColor("#0A0A0E"))
                    setStroke(dpToPx(1.5f), AndroidColor.parseColor("#00E5FF"))
                }
                elevation = dpToPx(8f).toFloat()
            }

            // Left pulsing Cyan Orb
            val orbView = View(appContext).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(AndroidColor.parseColor("#00E5FF"))
                }
            }
            val orbParams = LinearLayout.LayoutParams(dpToPx(10f), dpToPx(10f)).apply {
                rightMargin = dpToPx(10f)
            }
            pillLayout.addView(orbView, orbParams)

            orbAnimator = ObjectAnimator.ofPropertyValuesHolder(
                orbView,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 0.8f, 1.3f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.8f, 1.3f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.6f, 1.0f)
            ).apply {
                duration = 800L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }

            // Center concise status label
            val labelView = TextView(appContext).apply {
                text = initialText
                setTextColor(AndroidColor.parseColor("#F5F5F5"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dpToPx(185f)
            }
            val labelParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = dpToPx(10f)
            }
            pillLayout.addView(labelView, labelParams)
            statusTextView = labelView

            // Right close "×" button
            val closeBtn = TextView(appContext).apply {
                text = "×"
                setTextColor(AndroidColor.parseColor("#888888"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dpToPx(4f), 0, dpToPx(2f), 0)
                setOnClickListener { onCloseClicked() }
            }
            pillLayout.addView(
                closeBtn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            // Strictly anchored in the BOTTOM-RIGHT CORNER (Gravity.BOTTOM or Gravity.END)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                dpToPx(44f),
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                x = dpToPx(16f)
                y = dpToPx(84f)
            }

            wm.addView(pillLayout, lp)
            rootView = pillLayout
            isAdded = true
        } catch (_: Throwable) {
            isAdded = false
        }
    }

    fun hide() {
        if (!isAdded) return
        try {
            orbAnimator?.cancel()
            orbAnimator = null
            rootView?.let { view ->
                try {
                    windowManager?.removeViewImmediate(view)
                } catch (_: Throwable) {
                    windowManager?.removeView(view)
                }
            }
        } catch (_: Throwable) {
        } finally {
            rootView = null
            statusTextView = null
            isAdded = false
        }
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            appContext.resources.displayMetrics
        ).toInt()
    }
}
