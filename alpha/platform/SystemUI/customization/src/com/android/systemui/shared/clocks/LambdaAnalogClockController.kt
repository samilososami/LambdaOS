/* SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.shared.clocks

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.android.systemui.customization.R
import com.android.systemui.plugins.clocks.*
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.min

/** Native clock plugin. Tick, timezone and AOD are driven by SystemUI, never a timer. */
class LambdaAnalogClockController(
    private val context: Context,
    private val digital: DefaultClockController,
) : ClockController by digital {
    private val dial = Dial(context)
    private var dozing = false
    private var faceTheme = ThemeConfig(true, null)
    override val config = ClockConfig("LAMBDA_ANALOG",
        context.getString(R.string.lambda_clock_analog),
        context.getString(R.string.lambda_clock_description))
    override val events = object : ClockEvents by digital.events {
        override fun onTimeZoneChanged(timeZone: TimeZone) {
            digital.events.onTimeZoneChanged(timeZone)
            dial.zone = timeZone
            dial.tick()
        }
        override fun onTimeFormatChanged(is24Hr: Boolean) {
            digital.events.onTimeFormatChanged(is24Hr)
            dial.tick()
        }
        override fun onLocaleChanged(locale: Locale) {
            digital.events.onLocaleChanged(locale)
            dial.tick()
        }
    }
    private fun recolor() {
        dial.ink = if (dozing) Color.WHITE else faceTheme.seedColor
            ?: if (faceTheme.isDarkTheme) 0xfff4f4f4.toInt() else 0xff202020.toInt()
        dial.invalidate()
    }
    override val largeClock = object : ClockFaceController {
        override val view: View = dial
        override val config = ClockFaceConfig()
        override val theme get() = faceTheme
        override val layout = object : ClockFaceLayout by DefaultClockFaceLayout(dial) {
            override fun applyAodBurnIn(aodBurnInModel: AodClockBurnInModel) {
                dial.translationX = aodBurnInModel.translationX
                dial.translationY = aodBurnInModel.translationY
                dial.scaleX = aodBurnInModel.scale
                dial.scaleY = aodBurnInModel.scale
            }
        }
        override val events = object : ClockFaceEvents {
            override fun onTimeTick() = dial.tick()
            override fun onThemeChanged(theme: ThemeConfig) { faceTheme = theme; recolor() }
            override fun onFontSettingChanged(fontSizePx: Float) {}
            override fun onTargetRegionChanged(targetRegion: Rect?) {
                if (targetRegion == null) return
                val size = min(dial.preferredSize, min(targetRegion.width(), targetRegion.height()))
                if (size <= 0) return
                val lp = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                lp.topMargin = targetRegion.centerY() - size / 2
                // Only legacy frame hosts use absolute margins. Blueprint hosts own constraints.
                if (dial.parent is FrameLayout) dial.layoutParams = lp
            }
            override fun onSecondaryDisplayChanged(onSecondaryDisplay: Boolean) { dial.tick() }
        }
        override val animations = object : ClockAnimations by digital.largeClock.animations {
            override fun enter() {
                if (!ValueAnimator.areAnimatorsEnabled()) { dial.alpha = 1f; return }
                dial.alpha = 0f
                dial.animate().alpha(1f).setDuration(220).start()
            }
            override fun doze(fraction: Float) { dozing = fraction > 0f; recolor() }
            override fun charge() {}
            override fun fold(fraction: Float) {}
            override fun onPositionUpdated(fromLeft: Int, direction: Int, fraction: Float) {}
            override fun onPositionUpdated(distance: Float, fraction: Float) {}
            override fun onPickerCarouselSwiping(swipingFraction: Float) {}
        }
    }
    override fun initialize(isDarkTheme: Boolean, dozeFraction: Float, foldFraction: Float) {
        digital.initialize(isDarkTheme, dozeFraction, foldFraction)
        faceTheme = ThemeConfig(isDarkTheme, null)
        dozing = dozeFraction > 0f
        recolor()
        dial.tick()
    }
    private class Dial(context: Context) : View(context) {
        val preferredSize = (240 * resources.displayMetrics.density).toInt()
        var zone: TimeZone = TimeZone.getDefault()
        var ink: Int = Color.WHITE
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        private var hour = 0f
        private var minute = 0f
        init {
            id = resources.getIdentifier("lockscreen_clock_view_large", "id", context.packageName)
            layoutParams = FrameLayout.LayoutParams(preferredSize, preferredSize, Gravity.CENTER)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            tick()
        }
        fun tick() {
            val now = Calendar.getInstance(zone)
            minute = now.get(Calendar.MINUTE).toFloat()
            hour = now.get(Calendar.HOUR) + minute / 60f
            contentDescription = DateFormat.getTimeFormat(context).apply { timeZone = zone }.format(now.time)
            invalidate()
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(resolveSize(preferredSize, widthMeasureSpec),
                resolveSize(preferredSize, heightMeasureSpec))
        }
        override fun onDraw(canvas: Canvas) {
            val scale = min(width, height) / 240f
            canvas.save(); canvas.translate(width / 2f, height / 2f); canvas.scale(scale, scale)
            pen.color = ink; pen.strokeWidth = 1.8f; pen.alpha = 155
            for (i in 0 until 12) {
                canvas.save(); canvas.rotate(i * 30f)
                canvas.drawLine(0f, -108f, 0f, if (i % 3 == 0) -96f else -102f, pen)
                canvas.restore()
            }
            pen.alpha = 255
            canvas.save(); canvas.rotate(hour * 30f); pen.strokeWidth = 4f
            canvas.drawLine(0f, 6f, 0f, -57f, pen); canvas.restore()
            canvas.save(); canvas.rotate(minute * 6f); pen.strokeWidth = 2.5f
            canvas.drawLine(0f, 8f, 0f, -85f, pen); canvas.restore()
            canvas.drawCircle(0f, 0f, 3f, pen); canvas.restore()
        }
    }
}
