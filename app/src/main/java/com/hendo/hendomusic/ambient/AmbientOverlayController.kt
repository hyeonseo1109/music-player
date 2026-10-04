package com.hendo.hendomusic.ambient

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/** Constants are centralized so the physical S22 Ultra result can be tuned without touching behavior. */
object AmbientOverlaySpec {
    const val WINDOW_HEIGHT_DP = 72
    const val EFFECT_HEIGHT_DP = 48
    const val FRAME_DELAY_MS = 33L
    const val PALETTE_TRANSITION_MS = 1_250L
    const val COLOR_ROTATION_MS = 18_000L
    val BASE_X = floatArrayOf(.19f, .50f, .81f)
    val RANGE_X = floatArrayOf(.105f, .088f, .100f)
    val PERIOD_MS = longArrayOf(4_400L, 5_900L, 7_600L)
    val PHASE = floatArrayOf(0f, 2.1f, 4.2f)
    val CORE_ALPHA = intArrayOf(188, 174, 182)
}

/**
 * Owns the system-wide visual layer while PlaybackService is alive. The window is explicitly
 * NOT_TOUCHABLE and NOT_FOCUSABLE, so every gesture is delivered to the app underneath it.
 */
class AmbientOverlayController(
    context: Context,
    private val repository: AmbientPaletteRepository,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(WindowManager::class.java)
    private val view = AmbientOverlayView(appContext)
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        dp(AmbientOverlaySpec.WINDOW_HEIGHT_DP),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        // Draw in the full display coordinate space. System status icons stay above this
        // application overlay, while a transparent status bar lets the light show through.
        // One UI can keep TYPE_APPLICATION_OVERLAY's parent below the status bar even when
        // fit insets are disabled. Start with the known inset removed on Samsung, then verify
        // the real screen coordinate after attach and correct it precisely.
        y = if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) -statusBarInset() else 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
        title = "Hendo Ambient Light"
    }
    private var attached = false
    private var artworkUri: String? = null
    private var requestGeneration = 0

    fun show(newArtworkUri: String?) {
        if (!Settings.canDrawOverlays(appContext)) {
            hide()
            return
        }
        if (!attached) {
            runCatching { windowManager.addView(view, params) }
                .onSuccess {
                    attached = true
                    view.start()
                    view.post(::alignToDisplayTop)
                }
                .onFailure { attached = false }
        }
        if (artworkUri == newArtworkUri) return
        artworkUri = newArtworkUri
        val generation = ++requestGeneration
        scope.launch {
            val palette = repository.paletteFor(newArtworkUri)
            if (generation == requestGeneration && attached) view.setPalette(palette)
        }
    }

    fun hide() {
        requestGeneration++
        if (!attached) return
        view.stop()
        runCatching { windowManager.removeViewImmediate(view) }
        attached = false
    }

    fun destroy() {
        hide()
        artworkUri = null
    }

    private fun dp(value: Int): Int = (value * appContext.resources.displayMetrics.density).toInt()

    private fun statusBarInset(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return windowManager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
        }
        val resourceId = appContext.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId != 0) appContext.resources.getDimensionPixelSize(resourceId) else 0
    }

    private fun alignToDisplayTop() {
        if (!attached) return
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        if (location[1] == 0) return
        params.y -= location[1]
        runCatching { windowManager.updateViewLayout(view, params) }
    }
}

