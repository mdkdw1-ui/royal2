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
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.helper.R
import com.example.helper.ui.GridOverlayView

class SolverService : Service() {

    companion object {
        private const val TAG = "SolverService_Trace"
        private const val NOTIFICATION_ID = 9999
        private const val CHANNEL_ID = "ScreenCaptureChannel"
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private lateinit var gridOverlayView: GridOverlayView

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private fun showOverlayToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        
        if (!Settings.canDrawOverlays(this)) {
            showOverlayToast("⚠️ 실패: 다른 앱 위에 표시 권한 없음")
            stopSelf()
            return
        }

        backgroundThread = HandlerThread("ScreenCaptureThread").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            floatingView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
            val view = floatingView ?: throw NullPointerException("오버레이 레이아웃 인플레이트 실패")

            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )

            windowManager.addView(view, layoutParams)
            
            gridOverlayView = view.findViewById(R.id.gridOverlayView)
            val btnToggleGrid = view.findViewById<Button>(R.id.btnToggleGrid)
            val btnToggleHints = view.findViewById<Button>(R.id.btnToggleHints)

            btnToggleGrid.setOnClickListener {
                gridOverlayView.showGridLines = !gridOverlayView.showGridLines
                btnToggleGrid.text = if (gridOverlayView.showGridLines) "격자 숨기기" else "격자 보이기"
            }

            btnToggleHints.setOnClickListener {
                gridOverlayView.showMatchHints = !gridOverlayView.showMatchHints
                btnToggleHints.text = if (gridOverlayView.showMatchHints) "힌트 숨기기" else "힌트 보이기"
            }
            
            showOverlayToast("✅ 1단계: 제어판 UI 활성화 완료")

        } catch (e: Exception) {
            showOverlayToast("❌ UI 초기화 에러: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 🎯 진입 여부를 무조건 화면에 표시합니다.
        showOverlayToast("📥 서비스 신호 수신됨 (onStartCommand)")

        if (intent == null) {
            showOverlayToast("❌ 에러: 전달된 Intent 데이터가 완전히 비어있음 (Null)")
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra("RESULT_CODE", -1)
        
        // 🎯 [핵심 수정] 안드로이드 13(API 33) 이상 버전에 대응하는 안전한 방식으로 데이터 추출
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("RESULT_DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>("RESULT_DATA")
        }

        // 데이터가 누락되었는지 유저가 화면에서 즉시 인지할 수 있도록 토스트 유도
        showOverlayToast("📦 데이터 검증 -> Code: $resultCode, Data Null 여부: ${resultData == null}")

        if (resultCode == -1 || resultData == null) {
            showOverlayToast("❌ 에러: 권한 토큰 데이터 추출 실패로 가동 중단")
            return START_NOT_STICKY
        }

        // 1. 포그라운드 알림창 선제 가동 (안드로이드 시스템 요구사항)
        startForegroundServiceWithNotification()

        // 2. 캡처 엔진 가동
        // 🎯 [핵심 수정] 포그라운드 서비스가 커널에 완전히 등록될 시간을 벌어주기 위해 0.2초의 유예를 둡니다. (안드로이드 14 필수 대응)
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
                
                if (mediaProjection == null) {
                    showOverlayToast("❌ 2단계 실패: 시스템이 MediaProjection 토큰 발급을 거부함")
                    stopSelf()
                } else {
                    showOverlayToast("✅ 2단계: 캡처 엔진 연동 성공!")
                    startCaptureAndAnalysisLoop()
                }
            } catch (e: Exception) {
                showOverlayToast("❌ 엔진 가동 중 커널 크래시: ${e.message}")
            }
        }, 200)

        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "매칭 헬퍼", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("매칭 헬퍼 가동 중")
            .setContentText("화면 분석 대기 중")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCaptureAndAnalysisLoop() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        
        try {
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
            showOverlayToast("🚀 3단계 최종 가동: 실시간 화면 공유 시작됨!")
        } catch (e: Exception) {
            showOverlayToast("❌ 3단계 가상 디스플레이 생성 실패: ${e.message}")
            return
        }

        var isFirstFrameCaptured = false

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                try {
                    if (!isFirstFrameCaptured) {
                        showOverlayToast("📸 [실시간 프레임 동기화 완료]")
                        isFirstFrameCaptured = true
                    }

                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(buffer)

                    // 여기에 이미지 분석 알고리즘 추가

                    bitmap.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "픽셀 변환 실패", e)
                } finally {
                    image.close()
                }
            }
        }, backgroundHandler)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            backgroundThread?.quitSafely()
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            floatingView?.let { windowManager.removeView(it) }
            showOverlayToast("🛑 서비스 완전 종료")
        } catch (e: Exception) {
            Log.e(TAG, "종료 에러", e)
        }
    }
}
