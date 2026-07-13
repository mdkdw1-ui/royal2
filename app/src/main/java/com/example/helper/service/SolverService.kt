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

    private var isEditMode = false 

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { 
            Toast.makeText(this, "❌ 다른 앱 위에 그리기 권한이 없습니다.", Toast.LENGTH_SHORT).show()
            stopSelf()
            return 
        }

        backgroundThread = HandlerThread("ScreenCaptureThread").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val inflater = LayoutInflater.from(this)

            // 1. 격자 오버레이 뷰 설정 (처음엔 터치 불가능 상태)
            gridParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            gridOverlayView = inflater.inflate(R.layout.grid_overlay_layout, null) as GridOverlayView
            windowManager.addView(gridOverlayView, gridParams)

            // 2. 조작 제어판 뷰 설정
            panelParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 150
            }

            panelView = inflater.inflate(R.layout.overlay_layout, null)
            windowManager.addView(panelView, panelParams)
            
            val pView = panelView ?: return
            tvGridInfo = pView.findViewById(R.id.tvGridInfo)

            initControlPanelListeners(pView)
            setupAdvancedIndirectTouchListener() 
            updateInfoText()

        } catch (e: Exception) { 
            handleException("ServiceOnCreate", e) 
        }
    }

    private fun initControlPanelListeners(view: View) {
        val layoutHeader = view.findViewById<LinearLayout>(R.id.layoutHeader)
        val layoutExpandedBody = view.findViewById<LinearLayout>(R.id.layoutExpandedBody)
        val btnMinimize = view.findViewById<Button>(R.id.btnMinimize)
        val btnKillService = view.findViewById<Button>(R.id.btnKillService)
        val btnEditMode = view.findViewById<Button>(R.id.btnEditMode)
        val btnGridVisibility = view.findViewById<Button>(R.id.btnGridVisibility)

        // 제어판 상단 바 드래그 시 패널 이동
        var pInitialX = 0; var pInitialY = 0; var pTouchX = 0f; var pTouchY = 0f
        layoutHeader.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    pInitialX = panelParams.x
                    pInitialY = panelParams.y
                    pTouchX = event.rawX
                    pTouchY = event.rawY
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

        // 격자 선 숨기기 / 보이기 토글 버튼
        btnGridVisibility.setOnClickListener {
            gridOverlayView?.let { gov ->
                gov.showGridLines = !gov.showGridLines
                if (gov.showGridLines) {
                    btnGridVisibility.text = "👁️ 격자 보임"
                    btnGridVisibility.setBackgroundColor(android.graphics.Color.parseColor("#2196F3"))
                } else {
                    btnGridVisibility.text = "👁️ 격자 숨김"
                    btnGridVisibility.setBackgroundColor(android.graphics.Color.parseColor("#E91E63"))
                }
                gov.invalidate()
            }
        }

        // 격자 미세조정 드래그 모드 ON/OFF 토글
        btnEditMode.setOnClickListener {
            isEditMode = !isEditMode
            if (isEditMode) {
                btnEditMode.text = "🛠️ 조절 중"
                btnEditMode.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
                // 터치를 격자 뷰가 가로챌 수 있도록 FLAG 변경
                gridParams.flags = gridParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                btnEditMode.text = "🛠️ 격자 고정"
                btnEditMode.setBackgroundColor(android.graphics.Color.parseColor("#757575"))
                // 다시 격자 터치 안먹히게 락 걸기
                gridParams.flags = gridParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            gridOverlayView?.let { windowManager.updateViewLayout(it, gridParams) }
        }

        // 행렬 수치 조절창 접기 / 펼치기
        btnMinimize.setOnClickListener {
            if (layoutExpandedBody.visibility == View.VISIBLE) {
                layoutExpandedBody.visibility = View.GONE
                btnMinimize.text = "행렬 ▼"
            } else {
                layoutExpandedBody.visibility = View.VISIBLE
                btnMinimize.text = "행렬 ▲"
            }
            panelView?.let { windowManager.updateViewLayout(it, panelParams) }
        }

        // 서비스 강제 종료 버튼
        btnKillService.setOnClickListener { stopSelf() }

        // 행렬 단추 클릭 이벤트들
        view.findViewById<Button>(R.id.btnRowPlus).setOnClickListener { gridOverlayView?.let { it.rows++; updateInfoText(); it.invalidate() } }
        view.findViewById<Button>(R.id.btnRowMinus).setOnClickListener { gridOverlayView?.let { if(it.rows > 1) it.rows--; updateInfoText(); it.invalidate() } }
        view.findViewById<Button>(R.id.btnColPlus).setOnClickListener { gridOverlayView?.let { it.cols++; updateInfoText(); it.invalidate() } }
        view.findViewById<Button>(R.id.btnColMinus).setOnClickListener { gridOverlayView?.let { if(it.cols > 1) it.cols--; updateInfoText(); it.invalidate() } }
    }

    // 시야 방해 없는 스마트 간접 변위 드래그 시스템
    private fun setupAdvancedIndirectTouchListener() {
        var lockedCorner = -1 
        var startStartX = 0f; var startStartY = 0f
        var origX = 0f; var origY = 0f

        gridOverlayView?.setOnTouchListener { _, event ->
            if (!isEditMode) return@setOnTouchListener false
            val gov = gridOverlayView ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startStartX = event.x
                    startStartY = event.y
                    
                    val dTL = Math.hypot((startStartX - gov.tlX).toDouble(), (startStartY - gov.tlY).toDouble())
                    val dTR = Math.hypot((startStartX - gov.trX).toDouble(), (startStartY - gov.trY).toDouble())
                    val dBL = Math.hypot((startStartX - gov.blX).toDouble(), (startStartY - gov.blY).toDouble())
                    val dBR = Math.hypot((startStartX - gov.brX).toDouble(), (startStartY - gov.brY).toDouble())

                    val minVal = listOf(dTL, dTR, dBL, dBR).minOrNull() ?: return@setOnTouchListener false
                    lockedCorner = listOf(dTL, dTR, dBL, dBR).indexOf(minVal)

                    when(lockedCorner) {
                        0 -> { origX = gov.tlX; origY = gov.tlY }
                        1 -> { origX = gov.trX; origY = gov.trY }
                        2 -> { origX = gov.blX; origY = gov.blY }
                        3 -> { origX = gov.brX; origY = gov.brY }
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (lockedCorner == -1) return@setOnTouchListener false
                    val dx = event.x - startStartX
                    val dy = event.y - startStartY

                    when(lockedCorner) {
                        0 -> { gov.tlX = origX + dx; gov.tlY = origY + dy }
                        1 -> { gov.trX = origX + dx; gov.trY = origY + dy }
                        2 -> { gov.blX = origX + dx; gov.blY = origY + dy }
                        3 -> { gov.brX = origX + dx; gov.brY = origY + dy }
                    }
                    gov.invalidate()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { 
                    lockedCorner = -1 
                    true 
                }
                else -> false
            }
        }
    }

    private fun updateInfoText() {
        gridOverlayView?.let { tvGridInfo.text = "크기: ${it.rows}행 x ${it.cols}열" }
    }

    // GitHub용 에러 실시간 디스플레이 프레임워크
    private fun handleException(location: String, e: Exception) {
        Log.e("SolverService_CRASH", "[$location] 에서 크래시 및 예외 감지됨!", e)
        
        Handler(Looper.getMainLooper()).post {
            val exceptionName = e.javaClass.simpleName
            val exceptionMessage = e.message ?: "상세 원인 제공 안됨"
            
            val debugAlertText = when (e) {
                is SecurityException -> "❌ [권한 차단] Manifest에 mediaProjection 타입이 없거나 유저가 캡처를 거부했습니다.\n($exceptionMessage)"
                is NullPointerException -> "❌ [객체 공백] 화면 공유 토큰(MediaProjection) 빌드에 실패해 Null을 반환했습니다."
                is IllegalStateException -> "❌ [상태 불일치] 닫힌 이미지 리더에 접근했거나 잘못된 스레드에서 호출되었습니다."
                else -> "❌ [$location 에러 발생]\n유형: $exceptionName\n내용: $exceptionMessage"
            }
            Toast.makeText(applicationContext, debugAlertText, Toast.LENGTH_LONG).show()
        }
    }

    private fun startCaptureAndAnalysisLoop() {
        try {
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            
            val currentProjection = mediaProjection ?: throw NullPointerException("MediaProjection 토큰이 발급되지 않았습니다.")
            
            virtualDisplay = currentProjection.createVirtualDisplay(
                "ScreenCaptureEngine", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null
            )

            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    // [튕김 원천 봉쇄]: 조절 모드 도중에는 연산 로직을 스킵하여 인덱스 참조 크래시 방지
                    if (isEditMode || gridOverlayView == null) {
                        image.close()
                        return@setOnImageAvailableListener
                    }

                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width
                    val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(buffer)

                    // ----------------------------------------------------
                    // 💡 [Match3Solver 분석 연산 구역]
                    // 여기에 비트맵 가공 및 보드 분석 알고리즘 코드를 연동하시면 됩니다.
                    // ----------------------------------------------------

                    bitmap.recycle()
                } catch (e: Exception) {
                    handleException("ImageProcessingLoop", e)
                } finally {
                    image.close()
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            handleException("CreateVirtualDisplay", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        val resultCode = intent.getIntExtra("RESULT_CODE", -1)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>("RESULT_DATA")
        
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Toast.makeText(this, "❌ 미디어 프로젝션 화면 공유 승인이 취소되었습니다.", Toast.LENGTH_SHORT).show()
            return START_NOT_STICKY
        }
        
        // 상단 알림 바 포어그라운드 시동 필수 구역
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("HelperEngine", "Grid Engine", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, "HelperEngine")
            .setContentTitle("격자 보석 추적 엔진이 동작 중입니다.")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
            
        startForeground(8888, notification)

        // 미디어 프로젝션 바인딩 딜레이 안전장치
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
                
                startCaptureAndAnalysisLoop()
            } catch (e: Exception) {
                handleException("MediaProjectionInit", e)
            }
        }, 250)

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            backgroundThread?.quitSafely()
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            panelView?.let { windowManager.removeView(it) }
            gridOverlayView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.e("SolverService", "Destroy 리소스 해제 중 오류", e)
        }
    }
}
