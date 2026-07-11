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
            // 1. 안드로이드 14+ 대응 포그라운드 서비스 시작
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        notificationId, 
                        createNotification(), 
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(notificationId, createNotification())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 2. 미디어 프로젝션 토큰 가져오기
            try {
                val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpManager.getMediaProjection(resultCode, data)
                
                // 3. 화면 캡처 루프 진입
                startScreenCaptureLoop()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        return START_NOT_STICKY
    }

    private fun startScreenCaptureLoop() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        
        // [핵심 조치] 메모리 부족(OOM)으로 인한 튕김을 원천 차단하기 위해 해상도를 50%로 낮춰 캡처합니다.
        // GridDetector는 비율 기반으로 작동하므로 해상도가 낮아져도 정확하게 작동합니다.
        val width = metrics.widthPixels / 2
        val height = metrics.heightPixels / 2
        val density = metrics.densityDpi / 2

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        
        try {
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "SolverCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }

        // 1초 주기로 분석 실행
        handler.post(object : Runnable {
            override fun run() {
                processCurrentFrame()
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun processCurrentFrame() {
        val reader = imageReader ?: return
        
        // 이미지 획득 실패 시 안전하게 리턴
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Throwable) {
            null
        } ?: return

        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val width = image.width
            val height = image.height

            // 가로 패딩 여백을 계산하여 정확한 비트맵 크기 설정 (기기별 버퍼 크기 미스매치 방지)
            val cleanWidth = width + (rowStride - pixelStride * width) / pixelStride
            if (cleanWidth <= 0 || height <= 0) return

            // 비트맵 생성 및 픽셀 복사
            val bitmap = Bitmap.createBitmap(cleanWidth, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            // 격자 탐지 진행
            val gridResult = GridDetector.getGridDataFromBitmap(bitmap)
            
            if (gridResult.success && gridResult.categoryGrid != null) {
                val bestMoves = Match3Solver.getMatchCandidates(gridResult.categoryGrid)
                if (bestMoves.isNotEmpty()) {
                    // 계산 알고리즘 성공 (추후 로그나 오버레이 UI 반영)
                }
            }
            bitmap.recycle() // 메모리 해제
        } catch (t: Throwable) {
            // [중요] Exception뿐만 아니라 OutOfMemoryError 같은 시스템 에러까지 catch하여 앱이 터지는 것을 방지
            t.printStackTrace()
        } finally {
            try {
                image.close()
            } catch (e: Throwable) {
                e.printStackTrace()
            }
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
            .setContentText("화면 분석을 백그라운드에서 진행하고 있습니다.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
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
