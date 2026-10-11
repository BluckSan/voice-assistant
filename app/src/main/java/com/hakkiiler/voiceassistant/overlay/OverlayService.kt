package com.hakkiiler.voiceassistant.overlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import com.hakkiiler.voiceassistant.R
import com.hakkiiler.voiceassistant.di.AppContainer
import com.hakkiiler.voiceassistant.domain.model.AssistantStatus
import com.hakkiiler.voiceassistant.service.AssistantService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Плавающая кнопка поверх других приложений: нажал — ассистент слушает команду.
 * Цвет показывает состояние (серый — выключен, синий — ждёт активатор, зелёный — слушает, оранжевый — говорит).
 * Разрешение «поверх других окон» также позволяет сервису открывать приложения из фона.
 */
class OverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wm: WindowManager
    private var bubble: ImageView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!canDraw(this)) { stopSelf(); return }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        addBubble()
        scope.launch {
            AppContainer.from(this@OverlayService).state.collect { s -> tint(s.status) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val size = (56 * resources.displayMetrics.density).toInt()
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = 400 }

        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            val pad = size / 4
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(COLOR_IDLE) }
            elevation = 8f
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        view.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).toInt(); lp.y = startY + (e.rawY - downY).toInt()
                    wm.updateViewLayout(v, lp); true
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(e.rawX - downX) < 15 && abs(e.rawY - downY) < 15) {
                        AssistantService.start(this, listenNow = true)
                    }
                    true
                }
                else -> false
            }
        }
        wm.addView(view, lp)
        bubble = view
    }

    private fun tint(s: AssistantStatus) {
        val color = when (s) {
            AssistantStatus.WAITING_WAKE_WORD -> COLOR_WAITING
            AssistantStatus.LISTENING, AssistantStatus.PROCESSING -> COLOR_LISTENING
            AssistantStatus.SPEAKING -> COLOR_SPEAKING
            AssistantStatus.ERROR -> COLOR_ERROR
            else -> COLOR_IDLE
        }
        (bubble?.background as? GradientDrawable)?.setColor(color)
    }

    override fun onDestroy() {
        scope.cancel()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        super.onDestroy()
    }

    companion object {
        private const val COLOR_IDLE = 0xFF607D8B.toInt()
        private const val COLOR_WAITING = 0xFF3F51B5.toInt()
        private const val COLOR_LISTENING = 0xFF43A047.toInt()
        private const val COLOR_SPEAKING = 0xFFFB8C00.toInt()
        private const val COLOR_ERROR = 0xFFE53935.toInt()

        fun canDraw(context: Context) = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)
        fun start(context: Context) = context.startService(Intent(context, OverlayService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, OverlayService::class.java))
    }
}
