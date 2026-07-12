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
            
        // 🟢 [해결책 2] 안드로이드 10 이상 보안 정책 대응: 미디어 프로젝션 타입 선언을 반드시 명시해야 화면 공유가 정상 작동합니다.
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
        // 🟢 [해결책 1] 레이아웃을 VERTICAL 구조로 바꾼 후 두 줄에 나누어 배치하여 잘림 현상을 해결합니다.
        controlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E6000000")) 
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 10, 0, 10)
        }

        // 현재 수치 상태 시각화 라벨
        val statusText = TextView(this).apply {
            setTextColor(Color.GREEN)
            textSize = 12f
            gravity = Gravity.CENTER
            text = "L: $boundsLeft  |  R: $boundsRight  |  T: $boundsTop  |  B: $boundsBottom"
        }
        controlPanel?.addView(statusText)

        // 첫 번째 행 (좌우 조절용)
        val rowLeftRight = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        
        // 두 번째 행 (상하 조절용)
        val rowTopBottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
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
                    statusText.text = "L: $boundsLeft  |  R: $boundsRight  |  T: $boundsTop  |  B: $boundsBottom"
                    overlayView?.updateGrid(
                        left = boundsLeft,
                        top = boundsTop,
                        right = boundsRight,
                        bottom = boundsBottom,
                        candidates = emptyList()
                    )
                }
            }
            targetRow.addView(btn)
        }

        // 1층 레이아웃에 배정 (가로폭 여유 확보)
        createAdjustButton(rowLeftRight, "좌(L)-") { boundsLeft -= btnStep }
        createAdjustButton(rowLeftRight, "좌(L)+") { boundsLeft += btnStep }
        createAdjustButton(rowLeftRight, "우(R)-") { boundsRight -= btnStep }
        createAdjustButton(rowLeftRight, "우(R)+") { boundsRight += btnStep }

        // 2층 레이아웃에 배정
        createAdjustButton(rowTopBottom, "상(T)-") { boundsTop -= btnStep }
        createAdjustButton(rowTopBottom, "상(T)+") { boundsTop += btnStep }
        createAdjustButton(rowTopBottom, "하(B)-") { boundsBottom -= btnStep }
        createAdjustButton(rowTopBottom, "하(B)+") { boundsBottom += btnStep }

        controlPanel?.addView(rowLeftRight)
        controlPanel?.addView(rowTopBottom)

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
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
            startCapture()
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, backgroundHandler
        )

        imageReader?.setOnImageAvailableListener({ reader ->
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
                    candidates = candidates
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

        fun updateGrid(left: Int, top: Int, right: Int, bottom: Int, candidates: List<MatchCandidate>) {
            this.bLeft = left
            this.bTop = top
            this.bRight = right
            this.bBottom = bottom
            if (candidates.isNotEmpty()) {
                this.matchCandidates = candidates
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (bRight <= bLeft || bBottom <= bTop) return

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

            // 실시간 최적 알고리즘 결과(화살표) 그리기
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
