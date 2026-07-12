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
    private var statusText: TextView? = null
    private var errorText: TextView? = null
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    private lateinit var prefs: SharedPreferences

    // 사용자가 세팅할 경계선 좌표 변수
    private var boundsLeft = 50
    private var boundsTop = 500
    private var boundsRight = 1000
    private var boundsBottom = 1500

    // 🟢 [기능 1] 격자 온/오프 상태 변수
    private var isGridVisible = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("GridSettings", Context.MODE_PRIVATE)
        loadBounds() 

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "solver_channel")
            .setContentTitle("매칭 헬퍼 작동 중")
            .setContentText("오버레이 격자가 활성화되었습니다.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        // 1. 격자선 전용 뷰 (터치 관통)
        overlayView = OverlayView(this)
        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager?.addView(overlayView, overlayParams)

        // 2. 상단 미세조정 컨트롤 패널 생성
        createControlPanel()

        handlerThread = HandlerThread("CaptureThread").apply { start() }
        backgroundHandler = Handler(handlerThread!!.looper)
    }

    private fun createControlPanel() {
        controlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F2000000")) 
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(10, 15, 10, 15)
        }

        // 현재 수치 상태 시각화 라벨
        statusText = TextView(this).apply {
            setTextColor(Color.GREEN)
            textSize = 12f
            gravity = Gravity.CENTER
            text = "L: $boundsLeft  |  R: $boundsRight  |  T: $boundsTop  |  B: $boundsBottom"
        }
        controlPanel?.addView(statusText)

        // 🟢 [기능 2] 화면 공유 에러 실시간 출력용 텍스트 뷰 (초기에는 숨김)
        errorText = TextView(this).apply {
            setTextColor(Color.RED)
            textSize = 12f
            visibility = View.GONE
            gravity = Gravity.CENTER
            setPadding(0, 5, 0, 5)
        }
        controlPanel?.addView(errorText)

        // 버튼 행들 생성
        val rowLeftRight = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val rowTopBottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val rowToggleMenu = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 5, 0, 0)
        }

        val btnStep = 10 

        fun createAdjustButton(targetRow: LinearLayout, textStr: String, onClick: () -> Unit) {
            val btn = Button(this).apply {
                text = textStr
                textSize = 11f
                setTextColor(Color.WHITE)
                setOnClickListener { 
                    onClick() 
                    saveBounds()
                    statusText?.text = "L: $boundsLeft  |  R: $boundsRight  |  T: $boundsTop  |  B: $boundsBottom"
                    overlayView?.updateGrid(
                        left = boundsLeft,
                        top = boundsTop,
                        right = boundsRight,
                        bottom = boundsBottom,
                        candidates = emptyList(),
                        gridVisible = isGridVisible
                    )
                }
            }
            targetRow.addView(btn)
        }

        // 1층: 좌우 좌표 조절
        createAdjustButton(rowLeftRight, "좌(L)-") { boundsLeft -= btnStep }
        createAdjustButton(rowLeftRight, "좌(L)+") { boundsLeft += btnStep }
        createAdjustButton(rowLeftRight, "우(R)-") { boundsRight -= btnStep }
        createAdjustButton(rowLeftRight, "우(R)+") { boundsRight += btnStep }

        // 2층: 상하 좌표 조절
        createAdjustButton(rowTopBottom, "상(T)-") { boundsTop -= btnStep }
        createAdjustButton(rowTopBottom, "상(T)+") { boundsTop += btnStep }
        createAdjustButton(rowTopBottom, "하(B)-") { boundsBottom -= btnStep }
        createAdjustButton(rowTopBottom, "하(B)+") { boundsBottom += btnStep }

        // 🟢 [기능 1] 3층: 격자 온/오프 토글 버튼 배치
        val btnToggleGrid = Button(this).apply {
            text = "격자 숨기기"
            textSize = 11f
            setTextColor(Color.YELLOW)
            setOnClickListener {
                isGridVisible = !isGridVisible
                text = if (isGridVisible) "격자 숨기기" else "격자 보이기"
                overlayView?.updateGrid(
                    left = boundsLeft,
                    top = boundsTop,
                    right = boundsRight,
                    bottom = boundsBottom,
                    candidates = emptyList(),
                    gridVisible = isGridVisible
                )
            }
        }
        rowToggleMenu.addView(btnToggleGrid)

        controlPanel?.addView(rowLeftRight)
        controlPanel?.addView(rowTopBottom)
        controlPanel?.addView(rowToggleMenu)

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

    // 🟢 [기능 2] 에러 발생 시 UI 단에 붉은색 경고 출력 유틸리티
    private fun displayErrorToUser(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            errorText?.apply {
                text = "⚠️ $message"
                visibility = View.VISIBLE
            }
        }
    }

    private fun saveBounds() {
        prefs.edit().apply {
            putInt("left", boundsLeft)
            putInt("top", boundsTop)
            putInt("right", boundsRight)
            putInt("bottom", boundsBottom)
            apply()
        }
    }

    private fun loadBounds() {
        val metrics = resources.displayMetrics
        boundsLeft = prefs.getInt("left", (metrics.widthPixels * 0.05).toInt())
        boundsTop = prefs.getInt("top", (metrics.heightPixels * 0.25).toInt())
        boundsRight = prefs.getInt("right", (metrics.widthPixels * 0.95).toInt())
        boundsBottom = prefs.getInt("bottom", (metrics.heightPixels * 0.75).toInt())
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
            
            // 🟢 [기능 2] 미디어 프로젝션 획득 단계 에러 트래킹 추가
            try {
                mediaProjection = mpManager.getMediaProjection(resultCode, data)
                if (mediaProjection == null) {
                    displayErrorToUser("화면 공유 토큰이 Null입니다. 권한 팝업을 다시 확인해주세요.")
                } else {
                    startCapture()
                }
            } catch (e: SecurityException) {
                displayErrorToUser("보안 거부 (안드로이드 14+ 정책): 서비스 실행 전 토큰이 만료되었거나 누락되었습니다.\n[상세: ${e.localizedMessage}]")
            } catch (e: Exception) {
                displayErrorToUser("프로젝션 생성 에러: ${e.javaClass.simpleName} - ${e.localizedMessage}")
            }
        } else {
            displayErrorToUser("MainActivity로부터 정상적인 화면 캡처 결과 데이터가 전달되지 않았습니다.")
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        // 🟢 [기능 2] 가상 디스플레이 생성 에러 트래킹 추가
        try {
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, backgroundHandler
            )
            
            if (virtualDisplay == null) {
                displayErrorToUser("VirtualDisplay 매핑 실패: 가상화면을 만들 수 없습니다.")
                return
            }
        } catch (e: Exception) {
            displayErrorToUser("화면 캡처 파이프라인 생성 실패: ${e.localizedMessage}")
            return
        }

        imageReader?.setOnImageAvailableListener({ reader ->
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
                // 프레임 분석 중 발생하는 간헐적 크래시 방지
            }
        }, backgroundHandler)
    }

    private fun processFrame(bitmap: Bitmap) {
        val currentBounds = GridBounds(boundsLeft, boundsTop, boundsRight, boundsBottom)
        val gridResult = GridDetector.getGridDataFromBitmap(bitmap, currentBounds)
        
        if (gridResult.success && gridResult.categoryGrid != null && gridResult.bounds != null) {
            val candidates = Match3Solver.getMatchCandidates(gridResult.categoryGrid)
            Handler(Looper.getMainLooper()).post {
                overlayView?.updateGrid(
                    left = gridResult.bounds.left,
                    top = gridResult.bounds.top,
                    right = gridResult.bounds.right,
                    bottom = gridResult.bounds.bottom,
                    candidates = candidates,
                    gridVisible = isGridVisible
                )
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
        private var isVisibleMode = true // 🟢 격자 활성화 유무 플래그

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

        fun updateGrid(left: Int, top: Int, right: Int, bottom: Int, candidates: List<MatchCandidate>, gridVisible: Boolean) {
            this.bLeft = left
            this.bTop = top
            this.bRight = right
            this.bBottom = bottom
            this.isVisibleMode = gridVisible
            if (candidates.isNotEmpty()) {
                this.matchCandidates = candidates
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // 🟢 격자 비활성화 상태면 아무것도 그리지 않고 리턴
            if (!isVisibleMode || bRight <= bLeft || bBottom <= bTop) return

            val cellW = (bRight - bLeft) / 7f
            val cellH = (bBottom - bTop) / 9f

            // 격자선 그리기
            for (r in 0..9) {
                val y = bTop + r * cellH
                canvas.drawLine(bLeft.toFloat(), y, bRight.toFloat(), y, gridPaint)
            }
            for (c in 0..7) {
                val x = bLeft + c * cellW
                canvas.drawLine(x, bTop.toFloat(), x, bBottom.toFloat(), gridPaint)
            }

            // 최적 알고리즘 결과(화살표) 그리기
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
