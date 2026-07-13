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
import android.os.IBinder
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
import com.example.helper.core.Match3Solver
import com.example.helper.ui.GridOverlayView

class SolverService : Service() {

    companion object {
        private const val TAG = "SolverService_Error"
        private const val NOTIFICATION_ID = 9999
        private const val CHANNEL_ID = "ScreenCaptureChannel"
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private lateinit var gridOverlayView: GridOverlayView

    // 화면 공유 관련 변수들
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "[단계 1] SolverService onCreate 시작")

        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "실패: 다른 앱 위에 표시 권한 없음")
            stopSelf()
            return
        }

        // 오버레이 UI 뷰 생성
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            floatingView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
            val view = floatingView ?: throw NullPointerException("레이아웃 인플레이트 실패")

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
            Log.d(TAG, "[단계 2] 제어판 오버레이 뷰 윈도우 등록 성공")

        } catch (e: Exception) {
            Log.e(TAG, "오버레이 뷰 초기화 중 예외 발생", e)
        }
    }

    /**
     * MainActivity가 보낸 화면공유 승인 토큰 데이터(Intent)를 받는 곳
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "[단계 3] onStartCommand 진입")
        
        val resultCode = intent?.getIntExtra("RESULT_CODE", -1) ?: -1
        val resultData = intent?.getParcelableExtra<Intent>("RESULT_DATA")

        if (resultCode == -1 || resultData == null) {
            Log.e(TAG, "실패: MainActivity로부터 권한 데이터(ResultData)를 전달받지 못했습니다.")
            return START_NOT_STICKY
        }

        // 화면 공유를 작동하기 위해 상단바 알림(포그라운드 서비스) 강제 실행
        startForegroundServiceWithNotification()

        // 실제 화면 캡처 엔진 시동
        try {
            mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
            Log.d(TAG, "[단계 4] MediaProjection 엔진 객체 확보 성공")

            startCaptureAndAnalysisLoop()
        } catch (e: Exception) {
            Log.e(TAG, "MediaProjection 시동 중 에러 발생", e)
        }

        return START_STICKY
    }

    /**
     * 안드로이드 시스템 제한을 풀기 위한 상단 노티피케이션 활성화
     */
    private fun startForegroundServiceWithNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "매칭 헬퍼 공유 서비스", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("매칭 헬퍼가 실행 중입니다")
            .setContentText("실시간으로 화면을 분석하여 힌트를 계산합니다.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10 이상은 mediaProjection 타입을 명시해야 리젝/팅김이 없습니다.
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.d(TAG, "포그라운드 서비스 등록 완료 (상단바 알림 활성화)")
    }

    /**
     * [핵심] 실시간 가상 디스플레이를 열어 화면을 비트맵으로 찍어내는 루프
     */
    private fun startCaptureAndAnalysisLoop() {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        // 화면을 픽셀 스트림 데이터로 읽어오는 장치 세팅
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        
        try {
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
            Log.d(TAG, "[단계 5] 가상 디스플레이(VirtualDisplay) 연결 완료 -> 실시간 캡처 시작됨")
        } catch (e: Exception) {
            Log.e(TAG, "VirtualDisplay 생성 실패", e)
            return
        }

        // 화면에 변화가 생길 때마다 호출되는 리스너 등록
        imageReader?.setOnImageAvailableListener({ reader ->
            var image = reader.acquireLatestImage()
            if (image != null) {
                try {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    // 1. 현재 화면 스크린샷 비트맵으로 변환 완료
                    val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(buffer)

                    // ----------------------------------------------------
                    // 2. [연동 분석] 여기서 기존 분석 코드와 매칭 연동을 진행합니다.
                    // ----------------------------------------------------
                    // val currentGrid = GridDetector.detectGrid(bitmap) // 오차분석
                    // val candidates = Match3Solver.getMatchCandidates(currentGrid)
                    // gridOverlayView.updateData(currentGrid.size, currentGrid[0].size, candidates)
                    
                    // 정기적인 리소스 소모 방지를 위해 비트맵은 쓰고 나서 바로 해제해 줍니다.
                    bitmap.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "화면 픽셀 분석 중 에러 발생", e)
                } finally {
                    image.close() // 반드시 닫아주어야 다음 프레임이 찍힙니다.
                }
            }
        }, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "SolverService 서비스 종료 및 자원 해제")
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            floatingView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "자원 해제 중 예외 발생", e)
        }
    }
}