private class AmbientOverlayView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private var running = false
    private var animationStartMs = 0L
    private var transitionStartMs = 0L
    private var fromColors = AmbientPalette.Neutral.colors.toIntArray()
    private var targetColors = AmbientPalette.Neutral.colors.toIntArray()
    private var motionEnabled = true

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun start() {
        if (running) return
        running = true
        motionEnabled = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        animationStartMs = SystemClock.uptimeMillis()
        invalidate()
    }

    fun stop() {
        running = false
        removeCallbacks(frame)
    }

    fun setPalette(palette: AmbientPalette) {
        val now = SystemClock.uptimeMillis()
        fromColors = displayedColors(now)
        targetColors = palette.colors.toIntArray()
        transitionStartMs = now
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!running || width == 0 || height == 0) return
        val now = SystemClock.uptimeMillis()
        val colors = displayedColors(now)
        val elapsed = if (motionEnabled) now - animationStartMs else 0L
        // The visual keeps its original 48dp placement, while the larger transparent
        // window gives the radial fade enough room to reach zero alpha without clipping.
        val effectHeight = height * (AmbientOverlaySpec.EFFECT_HEIGHT_DP.toFloat() /
            AmbientOverlaySpec.WINDOW_HEIGHT_DP)
        val rotation = (elapsed % AmbientOverlaySpec.COLOR_ROTATION_MS).toFloat() /
            AmbientOverlaySpec.COLOR_ROTATION_MS * colors.size
        val colorStage = rotation.toInt().coerceIn(0, colors.lastIndex)
        val rawColorBlend = rotation - colorStage
        val colorBlend = rawColorBlend * rawColorBlend * (3f - 2f * rawColorBlend)
        colors.forEachIndexed { index, _ ->
            val primaryPhase = elapsed.toDouble() / AmbientOverlaySpec.PERIOD_MS[index] * 2.0 * PI + AmbientOverlaySpec.PHASE[index]
            val secondaryPhase = elapsed.toDouble() / (AmbientOverlaySpec.PERIOD_MS[index] * 1.7) * 2.0 * PI + index
            val wave = (sin(primaryPhase) * .72 + sin(secondaryPhase) * .28).toFloat()
            val centerX = width * (AmbientOverlaySpec.BASE_X[index] + wave * AmbientOverlaySpec.RANGE_X[index])
            val centerY = effectHeight * (.06f + .14f * sin(primaryPhase * .73 + index).toFloat())
            val pulse = (.5f + .5f * sin(primaryPhase * .61 + index * .8)).toFloat()
            val shape = (.5f + .5f * sin(primaryPhase * .91 + secondaryPhase * .37)).toFloat()
            val radius = width * (.185f + pulse * .070f)
            val scaleX = .84f + shape * .34f
            val requestedScaleY = .21f + pulse * .30f
            val scaleY = requestedScaleY.coerceAtMost(
                ((height - centerY).coerceAtLeast(1f) / radius * .94f).coerceAtLeast(.08f),
            )
            val sourceColor = lerpColor(
                colors[(index + colorStage) % colors.size],
                colors[(index + colorStage + 1) % colors.size],
                colorBlend,
            )
            val color = animatedTone(sourceColor, pulse)
            val alpha = (AmbientOverlaySpec.CORE_ALPHA[index] * (.72f + pulse * .28f)).toInt()
            // A low-luminance chromatic bed makes the light readable on white and pastel apps.
            // On an already dark surface it contributes almost no visible brightness, preserving
            // the existing dark-screen appearance while increasing contrast only where needed.
            val contrastRadius = radius * 1.08f
            val contrastAlpha = (92f * (.76f + pulse * .24f)).toInt()
            val contrastColor = contrastTone(sourceColor)
            paint.shader = RadialGradient(
                centerX,
                centerY,
                contrastRadius,
                intArrayOf(
                    withAlpha(contrastColor, contrastAlpha),
                    withAlpha(contrastColor, contrastAlpha / 2),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, .52f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.save()
            canvas.scale(scaleX * 1.04f, scaleY * 1.08f, centerX, centerY)
            canvas.drawCircle(centerX, centerY, contrastRadius, paint)
            canvas.restore()
            paint.shader = RadialGradient(
                centerX,
                centerY,
                radius,
                intArrayOf(
                    withAlpha(color, alpha),
                    withAlpha(color, alpha / 2),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, .43f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.save()
            canvas.scale(scaleX, scaleY, centerX, centerY)
            canvas.drawCircle(centerX, centerY, radius, paint)
            canvas.restore()
        }
        paint.shader = null
        removeCallbacks(frame)
        if (motionEnabled) postDelayed(frame, AmbientOverlaySpec.FRAME_DELAY_MS)
    }

    private val frame = Runnable { if (running) invalidate() }

    private fun displayedColors(now: Long): IntArray {
        if (transitionStartMs == 0L) return targetColors.copyOf()
        val progress = ((now - transitionStartMs).toFloat() / AmbientOverlaySpec.PALETTE_TRANSITION_MS).coerceIn(0f, 1f)
        val eased = progress * progress * (3f - 2f * progress)
        return IntArray(3) { index -> lerpColor(fromColors[index], targetColors[index], eased) }
    }

    private fun animatedTone(color: Int, phase: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        if (hsv[1] > .05f) {
            hsv[1] = (hsv[1] * (.82f + phase * .25f) + .04f + phase * .05f).coerceIn(.12f, .86f)
        }
        hsv[2] = (hsv[2] * (.74f + phase * .34f)).coerceIn(.18f, .98f)
        return Color.HSVToColor(hsv)
    }

    private fun contrastTone(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = (hsv[1] * 1.12f + .12f).coerceIn(.30f, .90f)
        hsv[2] = (hsv[2] * .20f).coerceIn(.08f, .16f)
        return Color.HSVToColor(hsv)
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun lerpColor(first: Int, second: Int, fraction: Float): Int = Color.rgb(
        (Color.red(first) + (Color.red(second) - Color.red(first)) * fraction).toInt(),
        (Color.green(first) + (Color.green(second) - Color.green(first)) * fraction).toInt(),
        (Color.blue(first) + (Color.blue(second) - Color.blue(first)) * fraction).toInt(),
    )
}
