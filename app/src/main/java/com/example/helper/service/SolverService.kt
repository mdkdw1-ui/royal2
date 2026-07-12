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
    
    // UI 최상위 컨테이너 및 상태 뷰
    private var controlPanelContainer: LinearLayout? = null
    private var expandedLayout: LinearLayout? = null
    private var collapsedLayout: LinearLayout? = null
    
    private var statusText: TextView? = null
    private var errorText: TextView? = null
    private var gridCountStatusText: TextView? = null
    
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

        // 사용자가 커스텀한 격자 갯수를 연산 코어엔진에 동기화
        GameConfig.COLS = gridCols
        GameConfig.ROWS = gridRows

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "solver_channel")
            .setContentTitle("매칭 헬퍼 작동 중")
            .setContentText("실시간 화면 분석 시스템이 켜져 있습니다.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
            
        // 안드로이드 14 강제 규격 바인딩
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

    private fun createControlPanel() {
        controlPanelContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2111111"))
            setPadding(12, 12, 12, 12)
        }

        // 1. 접혔을 때 보일 최소화 레이아웃
        collapsedLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE 
        }

        val btnMaximize = Button(this).apply {
            text = "🛠️ 격자 크기/갯수 조절판 펼치기"
            textSize = 12f
            setTextColor(Color.GREEN)
            setBackgroundColor(Color.parseColor("#44FFFFFF"))
            setOnClickListener {
                collapsedLayout?.visibility = View.GONE
                expandedLayout?.visibility = View.VISIBLE
            }
        }
        collapsedLayout?.addView(btnMaximize)

        // 2. 펼쳐졌을 때 보일 상세 조절 레이아웃
        expandedLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val topStatusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        statusText = TextView(this).apply {
            text = "● 실시간 분석 작동중"
            setTextColor(Color.GREEN)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topStatusRow.addView(statusText)

        val btnMinimize = Button(this).apply {
            text = "📁 조절판 접기"
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setOnClickListener {
                expandedLayout?.visibility = View.GONE
                collapsedLayout?.visibility = View.VISIBLE
            }
        }
        topStatusRow.addView(btnMinimize)

        val btnKill = Button(this).apply {
            text = "🔴 헬퍼 완전히 끄기"
            textSize = 11f
            setTextColor(Color.RED)
            setOnClickListener {
                Toast.makeText(this@SolverService, "매칭 헬퍼를 완전히 종료합니다.", Toast.LENGTH_SHORT).show()
                stopSelf()
            }
        }
        topStatusRow.addView(btnKill)
        expandedLayout?.addView(topStatusRow)

        errorText = TextView(this).apply {
            setTextColor(Color.RED)
            textSize = 11f
            visibility = View.GONE
            setPadding(0, 4, 0, 4)
        }
        expandedLayout?.addView(errorText)

        val coordinateInfoText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 4)
        }
        
        fun refreshUiTexts() {
            coordinateInfoText.text = "좌우범위: $boundsLeft ~ $boundsRight  |  상하범위: $boundsTop ~ $boundsBottom"
            gridCountStatusText?.text = "현재 격자 설정 ➔  [ 가로: ${gridCols}열 ]  x  [ 세로: ${gridRows}행 ]"
        }

        fun makeAdjustRow(title: String, onMinus: () -> Unit, onPlus: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val label = TextView(this).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 11f
                setPadding(8, 0, 8, 0)
            }
            val bm = Button(this).apply { text = "-" ; setOnClickListener { onMinus(); refreshUiTexts(); triggerRender() } }
            val bp = Button(this).apply { text = "+" ; setOnClickListener { onPlus(); refreshUiTexts(); triggerRender() } }
            row.addView(bm)
            row.addView(label)
            row.addView(bp)
            expandedLayout?.addView(row)
        }

        makeAdjustRow("전체 가로이동", { boundsLeft -= 10; boundsRight -= 10 }, { boundsLeft += 10; boundsRight += 10 })
        makeAdjustRow("가로폭 크기", { boundsLeft += 10; boundsRight -= 10 }, { boundsLeft -= 10; boundsRight += 10 })
        makeAdjustRow("전체 세로이동", { boundsTop -= 10; boundsBottom -= 10 }, { boundsTop += 10; boundsBottom += 10 })
        makeAdjustRow("세로높이 크기", { boundsTop += 10; boundsBottom -= 10 }, { boundsTop -= 10; boundsBottom += 10 })

        val gridCustomizerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
            setPadding(10, 10, 10, 10)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, 8, 0, 8)
            layoutParams = lp
        }

        gridCountStatusText = TextView(this).apply {
            setTextColor(Color.YELLOW)
            textSize = 12f
            gravity = Gravity.CENTER
        }
        gridCustomizerContainer.addView(gridCountStatusText)

        val colControlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val colLabel = TextView(this).apply { text = "가로 칸수(열) 조절 : "; setTextColor(Color.WHITE); textSize = 11f }
        val btnColMinus = Button(this).apply { text = "열 -" ; setOnClickListener { if(gridCols > 3) gridCols--; refreshUiTexts(); triggerRender() } }
        val btnColPlus = Button(this).apply { text = "열 +" ; setOnClickListener { if(gridCols < 15) gridCols++; refreshUiTexts(); triggerRender() } }
        colControlRow.addView(colLabel)
        colControlRow.addView(btnColMinus)
        colControlRow.addView(btnColPlus)
        gridCustomizerContainer.addView(colControlRow)

        val rowControlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val rowLabel = TextView(this).apply { text = "세로 칸수(행) 조절 : "; setTextColor(Color.WHITE); textSize = 11f }
        val btnRowMinus = Button(this).apply { text = "행 -" ; setOnClickListener { if(gridRows > 3) gridRows--; refreshUiTexts(); triggerRender() } }
        val btnRowPlus = Button(this).apply { text = "행 +" ; setOnClickListener { if(gridRows < 20) gridRows++; refreshUiTexts(); triggerRender() } }
        rowControlRow.addView(rowLabel)
        rowControlRow.addView(btnRowMinus)
        rowControlRow.addView(btnRowPlus)
        gridCustomizerContainer.addView(rowControlRow)

        expandedLayout?.addView(coordinateInfoText)
        expandedLayout?.addView(gridCustomizerContainer)

        val bottomActionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val btnToggleGrid = Button(this).apply {
            text = "격자 숨기기"
            setTextColor(Color.YELLOW)
            textSize = 11f
            setOnClickListener {
                isGridVisible = !isGridVisible
                text = if (isGridVisible) "격자 숨기기" else "격자 보이기"
                triggerRender()
            }
        }
        bottomActionRow.addView(btnToggleGrid)

        val btnToggleAnalysis = Button(this).apply {
            text = "분석 일시정지"
            setTextColor(Color.CYAN)
            textSize = 11f
            setOnClickListener {
                isAnalysisEnabled = !isAnalysisEnabled
                if (isAnalysisEnabled) {
                    text = "분석 일시정지"
                    statusText?.text = "● 실시간 분석 작동중"
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
        expandedLayout?.addView(bottomActionRow)

        controlPanelContainer?.addView(collapsedLayout)
        controlPanelContainer?.addView(expandedLayout)

        refreshUiTexts()

        val panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
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
                displayErrorToUser("보안 거부: 앱 메인 창에서 나가는 타이밍이 너무 빨랐습니다. 앱을 켠 상태에서 다시 활성화해 보세요.")
            } catch (e: Exception) {
                displayErrorToUser("캡처 초기화 에러: ${e.localizedMessage}")
            }
        } else {
            displayErrorToUser("정상적인 권한 응답 데이터를 받지 못했습니다. (코드 값: $resultCode)")
        }
        return START_NOT_STICKY
    }

    // 🎯 [핵심 교정 구역] Android 14 미디어 프로젝션 필수 규격 적용 완료
    private fun startCapture() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        try {
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            
            // 🔥 [수정 핵심] 가상 디스플레이 할당 전에 반드시 상태 추적 콜백을 먼저 등록해야 합니다.
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    // 사용자가 시스템 상단바 등에서 화면 캡처를 강제 종료했을 때 안전하게 자원을 해제합니다.
                    backgroundHandler?.post {
                        virtualDisplay?.release()
                        imageReader?.close()
                        virtualDisplay = null
                        imageReader = null
                    }
                }
            }, backgroundHandler)

            // 콜백이 무사히 등록된 후 가상 디스플레이를 생성하므로 에러가 해소됩니다.
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
