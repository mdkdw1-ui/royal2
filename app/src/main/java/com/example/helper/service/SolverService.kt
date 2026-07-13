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

            // 1. 격자 오버레이 뷰 설정
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

        layoutHeader.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val pInitialX = panelParams.x
                    val pInitialY = panelParams.y
                    val pTouchX = event.rawX
                    val pTouchY = event.rawY
                    layoutHeader.setTag(R.id.btnEditMode, Pair(pInitialX, pInitialY))
                    layoutHeader.setTag(R.id.btnGridVisibility, Pair(pTouchX, pTouchY))
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val initCoords = layoutHeader.getTag(R.id.btnEditMode) as? Pair<*, *>
                    val touchCoords = layoutHeader.getTag(R.id.btnGridVisibility) as? Pair<*, *>
                    if (initCoords != null && touchCoords != null) {
                        panelParams.x = (initCoords.first as Int) + (event.rawX - (touchCoords.first as Float)).toInt()
                        panelParams.y = (initCoords.second as Int) + (event.rawY - (touchCoords.second as Float)).toInt()
                        panelView?.let { windowManager.updateViewLayout(it, panelParams) }
                    }
                    true
                }
                else -> false
            }
        }

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

        btnEditMode.setOnClickListener {
            isEditMode = !isEditMode
            if (isEditMode) {
                btnEditMode.text = "🛠️ 조절 중"
                btnEditMode.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
                gridParams.flags = gridParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                btnEditMode.text = "🛠️ 격자 고정"
                btnEditMode.setBackgroundColor(android.graphics.Color.parseColor("#757575"))
                gridParams.flags = gridParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            gridOverlayView?.let { windowManager.updateViewLayout(it, gridParams) }
        }

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

        btnKillService.setOnClickListener { stopSelf() }

        view.findViewById<Button>(R.id.btnRowPlus).setOnClickListener { gridOverlayView?.let { it.rows++; updateInfoText(); it.invalidate() } }
        view.findViewById<Button>(R.id.btnRowMinus).setOnClickListener { gridOverlayView?.let { if(it.rows > 1) it.rows--; updateInfoText(); it.invalidate() } }
        view.findViewById<Button>(R.id.btnColPlus).setOnClickListener { gridOverlayView?.let { it.cols++; updateInfoText(); it.invalidate() } }
        view.findViewById<Button>(R.id.btnColMinus).setOnClickListener { gridOverlayView?.let { if(it.cols > 1) it.cols--; updateInfoText(); it.invalidate() } }
    }

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

    private fun handleException(location: String, e: Exception) {
        Log.e("SolverService_CRASH", "[$location] 예외 감지", e)
        Handler(Looper.getMainLooper()).post {
            val exceptionMessage = e.message ?: "상세 원인 없음"
            val debugAlertText = when (e) {
                is SecurityException -> "❌ [권한 차단] 캡처 권한이 거부되었습니다."
                is NullPointerException -> "❌ [객체 공백] 미디어 프로젝션 토큰이 비어있습니다."
                is IllegalStateException -> "❌ [상태 불일치] 닫힌 이미지 리더 접근 또는 잘못된 스레드 호출입니다."
                else -> "❌ [$location 에러]\n내용: $exceptionMessage"
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
            val currentProjection = mediaProjection ?: throw NullPointerException("MediaProjection 토큰 공백")
            
            virtualDisplay = currentProjection.createVirtualDisplay(
                "ScreenCaptureEngine", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null
            )

            imageReader?.setOnImageAvailableListener({ reader ->
                // ✨ [방어조치 1]: 닫히는 도중 비동기로 프레임이 올 때 발생하는 튕김 원천 봉쇄
                val image = try {
                    reader.acquireLatestImage()
                } catch (e: IllegalStateException) {
                    return@setOnImageAvailableListener // 이미 리더가 닫혔으므로 바로 안전 종료
                } ?: return@setOnImageAvailableListener

                try {
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

                    // [Match3Solver 분석 연산 구역]

                    bitmap.recycle()
                } catch (e: Exception) {
                    // 서비스 종료 시 발생하는 부차적 예외는 토스트 팝업 생략하고 로그 처리
                    if (e is IllegalStateException) {
                        Log.d("SolverService", "루프 내 안전 이미지 파기 가로챔")
                    } else {
                        handleException("ImageProcessingLoop", e)
                    }
                } finally {
                    try { image.close() } catch (e: Exception) {}
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
            // ✨ [방어조치 2]: 해제 순서 엄격 최적화 (리스너 제거 -> 디스플레이 종료 -> 엔진 파기)
            imageReader?.setOnImageAvailableListener(null, null) 
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.stop()
            mediaProjection = null
            
            backgroundThread?.quitSafely()
            panelView?.let { windowManager.removeView(it) }
            gridOverlayView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.e("SolverService", "Destroy 리소스 해제 중 오류", e)
        }
    }
}
