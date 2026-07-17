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
                // 🛠️ [문법 오류 수정] Color.444444에서 Color.parseColor("#444444")로 변경
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
                
                if (!isCapturing || !isLogicEnabled) {
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
            var image = reader.acquireLatestImage()
            if (image == null) {
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

                val fullBitmap = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                fullBitmap.copyPixelsFromBuffer(buffer)

                val u = (c + 0.5f) / cols
                val v = (r + 0.5f) / rows
                val topX = (1 - u) * ptTL.x + u * ptTR.x
                val topY = (1 - u) * ptTL.y + u * ptTR.y
                val bottomX = (1 - u) * ptBL.x + u * ptBR.x
                val bottomY = (1 - u) * ptBL.y + u * ptBR.y 
                val targetX = (1 - v) * topX + v * bottomX
                val targetY = (1 - v) * topY + v * bottomY

                val cellW = ((ptTR.x - ptTL.x) / cols).toInt().coerceAtLeast(1)
                val cellH = ((ptBL.y - ptTL.y) / rows).toInt().coerceAtLeast(1)
                
                val startX = (targetX - cellW / 2).toInt().coerceIn(0, fullBitmap.width - cellW)
                val startY = (targetY - cellH / 2).toInt().coerceIn(0, fullBitmap.height - cellH)

                val cellBitmap = Bitmap.createBitmap(fullBitmap, startX, startY, cellW, cellH)
                
                saveBitmapToFile(cellBitmap, "grabbed_obstacle")
                
                cellBitmap.recycle()
                fullBitmap.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "기믹 크롭 연산 오류", e)
                isGrabberProcessing = false
            } finally {
                image.close()
            }
        }
    }

    private fun findBestMoves(board: Array<Array<BlockColor>>): List<MatchMove> {
        val tempMoves = mutableListOf<TempMove>()
        val dr = intArrayOf(0, 1) 
        val dc = intArrayOf(1, 0)

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val color1 = board[r][c]
                if (color1 == BlockColor.UNKNOWN) continue

                for (i in 0 until 2) {
                    val nr = r + dr[i]
                    val nc = c + dc[i]

                    if (nr in 0 until rows && nc in 0 until cols) {
                        val color2 = board[nr][nc]
                        if (color2 == BlockColor.UNKNOWN || color1 == color2) continue
                        
                        board[r][c] = color2
                        board[nr][nc] = color1

                        val match1 = evaluateMatchAt(board, r, c, color2)
                        val match2 = evaluateMatchAt(board, nr, nc, color1)

                        if (match1 != null) {
                            val isFive = match1.first == MatchType.DISCO_BALL
                            val cells = if (isFive) getFiveMatchCells(board, r, c, color2) else emptyList()
                            tempMoves.add(TempMove(r, c, nr, nc, color2, match1.first, match1.second, isFive, cells))
                        }
                        if (match2 != null) {
                            val isFive = match2.first == MatchType.DISCO_BALL
                            val cells = if (isFive) getFiveMatchCells(board, nr, nc, color1) else emptyList()
                            tempMoves.add(TempMove(nr, nc, r, c, color1, match2.first, match2.second, isFive, cells))
                        }

                        board[r][c] = color1
                        board[nr][nc] = color2
                    }
                }
            }
        }

        return tempMoves
            .sortedByDescending { it.score }
            .distinctBy { "${it.fromRow},${it.fromCol}->${it.toRow},${it.toCol}" }
            .map { temp ->
                val korColor = getKoreanColorName(temp.color)
                val desc = when (temp.matchType) {
                    MatchType.DISCO_BALL -> "⚡[$korColor 절대강자 OOXOO] 디스코볼 확정 배치! 💣"
                    MatchType.TNT -> "💣 [$korColor TNT] 강력한 폭탄 생성!"
                    MatchType.ROCKET -> "🚀 [$korColor 로켓] 라인 클리어!"
                    MatchType.NORMAL_3 -> "🧩 [$korColor 3매치] 일반 매칭"
                }
                MatchMove(temp.fromRow, temp.fromCol, temp.toRow, temp.toCol, desc, temp.isFiveMatch, temp.fiveMatchCells)
            }
    }

    private fun evaluateMatchAt(board: Array<Array<BlockColor>>, r: Int, c: Int, color: BlockColor): Pair<MatchType, Int>? {
        var hLeft = 0
        while (c - hLeft - 1 >= 0 && board[r][c - hLeft - 1] == color) hLeft++
        var hRight = 0
        while (c + hRight + 1 < cols && board[r][c + hRight + 1] == color) hRight++
        val horizontalCount = hLeft + hRight + 1

        var vUp = 0
        while (r - vUp - 1 >= 0 && board[r - vUp - 1][c] == color) vUp++
        var vDown = 0
        while (r + vDown + 1 < rows && board[r + vDown + 1][c] == color) vDown++
        val verticalCount = vUp + vDown + 1

        val hasHorizontalMatch = horizontalCount >= 3
        val hasVerticalMatch = verticalCount >= 3

        if (!hasHorizontalMatch && !hasVerticalMatch) return null

        return when {
            horizontalCount >= 5 || verticalCount >= 5 -> Pair(MatchType.DISCO_BALL, 1000)
            hasHorizontalMatch && hasVerticalMatch -> Pair(MatchType.TNT, 500)
            horizontalCount == 4 || verticalCount == 4 -> Pair(MatchType.ROCKET, 300)
            else -> Pair(MatchType.NORMAL_3, 100)
        }
    }

    private fun getFiveMatchCells(board: Array<Array<BlockColor>>, r: Int, c: Int, color: BlockColor): List<Pair<Int, Int>> {
        val cells = mutableListOf<Pair<Int, Int>>()
        
        var hLeft = 0
        while (c - hLeft - 1 >= 0 && board[r][c - hLeft - 1] == color) hLeft++
        var hRight = 0
        while (c + hRight + 1 < cols && board[r][c + hRight + 1] == color) hRight++
        val horizontalCount = hLeft + hRight + 1

        var vUp = 0
        while (r - vUp - 1 >= 0 && board[r - vUp - 1][c] == color) vUp++
        var vDown = 0
        while (r + vDown + 1 < rows && board[r + vDown + 1][c] == color) vDown++
        val verticalCount = vUp + vDown + 1

        if (horizontalCount >= 5) {
            for (offset in -hLeft..hRight) {
                cells.add(Pair(r, c + offset))
            }
        } else if (verticalCount >= 5) {
            for (offset in -vUp..vDown) {
                cells.add(Pair(r + offset, c))
            }
        }
        return cells
    }

    private fun analyzeAndSolvePerspective(bitmap: Bitmap): List<MatchMove> {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val board = Array(rows) { Array(cols) { BlockColor.UNKNOWN } }

        val approxCellW = ((ptTR.x - ptTL.x) / cols).toInt().coerceAtLeast(1)
        val approxCellH = ((ptBL.y - ptTL.y) / rows).toInt().coerceAtLeast(1)

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (disabledCells[r][c]) {
                    board[r][c] = BlockColor.UNKNOWN
                    continue
                }

                val u = (c + 0.5f) / cols
                val v = (r + 0.5f) / rows

                val topX = (1 - u) * ptTL.x + u * ptTR.x
                val topY = (1 - u) * ptTL.y + u * ptTR.y
                val bottomX = (1 - u) * ptBL.x + u * ptBR.x
                val bottomY = (1 - u) * ptBL.y + u * ptBR.y 

                val targetX = (1 - v) * topX + v * bottomX
                val targetY = (1 - v) * topY + v * bottomY

                board[r][c] = identifyCellContent(bitmap, pixels, width, height, targetX, targetY, approxCellW, approxCellH)
            }
        }
        return findBestMoves(board)
    }

    private fun identifyCellContent(bitmap: Bitmap, pixels: IntArray, width: Int, height: Int, centerX: Float, centerY: Float, cellW: Int, cellH: Int): BlockColor {
        val halfW = cellW / 2
        val halfH = cellH / 2
        val startX = (centerX - halfW).toInt().coerceIn(0, bitmap.width - cellW)
        val startY = (centerY - halfH).toInt().coerceIn(0, bitmap.height - cellH)
        
        try {
            val cellBitmap = Bitmap.createBitmap(bitmap, startX, startY, cellW, cellH)
            val isObstacle = checkObstacleWithOpenCV(cellBitmap)
            cellBitmap.recycle()
            
            if (isObstacle != ObstacleType.NONE) {
                return BlockColor.UNKNOWN
            }
        } catch (e: Exception) {}

        return detectNormalBlockColor(pixels, width, height, centerX, centerY)
    }

    private fun checkObstacleWithOpenCV(cellBitmap: Bitmap): ObstacleType {
        if (!isOpenCVInitialized) return ObstacleType.NONE
        
        val cellMat = Mat()
        Utils.bitmapToMat(cellBitmap, cellMat)
        Imgproc.cvtColor(cellMat, cellMat, Imgproc.COLOR_RGBA2GRAY)

        val resultMat = Mat()
        var matched = false

        synchronized(dynamicTemplates) {
            for (template in dynamicTemplates) {
                if (cellMat.cols() >= template.cols() && cellMat.rows() >= template.rows()) {
                    Imgproc.matchTemplate(cellMat, template, resultMat, Imgproc.TM_CCOEFF_NORMED)
                    val minMaxLocResult = Core.minMaxLoc(resultMat)
                    
                    if (minMaxLocResult.maxVal >= 0.80) {
                        matched = true
                        break
                    }
                }
            }
        }

        cellMat.release()
        resultMat.release()
        return if (matched) ObstacleType.DETECTED_OBSTACLE else ObstacleType.NONE
    }

    private fun detectNormalBlockColor(pixels: IntArray, width: Int, height: Int, centerX: Float, centerY: Float): BlockColor {
        val radius = 6  
        val cX = centerX.toInt()
        val cY = centerY.toInt()
        var rCnt = 0; var bCnt = 0; var yCnt = 0; var gCnt = 0; var pCnt = 0
        var scannedPixels = 0
        val hsv = FloatArray(3)

        for (y in (cY - radius)..(cY + radius)) {
            for (x in (cX - radius)..(cX + radius)) {
                if (x < 0 || x >= width || y < 0 || y >= height) continue
                val pixel = pixels[y * width + x]
                Color.colorToHSV(pixel, hsv)
                
                scannedPixels++
                val hue = hsv[0]
                val sat = hsv[1]
                val valValue = hsv[2]

                if (sat < 0.35f || valValue < 0.35f) continue
                
                when {
                    (hue in 0f..20f) || (hue in 340f..360f) -> rCnt++
                    hue in 42f..62f -> yCnt++   
                    hue in 63f..144f -> gCnt++
                    hue in 145f..250f -> bCnt++ 
                    hue in 251f..339f -> pCnt++
                }
            }
        }

        if (scannedPixels == 0) return BlockColor.UNKNOWN

        val threshold = (scannedPixels * 0.25f).toInt().coerceAtLeast(20)
        val maxMap = mapOf(
            BlockColor.RED to rCnt, BlockColor.BLUE to bCnt, 
            BlockColor.YELLOW to yCnt, BlockColor.GREEN to gCnt, BlockColor.PURPLE to pCnt
        )

        val best = maxMap.filter { it.value > threshold }.maxByOrNull { it.value }
        return best?.key ?: BlockColor.UNKNOWN
    }

    private fun getKoreanColorName(color: BlockColor): String {
        return when(color) {
            BlockColor.RED -> "빨강"; BlockColor.BLUE -> "파랑"; BlockColor.YELLOW -> "노랑"
            BlockColor.GREEN -> "초록"; BlockColor.PURPLE -> "보라"; else -> "미정"
        }
    }

    private fun savePreferences() {
        val prefs = getSharedPreferences("GridHelperPrefs", Context.MODE_PRIVATE)
        val prefix = "${rows}x${cols}_" 
        
        prefs.edit().apply {
            putInt("rows", rows)
            putInt("cols", cols)
            putBoolean("isGridVisible", isGridVisible)
            
            putFloat("${prefix}ptTL_x", ptTL.x)
            putFloat("${prefix}ptTL_y", ptTL.y)
            putFloat("${prefix}ptTR_x", ptTR.x)
            putFloat("${prefix}ptTR_y", ptTR.y)
            putFloat("${prefix}ptBL_x", ptBL.x)
            putFloat("${prefix}ptBL_y", ptBL.y)
            putFloat("${prefix}ptBR_x", ptBR.x)
            putFloat("${prefix}ptBR_y", ptBR.y)
            
            val sb = StringBuilder()
            for (r in 0 until 20) {
                for (c in 0 until 20) {
                    if (disabledCells[r][c]) {
                        sb.append("$r,$c;")
                    }
                }
            }
            putString("${prefix}disabledCells", sb.toString())
            apply()
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("GridHelperPrefs", Context.MODE_PRIVATE)
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()

        rows = prefs.getInt("rows", 11)
        cols = prefs.getInt("cols", 9)
        isGridVisible = prefs.getBoolean("isGridVisible", true)

        val prefix = "${rows}x${cols}_"

        ptTL.set(prefs.getFloat("${prefix}ptTL_x", w * 0.05f), prefs.getFloat("${prefix}ptTL_y", h * 0.25f))
        ptTR.set(prefs.getFloat("${prefix}ptTR_x", w * 0.95f), prefs.getFloat("${prefix}ptTR_y", h * 0.25f))
        ptBL.set(prefs.getFloat("${prefix}ptBL_x", w * 0.05f), prefs.getFloat("${prefix}ptBL_y", h * 0.75f))
        ptBR.set(prefs.getFloat("${prefix}ptBR_x", w * 0.95f), prefs.getFloat("${prefix}ptBR_y", h * 0.75f))
        
        for (r in 0 until 20) {
            for (c in 0 until 20) {
                disabledCells[r][c] = false
            }
        }
        val disabledStr = prefs.getString("${prefix}disabledCells", "") ?: ""
        if (disabledStr.isNotEmpty()) {
            val tokens = disabledStr.split(";")
            for (token in tokens) {
                if (token.isNotEmpty()) {
                    val parts = token.split(",")
                    if (parts.size == 2) {
                        val r = parts[0].toIntOrNull()
                        val c = parts[1].toIntOrNull()
                        if (r != null && c != null && r < 20 && c < 20) {
                            disabledCells[r][c] = true
                        }
                    }
                }
            }
        }
        activeCorner = ptTL
    }

    inner class VisualOverlayView(context: Context) : View(context) {
        private val linePaint = Paint().apply { color = Color.parseColor("#8000FF00"); style = Paint.Style.STROKE; strokeWidth = 3f }
        private val handlePaint = Paint().apply { color = Color.parseColor("#FF00DF"); style = Paint.Style.FILL }
        private val activeHandlePaint = Paint().apply { color = Color.YELLOW; style = Paint.Style.FILL }
        private val strokePaint = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 5f }
        
        private val highlightPaint = Paint().apply { color = Color.parseColor("#80FFFF00"); style = Paint.Style.FILL }
        
        private val disabledOverlayPaint = Paint().apply { color = Color.parseColor("#80FF0000"); style = Paint.Style.FILL }
        private val disabledLinePaint = Paint().apply { color = Color.RED; strokeWidth = 5f; style = Paint.Style.STROKE }

        private var currentMoves = listOf<MatchMove>()
        private var selectedCorner: PointF? = null
        private val touchRadius = 70f
        private val magnetThreshold = 12f 

        fun updateMoves(moves: List<MatchMove>) {
            this.currentMoves = moves
            invalidate()
        }

        private fun getCellCornerX(r: Int, c: Int): Float {
            val u = c.toFloat() / cols
            val v = r.toFloat() / rows
            val topX = (1 - u) * ptTL.x + u * ptTR.x
            val bottomX = (1 - u) * ptBL.x + u * ptBR.x
            return (1 - v) * topX + v * bottomX
        }

        private fun getCellCornerY(r: Int, c: Int): Float {
            val u = c.toFloat() / cols
            val v = r.toFloat() / rows
            val topY = (1 - u) * ptTL.y + u * ptTR.y
            val bottomY = (1 - u) * ptBL.y + u * ptBR.y
            return (1 - v) * topY + v * bottomY
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            if (isGridVisible || isCalibrationMode) {
                for (i in 0..cols) {
                    val ratio = i.toFloat() / cols
                    val topX = (1 - ratio) * ptTL.x + ratio * ptTR.x
                    val topY = (1 - ratio) * ptTL.y + ratio * ptTR.y
                    val botX = (1 - ratio) * ptBL.x + ratio * ptBR.x
                    val botY = (1 - ratio) * ptBL.y + ratio * ptBR.y
                    canvas.drawLine(topX, topY, botX, botY, linePaint)
                }
                for (j in 0..rows) {
                    val ratio = j.toFloat() / rows
                    val leftX = (1 - ratio) * ptTL.x + ratio * ptBL.x
                    val leftY = (1 - ratio) * ptTL.y + ratio * ptBL.y
                    val rightX = (1 - ratio) * ptTR.x + ratio * ptBR.x
                    val rightY = (1 - ratio) * ptTR.y + ratio * ptBR.y
                    canvas.drawLine(leftX, leftY, rightX, rightY, linePaint)
                }

                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        if (disabledCells[r][c]) {
                            val xTL = getCellCornerX(r, c)
                            val yTL = getCellCornerY(r, c)
                            val xTR = getCellCornerX(r, c + 1)
                            val yTR = getCellCornerY(r, c + 1)
                            val xBL = getCellCornerX(r + 1, c)
                            val yBL = getCellCornerY(r + 1, c)
                            val xBR = getCellCornerX(r + 1, c + 1)
                            val yBR = getCellCornerY(r + 1, c + 1)

                            canvas.drawLine(xTL, yTL, xBR, yBR, disabledLinePaint)
                            canvas.drawLine(xTR, yTR, xBL, yBL, disabledLinePaint)

                            val cX = (xTL + xBR) / 2
                            val cY = (yTL + yBR) / 2
                            canvas.drawCircle(cX, cY, 15f, disabledOverlayPaint)
                        }
                    }
                }
            }

            if (isCalibrationMode) {
                fun drawHandle(corner: PointF, isActive: Boolean) {
                    canvas.drawCircle(corner.x, corner.y, 30f, if (isActive) activeHandlePaint else handlePaint)
                    canvas.drawCircle(corner.x, corner.y, 30f, strokePaint)
                }
                drawHandle(ptTL, activeCorner == ptTL)
                drawHandle(ptTR, activeCorner == ptTR)
                drawHandle(ptBL, activeCorner == ptBL)
                drawHandle(ptBR, activeCorner == ptBR)
            }

            if (isLogicEnabled && currentMoves.isNotEmpty()) {
                val move = currentMoves.first()
                if (move.isFiveMatch && move.fiveMatchCells.isNotEmpty()) {
                    for (cell in move.fiveMatchCells) {
                        val r = cell.first
                        val c = cell.second
                        if (r in 0 until rows && c in 0 until cols) {
                            val path = android.graphics.Path().apply {
                                moveTo(getCellCornerX(r, c), getCellCornerY(r, c))
                                lineTo(getCellCornerX(r, c + 1), getCellCornerY(r, c + 1))
                                lineTo(getCellCornerX(r + 1, c + 1), getCellCornerY(r + 1, c + 1))
                                lineTo(getCellCornerX(r + 1, c), getCellCornerY(r + 1, c))
                                close()
                            }
                            canvas.drawPath(path, highlightPaint)
                        }
                    }
                }
            }
        }

        private fun findTappedCell(x: Float, y: Float): Pair<Int, Int>? {
            var minDistance = Float.MAX_VALUE
            var bestCell: Pair<Int, Int>? = null
            
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val u = (c + 0.5f) / cols
                    val v = (r + 0.5f) / rows
                    val topX = (1 - u) * ptTL.x + u * ptTR.x
                    val topY = (1 - u) * ptTL.y + u * ptTR.y
                    val bottomX = (1 - u) * ptBL.x + u * ptBR.x
                    val bottomY = (1 - u) * ptBL.y + u * ptBR.y

                    val targetX = (1 - v) * topX + v * bottomX
                    val targetY = (1 - v) * topY + v * bottomY
                    
                    val dist = Math.hypot((x - targetX).toDouble(), (y - targetY).toDouble()).toFloat()
                    if (dist < minDistance) {
                        minDistance = dist
                        bestCell = Pair(r, c)
                    }
                }
            }
            return if (minDistance < 150f) bestCell else null
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isCalibrationMode && !isImageGrabberMode) return false

            if (isImageGrabberMode && (System.currentTimeMillis() - lastGrabberActivationTime < 300)) {
                return false
            }

            val x = event.x
            val y = event.y

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    selectedCorner = when {
                        abs(x - ptTL.x) < touchRadius && abs(y - ptTL.y) < touchRadius -> ptTL
                        abs(x - ptTR.x) < touchRadius && abs(y - ptTR.y) < touchRadius -> ptTR
                        abs(x - ptBL.x) < touchRadius && abs(y - ptBL.y) < touchRadius -> ptBL
                        abs(x - ptBR.x) < touchRadius && abs(y - ptBR.y) < touchRadius -> ptBR
                        else -> null
                    }
                    
                    if (selectedCorner != null) {
                        activeCorner = selectedCorner!!
                        mainHandler.post { refreshFloatingPanelUI() }
                        invalidate()
                    } else {
                        val cell = findTappedCell(x, y)
                        if (cell != null) {
                            val (r, c) = cell
                            if (r in 0 until rows && c in 0 until cols) {
                                if (isImageGrabberMode) {
                                    if (!isGrabberProcessing) {
                                        isGrabberProcessing = true
                                        grabbedRow = r
                                        grabbedCol = c
                                        processSingleGrabberCrop(r, c)
                                    }
                                } else {
                                    disabledCells[r][c] = !disabledCells[r][c]
                                    savePreferences()
                                }
                                invalidate()
                            }
                        }
                    }
                    return selectedCorner != null || findTappedCell(x, y) != null
                }
                MotionEvent.ACTION_MOVE -> {
                    val corner = selectedCorner ?: return false
                    corner.set(x, y)

                    when (corner) {
                        ptTL -> {
                            if (abs(ptTL.x - ptBL.x) < magnetThreshold) ptTL.x = ptBL.x
                            if (abs(ptTL.y - ptTR.y) < magnetThreshold) ptTL.y = ptTR.y
                        }
                        ptTR -> {
                            if (abs(ptTR.x - ptBR.x) < magnetThreshold) ptTR.x = ptBR.x
                            if (abs(ptTR.y - ptTL.y) < magnetThreshold) ptTR.y = ptTL.y
                        }
                        ptBL -> {
                            if (abs(ptBL.x - ptTL.x) < magnetThreshold) ptBL.x = ptTL.x
                            if (abs(ptBL.y - ptBR.y) < magnetThreshold) ptBL.y = ptBR.y
                        }
                        ptBR -> {
                            if (abs(ptBR.x - ptTR.x) < magnetThreshold) ptBR.x = ptTR.x
                            if (abs(ptBR.y - ptBL.y) < magnetThreshold) ptBR.y = ptBL.y
                        }
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> { selectedCorner = null }
            }
            return false
        }
    }

    private fun showToastOnMainThread(message: String) {
        mainHandler.post { Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show() }
    }

    private fun stopCapturePipeline() {
        isCapturing = false
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        mediaProjection?.stop(); mediaProjection = null
    }

    override fun onDestroy() {
        stopCapturePipeline()
        synchronized(dynamicTemplates) {
            dynamicTemplates.forEach { it.release() }
            dynamicTemplates.clear()
        }
        floatingControlView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        visualOverlayView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        backgroundThread?.quitSafely()
        super.onDestroy()
    }
}
