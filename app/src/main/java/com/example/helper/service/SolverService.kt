package com.example.helper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat

import com.example.helper.core.GridDetector
import com.example.helper.core.Match3Solver
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class SolverService : Service() {
    private val TAG = "SolverService"
    private val ROWS = 9
    private val COLS = 7 // GameConfig 규격에 맞게 7로 매핑

    private var windowManager: WindowManager? = null
    private var overlayView: OverlayView? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "solver_channel")
            .setContentTitle("매칭 헬퍼 작동 중")
            .setContentText("화면 분석 및 오버레이 격자를 표시하고 있습니다.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(1, notification)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = OverlayView(this)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        windowManager?.addView(overlayView, params)

        handlerThread = HandlerThread("CaptureThread").apply { start() }
        backgroundHandler = Handler(handlerThread!!.looper)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "solver_channel", "Solver Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
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
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, backgroundHandler
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            
            // 기존 OpenCV 변환 과정을 제거하고 순수 안드로이드 비트맵으로 바로 처리
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            val cleanBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            processFrame(cleanBitmap)
        }, backgroundHandler)
    }

    private fun processFrame(bitmap: Bitmap) {
        val gridResult = GridDetector.getGridDataFromBitmap(bitmap)
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
        if (overlayView != null) {
            windowManager?.removeView(overlayView)
        }
    }

    // 🟢 내부에 격자선(Grid Link) 및 매칭 화살표만 깔끔히 그리는 최적화 뷰
    class OverlayView(context: Context) : View(context) {
        private var bLeft = 0
        private var bTop = 0
        private var bRight = 0
        private var bBottom = 0
        private var matchCandidates: List<com.example.helper.core.MatchCandidate> = emptyList()

        private val gridPaint = Paint().apply {
            color = Color.parseColor("#8800FF00") // 반투명 초록색 격자선
            style = Paint.Style.STROKE
            strokeWidth = 4f
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

        fun updateGrid(boundsLeft: Int, boundsTop: Int, boundsRight: Int, boundsBottom: Int, candidates: List<com.example.helper.core.MatchCandidate>) {
            this.bLeft = boundsLeft
            this.bTop = boundsTop
            this.bRight = boundsRight
            this.bBottom = boundsBottom
            this.matchCandidates = candidates
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (bRight <= bLeft || bBottom <= bTop) return

            val cellW = (bRight - bLeft) / 7f
            val cellH = (bBottom - bTop) / 9f

            // 1. 🟢 [요청 반영] 내부 색상 점 제거 및 순수 외곽 격자 테두리선 시각화
            for (r in 0..9) {
                val y = bTop + r * cellH
                canvas.drawLine(bLeft.toFloat(), y, bRight.toFloat(), y, gridPaint)
            }
            for (c in 0..7) {
                val x = bLeft + c * cellW
                canvas.drawLine(x, bTop.toFloat(), x, bBottom.toFloat(), gridPaint)
            }

            // 2. 추천 매칭 화살표 시각화 (최적의 한 수 표시용)
            val bestCandidate = matchCandidates.maxByOrNull { it.score } ?: return
            if (bestCandidate.score > 0) {
                val r = bestCandidate.row
                val c = bestCandidate.col
                
                val fx = bLeft + c * cellW + cellW / 2f
                val fy = bTop + r * cellH + cellH / 2f

                var tx = fx
                var ty = fy

                // Match3Solver 스펙에 맞춰 이동 방향 연산
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
                    lineTo(
                        (tx - arrowLength * cos(angle - arrowWidthAngle)).toFloat(),
                        (ty - arrowLength * sin(angle - arrowWidthAngle)).toFloat()
                    )
                    lineTo(
                        (tx - arrowLength * cos(angle + arrowWidthAngle)).toFloat(),
                        (ty - arrowLength * sin(angle + arrowWidthAngle)).toFloat()
                    )
                    close()
                }
                canvas.drawPath(path, arrowPaint)
            }
        }
    }
}
