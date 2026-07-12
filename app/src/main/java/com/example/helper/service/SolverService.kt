package com.example.helper.service

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
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
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
    private var controlPanel: LinearLayout? = null
    
    // UI 컴포넌트들
    private var statusText: TextView? = null
    private var errorText: TextView? = null
    private var expandedControlsLayout: LinearLayout? = null
    private var btnFoldToggle: Button? = null
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    private lateinit var prefs: SharedPreferences

    // 설정 변수들
    private var boundsLeft = 50
    private var boundsTop = 500
    private var boundsRight = 1000
    private var boundsBottom = 1500
    
    // 🟢 [기능 2] 동적 격자 개수 조절 변수 (초기값 7x9)
    private var gridCols = 7
    private var gridRows = 9

    // 🟢 [기능 4] 분석 활성화 온오프 및 UI 토글 상태
    private var isAnalysisEnabled = true
    private var isGridVisible = true
    private var isPanelFolded = false // 🟢 [기능 3] 조절판 축소 여부

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("GridSettings", Context.MODE_PRIVATE)
        loadSettings() 

        // 즉시 핵심 로직 설정 동기화
        GameConfig.COLS = gridCols
        GameConfig.ROWS = gridRows

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "solver_channel")
            .setContentTitle("매칭 헬퍼 작동 중")
            .setContentText("분석 시스템이 대기 중입니다.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        // 1. 격자 뷰 레이아웃 등록
        overlayView = OverlayView(this)
        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager?.addView(overlayView, overlayParams)

        // 2. 통합 스마트 제어판 UI 구성
        createControlPanel()

        handlerThread = HandlerThread("CaptureThread").apply { start() }
        backgroundHandler = Handler(handlerThread!!.looper)
    }

    private fun createControlPanel() {
        controlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FA111111")) 
            setPadding(15, 15, 15, 15)
        }

        // ==========================================
        // 🟢 [기능 4] 고정 상단바 (상태 안내판, 최소화 토글, 킬스위치)
        // ==========================================
        val topHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        statusText = TextView(this).apply {
            setTextColor(Color.GREEN)
            textSize = 12f
            text = "● 헬퍼 실행 중"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topHeaderRow.addView(statusText)

        // [기능 3] 조절판 접기/펴기 버튼
        btnFoldToggle = Button(this).apply {
            text = "⚙️ 조절 설정"
            textSize = 11f
            setTextColor(Color.WHITE)
            setOnClickListener {
                isPanelFolded = !isPanelFolded
                if (isPanelFolded) {
                    expandedControlsLayout?.visibility = View.GONE
                    text = "🛠️ 조절판 열기"
                } else {
                    expandedControlsLayout?.visibility = View.VISIBLE
                    text = "⚙️ 조절 설정"
                }
            }
        }
        topHeaderRow.addView(btnFoldToggle)

        // [기능 4] 서비스 완전 종료 킬스위치 (Kill Switch)
        val btnKillService = Button(this).apply {
            text = "🔴 종료"
            textSize = 11f
            setTextColor(Color.RED)
            setOnClickListener {
                Toast.makeText(this@SolverService, "매칭 헬퍼 서비스를 종료합니다.", Toast.LENGTH_SHORT).show()
                stopSelf()
            }
        }
        topHeaderRow.addView(btnKillService)
        controlPanel?.addView(topHeaderRow)

        // 에러 출력란
        errorText = TextView(this).apply {
            setTextColor(Color.RED)
            textSize = 11f
            visibility = View.GONE
            setPadding(0, 5, 0, 5)
        }
        controlPanel?.addView(errorText)

        // ==========================================
        // 🟢 접고 펼쳐지는 상세 설정 레이아웃 구역
        // ==========================================
        expandedControlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 10, 0, 0)
        }

        // 현재 수치 디스플레이
        val infoDetailsText = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }
        
        fun updateDetailsDisplay() {
            infoDetailsText.text = "좌우: $boundsLeft~$boundsRight | 상하: $boundsTop~$boundsBottom\n크기: ${gridCols}열 x ${gridRows}행"
        }
        updateDetailsDisplay()
        expandedControlsLayout?.addView(infoDetailsText)

        // 공통 버튼 생성 팩토리
        fun addControlRow(label: String, decAction: () -> Unit, incAction: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val lbl = TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(10, 0, 10, 0)
            }
            val btnMinus = Button(this).apply { text = "-" ; setOnClickListener { decAction(); updateDetailsDisplay(); refreshOverlay() } }
            val btnPlus = Button(this).apply { text = "+" ; setOnClickListener { incAction(); updateDetailsDisplay(); refreshOverlay() } }
            
            row.addView(btnMinus)
            row.addView(lbl)
            row.addView(btnPlus)
            expandedControlsLayout?.addView(row)
        }

        // 1. 가로/세로 영역 조절 버튼 배치
        addControlRow("가로이동 (-/+)", { boundsLeft -= 10; boundsRight -= 10 }, { boundsLeft += 10; boundsRight += 10 })
        addControlRow("가로크기 (-/+)", { boundsLeft += 10; boundsRight -= 10 }, { boundsLeft -= 10; boundsRight += 10 })
        addControlRow("세로이동 (-/+)", { boundsTop -= 10; boundsBottom -= 10 }, { boundsTop += 10; boundsBottom += 10 })
        addControlRow("세로크기 (-/+)", { boundsTop += 10; boundsBottom -= 10 }, { boundsTop -= 10; boundsBottom += 10 })

        // 2. 🟢 [기능 2] 격자 칸 개수 실시간 변동 조절 버튼 배치
        addControlRow("격자 가로(열 개수)", { if (gridCols > 3) gridCols-- }, { if (gridCols < 15) gridCols++ })
        addControlRow("격자 세로(행 개수)", { if (gridRows > 3) gridRows-- }, { if (gridRows < 20) gridRows++ })

        // 3. 기능 토글 하단바 구성
        val utilityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 0)
        }

        // 격자 온오프 버튼
        val btnToggleGrid = Button(this).apply {
            text = "격자 숨기기"
            textSize = 11f
            setTextColor(Color.YELLOW)
            setOnClickListener {
                isGridVisible = !isGridVisible
                text = if (isGridVisible) "격자 숨기기" else "격자 보이기"
                refreshOverlay()
            }
        }
        utilityRow.addView(btnToggleGrid)

        // [기능 4] 분석 연동 실시간 On/Off 스위치 버튼
        val btnToggleAnalysis = Button(this).apply {
            text = "분석 중지"
            textSize = 11f
            setTextColor(Color.CYAN)
            setOnClickListener {
                isAnalysisEnabled = !isAnalysisEnabled
                if (isAnalysisEnabled) {
                    text = "분석 중지"
                    statusText?.text = "● 헬퍼 실행 중"
                    statusText?.setTextColor(Color.GREEN)
                } else {
                    text = "분석 시작"
                    statusText?.text = "■ 분석 일시중지됨"
                    statusText?.setTextColor(Color.YELLOW)
                    overlayView?.clearCandidates() 
                }
            }
        }
        utilityRow.addView(btnToggleAnalysis)

        expandedControlsLayout?.addView(utilityRow)
        controlPanel?.addView(expandedControlsLayout)

        val panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
        }
        windowManager?.addView(controlPanel, panelParams)
    }

    private fun refreshOverlay() {
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
        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")
        
        if (resultCode != -1 && data != null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            
            // 🟢 [기능 1 - 해결 핵심] 안드로이드 14 보안 동기화 대응 핸들러 딜레이 부여
            // 서비스 래퍼가 OS에 Foreground Service 상태로 도달하고 바인딩을 매칭할 시간(200ms)을 보장합니다.
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    mediaProjection = mpManager.getMediaProjection(resultCode, data)
                    if (mediaProjection == null) {
                        displayErrorToUser("화면 공유 권한 승인을 획득하지 못했습니다. 다시 시도해 주세요.")
                    } else {
                        errorText?.visibility = View.GONE
                        startCapture()
                    }
                } catch (e: SecurityException) {
                    displayErrorToUser("보안 거부됨: OS가 Foreground 서비스 승인 절차를 아직 대기 중입니다. 앱을 끈 후 다시 실행해 보세요.\n[원인: ${e.localizedMessage}]")
                } catch (e: Exception) {
                    displayErrorToUser("프로젝션 토큰 생성 에러: ${e.localizedMessage}")
                }
            }, 200) 
        } else {
            displayErrorToUser("MainActivity로부터 인텐트 응답을 받지 못했습니다.")
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
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, backgroundHandler
            )
        } catch (e: Exception) {
            displayErrorToUser("가상 화면 생성 파이프라인 실패: ${e.localizedMessage}")
            return
        }

        imageReader?.setOnImageAvailableListener({ reader ->
            if (!isAnalysisEnabled) return@setOnImageAvailableListener // [기능 4] 중지 시 연산 패스
            
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
                // 프레임 드랍 예외 보호
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
        if (controlPanel != null) windowManager?.removeView(controlPanel)
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

        fun clearCandidates() {
            this.matchCandidates = emptyList()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!isVisibleMode || bRight <= bLeft || bBottom <= bTop) return

            // 🟢 [기능 2] 동적으로 변경된 행/열 값을 대입하여 화면 격자선을 실시간 드로잉합니다.
            val cellW = (bRight - bLeft) / drawCols.toFloat()
            val cellH = (bBottom - bTop) / drawRows.toFloat()

            // 가로 격자선 그리기
            for (r in 0..drawRows) {
                val y = bTop + r * cellH
                canvas.drawLine(bLeft.toFloat(), y, bRight.toFloat(), y, gridPaint)
            }
            // 세로 격자선 그리기
            for (c in 0..drawCols) {
                val x = bLeft + c * cellW
                canvas.drawLine(x, bTop.toFloat(), x, bBottom.toFloat(), gridPaint)
            }

            // 알고리즘 추천 화살표 그리기
            val bestCandidate = matchCandidates.maxByOrNull { it.score } ?: return
            if (bestCandidate.score > 0) {
                val r = bestCandidate.row
                val c = bestCandidate.col
                
                // 가변된 칸수에 맞춰 화살표 중심점 자동 추적
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
