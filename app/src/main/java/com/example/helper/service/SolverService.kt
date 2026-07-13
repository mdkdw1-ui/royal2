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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
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
    
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var gridParams: WindowManager.LayoutParams

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var isLogicEnabled = true
    private var isEditMode = false // ✨ 격자 직접 드래그 조절 모드 플래그

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

            // 1. 격자 오버레이 설정 (기본값은 터치 관통 모드)
            gridParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            gridOverlayView = inflater.inflate(R.layout.grid_overlay_layout, null) as GridOverlayView
            windowManager.addView(gridOverlayView, gridParams)

            // 2. 상단 조작 제어판 레이아웃 설정
            panelParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 120
            }

            panelView = inflater.inflate(R.layout.control_panel_layout, null)
            windowManager.addView(panelView, panelParams)
            
            val pView = panelView ?: return
            tvGridInfo = pView.findViewById(R.id.tvGridInfo)

            initControlPanelListeners(pView)
            setupDirectGridTouchListener() // 드래그 편집 기능 바인딩
            updateInfoText()

        } catch (e: Exception) {
            Log.e(TAG, "이중 오버레이 초기화 실패", e)
        }
    }

    private fun initControlPanelListeners(view: View) {
        val layoutHeader = view.findViewById<LinearLayout>(R.id.layoutHeader)
        val layoutExpandedBody = view.findViewById<LinearLayout>(R.id.layoutExpandedBody)
        val btnMinimize = view.findViewById<Button>(R.id.btnMinimize)
        val btnKillService = view.findViewById<Button>(R.id.btnKillService)
        val btnToggleLogic = view.findViewById<Button>(R.id.btnToggleLogic)
        val btnEditGrid = view.findViewById<Button>(R.id.btnToggleGrid) // 기존 btnToggleGrid를 편집모드 전환용으로 활용

        // 제어판 자체를 상단바 잡고 드래그하여 옮기는 기능
        var pInitialX = 0; var pInitialY = 0; var pTouchX = 0f; var pTouchY = 0f
        layoutHeader.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    pInitialX = panelParams.x; pInitialY = panelParams.y
                    pTouchX = event.rawX; pTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    panelParams.x = pInitialX + (event.rawX - pTouchX).toInt()
                    panelParams.y = pInitialY + (event.rawY - pTouchY).toInt()
                    panelView?.let { windowManager.updateViewLayout(it, panelParams) }
                    true
                }
                else -> false
            }
        }

        // 제어판 숨기기 / 펼치기
        btnMinimize.setOnClickListener {
            if (layoutExpandedBody.visibility == View.VISIBLE) {
                layoutExpandedBody.visibility = View.GONE
                btnMinimize.text = "펼치기 ▼"
            } else {
                layoutExpandedBody.visibility = View.VISIBLE
                btnMinimize.text = "접기 ▲"
            }
            panelView?.let { windowManager.updateViewLayout(it, panelParams) }
        }

        // ✨ 핵심 편의성: 터치식 격자 편집 모드 토글 스위치
        btnEditGrid.text = "격자 고정 상태"
        btnEditGrid.setBackgroundColor(android.graphics.Color.parseColor("#757575"))
        btnEditGrid.setOnClickListener {
            isEditMode = !isEditMode
            if (isEditMode) {
                btnEditGrid.text = "격자 편집중 (터치 조절)"
                btnEditGrid.setBackgroundColor(android.graphics.Color.parseColor("#E91E63"))
                // 격자 레이어가 터치를 받도록 FLAG_NOT_TOUCHABLE 제거 제거
                gridParams.flags = gridParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                showOverlayToast("모서리 근처를 손가락으로 잡고 드래그하세요.")
            } else {
                btnEditGrid.text = "격자 고정 상태"
                btnEditGrid.setBackgroundColor(android.graphics.Color.parseColor("#757575"))
                // 게임 클릭이 통과하도록 다시 관통 플래그 주입
                gridParams.flags = gridParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                showOverlayToast("격자가 고정되었습니다. 게임 플레이 가능!")
            }
            gridOverlayView?.let { windowManager.updateViewLayout(it, gridParams) }
        }

        btnToggleLogic.setOnClickListener {
            isLogicEnabled = !isLogicEnabled
            btnToggleLogic.text = if (isLogicEnabled) "로직: ON" else "로직: OFF"
            btnToggleLogic.setBackgroundColor(android.graphics.Color.parseColor(if (isLogicEnabled) "#4CAF50" else "#757575"))
            gridOverlayView?.let { it.showMatchHints = isLogicEnabled; it.invalidate() }
        }

        btnKillService.setOnClickListener { stopSelf() }

        // 행렬 조절 단추
        view.findViewById<Button>(R.id.btnRowPlus).setOnClickListener { gridOverlayView?.let { it.rows++; updateInfoText(); it.invalidate() } }
        view.findViewById<Button>(R.id.btnRowMinus).setOnClickListener { gridOverlayView?.let { if(it.rows > 1) it.rows--; updateInfoText(); it.invalidate() } }
        view.findViewById<Button>(R.id.btnColPlus).setOnClickListener { gridOverlayView?.let { it.cols++; updateInfoText(); it.invalidate() } }
        view.findViewById<Button>(R.id.btnColMinus).setOnClickListener { gridOverlayView?.let { if(it.cols > 1) it.cols--; updateInfoText(); it.invalidate() } }
    }

    // ✨ 4모서리 터치 드래그 위치 연산 시스템
    private fun setupDirectGridTouchListener() {
        gridOverlayView?.setOnTouchListener { _, event ->
            if (!isEditMode) return@setOnTouchListener false
            
            val gov = gridOverlayView ?: return@setOnTouchListener false
            val x = event.x
            val y = event.y

            when (event.action) {
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_DOWN -> {
                    // 터치한 위치가 4곳의 꼭짓점 중 어느 곳과 가장 가까운지 거리를 계산하여 동적 매핑
                    val distTL = Math.hypot((x - gov.tlX).toDouble(), (y - gov.tlY).toDouble())
                    val distTR = Math.hypot((x - gov.trX).toDouble(), (y - gov.trY).toDouble())
                    val distBL = Math.hypot((x - gov.blX).toDouble(), (y - gov.blY).toDouble())
                    val distBR = Math.hypot((x - gov.brX).toDouble(), (y - gov.brY).toDouble())

                    val minDist = listOf(distTL, distTR, distBL, distBR).minOrNull() ?: return@setOnTouchListener false
                    
                    // 터치 오차범위(150px) 내에 있을 때 자석처럼 달라붙어 이동
                    if (minDist < 150.0) {
                        when (minDist) {
                            distTL -> { gov.tlX = x; gov.tlY = y }
                            distTR -> { gov.trX = x; gov.trY = y }
                            distBL -> { gov.blX = x; gov.blY = y }
                            distBR -> { gov.brX = x; gov.brY = y }
                        }
                        gov.invalidate()
                    }
                }
            }
            true
        }
    }

    private fun updateInfoText() {
        gridOverlayView?.let { tvGridInfo.text = "크기: ${it.rows}행 x ${it.cols}열" }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        val resultCode = intent.getIntExtra("RESULT_CODE", -1)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>("RESULT_DATA")

        if (resultCode != Activity.RESULT_OK || resultData == null) return START_NOT_STICKY
        startForegroundServiceWithNotification()

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
                startCaptureAndAnalysisLoop()
            } catch (e: Exception) { Log.e(TAG, "엔진 가동 실패", e) }
        }, 200)

        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Helper Engine", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("매칭 연산 엔진 가동중").setSmallIcon(android.R.drawable.sym_def_app_icon).build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startCaptureAndAnalysisLoop() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels; val height = metrics.heightPixels; val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (isLogicEnabled && gridOverlayView != null) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(buffer)

                    // -------------------------------------------------------------
                    // 🎯 [이곳에 OOXOO 특화 연산 탑재]
                    // 가상의 보드 색상 맵핑 배열을 스캔했다고 가정 (예시: board[row][col])
                    // -------------------------------------------------------------
                    val rCount = gridOverlayView!!.rows
                    val cCount = gridOverlayView!!.cols
                    
                    // 디버깅용 중앙 녹색 제거! 오직 OOXOO 스캔 결과 리스트만 그리도록 뷰에 전달합니다.
                    val detectedTargets = mutableListOf<android.graphics.Point>()

                    // 1. 가로축 OOXOO 탐색 알고리즘 (A A X A A 탐색)
                    /*
                    for (r in 0 until rCount) {
                        for (c in 0 until cCount - 4) {
                            val color0 = getCellColor(bitmap, r, c)
                            val color1 = getCellColor(bitmap, r, c + 1)
                            val color2 = getCellColor(bitmap, r, c + 2) // 가운데(X)
                            val color3 = getCellColor(bitmap, r, c + 3)
                            val color4 = getCellColor(bitmap, r, c + 4)

                            // OOO, OOOO 필터링: 반드시 0,1,3,4번은 같고 2번은 다른 색이어야 유효함!
                            if (color0 == color1 && color1 == color3 && color3 == color4 && color0 != color2) {
                                // 위나 아래에서 color0과 일치하는 블록을 스와이프해 오면 5연쇄 완성되는 조건 검증
                                if ((r > 0 && getCellColor(bitmap, r - 1, c + 2) == color0) ||
                                    (r < rCount - 1 && getCellColor(bitmap, r + 1, c + 2) == color0)) {
                                    detectedTargets.add(android.graphics.Point(c + 2, r)) // 매칭 유도 중심점 기록
                                }
                            }
                        }
                    }
                    */

                    // 2. 세로축 OOXOO 탐색 알고리즘 동일하게 적용 후 GridOverlayView에 갱신 전달
                    // gridOverlayView?.setMatchHintsList(detectedTargets)

                    bitmap.recycle()
                }
            } catch (e: Exception) { Log.e(TAG, "분석 에러", e) } finally { image.close() }
        }, backgroundHandler)
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundThread?.quitSafely()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        panelView?.let { windowManager.removeView(it) }
        gridOverlayView?.let { windowManager.removeView(it) }
    }
}
