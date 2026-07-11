package com.example.helper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.helper.core.GridDetector
import com.example.helper.core.Match3Solver

class SolverService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val channelId = "solver_service_channel"
    private val notificationId = 8888

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode != -1 && data != null) {
            // 안드로이드 14 이상 대응: 화면 캡처 권한 토큰이 확보된 이 시점에 포그라운드 서비스 유형을 명시하여 시작
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    notificationId, 
                    createNotification(), 
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(notificationId, createNotification())
            }

            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
            
            startScreenCaptureLoop()
        }
        
        return START_NOT_STICKY
    }

    private fun startScreenCaptureLoop() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "SolverCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        handler.post(object : Runnable {
            override fun run() {
                processCurrentFrame()
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun processCurrentFrame() {
        val reader = imageReader ?: return
        val image = reader.acquireLatestImage() ?: return

        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val width = image.width
            val height = image.height

            val bitmap = Bitmap.createBitmap(
                width + (rowStride - pixelStride * width) / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            val gridResult = GridDetector.getGridDataFromBitmap(bitmap)
            
            if (gridResult.success && gridResult.categoryGrid != null) {
                val bestMoves = Match3Solver.getMatchCandidates(gridResult.categoryGrid)
                if (bestMoves.isNotEmpty()) {
                    // 추후 UI 화살표 렌더링 로직 들어갈 자리
                }
            }
            bitmap.recycle()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Match-3 Solver Running",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Match-3 Solver가 실행 중입니다")
            .setContentText("화면 분석을 진행하고 있습니다.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
    }
}
