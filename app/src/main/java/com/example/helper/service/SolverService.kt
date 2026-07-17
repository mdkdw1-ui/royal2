package com.example.helper.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.lang.StringBuilder
import kotlin.math.abs

enum class BlockColor { RED, BLUE, YELLOW, GREEN, PURPLE, UNKNOWN }

enum class ObstacleType { NONE, DETECTED_OBSTACLE }

data class MatchMove(
    val fromRow: Int, val fromCol: Int,
    val toRow: Int, val toCol: Int,
    val description: String,
    val isFiveMatch: Boolean = false,
    val fiveMatchCells: List<Pair<Int, Int>> = emptyList()
)

private enum class MatchType {
    DISCO_BALL, TNT, ROCKET, NORMAL_3
}

private data class TempMove(
    val fromRow: Int, val fromCol: Int,
    val toRow: Int, val toCol: Int,
    val color: BlockColor,
    val matchType: MatchType,
    val score: Int,
    val isFiveMatch: Boolean = false,
    val fiveMatchCells: List<Pair<Int, Int>> = emptyList()
)

class SolverService : Service() {

    private val TAG = "GridHelper_Service"
    private val binder = SolverBinder()
    
    var rows = 11
    var cols = 9

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private lateinit var windowManager: WindowManager
    
    private var floatingControlView: LinearLayout? = null 
    private var visualOverlayView: VisualOverlayView? = null 

    private var floatParams: WindowManager.LayoutParams? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    private var isCapturing = false
    private var isManualAnalysisRequested = false 

    private var isLogicEnabled = true
    private var isLargeMode = true

    private var isGridVisible = true
    private val disabledCells = Array(20) { BooleanArray(20) { false } }

    private var isAutoScanEnabled = true
    private val AUTO_SCAN_INTERVAL_MS = 1000L 
    private var lastAnalysisTime = 0L
    private var miniScanButton: Button? = null 

    private val ptTL = PointF()
    private val ptTR = PointF()
    private val ptBL = PointF()
    private val ptBR = PointF()
    private var isCalibrationMode = false 
    
    private var activeCorner: PointF = ptTL

    // 📸 Klicker 스타일 실시간 런타임 이미지 캡처 제어 변수
    private var isImageGrabberMode = false 
    private var grabbedRow = -1
    private var grabbedCol = -1
    
    // 🛠️ 기믹 따기 도중 자동 스캔 파이프라인의 간섭을 막기 위한 동기화 락 필드
    private var isGrabberProcessing = false
    private var lastGrabberActivationTime = 0L
    
    private val dynamicTemplates = mutableListOf<Mat>()
    private var isOpenCVInitialized = false

