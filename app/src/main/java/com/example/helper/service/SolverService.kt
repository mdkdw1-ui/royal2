package com.example.helper.service

import android.app.Activity
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
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.helper.R
import com.example.helper.ui.GridOverlayView

class SolverService : Service() {

    companion object {
        private const val TAG = "SolverService_Core"
        private const val NOTIFICATION_ID = 8888
        private const val CHANNEL_ID = "ScreenCaptureChannel"
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private lateinit var gridOverlayView: GridOverlayView
    private lateinit var tvGridInfo: TextView

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var isLogicEnabled = true // 로직 온오프 스위치 상태 변수

    private fun showOverlayToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        backgroundThread = HandlerThread("ScreenCaptureThread").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        // 제어판 인플레이트 및 윈도우 등록
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            floatingView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
            val view = floatingView ?: return

            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )

            windowManager.addView(view, layoutParams)
            
            gridOverlayView = view.findViewById(R.id.gridOverlayView)
            tvGridInfo = view.findViewById(R.id.tvGridInfo)

            // 🎯 조작부 리스너 연결 바인딩
            initControlPanelListeners(view)
            updateInfoText()

        } catch (e: Exception) {
            Log.e(TAG, "UI 렌더링 도중 크래시 발생", e)
        }
    }

    private fun initControlPanelListeners(view: View) {
        val btnToggleLogic = view.findViewById<Button>(R.id.btnToggleLogic)
        val btnToggleGrid = view.findViewById<Button>(R.id.btnToggleGrid)
        val btnKillService = view.findViewById<Button>(R.id.btnKillService)

        // 로직 ON/OFF 토글
        btnToggleLogic.setOnClickListener {
            isLogicEnabled = !isLogicEnabled
            if (isLogicEnabled) {
                btnToggleLogic.text = "로직: ON"
                btnToggleLogic.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
                gridOverlayView.showMatchHints = true
            } else {
                btnToggleLogic.text = "로직: OFF"
                btnToggleLogic.setBackgroundColor(android.graphics.Color.parseColor("#757575"))
                gridOverlayView.showMatchHints = false
            }
            gridOverlayView.invalidate()
        }

        // 격자선 숨기기/보이기 토글
        btnToggleGrid.setOnClickListener {
            gridOverlayView.showGridLines = !gridOverlayView.showGridLines
            btnToggleGrid.text = if (gridOverlayView.showGridLines) "격자 숨기기" else "격자 보이기"
            gridOverlayView.invalidate()
        }

        // 킬 스위치 (즉시 완전 종료)
        btnKillService.setOnClickListener {
            showOverlayToast("🛑 서비스를 강제 종료합니다.")
            stopSelf()
        }

        // 위치 및 크기 세부 조정 조이스틱 리스너 (클릭당 15픽셀씩 이동 및 세팅)
        view.findViewById<Button>(R.id.btnMoveUp).setOnClickListener { gridOverlayView.offsetY -= 15; gridOverlayView.invalidate() }
        view.findViewById<Button>(R.id.btnMoveDown).setOnClickListener { gridOverlayView.offsetY += 15; gridOverlayView.invalidate() }
        view.findViewById<Button>(R.id.btnMoveLeft).setOnClickListener { gridOverlayView.offsetX -= 15; gridOverlayView.invalidate() }
        view.findViewById<Button>(R.id.btnMoveRight).setOnClickListener { gridOverlayView.offsetX += 15; gridOverlayView.invalidate() }
        
        view.findViewById<Button>(R.id.btnSizePlus).setOnClickListener { gridOverlayView.gridSize += 20; gridOverlayView.invalidate() }
        view.findViewById<Button>(R.id.btnSizeMinus).setOnClickListener { gridOverlayView.gridSize -= 20; gridOverlayView.invalidate() }

        // 칸수(행/열) 증감 리스너
        view.findViewById<Button>(R.id.btnRowPlus).setOnClickListener { gridOverlayView.rows++; updateInfoText(); gridOverlayView.invalidate() }
        view.findViewById<Button>(R.id.btnRowMinus).setOnClickListener { if(gridOverlayView.rows > 1) gridOverlayView.rows--; updateInfoText(); gridOverlayView.invalidate() }
        view.findViewById<Button>(R.id.btnColPlus).setOnClickListener { gridOverlayView.cols++; updateInfoText(); gridOverlayView.invalidate() }
        view.findViewById<Button>(R.id.btnColMinus).setOnClickListener { if(gridOverlayView.cols > 1) gridOverlayView.cols--; updateInfoText(); gridOverlayView.invalidate() }
    }

    private fun updateInfoText() {
        tvGridInfo.text = "칸수 설정: ${gridOverlayView.rows}행 x ${gridOverlayView.cols}열"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val resultCode = intent.getIntExtra("RESULT_CODE", -1)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("RESULT_DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>("RESULT_DATA")
        }

        // 🎯 [핵심 버그 수정] -1은 Activity.RESULT_OK 상태이므로, RESULT_OK가 아닐 때 차단하도록 조건을 고쳤습니다!
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            showOverlayToast("❌ 권한 토큰 획득 실패")
            return START_NOT_STICKY
        }

        startForegroundServiceWithNotification()

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
                
                if (mediaProjection == null) {
                    showOverlayToast("❌ 시스템 미디어 서버 거부")
                    stopSelf()
                } else {
                    showOverlayToast("🚀 매칭분석 시스템 가동 시작!")
                    startCaptureAndAnalysisLoop()
                }
            } catch (e: Exception) {
                Log.e(TAG, "미디어 프로젝션 시동 실패", e)
            }
        }, 200)

        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Helper Notification", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("매칭 연산 엔진 가동중")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
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
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                // 🎯 사용자가 '로직 ON' 일때만 OOXOO 알고리즘 가동
                if (isLogicEnabled) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(buffer)

                    // 🛠️ [실시간 격자 동기화 연산 구역]
                    // 여기서 gridOverlayView.offsetX, offsetY, gridSize, rows, cols 값을 기반으로 
                    // 비트맵 상의 매칭 블럭 색상 검출 및 OOXOO 알고리즘 연산을 수행하게 됩니다.

                    bitmap.recycle()
                }
            } catch (e: Exception) {
                Log.e(TAG, "프레임 분석 루프 에러", e)
            } finally {
                image.close()
            }
        }, backgroundHandler)
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundThread?.quitSafely()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        floatingView?.let { windowManager.removeView(it) }
    }
}
