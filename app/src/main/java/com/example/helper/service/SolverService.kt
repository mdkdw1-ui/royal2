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
import android.view.Gravity
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
    private var panelView: View? = null
    private var gridOverlayView: GridOverlayView? = null
    private lateinit var tvGridInfo: TextView

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var isLogicEnabled = true

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

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val inflater = LayoutInflater.from(this)

            // 🎯 [핵심 설계 변경 1] 화면 전체를 덮는 완벽한 터치 관통형(FLAG_NOT_TOUCHABLE) 격자 레이어 생성
            val gridParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            gridOverlayView = inflater.inflate(R.layout.grid_overlay_layout, null) as GridOverlayView
            windowManager.addView(gridOverlayView, gridParams)

            // 🎯 [핵심 설계 변경 2] 상단에만 작게 붙어 터치가 작동하는 제어판 레이어 생성 (WRAP_CONTENT)
            val panelParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            panelParams.gravity = Gravity.TOP
            panelParams.y = 100 // 상단 바 알림 영역을 가리지 않도록 약간 아래 배치

            panelView = inflater.inflate(R.layout.control_panel_layout, null)
            windowManager.addView(panelView, panelParams)
            
            val pView = panelView ?: return
            tvGridInfo = pView.findViewById(R.id.tvGridInfo)

            initControlPanelListeners(pView)
            updateInfoText()

        } catch (e: Exception) {
            Log.e(TAG, "이중 오버레이 윈도우 생성 실패", e)
        }
    }

    private fun initControlPanelListeners(view: View) {
        val btnToggleLogic = view.findViewById<Button>(R.id.btnToggleLogic)
        val btnToggleGrid = view.findViewById<Button>(R.id.btnToggleGrid)
        val btnKillService = view.findViewById<Button>(R.id.btnKillService)

        btnToggleLogic.setOnClickListener {
            isLogicEnabled = !isLogicEnabled
            gridOverlayView?.let { gov ->
                if (isLogicEnabled) {
                    btnToggleLogic.text = "로직: ON"
                    btnToggleLogic.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
                    gov.showMatchHints = true
                } else {
                    btnToggleLogic.text = "로직: OFF"
                    btnToggleLogic.setBackgroundColor(android.graphics.Color.parseColor("#757575"))
                    gov.showMatchHints = false
                }
                gov.invalidate()
            }
        }

        btnToggleGrid.setOnClickListener {
            gridOverlayView?.let { gov ->
                gov.showGridLines = !gov.showGridLines
                btnToggleGrid.text = if (gov.showGridLines) "격자 숨기기" else "격자 보이기"
                gov.invalidate()
            }
        }

        btnKillService.setOnClickListener {
            showOverlayToast("🛑 서비스를 강제 종료합니다.")
            stopSelf()
        }

        // 위치 및 크기 조절 시 터치 패널 관통 레이어 리프레시 연동
        view.findViewById<Button>(R.id.btnMoveUp).setOnClickListener { gridOverlayView?.let { it.offsetY -= 15; it.invalidate() } }
        view.findViewById<Button>(R.id.btnMoveDown).setOnClickListener { gridOverlayView?.let { it.offsetY += 15; it.invalidate() } }
        view.findViewById<Button>(R.id.btnMoveLeft).setOnClickListener { gridOverlayView?.let { it.offsetX -= 15; it.invalidate() } }
        view.findViewById<Button>(R.id.btnMoveRight).setOnClickListener { gridOverlayView?.let { it.offsetX += 15; it.invalidate() } }
        
        view.findViewById<Button>(R.id.btnSizePlus).setOnClickListener { gridOverlayView?.let { it.gridSize += 20; it.invalidate() } }
        view.findViewById<Button>(R.id.btnSizeMinus).setOnClickListener { gridOverlayView?.let { it.gridSize -= 20; it.invalidate() } }

        view.findViewById<Button>(R.id.btnRowPlus).setOnClickListener { gridOverlayView?.let { it.rows++; updateInfoText(); it.invalidate() } }
        view.findViewById<Button>(R.id.btnRowMinus).setOnClickListener { gridOverlayView?.let { if(it.rows > 1) it.rows--; updateInfoText(); it.invalidate() } }
        view.findViewById<Button>(R.id.btnColPlus).setOnClickListener { gridOverlayView?.let { it.cols++; updateInfoText(); it.invalidate() } }
        view.findViewById<Button>(R.id.btnColMinus).setOnClickListener { gridOverlayView?.let { if(it.cols > 1) it.cols--; updateInfoText(); it.invalidate() } }
    }

    private fun updateInfoText() {
        gridOverlayView?.let {
            tvGridInfo.text = "칸수 설정: ${it.rows}행 x ${it.cols}열"
        }
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
                if (isLogicEnabled) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(buffer)

                    // [실시간 격자 동기화 연산 구역]
                    // gridOverlayView가 독립 실행되므로 메인 데이터 동기화 코드가 이 위치로 들어가게 됩니다.

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
        
        // 두 개의 분리된 뷰를 윈도우 매니저에서 완전히 해제
        panelView?.let { windowManager.removeView(it) }
        gridOverlayView?.let { windowManager.removeView(it) }
    }
}