    inner class SolverBinder : Binder() {
        fun getService(): SolverService = this@SolverService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        backgroundThread = HandlerThread("SolverBackgroundWorker").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
        
        if (OpenCVLoader.initDebug()) {
            Log.d(TAG, "OpenCV 엔진 빌드 로드 성공")
            isOpenCVInitialized = true
            backgroundHandler?.post { loadDynamicTemplatesFromStorage() }
        } else {
            Log.e(TAG, "OpenCV 엔진 로드 실패")
        }

        loadPreferences()
        
        createVisualOverlayWindow()
        createFloatingControlWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceInternal()
        if (intent != null) {
            val resultCode = intent.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED)
            val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("RESULT_DATA", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("RESULT_DATA")
            }

            if (resultCode == Activity.RESULT_OK && resultData != null) {
                backgroundHandler?.post { setupScreenCapturePipeline(resultCode, resultData) }
            }
        }
        return START_STICKY
    }

    private fun loadDynamicTemplatesFromStorage() {
        if (!isOpenCVInitialized) return
        
        synchronized(dynamicTemplates) {
            dynamicTemplates.forEach { it.release() }
            dynamicTemplates.clear()
        }

        try {
            val dir = getExternalFilesDir("captures")
            if (dir != null && dir.exists()) {
                val files = dir.listFiles { _, name -> name.endsWith(".png") }
                files?.forEach { file ->
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        val mat = Mat()
                        Utils.bitmapToMat(bitmap, mat)
                        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY)
                        
                        synchronized(dynamicTemplates) {
                            dynamicTemplates.add(mat)
                        }
                        bitmap.recycle()
                        Log.d(TAG, "기믹 로드 완료: ${file.name}")
                    }
                }
                mainHandler.post { refreshFloatingPanelUI() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "저장소 템플릿 로드 실패", e)
        }
    }

    private fun saveBitmapToFile(bitmap: Bitmap, filename: String) {
        try {
            val dir = getExternalFilesDir("captures")
            if (dir != null && !dir.exists()) dir.mkdirs()
            
            val file = File(dir, "${filename}_${System.currentTimeMillis()}.png")
            val out = FileOutputStream(file)
            
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
            out.close()
            
            val newMat = Mat()
            Utils.bitmapToMat(bitmap, newMat)
            Imgproc.cvtColor(newMat, newMat, Imgproc.COLOR_RGBA2GRAY)
            
            synchronized(dynamicTemplates) {
                dynamicTemplates.add(newMat)
            }
            
            showToastOnMainThread("📸 기믹 실시간 등록 완료!\n현재 총 기믹 수: ${dynamicTemplates.size}개")
        } catch (e: Exception) {
            Log.e(TAG, "이미지 저장 및 실시간 매핑 실패", e)
            showToastOnMainThread("❌ 기믹 저장 실패!")
        } finally {
            grabbedRow = -1
            grabbedCol = -1
            isImageGrabberMode = false
            isGrabberProcessing = false
            
            mainHandler.post {
                visualOverlayView?.let { overlay ->
                    val params = overlay.layoutParams as WindowManager.LayoutParams
                    params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    windowManager.updateViewLayout(overlay, params)
                }
                refreshFloatingPanelUI()
            }
        }
    }

    private fun clearAllDynamicTemplates() {
        backgroundHandler?.post {
            try {
                synchronized(dynamicTemplates) {
                    dynamicTemplates.forEach { it.release() }
                    dynamicTemplates.clear()
                }
                
                val dir = getExternalFilesDir("captures")
                if (dir != null && dir.exists()) {
                    val files = dir.listFiles()
                    files?.forEach { it.delete() }
                }
                
                showToastOnMainThread("🧹 모든 기믹 데이터 초기화 완료!")
                mainHandler.post { refreshFloatingPanelUI() }
                visualOverlayView?.invalidate()
            } catch (e: Exception) {
                Log.e(TAG, "데이터 초기화 에러", e)
            }
        }
    }

    private fun startForegroundServiceInternal() {
        val channelId = "GridHelperServiceChannel"
        val channelName = "Grid Helper Service"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Grid Helper Active")
            .setContentText("백그라운드 자동 스캔 엔진 가동 중")
            .setSmallIcon(android.R.drawable.ic_menu_compass) 
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1001, notification)
        }
    }

    private fun createVisualOverlayWindow() {
        mainHandler.post {
            visualOverlayView = VisualOverlayView(applicationContext)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            windowManager.addView(visualOverlayView, params)
        }
    }

    private fun createFloatingControlWindow() {
        mainHandler.post {
            val context = applicationContext
            floatParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 50
                y = 150
            }

            floatingControlView = object : LinearLayout(context) {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f
                private val touchSlop = 15f 

                override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                    val params = floatParams ?: return super.onInterceptTouchEvent(ev)
                    when (ev.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = ev.rawX
                            initialTouchY = ev.rawY
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = abs(ev.rawX - initialTouchX)
                            val dy = abs(ev.rawY - initialTouchY)
                            if (dx > touchSlop || dy > touchSlop) return true 
                        }
                    }
                    return super.onInterceptTouchEvent(ev)
                }

                override fun onTouchEvent(event: MotionEvent): Boolean {
                    val params = floatParams ?: return super.onTouchEvent(event)
                    when (event.action) {
                        MotionEvent.ACTION_MOVE -> {
                            params.x = initialX + (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager.updateViewLayout(this, params)
                            return true
                        }
                    }
                    return super.onTouchEvent(event)
                }
            }.apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#EE111111"))
                setPadding(20, 15, 20, 15)
            }

            refreshFloatingPanelUI()
            windowManager.addView(floatingControlView, floatParams)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun setAutoRepeatListener(view: View, action: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var runnable: Runnable? = null
        
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    handler.removeCallbacksAndMessages(null)
                    runnable = object : Runnable {
                        override fun run() {
                            action()
                            handler.postDelayed(this, 60)
                        }
                    }
                    action() 
                    handler.postDelayed(runnable!!, 400) 
                    v.isPressed = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacksAndMessages(null)
                    runnable = null
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }
    }

    private fun changeGridSize(newRows: Int, newCols: Int) {
        savePreferences() 
        rows = newRows
        cols = newCols
        
        val prefs = getSharedPreferences("GridHelperPrefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt("rows", rows)
            putInt("cols", cols)
            apply()
        }
        
        loadPreferences() 
        refreshFloatingPanelUI()
        visualOverlayView?.invalidate()
    }

    private fun refreshFloatingPanelUI() {
        val view = floatingControlView ?: return
        view.removeAllViews()
        val context = applicationContext

        if (isImageGrabberMode) {
            val btnCancel = Button(context).apply {
                text = "❌ 기믹 따기 취소"
                textSize = 11f
                setBackgroundColor(Color.parseColor("#FF0000"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    isImageGrabberMode = false
                    isGrabberProcessing = false
                    val overlay = visualOverlayView ?: return@setOnClickListener
                    val params = overlay.layoutParams as WindowManager.LayoutParams
                    params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    windowManager.updateViewLayout(overlay, params)
                    
                    refreshFloatingPanelUI()
                    overlay.invalidate()
                }
            }
            view.addView(btnCancel)

            floatParams?.let { params ->
                try { windowManager.updateViewLayout(view, params) } catch (e: Exception) {}
            }
            return 
        }

        val btnSizeToggle = Button(context).apply {
            text = if (isLargeMode) "◀ 미니 모드" else "▶ 확장 패널"
            textSize = 11f
            setBackgroundColor(Color.parseColor("#333333"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                isLargeMode = !isLargeMode
                refreshFloatingPanelUI()
            }
        }
        view.addView(btnSizeToggle)

        if (isLargeMode) {
            miniScanButton = null 
            
            val tvStatus = TextView(context).apply {
                text = "엔진: ${if(isLogicEnabled) "ON" else "OFF"} / 자동화: ${if(isAutoScanEnabled) "ON" else "OFF"}\n격자: ${rows}x${cols} / 등록 기믹: ${dynamicTemplates.size}개"
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding(0, 10, 0, 10)
            }
            view.addView(tvStatus)

            val btnAutoToggle = Button(context).apply {
                text = if (isAutoScanEnabled) "🔄 자동 스캔: ON" else "⏸️ 자동 스캔: OFF"
                setBackgroundColor(if (isAutoScanEnabled) Color.parseColor("#007F0E") else Color.DKGRAY)
                setTextColor(Color.WHITE)
                setOnClickListener {
                    isAutoScanEnabled = !isAutoScanEnabled
                    text = if (isAutoScanEnabled) "🔄 자동 스캔: ON" else "⏸️ 자동 스캔: OFF"
                    setBackgroundColor(if (isAutoScanEnabled) Color.parseColor("#007F0E") else Color.DKGRAY)
                }
            }
            view.addView(btnAutoToggle)

            val btnScan = Button(context).apply {
                text = "🎯 즉시 수동 스캔"
                setBackgroundColor(Color.parseColor("#FF6200EE"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    if (!isLogicEnabled) {
                        showToastOnMainThread("로직이 OFF 상태입니다."); return@setOnClickListener
                    }
                    isManualAnalysisRequested = true
                }
            }
            view.addView(btnScan)

            val btnGrabberToggle = Button(context).apply {
                text = "📷 기믹 그림 따기"
                setBackgroundColor(Color.parseColor("#5A0063"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    isImageGrabberMode = true
                    isGrabberProcessing = false
                    val overlay = visualOverlayView ?: return@setOnClickListener
                    val params = overlay.layoutParams as WindowManager.LayoutParams
                    
                    lastGrabberActivationTime = System.currentTimeMillis()
                    isCalibrationMode = false 
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    
                    windowManager.updateViewLayout(overlay, params)
                    refreshFloatingPanelUI()
                }
            }
            view.addView(btnGrabberToggle)

            val btnClearTemplates = Button(context).apply {
                text = "🧹 기믹 전체 초기화"
                setBackgroundColor(Color.parseColor("#FF8C00"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    clearAllDynamicTemplates()
                }
            }
            view.addView(btnClearTemplates)

            val btnLogicToggle = Button(context).apply {
                text = if (isLogicEnabled) "⚙️ 엔진 활성화" else "❌ 엔진 비활성화"
                setBackgroundColor(if (isLogicEnabled) Color.parseColor("#007F0E") else Color.DKGRAY)
                setTextColor(Color.WHITE)
                setOnClickListener {
                    isLogicEnabled = !isLogicEnabled
                    text = if (isLogicEnabled) "⚙️ 엔진 활성화" else "❌ 엔진 비활성화"
                    setBackgroundColor(if (isLogicEnabled) Color.parseColor("#007F0E") else Color.DKGRAY)
                    if(!isLogicEnabled) visualOverlayView?.updateMoves(emptyList())
                }
            }
            view.addView(btnLogicToggle)

            val btnCalibrate = Button(context).apply {
                text = if (isCalibrationMode) "💾 설정 완료" else "📐 격자 맞춤 분할"
                setBackgroundColor(if (isCalibrationMode) Color.parseColor("#FF00DF") else Color.parseColor("#444444"))
                setTextColor(Color.WHITE)
                setOnClickListener { toggleCalibrationMode() }
            }
            view.addView(btnCalibrate)

            if (isCalibrationMode) {
                val cornerName = when (activeCorner) {
                    ptTL -> "좌상"
                    ptTR -> "우상"
                    ptBL -> "좌하"
                    ptBR -> "우하"
                    else -> "미정"
                }

                val tvDpadTitle = TextView(context).apply {
                    text = "🎯 [${cornerName}] 미세조절 (꾹 누름 지원)"
                    setTextColor(Color.YELLOW)
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setPadding(0, 10, 0, 5)
                }
                view.addView(tvDpadTitle)

                val dpadLayout = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                }

                val rowUp = LinearLayout(context).apply { gravity = Gravity.CENTER }
                val btnUp = Button(context).apply {
                    text = "▲"
                    textSize = 10f
                    layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(35))
                    setAutoRepeatListener(this) { activeCorner.y -= 2f; visualOverlayView?.invalidate() }
                }
                rowUp.addView(btnUp)
                dpadLayout.addView(rowUp)

                val rowMid = LinearLayout(context).apply { gravity = Gravity.CENTER }
                val btnLeft = Button(context).apply {
                    text = "◀"
                    textSize = 10f
                    layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(35))
                    setAutoRepeatListener(this) { activeCorner.x -= 2f; visualOverlayView?.invalidate() }
                }
                val spacer = View(context).apply { layoutParams = LinearLayout.LayoutParams(dpToPx(15), dpToPx(35)) }
                val btnRight = Button(context).apply {
                    text = "▶"
                    textSize = 10f
                    layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(35))
                    setAutoRepeatListener(this) { activeCorner.x += 2f; visualOverlayView?.invalidate() }
                }
                rowMid.addView(btnLeft)
                rowMid.addView(spacer)
                rowMid.addView(btnRight)
                dpadLayout.addView(rowMid)

                val rowDown = LinearLayout(context).apply { gravity = Gravity.CENTER }
                val btnDown = Button(context).apply {
                    text = "▼"
                    textSize = 10f
                    layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(35))
                    setAutoRepeatListener(this) { activeCorner.y += 2f; visualOverlayView?.invalidate() }
                }
                rowDown.addView(btnDown)
                dpadLayout.addView(rowDown)
                view.addView(dpadLayout)

                val tvTips = TextView(context).apply {
                    text = "💡 칸을 터치해 스캔 비활성화(X)\n💡 모서리를 터치한 뒤 화살표를 꾹 누르세요."
                    setTextColor(Color.parseColor("#FF9F9F"))
                    textSize = 9f
                    setPadding(0, 5, 0, 5)
                }
                view.addView(tvTips)
            }

            val btnGridToggle = Button(context).apply {
                text = if (isGridVisible) "🌐 격자: 보임" else "🌐 격자: 숨김"
                setBackgroundColor(if (isGridVisible) Color.parseColor("#007F0E") else Color.DKGRAY)
                setTextColor(Color.WHITE)
                setOnClickListener {
                    isGridVisible = !isGridVisible
                    text = if (isGridVisible) "🌐 격자: 보임" else "🌐 격자: 숨김"
                    setBackgroundColor(if (isGridVisible) Color.parseColor("#007F0E") else Color.DKGRAY)
                    visualOverlayView?.invalidate()
                    savePreferences()
                }
            }
            view.addView(btnGridToggle)

            val matrixBox = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val btnAddRow = Button(context).apply { text = "행+"; setOnClickListener { changeGridSize(rows + 1, cols) } }
            val btnSubRow = Button(context).apply { text = "행-"; setOnClickListener { if(rows > 3) changeGridSize(rows - 1, cols) } }
            val btnAddCol = Button(context).apply { text = "열+"; setOnClickListener { changeGridSize(rows, cols + 1) } }
            val btnSubCol = Button(context).apply { text = "열-"; setOnClickListener { if(cols > 3) changeGridSize(rows, cols - 1) } }
            matrixBox.addView(btnSubRow); matrixBox.addView(btnAddRow); matrixBox.addView(btnSubCol); matrixBox.addView(btnAddCol)
            view.addView(matrixBox)

            val btnKill = Button(context).apply {
                text = "☠️ 헬퍼 프로그램 종료"
                setBackgroundColor(Color.RED)
                setTextColor(Color.WHITE)
                setOnClickListener { stopSelf() }
            }
            view.addView(btnKill)
        } else {
            miniScanButton = Button(context).apply {
                text = if (isAutoScanEnabled) "🔍 자동 탐지 중" else "⚡ 수동 스캔"
                textSize = 11f
                setBackgroundColor(Color.parseColor("#222222"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    isManualAnalysisRequested = true
                }
            }
            view.addView(miniScanButton)
        }

        floatParams?.let { params ->
            try { windowManager.updateViewLayout(view, params) } catch (e: Exception) {}
        }
    }

    private fun toggleCalibrationMode() {
        isCalibrationMode = !isCalibrationMode
        val overlay = visualOverlayView ?: return
        val params = overlay.layoutParams as WindowManager.LayoutParams
        if (isCalibrationMode) {
            isImageGrabberMode = false
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            showToastOnMainThread("📐 격자점 조절 및 터치 스캔 해제(X) 기능 활성화")
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            showToastOnMainThread("💾 [${rows}x${cols}] 전용 격자 배치 및 제외 구역 저장 완료!")
            savePreferences()
        }
        windowManager.updateViewLayout(overlay, params)
        refreshFloatingPanelUI()
        overlay.invalidate()
    }

    private fun setupScreenCapturePipeline(resultCode: Int, resultData: Intent) {
        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            stopCapturePipeline()

            mediaProjection = mpManager.getMediaProjection(resultCode, resultData)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { super.onStop(); stopCapturePipeline() }
            }, backgroundHandler)

            val metrics = resources.displayMetrics
            imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "GridHelperCapture", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, backgroundHandler
            )

            isCapturing = true

            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader?.acquireLatestImage() ?: return@setOnImageAvailableListener
                
                // 🛠️ [해결책] 기믹 따기 모드일 때는 스트림을 소모하지 않고 즉시 닫아서 터치 이벤트 측에 권한을 양보함
                if (!isCapturing || !isLogicEnabled || isImageGrabberMode) {
                    image.close()
                    return@setOnImageAvailableListener
                }
                
                try {
                    if (isGrabberProcessing) {
                        image.close()
                        return@setOnImageAvailableListener
                    }

                    val currentTime = System.currentTimeMillis()
                    val shouldAnalyze = isManualAnalysisRequested || 
                        (isAutoScanEnabled && (currentTime - lastAnalysisTime >= AUTO_SCAN_INTERVAL_MS))

                    if (shouldAnalyze) {
                        isManualAnalysisRequested = false
                        lastAnalysisTime = currentTime
                        
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val w = metrics.widthPixels
                        val h = metrics.heightPixels
                        val rowPadding = rowStride - pixelStride * w

                        val bitmap = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                        bitmap.copyPixelsFromBuffer(buffer)
                        
                        val matchedMoves = analyzeAndSolvePerspective(bitmap)
                        
                        mainHandler.post {
                            visualOverlayView?.updateMoves(matchedMoves)
                            
                            if (!isLargeMode) {
                                val miniBtn = miniScanButton
                                if (miniBtn != null) {
                                    if (matchedMoves.isNotEmpty()) {
                                        val bestMove = matchedMoves.first()
                                        if (bestMove.isFiveMatch) {
                                            miniBtn.text = "⚡ 5개 매칭!"
                                            miniBtn.setBackgroundColor(Color.parseColor("#FFFF00"))
                                        } else {
                                            val shortMsg = bestMove.description
                                                .replace("⚡[", "")
                                                .replace(" 절대강자 OOXOO] 디스코볼 확정 배치! 💣", " 디볼")
                                                .replace("💣 [", "")
                                                .replace("] 강력한 폭탄 생성!", " TNT")
                                                .replace("🚀 [", "")
                                                .replace("] 라인 클리어!", " 로켓")
                                                .replace("🧩 [", "")
                                                .replace("] 일반 매칭", " 3매치")
                                            
                                            miniBtn.text = "⚡ $shortMsg!"
                                            miniBtn.setBackgroundColor(Color.parseColor("#FF00DF")) 
                                        }
                                    } else {
                                        miniBtn.text = "🔍 자동 탐지 중"
                                        miniBtn.setBackgroundColor(Color.parseColor("#222222"))
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "자동 분석 루프 실패: ${e.message}")
                } finally {
                    try { image.close() } catch (e: Exception) {}
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "캡처 서비스 구축 에러", e)
        }
    }

    private fun processSingleGrabberCrop(r: Int, c: Int) {
        val reader = imageReader ?: return
        backgroundHandler?.post {
            // 🛠️ [해결책] 스트림 독점 상태이므로 넉넉하게 대기하면서 최신 프레임을 획득
            var image = reader.acquireLatestImage()
            if (image == null) {
                Thread.sleep(50)
                image = reader.acquireNextImage()
            }
            if (image == null) {
                Log.e(TAG, "단발성 스크린샷 캡처 프레임 획득 실패")
                isGrabberProcessing = false
                return@post
            }

            try {
                val metrics = resources.displayMetrics
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val w = metrics.widthPixels
                val h = metrics.heightPixels
                val rowPadding = rowStride - pixelStride * w

                val fullBitmap = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888) FullBitmap.copyPixelsFromBuffer(buffer)

                Val u = (c + 0.5f) / cols
                Val v = (r + 0.5f) / rows
                Val topX = (1 - u) * ptTL.x + u * ptTR.x
                Val topY = (1 - u) * ptTL.y + u * ptTR.y
                Val bottomX = (1 - u) * ptBL.x + u * ptBR.x
                Val bottomY = (1 - u) * ptBL.y + u * ptBR.y 
                Val targetX = (1 - v) * topX + v * bottomX
                Val targetY = (1 - v) * topY + v * bottomY

                Val cellW = ((ptTR.x - ptTL.x) / cols).toInt().coerceAtLeast(1)
                Val cellH = ((ptBL.y - ptTL.y) / rows).toInt().coerceAtLeast(1)
                
                Val startX = (targetX - cellW / 2).toInt().coerceIn(0, fullBitmap.width - cellW)
                Val startY = (targetY - cellH / 2).toInt().coerceIn(0, fullBitmap.height - cellH)

                Val cellBitmap = Bitmap.createBitmap(fullBitmap, startX, startY, cellW, cellH)
                
                SaveBitmapToFile(cellBitmap, "grabbed_obstacle")
                
                CellBitmap.recycle()
                FullBitmap.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "기믹 크롭 연산 오류", e)
                IsGrabberProcessing = false
            } finally {
                Image.close()
            }
        }
    }

    Private fun findBestMoves(board: Array<Array<BlockColor>>): List<MatchMove> {
        Val tempMoves = mutableListOf<TempMove>()
        Val dr = intArrayOf(0, 1) 
        Val dc = intArrayOf(1, 0)

        For (r in 0 until rows) {
            For (c in 0 until cols) {
                Val color1 = board[r][c]
                If (color1 == BlockColor.UNKNOWN) continue

                For (i in 0 until 2) {
                    Val nr = r + dr[i]
                    Val nc = c + dc[i]

                    If (nr in 0 until rows && nc in 0 until cols) {
                        Val color2 = board[nr][nc]
                        If (color2 == BlockColor.UNKNOWN || color1 == color2) continue
                        
                        Board[r][c] = color2
                        Board[nr][nc] = color1

                        Val match1 = evaluateMatchAt(board, r, c, color2)
                        Val match2 = evaluateMatchAt(board, nr, nc, color1)

                        If (match1 != null) {
                            Val isFive = match1.first == MatchType.DISCO_BALL
                            Val cells = if (isFive) getFiveMatchCells(board, r, c, color2) else emptyList()
                            TempMoves.add(TempMove(r, c, nr, nc, color2, match1.first, match1.second, isFive, cells))
                        }
                        If (match2 != null) {
                            Val isFive = match2.first == MatchType.DISCO_BALL
                            Val cells = if (isFive) getFiveMatchCells(board, nr, nc, color1) else emptyList()
                            TempMoves.add(TempMove(nr, nc, r, c, color1, match2.first, match2.second, isFive, cells))
                        }

                        Board[r][c] = color1
                        Board[nr][nc] = color2
                    }
                }
            }
        }

        Return tempMoves
            .sortedByDescending { it.score }
            .distinctBy { "${it.fromRow},${it.fromCol}->${it.toRow},${it.toCol}" }
            .map { temp ->
                Val korColor = getKoreanColorName(temp.color)
                Val desc = when (temp.matchType) {
                    MatchType.DISCO_BALL -> "⚡[$korColor 절대강자 OOXOO] 디스코볼 확정 배치! 💣"
                    MatchType.TNT -> "💣 [$korColor TNT] 강력한 폭탄 생성!"
                    MatchType.ROCKET -> "🚀 [$korColor 로켓] 라인 클리어!"
                    MatchType.NORMAL_3 -> "🧩 [$korColor 3매치] 일반 매칭"
                }
                MatchMove(temp.fromRow, temp.fromCol, temp.toRow, temp.toCol, desc, temp.isFiveMatch, temp.fiveMatchCells)
            }
    }

    Private fun evaluateMatchAt(board: Array<Array<BlockColor>>, r: Int, c: Int, color: BlockColor): Pair<MatchType, Int>? {
        Var hLeft = 0
        While (c - hLeft - 1 >= 0 && board[r][c - hLeft - 1] == color) hLeft++
        Var hRight = 0
        While (c + hRight + 1 < cols && board[r][c + hRight + 1] == color) hRight++
        Val horizontalCount = hLeft + hRight + 1

        Var vUp = 0
        While (r - vUp - 1 >= 0 && board[r - vUp - 1][c] == color) vUp++
        Var vDown = 0
        While (r + vDown + 1 < rows && board[r + vDown + 1][c] == color) vDown++
        Val verticalCount = vUp + vDown + 1

        Val hasHorizontalMatch = horizontalCount >= 3
        Val hasVerticalMatch = verticalCount >= 3

        If (!hasHorizontalMatch && !hasVerticalMatch) return null

        Return when {
            HorizontalCount >= 5 || verticalCount >= 5 -> Pair(MatchType.DISCO_BALL, 1000)
            HasHorizontalMatch && hasVerticalMatch -> Pair(MatchType.TNT, 500)
            HorizontalCount == 4 || verticalCount == 4 -> Pair(MatchType.ROCKET, 300)
            Else -> Pair(MatchType.NORMAL_3, 100)
        }
    }

    Private fun getFiveMatchCells(board: Array<Array<BlockColor>>, r: Int, c: Int, color: BlockColor): List<Pair<Int, Int>> {
        Val cells = mutableListOf<Pair<Int, Int>>()
        
        Var hLeft = 0
        While (c - hLeft - 1 >= 0 && board[r][c - hLeft - 1] == color) hLeft++
        Var hRight = 0
        While (c + hRight + 1 < cols && board[r][c + hRight + 1] == color) hRight++
        Val horizontalCount = hLeft + hRight + 1

        Var vUp = 0
        While (r - vUp - 1 >= 0 && board[r - vUp - 1][c] == color) vUp++
        Var vDown = 0
        While (r + vDown + 1 < rows && board[r + vDown + 1][c] == color) vDown++
        Val verticalCount = vUp + vDown + 1

        If (horizontalCount >= 5) {
            For (offset in -hLeft..hRight) {
                Cells.add(Pair(r, c + offset))
            }
        } else if (verticalCount >= 5) {
            For (offset in -vUp..vDown) {
                Cells.add(Pair(r + offset, c))
            }
        }
        Return cells
    }

    Private fun analyzeAndSolvePerspective(bitmap: Bitmap): List<MatchMove> {
        Val width = bitmap.width
        Val height = bitmap.height
        Val pixels = IntArray(width * height)
        Bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        Val board = Array(rows) { Array(cols) { BlockColor.UNKNOWN } }

        Val approxCellW = ((ptTR.x - ptTL.x) / cols).toInt().coerceAtLeast(1)
        Val approxCellH = ((ptBL.y - ptTL.y) / rows).toInt().coerceAtLeast(1)

        For (r in 0 until rows) {
            For (c in 0 until cols) {
                If (disabledCells[r][c]) {
                    Board[r][c] = BlockColor.UNKNOWN
                    Continue
                }

                Val u = (c + 0.5f) / cols
                Val v = (r + 0.5f) / rows

                Val topX = (1 - u) * ptTL.x + u * ptTR.x
                Val topY = (1 - u) * ptTL.y + u * ptTR.y
                Val bottomX = (1 - u) * ptBL.x + u * ptBR.x
                Val bottomY = (1 - u) * ptBL.y + u * ptBR.y 

                Val targetX = (1 - v) * topX + v * bottomX
                Val targetY = (1 - v) * topY + v * bottomY

                Board[r][c] = identifyCellContent(bitmap, pixels, width, height, targetX, targetY, approxCellW, approxCellH)
            }
        }
        Return findBestMoves(board)
    }

    Private fun identifyCellContent(bitmap: Bitmap, pixels: IntArray, width: Int, height: Int, centerX: Float, centerY: Float, cellW: Int, cellH: Int): BlockColor {
        Val halfW = cellW / 2
        Val halfH = cellH / 2
        Val startX = (centerX - halfW).toInt().coerceIn(0, bitmap.width - cellW)
        Val startY = (centerY - halfH).toInt().coerceIn(0, bitmap.height - cellH)
        
        Try {
            Val cellBitmap = Bitmap.createBitmap(bitmap, startX, startY, cellW, cellH)
            Val isObstacle = checkObstacleWithOpenCV(cellBitmap)
            CellBitmap.recycle()
            
            If (isObstacle != ObstacleType.NONE) {
                Return BlockColor.UNKNOWN
            }
        } catch (e: Exception) {}

        Return detectNormalBlockColor(pixels, width, height, centerX, centerY)
    }

    Private fun checkObstacleWithOpenCV(cellBitmap: Bitmap): ObstacleType {
        If (!isOpenCVInitialized) return ObstacleType.NONE
        
        Val cellMat = Mat()
        Utils.bitmapToMat(cellBitmap, cellMat)
        Imgproc.cvtColor(cellMat, cellMat, Imgproc.COLOR_RGBA2GRAY)

        Val resultMat = Mat()
        Var matched = false

        Synchronized(dynamicTemplates) {
            For (template in dynamicTemplates) {
                If (cellMat.cols() >= template.cols() && cellMat.rows() >= template.rows()) {
                    Imgproc.matchTemplate(cellMat, template, resultMat, Imgproc.TM_CCOEFF_NORMED)
                    Val minMaxLocResult = Core.minMaxLoc(resultMat)
                    
                    If (minMaxLocResult.maxVal >= 0.80) {
                        Matched = true
                        Break
                    }
                }
            }
        }

        CellMat.release()
        ResultMat.release()
        Return if (matched) ObstacleType.DETECTED_OBSTACLE else ObstacleType.NONE
    }

    Private fun detectNormalBlockColor(pixels: IntArray, width: Int, height: Int, centerX: Float, centerY: Float): BlockColor {
        Val radius = 6  
        Val cX = centerX.toInt()
        Val cY = centerY.toInt()
        Var rCnt = 0; var bCnt = 0; var yCnt = 0; var gCnt = 0; var pCnt = 0
        Var scannedPixels = 0
        Val hsv = FloatArray(3)

        For (y in (cY - radius)..(cY + radius)) {
            For (x in (cX - radius)..(cX + radius)) {
                If (x < 0 || x >= width || y < 0 || y >= height) continue
                Val pixel = pixels[y * width + x]
                Color.colorToHSV(pixel, hsv)
                
                ScannedPixels++
                Val hue = hsv[0]
                Val sat = hsv[1]
                Val valValue = hsv[2]

                If (sat < 0.35f || valValue < 0.35f) continue
                
                When {
                    (hue in 0f..20f) || (hue in 340f..360f) -> rCnt++
                    Hue in 42f..62f -> yCnt++   
                    Hue in 63f..144f -> gCnt++
                    Hue in 145f..250f -> bCnt++ 
                    Hue in 251f..339f -> pCnt++
                }
            }
        }

        If (scannedPixels == 0) return BlockColor.UNKNOWN

        Val threshold = (scannedPixels * 0.25f).toInt().coerceAtLeast(20)
        Val maxMap = mapOf(
            BlockColor.RED to rCnt, BlockColor.BLUE to bCnt, 
            BlockColor.YELLOW to yCnt, BlockColor.GREEN to gCnt, BlockColor.PURPLE to pCnt
        )

        Val best = maxMap.filter { it.value > threshold }.maxByOrNull { it.value }
        Return best?.key ?: BlockColor.UNKNOWN
    }

    Private fun getKoreanColorName(color: BlockColor): String {
        Return when(color) {
            BlockColor.RED -> "빨강"; BlockColor.BLUE -> "파랑"; BlockColor.YELLOW -> "노랑"
            BlockColor.GREEN -> "초록"; BlockColor.PURPLE -> "보라"; else -> "미정"
        }
    }

    Private fun savePreferences() {
        Val prefs = getSharedPreferences("GridHelperPrefs", Context.MODE_PRIVATE)
        Val prefix = "${rows}x${cols}_" 
        
        Prefs.edit().apply {
            PutInt("rows", rows)
            PutInt("cols", cols)
            PutBoolean("isGridVisible", isGridVisible)
            
            PutFloat("${prefix}ptTL_x", ptTL.x)
            PutFloat("${prefix}ptTL_y", ptTL.y)
            PutFloat("${prefix}ptTR_x", ptTR.x)
            PutFloat("${prefix}ptTR_y", ptTR.y)
            PutFloat("${prefix}ptBL_x", ptBL.x)
            PutFloat("${prefix}ptBL_y", ptBL.y)
            PutFloat("${prefix}ptBR_x", ptBR.x)
            PutFloat("${prefix}ptBR_y", ptBR.y)
            
            Val sb = StringBuilder()
            For (r in 0 until 20) {
                For (c in 0 until 20) {
                    If (disabledCells[r][c]) {
                        Sb.append("$r,$c;")
                    }
                }
            }
            PutString("${prefix}disabledCells", sb.toString())
            Apply()
        }
    }

    Private fun loadPreferences() {
        Val prefs = getSharedPreferences("GridHelperPrefs", Context.MODE_PRIVATE)
        Val metrics = resources.displayMetrics
        Val w = metrics.widthPixels.toFloat()
        Val h = metrics.heightPixels.toFloat()

        Rows = prefs.getInt("rows", 11)
        Cols = prefs.getInt("cols", 9)
        IsGridVisible = prefs.getBoolean("isGridVisible", true)

        Val prefix = "${rows}x${cols}_"

        PtTL.set(prefs.getFloat("${prefix}ptTL_x", w * 0.05f), prefs.getFloat("${prefix}ptTL_y", h * 0.25f))
        PtTR.set(prefs.getFloat("${prefix}ptTR_x", w * 0.95f), prefs.getFloat("${prefix}ptTR_y", h * 0.25f))
        PtBL.set(prefs.getFloat("${prefix}ptBL_x", w * 0.05f), prefs.getFloat("${prefix}ptBL_y", h * 0.75f))
        PtBR.set(prefs.getFloat("${prefix}ptBR_x", w * 0.95f), prefs.getFloat("${prefix}ptBR_y", h * 0.75f))
        
        For (r in 0 until 20) {
            For (c in 0 until 20) {
                DisabledCells[r][c] = false
            }
        }
        Val disabledStr = prefs.getString("${prefix}disabledCells", "") ?: ""
        If (disabledStr.isNotEmpty()) {
            Val tokens = disabledStr.split(";")
            For (token in tokens) {
                If (token.isNotEmpty()) {
                    Val parts = token.split(",")
                    If (parts.size == 2) {
                        Val r = parts[0].toIntOrNull()
                        Val c = parts[1].toIntOrNull()
                        If (r != null && c != null && r < 20 && c < 20) {
                            DisabledCells[r][c] = true
                        }
                    }
                }
            }
        }
        ActiveCorner = ptTL
    }

    Inner class VisualOverlayView(context: Context) : View(context) {
        Private val linePaint = Paint().apply { color = Color.parseColor("#8000FF00"); style = Paint.Style.STROKE; strokeWidth = 3f }
        Private val handlePaint = Paint().apply { color = Color.parseColor("#FF00DF"); style = Paint.Style.FILL }
        Private val activeHandlePaint = Paint().apply { color = Color.YELLOW; style = Paint.Style.FILL }
        Private val strokePaint = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 5f }
        
        Private val highlightPaint = Paint().apply { color = Color.parseColor("#80FFFF00"); style = Paint.Style.FILL }
        
        Private val disabledOverlayPaint = Paint().apply { color = Color.parseColor("#80FF0000"); style = Paint.Style.FILL }
        Private val disabledLinePaint = Paint().apply { color = Color.RED; strokeWidth = 5f; style = Paint.Style.STROKE }

        Private var currentMoves = listOf<MatchMove>()
        Private var selectedCorner: PointF? = null
        Private val touchRadius = 70f
        Private val magnetThreshold = 12f 

        Fun updateMoves(moves: List<MatchMove>) {
            This.currentMoves = moves
            Invalidate()
        }

        Private fun getCellCornerX(r: Int, c: Int): Float {
            Val u = c.toFloat() / cols
            Val v = r.toFloat() / rows
            Val topX = (1 - u) * ptTL.x + u * ptTR.x
            Val bottomX = (1 - u) * ptBL.x + u * ptBR.x
            Return (1 - v) * topX + v * bottomX
        }

        Private fun getCellCornerY(r: Int, c: Int): Float {
            Val u = c.toFloat() / cols
            Val v = r.toFloat() / rows
            Val topY = (1 - u) * ptTL.y + u * ptTR.y
            Val bottomY = (1 - u) * ptBL.y + u * ptBR.y
            Return (1 - v) * topY + v * bottomY
        }

        Override fun onDraw(canvas: Canvas) {
            Super.onDraw(canvas)

            If (isGridVisible || isCalibrationMode) {
                For (i in 0..cols) {
                    Val ratio = i.toFloat() / cols
                    Val topX = (1 - ratio) * ptTL.x + ratio * ptTR.x
                    Val topY = (1 - ratio) * ptTL.y + ratio * ptTR.y
                    Val botX = (1 - ratio) * ptBL.x + ratio * ptBR.x
                    Val botY = (1 - ratio) * ptBL.y + ratio * ptBR.y
                    Canvas.drawLine(topX, topY, botX, botY, linePaint)
                }
                For (j in 0..rows) {
                    Val ratio = j.toFloat() / rows
                    Val leftX = (1 - ratio) * ptTL.x + ratio * ptBL.x
                    Val leftY = (1 - ratio) * ptTL.y + ratio * ptBL.y
                    Val rightX = (1 - ratio) * ptTR.x + ratio * ptBR.x
                    Val rightY = (1 - ratio) * ptTR.y + ratio * ptBR.y
                    Canvas.drawLine(leftX, leftY, rightX, rightY, linePaint)
                }

                For (r in 0 until rows) {
                    For (c in 0 until cols) {
                        If (disabledCells[r][c]) {
                            Val xTL = getCellCornerX(r, c)
                            Val yTL = getCellCornerY(r, c)
                            Val xTR = getCellCornerX(r, c + 1)
                            Val yTR = getCellCornerY(r, c + 1)
                            Val xBL = getCellCornerX(r + 1, c)
                            Val yBL = getCellCornerY(r + 1, c)
                            Val xBR = getCellCornerX(r + 1, c + 1)
                            Val yBR = getCellCornerY(r + 1, c + 1)

                            Canvas.drawLine(xTL, yTL, xBR, yBR, disabledLinePaint)
                            Canvas.drawLine(xTR, yTR, xBL, yBL, disabledLinePaint)

                            Val cX = (xTL + xBR) / 2
                            Val cY = (yTL + yBR) / 2
                            Canvas.drawCircle(cX, cY, 15f, disabledOverlayPaint)
                        }
                    }
                }
            }

            If (isCalibrationMode) {
                Fun drawHandle(corner: PointF, isActive: Boolean) {
                    Canvas.drawCircle(corner.x, corner.y, 30f, if (isActive) activeHandlePaint else handlePaint)
                    Canvas.drawCircle(corner.x, corner.y, 30f, strokePaint)
                }
                DrawHandle(ptTL, activeCorner == ptTL)
                DrawHandle(ptTR, activeCorner == ptTR)
                DrawHandle(ptBL, activeCorner == ptBL)
                DrawHandle(ptBR, activeCorner == ptBR)
            }

            If (isLogicEnabled && currentMoves.isNotEmpty()) {
                Val move = currentMoves.first()
                If (move.isFiveMatch && move.fiveMatchCells.isNotEmpty()) {
                    For (cell in move.fiveMatchCells) {
                        Val r = cell.first
                        Val c = cell.second
                        If (r in 0 until rows && c in 0 until cols) {
                            Val path = android.graphics.Path().apply {
                                MoveTo(getCellCornerX(r, c), getCellCornerY(r, c))
                                LineTo(getCellCornerX(r, c + 1), getCellCornerY(r, c + 1))
                                LineTo(getCellCornerX(r + 1, c + 1), getCellCornerY(r + 1, c + 1))
                                LineTo(getCellCornerX(r + 1, c), getCellCornerY(r + 1, c))
                                Close()
                            }
                            Canvas.drawPath(path, highlightPaint)
                        }
                    }
                }
            }
        }

        Private fun findTappedCell(x: Float, y: Float): Pair<Int, Int>? {
            Var minDistance = Float.MAX_VALUE
            Var bestCell: Pair<Int, Int>? = null
            
            For (r in 0 until rows) {
                For (c in 0 until cols) {
                    Val u = (c + 0.5f) / cols
                    Val v = (r + 0.5f) / rows
                    Val topX = (1 - u) * ptTL.x + u * ptTR.x
                    Val topY = (1 - u) * ptTL.y + u * ptTR.y
                    Val bottomX = (1 - u) * ptBL.x + u * ptBR.x
                    Val bottomY = (1 - u) * ptBL.y + u * ptBR.y

                    Val targetX = (1 - v) * topX + v * bottomX
                    Val targetY = (1 - v) * topY + v * bottomY
                    
                    Val dist = Math.hypot((x - targetX).toDouble(), (y - targetY).toDouble()).toFloat()
                    If (dist < minDistance) {
                        MinDistance = dist
                        BestCell = Pair(r, c)
                    }
                }
            }
            Return if (minDistance < 150f) bestCell else null
        }

        Override fun onTouchEvent(event: MotionEvent): Boolean {
            If (!isCalibrationMode && !isImageGrabberMode) return false

            If (isImageGrabberMode && (System.currentTimeMillis() - lastGrabberActivationTime < 300)) {
                Return false
            }

            Val x = event.x
            Val y = event.y

            When (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    SelectedCorner = when {
                        Abs(x - ptTL.x) < touchRadius && abs(y - ptTL.y) < touchRadius -> ptTL
                        Abs(x - ptTR.x) < touchRadius && abs(y - ptTR.y) < touchRadius -> ptTR
                        Abs(x - ptBL.x) < touchRadius && abs(y - ptBL.y) < touchRadius -> ptBL
                        Abs(x - ptBR.x) < touchRadius && abs(y - ptBR.y) < touchRadius -> ptBR
                        Else -> null
                    }
                    
                    If (selectedCorner != null) {
                        ActiveCorner = selectedCorner!!
                        MainHandler.post { refreshFloatingPanelUI() }
                        Invalidate()
                    } else {
                        Val cell = findTappedCell(x, y)
                        If (cell != null) {
                            Val (r, c) = cell
                            If (r in 0 until rows && c in 0 until cols) {
                                If (isImageGrabberMode) {
                                    If (!isGrabberProcessing) {
                                        IsGrabberProcessing = true
                                        GrabbedRow = r
                                        GrabbedCol = c
                                        ProcessSingleGrabberCrop(r, c)
                                    }
                                } else {
                                    DisabledCells[r][c] = !disabledCells[r][c]
                                    SavePreferences()
                                }
                                Invalidate()
                            }
                        }
                    }
                    Return selectedCorner != null || findTappedCell(x, y) != null
                }
                MotionEvent.ACTION_MOVE -> {
                    Val corner = selectedCorner ?: return false
                    Corner.set(x, y)

                    When (corner) {
                        PtTL -> {
                            If (abs(ptTL.x - ptBL.x) < magnetThreshold) ptTL.x = ptBL.x
                            If (abs(ptTL.y - ptTR.y) < magnetThreshold) ptTL.y = ptTR.y
                        }
                        PtTR -> {
                            If (abs(ptTR.x - ptBR.x) < magnetThreshold) ptTR.x = ptBR.x
                            If (abs(ptTR.y - ptTL.y) < magnetThreshold) ptTR.y = ptTL.y
                        }
                        PtBL -> {
                            If (abs(ptBL.x - ptTL.x) < magnetThreshold) ptBL.x = ptTL.x
                            If (abs(ptBL.y - ptBR.y) < magnetThreshold) ptBL.y = ptBR.y
                        }
                        PtBR -> {
                            If (abs(ptBR.x - ptTR.x) < magnetThreshold) ptBR.x = ptTR.x
                            If (abs(ptBR.y - ptBL.y) < magnetThreshold) ptBR.y = ptBL.y
                        }
                    }
                    Invalidate()
                    Return true
                }
                MotionEvent.ACTION_UP -> { selectedCorner = null }
            }
            Return false
        }
    }

    Private fun showToastOnMainThread(message: String) {
        MainHandler.post { Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show() }
    }

    Private fun stopCapturePipeline() {
        IsCapturing = false
        VirtualDisplay?.release(); virtualDisplay = null
        ImageReader?.close(); imageReader = null
        MediaProjection?.stop(); mediaProjection = null
    }

    Override fun onDestroy() {
        StopCapturePipeline()
        Synchronized(dynamicTemplates) {
            DynamicTemplates.forEach { it.release() }
            DynamicTemplates.clear()
        }
        FloatingControlView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        VisualOverlayView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        BackgroundThread?.quitSafely()
        Super.onDestroy()
    }
}
