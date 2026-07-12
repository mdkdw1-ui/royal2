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
import androidx.core.app.NotificationCompat

import com.example.helper.core.GridBounds
import com.example.helper.core.GridDetector
import com.example.helper.core.Match3Solver
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

    // 🟢 사용자가 세팅할 경계선 좌표 변수
    private var boundsLeft = 50
    private var boundsTop = 500
    private var boundsRight = 1000
    private var boundsBottom = 1500

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("GridSettings", Context.MODE_PRIVATE)
        loadBounds() // 기존에 저장된 설정이 있으면 불러옴

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "solver_channel")
            .setContentTitle("매칭 헬퍼 작동 중")
            .setContentText("오버레이 격자가 활성화되었습니다.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(1, notification)

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

        // 2. 🟢 상단 미세조정 컨트롤 패널 생성 (터치 가능 영역)
        createControlPanel()

        handlerThread = HandlerThread("CaptureThread").apply { start() }
        backgroundHandler = Handler(handlerThread!!.looper)
    }

    private fun createControlPanel() {
        controlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            backgroundColor = Color.parseColor("#CC000000") // 반투명 검은색 배경
            gravity = Gravity.CENTER
        }

        val btnStep = 10 // 한 번 클릭 시 이동할 픽셀 단위

        fun createAdjustButton(textStr: String, onClick: () -> Unit) {
            val btn = Button(this).apply {
                text = textStr
                textSize = 11f
                setTextColor(Color.WHITE)
                setOnClickListener { 
                    onClick() 
                    saveBounds()
                    overlayView?.updateGrid(boundsLeft, boundsTop, boundsRight, boundsBottom, emptyList())
                }
            }
            controlPanel?.addView(btn)
        }

        // 버튼 레이아웃 추가
        createAdjustButton("시작점 좌측-") { boundsLeft -= btnStep }
        createAdjustButton("시작점 좌측+") { boundsLeft += btnStep }
        createAdjustButton("상단 경계-") { boundsTop -= btnStep }
        createAdjustButton("상단 경계+") { boundsTop += btnStep }
        createAdjustButton("우측 경계-") { boundsRight -= btnStep }
        createAdjustButton("우측 경계+") { boundsRight += btnStep }
        createAdjustButton("하단 경계-") { boundsBottom -= btnStep }
        createAdjustButton("하단 경계+") { boundsBottom += btnStep }

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
        // 저장된 게 없으면 화면 해상도 기반 초기 기본값 세팅
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
        // 🟢 사용자가 맞춰둔 조절 수치를 기준으로 이미지 분석을 요청합니다.
        val currentBounds = GridBounds(boundsLeft, boundsTop, boundsRight, boundsBottom)
        val gridResult = GridDetector.getGridDataFromBitmap(bitmap, currentBounds)
        
        if (gridResult.success && gridResult.categoryGrid != null && gridResult.bounds != null) {
            val candidates = Match3Solver.getMatchCandidates(gridResult.categoryGrid)
            Handler(Looper.getMainLooper()).post {
                overlayView?.updateGrid(
                    boundsLeft = gridResult.bounds.left,
                    boundsTop = gridResult.bounds.top,
                    boundsRight = gridResult.bounds.right,
                    boundsBottom = gridResult.bounds.bottom,
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
        private var matchCandidates: List<com.example.helper.core.MatchCandidate> = emptyList()

        private val gridPaint = Paint().apply {
            color = Color.parseColor("#AA00FF00") // 뚜렷한 반투명 녹색 격자
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

        fun updateGrid(left: Int, top: Int, right: Int, bottom: Int, candidates: List<com.example.helper.core.MatchCandidate>) {
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

            // 격자선 캔버스 드로잉
            for (r in 0..9) {
                val y = bTop + r * cellH
                canvas.drawLine(bLeft.toFloat(), y, bRight.toFloat(), y, gridPaint)
            }
            for (c in 0..7) {
                val x = bLeft + c * cellW
                canvas.drawLine(x, bTop.toFloat(), x, bBottom.toFloat(), gridPaint)
            }

            // 최적의 화살표 드로잉 루틴
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
