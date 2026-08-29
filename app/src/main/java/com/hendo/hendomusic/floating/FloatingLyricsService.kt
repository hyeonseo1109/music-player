package com.hendo.hendomusic.floating

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Point
import android.graphics.PixelFormat
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.hendo.hendomusic.LuminaraApplication
import com.hendo.hendomusic.playback.PlaybackService
import com.hendo.hendomusic.lyrics.LrcCodec
import com.hendo.hendomusic.lyrics.SyncedLyricLine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.math.abs

class FloatingLyricsService : Service() {
    private lateinit var windowManager: WindowManager
    private var root: LinearLayout? = null
    private var controller: MediaController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var params: WindowManager.LayoutParams
    private var expanded = false
    private var currentTrackId: String? = null
    private var plainLyrics = ""
    private var syncedLyrics: List<SyncedLyricLine> = emptyList()
    private var floatingLineCount = 2

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        scope.launch { createOverlay() }
        scope.launch {
            while (isActive) { refresh(); delay(250) }
        }
        MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync().also { future ->
            future.addListener({ runCatching { future.get() }.onSuccess { c -> controller = c; c.addListener(listener); refresh() } }, mainExecutor)
        }
    }

    private val listener = object : Player.Listener { override fun onEvents(player: Player, events: Player.Events) = refresh() }

    private suspend fun createOverlay() {
        val settings = (application as LuminaraApplication).container.preferences.settings.first()
        floatingLineCount = settings.floatingLines
        params = WindowManager.LayoutParams(
            620, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = settings.overlayX; y = settings.overlayY }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(22)
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 28f; setColor(Color.argb((settings.floatingAlpha * 255).toInt(), 14, 10, 22)) }
            addView(TextView(context).apply { id = TITLE_ID; setTextColor(Color.WHITE); textSize = settings.floatingFontSize / 2f; gravity = Gravity.CENTER; text = "HendoMusic" })
            addView(TextView(context).apply { id = LYRIC_ID; setTextColor(Color.WHITE); textSize = (settings.floatingFontSize + 1).toFloat(); gravity = Gravity.CENTER; maxLines = floatingLineCount; text = "가사를 불러올 수 없습니다." })
            setOnTouchListener(DragTouchListener())
            setOnClickListener { if (!expanded) expandControls() }
        }
        windowManager.addView(root, params)
    }

    private fun expandControls() {
        val view = root ?: return
        expanded = true
        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(button(android.R.drawable.ic_media_previous) { controller?.seekToPreviousMediaItem() })
            addView(button(if (controller?.isPlaying == true) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play) { controller?.let { if (it.isPlaying) it.pause() else it.play() }; collapseLater() })
            addView(button(android.R.drawable.ic_media_next) { controller?.seekToNextMediaItem() })
            addView(button(android.R.drawable.ic_menu_preferences) {
                startActivity(Intent(this@FloatingLyricsService, com.hendo.hendomusic.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            })
            addView(button(android.R.drawable.ic_menu_close_clear_cancel) { stopSelf() })
        }
        view.addView(controls)
        collapseLater()
    }
    private fun button(icon: Int, click: () -> Unit) = ImageButton(this).apply { setImageResource(icon); setColorFilter(Color.WHITE); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { click() } }
    private fun collapseLater() { root?.postDelayed({ if(expanded && root?.childCount == 3) { root?.removeViewAt(2); expanded = false } }, 5_000) }
    private fun refresh() {
        val player = controller ?: return
        root?.findViewById<TextView>(TITLE_ID)?.text = player.mediaMetadata.title?.toString() ?: "HendoMusic"
        val trackId = player.currentMediaItem?.mediaMetadata?.extras?.getString(PlaybackService.KEY_TRACK_ID)
        if (trackId != currentTrackId) {
            currentTrackId = trackId
            plainLyrics = ""; syncedLyrics = emptyList()
            trackId?.let { id -> scope.launch(Dispatchers.IO) {
                val dao = (application as LuminaraApplication).container.database.dao()
                val lyric = dao.lyrics(id)
                val lines = lyric?.let { dao.lyricLines(it.id) }.orEmpty()
                withContext(Dispatchers.Main) {
                    if (currentTrackId == id) {
                        plainLyrics = lyric?.plainText.orEmpty()
                        syncedLyrics = lines.map { SyncedLyricLine(it.id.toString(), it.startTimeMs, it.text) }
                        updateLyricText(player.currentPosition)
                    }
                }
            } }
        }
        updateLyricText(player.currentPosition)
    }

    private fun updateLyricText(positionMs: Long) {
        val text: CharSequence = if (syncedLyrics.isNotEmpty()) {
            val active = LrcCodec.activeIndex(syncedLyrics, positionMs).coerceAtLeast(0)
            val lines = syncedLyrics.drop(active).take(floatingLineCount).map { it.text }
            SpannableString(lines.joinToString("\n")).apply {
                lines.firstOrNull()?.length?.takeIf { it > 0 }?.let { setSpan(ForegroundColorSpan(Color.rgb(190, 166, 255)), 0, it, 0) }
            }
        } else plainLyrics.lineSequence().filter { it.isNotBlank() }.take(floatingLineCount).joinToString("\n").ifBlank { "가사를 불러올 수 없습니다." }
        root?.findViewById<TextView>(LYRIC_ID)?.text = text
    }

    private fun clampPosition() {
        val view = root ?: return
        val (width, height) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            Point().also { windowManager.defaultDisplay.getSize(it) }.let { it.x to it.y }
        }
        params.x = params.x.coerceIn(0, (width - view.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (height - view.height).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when(e.action) {
                MotionEvent.ACTION_DOWN -> { downX=e.rawX; downY=e.rawY; startX=params.x; startY=params.y; return true }
                MotionEvent.ACTION_MOVE -> { params.x=(startX+e.rawX-downX).toInt(); params.y=(startY+e.rawY-downY).toInt(); clampPosition(); return true }
                MotionEvent.ACTION_UP -> { clampPosition(); scope.launch { (application as LuminaraApplication).container.preferences.setOverlayPosition(params.x, params.y) }; if(abs(e.rawX-downX)<12 && abs(e.rawY-downY)<12) v.performClick(); return true }
            }; return false
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); root?.post(::clampPosition) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { controller?.removeListener(listener); controller?.release(); root?.let { runCatching { windowManager.removeView(it) } }; scope.cancel(); super.onDestroy() }
    companion object { const val TITLE_ID = 0x101010; const val LYRIC_ID = 0x101011 }
}
