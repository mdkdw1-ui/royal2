package com.example.helper.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.example.helper.MainActivity

class SolverService : Service() {
    private val TAG = "SolverService"
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    override fun onCreate() {
        super.onCreate()
        val channelId = "solver_service"
        val channel = NotificationChannel(channelId, "Solver", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("화면 분석 실행 중")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = IntentCompat.getParcelableExtra(intent!!, "data", Intent::class.java)

        if (resultCode != -1 && data != null) {
            startCapture(resultCode, data)
        }
        return START_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, data)

        handlerThread = HandlerThread("CaptureThread").apply { start() }
        handler = Handler(handlerThread!!.looper)

        val metrics = resources.displayMetrics
        imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)

        mediaProjection?.createVirtualDisplay(
            "Solver", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, handler
        )
        Log.d(TAG, "캡처 엔진 가동 완료")
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        mediaProjection?.stop()
        imageReader?.close()
        handlerThread?.quitSafely()
        super.onDestroy()
    }
}
