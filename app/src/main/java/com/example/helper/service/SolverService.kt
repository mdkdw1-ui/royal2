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

    /**
     * 🎯 [핵심 추가] 안드로이드 스튜디오 없이 핸드폰 화면에 바로 에러를 띄우기 위한 헬퍼 함수
     */
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
            
            showOverlayToast("✅ 1단계: 제어판 UI 활성화")

        } catch (e: Exception) {
            showOverlayToast("❌ UI 초기화 에러: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("RESULT_CODE", -1) ?: -1
        val resultData = intent?.getParcelableExtra<Intent>("RESULT_DATA")

        if (resultCode == -1 || resultData == null) {
            // 단순 서비스 재시작일 때는 무시
            return START_NOT_STICKY
        }

        startForegroundServiceWithNotification()

        try {
            mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
            
            if (mediaProjection == null) {
                showOverlayToast("❌ 2단계 실패: MediaProjection 토큰 무효화")
                stopSelf()
                return START_NOT_STICKY
            }

            showOverlayToast("✅ 2단계: 캡처 엔진 토큰 확보")
            startCaptureAndAnalysisLoop()
            
        } catch (e: Exception) {
            showOverlayToast("❌ 엔진 시동 크래시: ${e.message}")
        }

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
            
            // 이 토스트가 뜨면 시스템 레벨에서 화면 캡처 통로가 완전히 뚫린 것입니다.
            showOverlayToast("🚀 3단계 최종 성공: 실시간 캡처 가동 시작!")
            
        } catch (e: Exception) {
            showOverlayToast("❌ 3단계 가상 디스플레이 생성 실패: ${e.message}")
            return
        }

        // 첫 프레임 수신 여부 확인용 플래그
        var isFirstFrameCaptured = false

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                try {
                    if (!isFirstFrameCaptured) {
                        // 최초 1회만 화면 데이터 유입 성공 토스트를 띄웁니다.
                        showOverlayToast("📸 [실시간 프레임 스트리밍 중]")
                        isFirstFrameCaptured = true
                    }

                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(buffer)

                    // TODO: 이 아래에 OpenCV 분석 및 힌트 연동 코드 배치

                    bitmap.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "픽셀 에러", e)
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
            showOverlayToast("🛑 서비스 종료 및 자원 해제 완료")
        } catch (e: Exception) {
            Log.e(TAG, "종료 에러", e)
        }
    }
}
