package com.example.helper.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.lang.StringBuilder

class SolverService : Service() {
    private val TAG = "OOXOO_Service"
    private val binder = SolverBinder()

    // 고정 격자 크기 (Royal Match 기본값)
    private val ROWS = 11
    private val COLS = 9

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var windowManager: WindowManager

    private var overlayView: OverlayView? = null
    private var controlView: LinearLayout? = null
    private var floatParams: WindowManager.LayoutParams? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var isCapturing = false
    private var isScanning = false

    // 격자 모서리 (고정값 - 화면 중앙 영역)
    private val ptTL = PointF()
    private val ptTR = PointF()
    private val ptBL = PointF()
    private val ptBR = PointF()

    // 발견된 OOXOO 위치 목록
    private var foundPositions = mutableListOf<Pair<Int, Int>>()

    inner class SolverBinder : Binder() {
        fun getService(): SolverService = this@SolverService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        backgroundThread = HandlerThread("OOXOO_Worker").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        // 격자 모서리 초기화 (화면 비율에 맞게)
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()

        ptTL.set(w * 0.05f, h * 0.20f)
        ptTR.set(w * 0.95f, h * 0.20f)
        ptBL.set(w * 0.05f, h * 0.80f)
        ptBR.set(w * 0.95f, h * 0.80f)

        createOverlayWindow()
        createControlWindow()
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
                backgroundHandler?.post { setupScreenCapture(resultCode, resultData) }
            }
        }
        return START_STICKY
    }

    private fun startForegroundServiceInternal() {
        val channelId = "OOXOO_Channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, "OOXOO Helper", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("OOXOO 헬퍼")
            .setContentText("OOXOO 패턴 감지 중...")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1001, notification)
        }
    }

    private fun createOverlayWindow() {
        mainHandler.post {
            overlayView = OverlayView(applicationContext)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            windowManager.addView(overlayView, params)
        }
    }

    private fun createControlWindow() {
        mainHandler.post {
            val context = applicationContext
            floatParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 30
                y = 100
            }

            controlView = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#DD111111"))
                setPadding(20, 15, 20, 15)
            }

            // 상태 표시
            val tvStatus = TextView(context).apply {
                text = "🎯 OOXOO 패턴 감지기"
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(0, 0, 0, 10)
            }
            controlView!!.addView(tvStatus)

            // 스캔 버튼
            val btnScan = Button(context).apply {
                text = "🔍 지금 스캔"
                setBackgroundColor(Color.parseColor("#FF6200EE"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    if (!isScanning) {
                        isScanning = true
                        performScan()
                    }
                }
            }
            controlView!!.addView(btnScan)

            // 결과 개수 표시
            val tvCount = TextView(context).apply {
                text = "발견: 0개"
                setTextColor(Color.parseColor("#FFCCCC"))
                textSize = 12f
                setPadding(0, 10, 0, 0)
            }
            controlView!!.addView(tvCount)

            // 종료 버튼
            val btnKill = Button(context).apply {
                text = "❌ 종료"
                setBackgroundColor(Color.RED)
                setTextColor(Color.WHITE)
                setOnClickListener { stopSelf() }
            }
            controlView!!.addView(btnKill)

            windowManager.addView(controlView, floatParams)
        }
    }

    private fun performScan() {
        backgroundHandler?.post {
            val reader = imageReader ?: return@post
            var image = reader.acquireLatestImage()
            if (image == null) {
                try { Thread.sleep(50) } catch (e: Exception) {}
                image = reader.acquireNextImage()
            }
            if (image == null) {
                mainHandler.post {
                    Toast.makeText(applicationContext, "화면 캡처 실패", Toast.LENGTH_SHORT).show()
                    isScanning = false
                }
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

                val bitmap = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)

                // OOXOO 패턴 탐색
                val positions = findOOXOO(bitmap)

                mainHandler.post {
                    foundPositions.clear()
                    foundPositions.addAll(positions)
                    overlayView?.updatePositions(positions)
                    updateControlUI(positions.size)
                    isScanning = false

                    if (positions.isNotEmpty()) {
                        Toast.makeText(
                            applicationContext,
                            "✅ OOXOO 발견! ${positions.size}개 위치 표시됨",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            applicationContext,
                            "❌ OOXOO 패턴 없음",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                bitmap.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "스캔 오류", e)
                mainHandler.post { isScanning = false }
            } finally {
                image.close()
            }
        }
    }

    private fun findOOXOO(bitmap: Bitmap): List<Pair<Int, Int>> {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 색상 맵 생성
        val colorGrid = Array(ROWS) { IntArray(COLS) }

        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                val u = (c + 0.5f) / COLS
                val v = (r + 0.5f) / ROWS

                val topX = (1 - u) * ptTL.x + u * ptTR.x
                val topY = (1 - u) * ptTL.y + u * ptTR.y
                val bottomX = (1 - u) * ptBL.x + u * ptBR.x
                val bottomY = (1 - u) * ptBL.y + u * ptBR.y

                val targetX = (1 - v) * topX + v * bottomX
                val targetY = (1 - v) * topY + v * bottomY

                val cx = targetX.toInt().coerceIn(0, width - 1)
                val cy = targetY.toInt().coerceIn(0, height - 1)
                val pixel = pixels[cy * width + cx]

                val hsv = FloatArray(3)
                Color.colorToHSV(pixel, hsv)
                val hue = hsv[0]
                val sat = hsv[1]

                colorGrid[r][c] = when {
                    sat < 0.25f -> -1  // 빈칸
                    hue in 0f..30f -> 1  // 빨강/갈색
                    hue in 31f..70f -> 2 // 노랑
                    hue in 71f..160f -> 3 // 초록
                    hue in 161f..230f -> 4 // 파랑
                    hue in 231f..360f -> 5 // 보라
                    else -> -1
                }
            }
        }

        val result = mutableListOf<Pair<Int, Int>>()

        // 가로 OOXOO 검사
        for (r in 0 until ROWS) {
            for (c in 0 until COLS - 4) {
                val color = colorGrid[r][c]
                if (color == -1) continue

                // O O X O O
                if (colorGrid[r][c] == color &&
                    colorGrid[r][c + 1] == color &&
                    colorGrid[r][c + 2] != color &&
                    colorGrid[r][c + 3] == color &&
                    colorGrid[r][c + 4] == color) {

                    val xCol = c + 2
                    // X의 위/아래에 같은 색 O가 있는지 확인
                    val hasAdjacentO =
                        (r > 0 && colorGrid[r - 1][xCol] == color) ||
                        (r < ROWS - 1 && colorGrid[r + 1][xCol] == color)

                    if (hasAdjacentO) {
                        result.add(Pair(r, xCol))  // (행, 열)
                    }
                }
            }
        }

        // 세로 OOXOO 검사
        for (c in 0 until COLS) {
            for (r in 0 until ROWS - 4) {
                val color = colorGrid[r][c]
                if (color == -1) continue

                // O
                // O
                // X
                // O
                // O
                if (colorGrid[r][c] == color &&
                    colorGrid[r + 1][c] == color &&
                    colorGrid[r + 2][c] != color &&
                    colorGrid[r + 3][c] == color &&
                    colorGrid[r + 4][c] == color) {

                    val xRow = r + 2
                    // X의 좌/우에 같은 색 O가 있는지 확인
                    val hasAdjacentO =
                        (c > 0 && colorGrid[xRow][c - 1] == color) ||
                        (c < COLS - 1 && colorGrid[xRow][c + 1] == color)

                    if (hasAdjacentO) {
                        result.add(Pair(xRow, c))  // (행, 열)
                    }
                }
            }
        }

        return result.distinct()
    }

    private fun updateControlUI(count: Int) {
        controlView?.let { view ->
            val tvCount = view.getChildAt(2) as? TextView
            tvCount?.text = "발견: $count개"
        }
    }

    private fun setupScreenCapture(resultCode: Int, resultData: Intent) {
        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            mediaProjection = mpManager.getMediaProjection(resultCode, resultData)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { super.onStop(); stopCapture() }
            }, backgroundHandler)

            val metrics = resources.displayMetrics
            imageReader = ImageReader.newInstance(
                metrics.widthPixels,
                metrics.heightPixels,
                PixelFormat.RGBA_8888,
                2
            )

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "OOXOO_Capture",
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                backgroundHandler
            )

            isCapturing = true

            // 첫 스캔 자동 실행
            backgroundHandler?.postDelayed({
                if (isCapturing && !isScanning) {
                    isScanning = true
                    performScan()
                }
            }, 500)

        } catch (e: Exception) {
            Log.e(TAG, "캡처 설정 실패", e)
        }
    }

    private fun stopCapture() {
        isCapturing = false
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    override fun onDestroy() {
        stopCapture()
        controlView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        overlayView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        backgroundThread?.quitSafely()
        super.onDestroy()
    }

    // 오버레이 뷰 - 발견된 위치에 빨간 동그라미 표시
    inner class OverlayView(context: Context) : View(context) {
        private var positions = listOf<Pair<Int, Int>>()
        private val circlePaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.FILL
            alpha = 180
        }
        private val borderPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 30f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        fun updatePositions(newPositions: List<Pair<Int, Int>>) {
            this.positions = newPositions
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            for ((row, col) in positions) {
                // 해당 셀의 중심 좌표 계산
                val u = (col + 0.5f) / COLS
                val v = (row + 0.5f) / ROWS

                val topX = (1 - u) * ptTL.x + u * ptTR.x
                val topY = (1 - u) * ptTL.y + u * ptTR.y
                val bottomX = (1 - u) * ptBL.x + u * ptBR.x
                val bottomY = (1 - u) * ptBL.y + u * ptBR.y

                val cx = (1 - v) * topX + v * bottomX
                val cy = (1 - v) * topY + v * bottomY

                // 빨간 동그라미
                canvas.drawCircle(cx, cy, 40f, circlePaint)
                canvas.drawCircle(cx, cy, 40f, borderPaint)

                // "X" 표시
                canvas.drawText("X", cx, cy + 10f, textPaint)
            }
        }
    }
}
