package com.example.helper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.example.helper.core.GridDetector
import com.example.helper.core.Match3Solver
import com.example.helper.core.GameConfig

class SolverService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private lateinit var windowManager: WindowManager
    private var overlayView: ImageView? = null
    
    private val handler = Handler(Looper.getMainLooper())
    private val channelId = "solver_service_channel"
    private val notificationId = 8888

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        // 안드로이드 정책 상 포그라운드 서비스는 즉시 알림을 띄워야 합니다.
        startForeground(notificationId, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode != -1 && data != null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
            
            // 화면 캡처 및 분석 루프 시작
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

        // 실시간 화면 스냅샷을 받아올 버퍼 생성
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "SolverCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        // 1초 주기로 화면을 분석하는 캡처 테스크 반복 실행
        handler.post(object : Runnable {
            override fun run() {
                processCurrentFrame()
                handler.postDelayed(this, 1000) // 1000ms = 1초 간격
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

            // 1. 화면 Image 버퍼 데이터를 비트맵으로 변환
            val bitmap = Bitmap.createBitmap(
                width + (rowStride - pixelStride * width) / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // 2. [핵심] 방금 추가한 GridDetector를 이용해 7x9 격자 추출 및 구멍(HOLE) 우회 분석
            val gridResult = GridDetector.getGridDataFromBitmap(bitmap)
            
            if (gridResult.success && gridResult.categoryGrid != null) {
                // 3. 알고리즘 엔진(Match3Solver)에 비정형 정밀 격자 배열 주입하여 최적의 해 구하기
                val bestMoves = Match3Solver.getMatchCandidates(gridResult.categoryGrid)
                
                if (bestMoves.isNotEmpty()) {
                    // 4. 찾은 해가 있다면 화면 위에 오버레이 화살표 표시
                    val topMove = bestMoves[0] 
                    // TODO: UI 오버레이 레이어에 topMove 가리키는 화살표 드로잉 처리 가능
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
                "Match-3 Solver 백그라운드 구동 채널",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Match-3 Solver 가 작동 중입니다")
            .setContentText("실시간으로 화면 리소스를 분석하고 있습니다.")
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
