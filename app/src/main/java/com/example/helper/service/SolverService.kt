package com.example.helper.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat

import com.example.helper.core.GameConfig
import com.example.helper.core.GridBounds
import com.example.helper.core.GridDetector
import com.example.helper.core.Match3Solver
import com.example.helper.core.MatchCandidate
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class SolverService : Service() {
    private var windowManager: WindowManager? = null
    private var overlayView: OverlayView? = null
    
    // UI 최상위 컨테이너 및 상태 뷰
    private var controlPanelContainer: LinearLayout? = null
    private lateinit var panelParams: WindowManager.LayoutParams
    
    private var statusText: TextView? = null
    private var errorText: TextView? = null
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    private lateinit var prefs: SharedPreferences

    // 격자 좌표 설정 변수
    private var boundsLeft = 50
    private var boundsTop = 500
    private var boundsRight = 1000
    private var boundsBottom = 1500
    
    // 격자 칸수 변수
    private var gridCols = 7
    private var gridRows = 9

    // 컨트롤 플래그
    private var isAnalysisEnabled = true
    private var isGridVisible = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("GridSettings", Context.MODE_PRIVATE)
        loadSettings() 

        // 연산 코어엔진에 가로/세로 칸수 동기화
        GameConfig.COLS = gridCols
        GameConfig.ROWS = gridRows

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "solver_channel")
            .setContentTitle("매칭 헬퍼 작동 중")
            .setContentText("실시간 화면 분석 시스템이 켜져 있습니다.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        // 1. 투명 격자선 오버레이 뷰 등록
        overlayView = OverlayView(this)
        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager?.addView(overlayView, overlayParams)

        // 2. 고기능 통합 플로팅 제어판 빌드
        createControlPanel()

        handlerThread = HandlerThread("CaptureThread").apply { start() }
        backgroundHandler = Handler(handlerThread!!.looper)
    }

    // 🎯 [UX 개선 핵심] 슬라이더와 미세조절 버튼을 결합한 통합 로우 생성 함수
    private fun createSliderRow(
        bodyLayout: LinearLayout,
        title: String,
        currentVal: Int,
        maxVal: Int,
        onAction: (Int) -> Unit
    ): SeekBar {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }
        
        val labelLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        
        val tvLabel = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        
        val tvValue = TextView(this).apply {
            text = currentVal.toString()
            setTextColor(Color.YELLOW)
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(10, 0, 10, 0)
        }
        
        labelLayout.addView(tvLabel)
        labelLayout.addView(tvValue)
        container.addView(labelLayout)
        
        val controlLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        
        val seekBar = SeekBar(this).apply {
            max = maxVal
            progress = currentVal
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        
        val btnMinus = Button(this).apply {
            text = "-"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#44FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(100, 80)
            setOnClickListener {
                val step = if (title.contains("수")) 1 else 10
                seekBar.progress = maxOf(0, seekBar.progress - step)
            }
        }
        
        val btnPlus = Button(this).apply {
            text = "+"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#44FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(100, 80)
            setOnClickListener {
                val step = if (title.contains("수")) 1 else 10
                seekBar.progress = minOf(maxVal, seekBar.progress + step)
            }
        }
        
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                var p = progress
                if (title.contains("수") && p < 3) {
                    p = 3
                    seekBar.progress = 3
                }
                tvValue.text = p.toString()
                onAction(p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        
        controlLayout.addView(btnMinus)
        controlLayout.addView(seekBar)
        controlLayout.addView(btnPlus)
        container.addView(controlLayout)
        
        bodyLayout.addView(container)
        return seekBar
    }

    private fun createControlPanel() {
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        // 1. 최상위 카드 컨테이너 설정 (디자인 강화 및 플로팅 카드화)
        controlPanelContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2151515")) // 고급스러운 딥 다크 테마
                cornerRadius = 28f
                setStroke(3, Color.parseColor("#66FFFFFF")) // 은은한 실버 아웃라인
            }
            setPadding(20, 16, 20, 16)
        }

        // 2. 드래그 이동이 가능한 상단 헤더 바 구축
        val headerView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 4, 4, 12)
        }

        val tvTitle = TextView(this).apply {
            text = "☰ 매칭 헬퍼 (드래그 이동)"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerView.addView(tvTitle)

        val bodyLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val btnToggleMinimize = Button(this).apply {
            text = "접기"
            textSize = 11f
            setTextColor(Color.CYAN)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(120, 80)
            setOnClickListener {
                if (bodyLayout.visibility == View.VISIBLE) {
                    bodyLayout.visibility = View.GONE
                    text = "펼치기"
                } else {
                    bodyLayout.visibility = View.VISIBLE
                    text = "접기"
                }
            }
        }
        headerView.addView(btnToggleMinimize)

        val btnKill = Button(this).apply {
            text = "종료"
            textSize = 11f
            setTextColor(Color.RED)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(120, 80)
            setOnClickListener {
                Toast.makeText(this@SolverService, "매칭 헬퍼를 종료합니다.", Toast.LENGTH_SHORT).show()
                stopSelf()
            }
        }
        headerView.addView(btnKill)
        controlPanelContainer?.addView(headerView)

        errorText = TextView(this).apply {
            setTextColor(Color.RED)
            textSize = 11f
            visibility = View.GONE
            setPadding(4, 0, 4, 8)
        }
        bodyLayout.addView(errorText)

        statusText = TextView(this).apply {
            text = "● 실시간 분석 엔진 가동 중"
            setTextColor(Color.GREEN)
            textSize = 12f
            setPadding(4, 0, 4, 12)
        }
        bodyLayout.addView(statusText)

        // 3. 편리한 슬라이더 대량 배치 (액션 유도 연동)
        createSliderRow(bodyLayout, "좌측 경계 (Left)", boundsLeft, screenWidth) { boundsLeft = it; triggerRender() }
        createSliderRow(bodyLayout, "우측 경계 (Right)", boundsRight, screenWidth) { boundsRight = it; triggerRender() }
        createSliderRow(bodyLayout, "상단 경계 (Top)", boundsTop, screenHeight) { boundsTop = it; triggerRender() }
        createSliderRow(bodyLayout, "하단 경계 (Bottom)", boundsBottom, screenHeight) { boundsBottom = it; triggerRender() }
        createSliderRow(bodyLayout, "가로 격자 수 (열)", gridCols, 15) { gridCols = it; triggerRender() }
        createSliderRow(bodyLayout, "세로 격자 수 (행)", gridRows, 25) { gridRows = it; triggerRender() }

        // 하단 조작 토글 라인
        val bottomActionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 4)
        }

        val btnToggleGrid = Button(this).apply {
            text = "격자선 숨기기"
            setTextColor(Color.YELLOW)
            textSize = 11f
            setBackgroundColor(Color.parseColor("#22FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(0, 95, 1f).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener {
                isGridVisible = !isGridVisible
                text = if (isGridVisible) "격자선 숨기기" else "격자선 보이기"
                triggerRender()
            }
        }
        bottomActionRow.addView(btnToggleGrid)

        val btnToggleAnalysis = Button(this).apply {
            text = "분석 일시정지"
            setTextColor(Color.parseColor("#FF00E5FF"))
            textSize = 11f
            setBackgroundColor(Color.parseColor("#22FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(0, 95, 1f).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener {
                isAnalysisEnabled = !isAnalysisEnabled
                if (isAnalysisEnabled) {
                    text = "분석 일시정지"
                    statusText?.text = "● 실시간 분석 엔진 가동 중"
                    statusText?.setTextColor(Color.GREEN)
                } else {
                    text = "분석 재개하기"
                    statusText?.text = "■ 분석 일시중지됨"
                    statusText?.setTextColor(Color.YELLOW)
                    overlayView?.clearTargets()
                }
            }
        }
        bottomActionRow.addView(btnToggleAnalysis)
        bodyLayout.addView(bottomActionRow)

        controlPanelContainer?.addView(bodyLayout)

        // WindowManager 위치 배치 설정 규격 (가로폭을 92%로 채워 콤팩트한 패널 구현)
        panelParams = WindowManager.LayoutParams(
            (screenWidth * 0.92).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth * 0.04).toInt()
            y = 150
        }

        // 4. [이동 편의성] 헤더 바 터치 시 자유로운 윈도우 이동 기능 바인딩
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        headerView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = panelParams.x
                    initialY = panelParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    panelParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    panelParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(controlPanelContainer, panelParams)
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(controlPanelContainer, panelParams)
    }

    private fun triggerRender() {
        saveSettings()
        GameConfig.COLS = gridCols
        GameConfig.ROWS = gridRows
        overlayView?.updateGrid(boundsLeft, boundsTop, boundsRight, boundsBottom, emptyList(), isGridVisible, gridCols, gridRows)
    }

    private fun displayErrorToUser(message: String) {
        Handler(Looper.getMainLooper()).post {
            errorText?.apply {
                text = "⚠️ $message"
                visibility = View.VISIBLE
            }
        }
    }

    private fun saveSettings() {
        prefs.edit().apply {
            putInt("left", boundsLeft)
            putInt("top", boundsTop)
            putInt("right", boundsRight)
            putInt("bottom", boundsBottom)
            putInt("cols", gridCols)
            putInt("rows", gridRows)
            apply()
        }
    }

    private fun loadSettings() {
        val metrics = resources.displayMetrics
        boundsLeft = prefs.getInt("left", (metrics.widthPixels * 0.05).toInt())
        boundsTop = prefs.getInt("top", (metrics.heightPixels * 0.25).toInt())
        boundsRight = prefs.getInt("right", (metrics.widthPixels * 0.95).toInt())
        boundsBottom = prefs.getInt("bottom", (metrics.heightPixels * 0.75).toInt())
        gridCols = prefs.getInt("cols", 7)
        gridRows = prefs.getInt("rows", 9)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("solver_channel", "Solver Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra<Intent>("data")
        }
        
        if (resultCode == Activity.RESULT_OK && data != null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            try {
                mediaProjection = mpManager.getMediaProjection(resultCode, data)
                if (mediaProjection == null) {
                    displayErrorToUser("화면 공유 권한 승인을 획득하지 못했습니다. 다시 시작해 주세요.")
                } else {
                    errorText?.visibility = View.GONE
                    startCapture()
                }
            } catch (e: SecurityException) {
                displayErrorToUser("보안 거부: 앱 창에서 나가는 타이밍이 너무 빨랐습니다. 다시 활성화해 보세요.")
            } catch (e: Exception) {
                displayErrorToUser("캡처 초기화 에러: ${e.localizedMessage}")
            }
        } else {
            displayErrorToUser("정상적인 권한 응답 데이터를 받지 못했습니다. (코드 값: $resultCode)")
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        try {
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    backgroundHandler?.post {
                        virtualDisplay?.release()
                        imageReader?.close()
                        virtualDisplay = null
                        imageReader = null
                    }
                }
            }, backgroundHandler)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, backgroundHandler
            )
        } catch (e: Exception) {
            displayErrorToUser("가상 디스플레이 할당에 실패했습니다: ${e.localizedMessage}")
            return
        }

        imageReader?.setOnImageAvailableListener({ reader ->
            if (!isAnalysisEnabled) return@setOnImageAvailableListener 
            try {
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()

                val cleanBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                processFrame(cleanBitmap)
            } catch (e: Exception) {
                // 프레임 유실 무시
            }
        }, backgroundHandler)
    }

    private fun processFrame(bitmap: Bitmap) {
        val currentBounds = GridBounds(boundsLeft, boundsTop, boundsRight, boundsBottom)
        val gridResult = GridDetector.getGridDataFromBitmap(bitmap, currentBounds)
        
        if (gridResult.success && gridResult.categoryGrid != null && gridResult.bounds != null) {
            val candidates = Match3Solver.getMatchCandidates(gridResult.categoryGrid)
            Handler(Looper.getMainLooper()).post {
                if (isAnalysisEnabled) {
                    overlayView?.updateGrid(
                        left = gridResult.bounds.left,
                        top = gridResult.bounds.top,
                        right = gridResult.bounds.right,
                        bottom = gridResult.bounds.bottom,
                        candidates = candidates,
                        gridVisible = isGridVisible,
                        cols = gridCols,
                        rows = gridRows
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        handlerThread?.quitSafely()
        if (overlayView != null) windowManager?.removeView(overlayView)
        if (controlPanelContainer != null) windowManager?.removeView(controlPanelContainer)
    }

    class OverlayView(context: Context) : View(context) {
        private var bLeft = 0
        private var bTop = 0
        private var bRight = 0
        private var bBottom = 0
        private var matchCandidates: List<MatchCandidate> = emptyList()
        private var isVisibleMode = true
        private var drawCols = 7
        private var drawRows = 9

        private val gridPaint = Paint().apply {
            color = Color.parseColor("#AA00FF00")
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        private val linePaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 9f
            isAntiAlias = true
        }
        private val arrowPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        fun updateGrid(left: Int, top: Int, right: Int, bottom: Int, candidates: List<MatchCandidate>, gridVisible: Boolean, cols: Int, rows: Int) {
            this.bLeft = left
            this.bTop = top
            this.bRight = right
            this.bBottom = bottom
            this.isVisibleMode = gridVisible
            this.drawCols = cols
            this.drawRows = rows
            if (candidates.isNotEmpty()) {
                this.matchCandidates = candidates
            }
            invalidate()
        }

        fun clearTargets() {
            this.matchCandidates = emptyList()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!isVisibleMode || bRight <= bLeft || bBottom <= bTop) return

            val cellW = (bRight - bLeft) / drawCols.toFloat()
            val cellH = (bBottom - bTop) / drawRows.toFloat()

            for (r in 0..drawRows) {
                val y = bTop + r * cellH
                canvas.drawLine(bLeft.toFloat(), y, bRight.toFloat(), y, gridPaint)
            }
            for (c in 0..drawCols) {
                val x = bLeft + c * cellW
                canvas.drawLine(x, bTop.toFloat(), x, bBottom.toFloat(), gridPaint)
            }

            val bestCandidate = matchCandidates.maxByOrNull { it.score } ?: return
            if (bestCandidate.score > 0) {
                val r = bestCandidate.row
                val c = bestCandidate.col
                
                val fx = bLeft + c * cellW + cellW / 2f
                val fy = bTop + r * cellH + cellH / 2f
                var tx = fx
                var ty = fy

                when (bestCandidate.move) {
                    "right" -> tx += cellW
                    "down" -> ty += cellH
                }

                canvas.drawLine(fx, fy, tx, ty, linePaint)
                val angle = atan2((ty - fy).toDouble(), (tx - fx).toDouble())
                val arrowLength = 36f
                val arrowWidthAngle = Math.PI / 6.0
                val path = Path().apply {
                    moveTo(tx, ty)
                    lineTo((tx - arrowLength * cos(angle - arrowWidthAngle)).toFloat(), (ty - arrowLength * sin(angle - arrowWidthAngle)).toFloat())
                    lineTo((tx - arrowLength * cos(angle + arrowWidthAngle)).toFloat(), (ty - arrowLength * sin(angle + arrowWidthAngle)).toFloat())
                    close()
                }
                canvas.drawPath(path, arrowPaint)
            }
        }
    }
}
