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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
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
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat

enum class BlockColor { RED, BLUE, YELLOW, GREEN, PURPLE, UNKNOWN }

data class MatchMove(
    val fromRow: Int, val fromCol: Int,
    val toRow: Int, val toCol: Int,
    val matchCount: Int,
    val description: String
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
    private var overlayRootView: FrameLayout? = null
    private var statusTextView: TextView? = null
    private var visualOverlayView: VisualOverlayView? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    private var isCapturing = false
    private var isManualAnalysisRequested = false 

    private var screenWidth = 0
    private var screenHeight = 0
    private var gridLeft = 0f
    private var gridTop = 0f
    private var gridWidth = 0f
    private var gridHeight = 0f

    inner class SolverBinder : Binder() {
        fun getService(): SolverService = this@SolverService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        backgroundThread = HandlerThread("SolverBackgroundWorker").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
        
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        
        gridWidth = screenWidth * 0.92f
        gridHeight = screenHeight * 0.52f
        gridLeft = (screenWidth - gridWidth) / 2f
        gridTop = (screenHeight - gridHeight) / 2f
        
        createFloatingOverlayUI()
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
                backgroundHandler?.post {
                    setupScreenCapturePipeline(resultCode, resultData)
                }
            } else {
                showToastOnMainThread("❌ 유효하지 않은 미디어 프로젝션 데이터 수신")
            }
        }
        return START_STICKY
    }

    private fun createFloatingOverlayUI() {
        mainHandler.post {
            val context = applicationContext
            overlayRootView = FrameLayout(context)

            val controlPanel = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.parseColor("#CC000000")) 
                setPadding(30, 20, 30, 20)
                gravity = Gravity.CENTER_VERTICAL
            }

            statusTextView = TextView(context).apply {
                text = "엔진 대기 중..."
                setTextColor(Color.WHITE)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnManualMatch = Button(context).apply {
                text = "수동 격자 매칭"
                setBackgroundColor(Color.parseColor("#FF6200EE")) 
                setTextColor(Color.WHITE)
                setPadding(20, 10, 20, 10)
                setOnClickListener {
                    isManualAnalysisRequested = true
                    statusTextView?.text = "화면 스캔 및 분석 중..."
                    showToastOnMainThread("🎯 현재 화면 매칭 스캔을 시작합니다.")
                }
            }

            controlPanel.addView(statusTextView)
            controlPanel.addView(btnManualMatch)

            val controlParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
                topMargin = 80 
            }
            overlayRootView?.addView(controlPanel, controlParams)

            visualOverlayView = VisualOverlayView(context)
            val overlayParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            overlayRootView?.addView(visualOverlayView, overlayParams)

            val windowParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
                else 
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or 
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )

            try {
                windowManager.addView(overlayRootView, windowParams)
                Log.d(TAG, "최상위 오버레이 윈도우 등록 성공")
            } catch (e: Exception) {
                Log.e(TAG, "오버레이 윈도우 생성 실패: ${e.message}")
            }
        }
    }

    private fun setupScreenCapturePipeline(resultCode: Int, resultData: Intent) {
        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            stopCapturePipeline()

            mediaProjection = mpManager.getMediaProjection(resultCode, resultData)
            
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    stopCapturePipeline()
                }
            }, backgroundHandler)

            val metrics = resources.displayMetrics
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "GridHelperCapture",
                screenWidth, screenHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, backgroundHandler
            )

            isCapturing = true
            mainHandler.post { statusTextView?.text = "분석기 정상 기동 (대기 중)" }

            imageReader?.setOnImageAvailableListener({ reader ->
                if (!isCapturing) return@setOnImageAvailableListener
                val image = reader?.acquireLatestImage() ?: return@setOnImageAvailableListener
                
                try {
                    if (isManualAnalysisRequested) {
                        isManualAnalysisRequested = false
                        
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * screenWidth

                        val bitmap = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride, screenHeight, Bitmap.Config.ARGB_8888)
                        bitmap.copyPixelsFromBuffer(buffer)
                        
                        val matchedMoves = analyzeAndSolve(bitmap, gridLeft, gridTop, gridWidth, gridHeight)
                        
                        mainHandler.post {
                            if (matchedMoves.isEmpty()) {
                                statusTextView?.text = "분석 완료: 매칭 항목 없음"
                            } else {
                                val bestMove = matchedMoves.first()
                                statusTextView?.text = "${bestMove.description}"
                            }
                            visualOverlayView?.updateMoves(matchedMoves)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "이미지 분석 파이프라인 연산 실패: ${e.message}")
                } finally {
                    image.close()
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "캡처 파이프라인 구축 에러", e)
            showToastOnMainThread("❌ 화면 초기화 실패: ${e.message}")
        }
    }

    private fun startForegroundServiceInternal() {
        val channelId = "solver_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "격자 헬퍼 분석 엔진", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("격자 헬퍼 오버레이 동작 중")
            .setContentText("화면 위에 상태바 및 수동 매칭 패널이 표시됩니다.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    private fun showToastOnMainThread(message: String) {
        mainHandler.post { Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show() }
    }

    /**
     * 🎯 [핵심] 알고리즘 연산 구현부 및 OOXOO 절대 강자 탐지 로직
     */
    private fun findBestMoves(board: Array<Array<BlockColor>>): List<MatchMove> {
        val moves = mutableListOf<MatchMove>()
        val dr = intArrayOf(-1, 1, 0, 0)
        val dc = intArrayOf(0, 0, -1, 1)

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (board[r][c] == BlockColor.UNKNOWN) continue

                for (i in 0 until 4) {
                    val nr = r + dr[i]
                    val nc = c + dc[i]

                    if (nr in 0 until rows && nc in 0 until cols) {
                        if (board[nr][nc] == BlockColor.UNKNOWN) continue
                        
                        // 1. 가상 스왑 시뮬레이션 실행
                        val temp = board[r][c]
                        board[r][c] = board[nr][nc]
                        board[nr][nc] = temp

                        // 2. 일반 매칭 길이 계산
                        val m1 = checkMaxMatchLength(board, r, c)
                        val m2 = checkMaxMatchLength(board, nr, nc)
                        val maxLen = maxOf(m1, m2)

                        // 3. 🔥 [OOXOO 절대 강자 검출 로직]
                        // 스왑의 결과물로 타깃 지점에 완벽한 샌드위치 브릿지 형태가 완성되었는지 검사
                        val isOoxooTarget = isOoxooPattern(board, r, c) || isOoxooPattern(board, nr, nc)

                        if (isOoxooTarget) {
                            // OOXOO 구조가 완성되었다면 999점의 가중치를 부여해 무조건 정렬 1순위 상단 박제
                            val colorName = getKoreanColorName(board[r][c])
                            val desc = "⚡[$colorName OOXOO] 최강 디스코볼 완성! 💣"
                            moves.add(MatchMove(r, c, nr, nc, 999, desc))
                        } else if (maxLen >= 3) {
                            val desc = if (maxLen >= 5) "디스코볼 생성 가능! 🎯" else "${maxLen}개 매칭"
                            moves.add(MatchMove(r, c, nr, nc, maxLen, desc))
                        }

                        // 4. 원래 상태로 복구
                        board[nr][nc] = board[r][c]
                        board[r][c] = temp
                    }
                }
            }
        }
        // 가중치(matchCount)가 높은 순서대로 정렬하므로 999점을 받은 OOXOO가 무조건 맨 위로 올라감
        return moves.sortedByDescending { it.matchCount }
    }

    /**
     * 🎯 OOXOO 패턴 여부 정밀 판별기
     * 특정 조각이 들어간 자리를 중심으로 좌우 2칸씩 혹은 상하 2칸씩 동일 색상인지 검증
     */
    private fun isOoxooPattern(board: Array<Array<BlockColor>>, r: Int, c: Int): Boolean {
        val color = board[r][c]
        if (color == BlockColor.UNKNOWN) return false

        // 1. 가로축 OOXOO 탐지 (내 위치 c를 기준으로 좌측 2개, 우측 2개가 내 색상과 일치하는지)
        if (c >= 2 && c < cols - 2) {
            if (board[r][c - 2] == color && board[r][c - 1] == color &&
                board[r][c + 1] == color && board[r][c + 2] == color) {
                return true
            }
        }

        // 2. 세로축 OOXOO 탐지 (내 위치 r을 기준으로 상단 2개, 하단 2개가 내 색상과 일치하는지)
        if (r >= 2 && r < rows - 2) {
            if (board[r - 2][c] == color && board[r - 1][c] == color &&
                board[r + 1][c] == color && board[r + 2][c] == color) {
                return true
            }
        }

        return false
    }

    private fun getKoreanColorName(color: BlockColor): String {
        return when (color) {
            BlockColor.RED -> "빨강"
            BlockColor.BLUE -> "파랑"
            BlockColor.YELLOW -> "노랑"
            BlockColor.GREEN -> "초록"
            BlockColor.PURPLE -> "보라"
            else -> "미정"
        }
    }

    fun analyzeAndSolve(bitmap: Bitmap, gridLeft: Float, gridTop: Float, gridWidth: Float, gridHeight: Float): List<MatchMove> {
        val cellWidth = gridWidth / cols
        val cellHeight = gridHeight / rows
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val board = Array(rows) { Array(cols) { BlockColor.UNKNOWN } }
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val centerX = gridLeft + (c + 0.5f) * cellWidth
                val centerY = gridTop + (r + 0.5f) * cellHeight
                board[r][c] = detectCellColorROI(pixels, width, height, centerX, centerY)
            }
        }
        return findBestMoves(board)
    }

    private fun detectCellColorROI(pixels: IntArray, width: Int, height: Int, centerX: Float, centerY: Float): BlockColor {
        val radius = 12 
        val cX = centerX.toInt()
        val cY = centerY.toInt()
        var redCount = 0; var blueCount = 0; var yellowCount = 0; var greenCount = 0; var purpleCount = 0
        val hsv = FloatArray(3)

        for (y in (cY - radius)..(cY + radius)) {
            for (x in (cX - radius)..(cX + radius)) {
                if (x < 0 || x >= width || y < 0 || y >= height) continue
                val pixel = pixels[y * width + x]
                Color.colorToHSV(pixel, hsv)
                if (hsv[1] < 0.15f || hsv[2] < 0.15f) continue
                when {
                    (hsv[0] in 0f..20f) || (hsv[0] in 340f..360f) -> redCount++    
                    hsv[0] in 21f..70f -> yellowCount++                         
                    hsv[0] in 71f..165f -> greenCount++ 
                    hsv[0] in 166f..255f -> blueCount++                         
                    hsv[0] in 256f..339f -> purpleCount++                       
                }
            }
        }

        val totalSamples = ((radius * 2) + 1) * ((radius * 2) + 1)
        val threshold = totalSamples * 0.12f 
        val counts = mapOf(
            BlockColor.RED to redCount, BlockColor.BLUE to blueCount,
            BlockColor.YELLOW to yellowCount, BlockColor.GREEN to greenCount, BlockColor.PURPLE to purpleCount
        )
        val maxEntry = counts.maxByOrNull { it.value } ?: return BlockColor.UNKNOWN
        return if (maxEntry.value > threshold) maxEntry.key else BlockColor.UNKNOWN
    }

    private fun checkMaxMatchLength(board: Array<Array<BlockColor>>, r: Int, c: Int): Int {
        val color = board[r][c]
        if (color == BlockColor.UNKNOWN) return 0
        var hc = 1; var cc = c + 1
        while (cc < cols && board[r][cc] == color) { hc++; cc++ }; cc = c - 1
        while (cc >= 0 && board[r][cc] == color) { hc++; cc-- }
        var vc = 1; var rr = r + 1
        while (rr < rows && board[rr][c] == color) { vc++; rr++ }; rr = r - 1
        while (rr >= 0 && board[rr][c] == color) { vc++; rr-- }
        return maxOf(hc, vc)
    }

    inner class VisualOverlayView(context: Context) : View(context) {
        private val gridPaint = Paint().apply {
            color = Color.parseColor("#40FFFFFF") 
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        
        private val arrowPaint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = 10f
            textSize = 40f
        }

        private var currentMoves = listOf<MatchMove>()

        fun updateMoves(moves: List<MatchMove>) {
            this.currentMoves = moves
            invalidate() 
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cellWidth = gridWidth / cols
            val cellHeight = gridHeight / rows
            
            for (i in 0..cols) {
                val x = gridLeft + i * cellWidth
                canvas.drawLine(x, gridTop, x, gridTop + gridHeight, gridPaint)
            }
            for (j in 0..rows) {
                val y = gridTop + j * cellHeight
                canvas.drawLine(gridLeft, y, gridLeft + gridWidth, y, gridPaint)
            }

            if (currentMoves.isNotEmpty()) {
                val topMove = currentMoves.first()
                
                val startX = gridLeft + (topMove.fromCol + 0.5f) * cellWidth
                val startY = gridTop + (topMove.fromRow + 0.5f) * cellHeight
                val endX = gridLeft + (topMove.toCol + 0.5f) * cellWidth
                val endY = gridTop + (topMove.toRow + 0.5f) * cellHeight

                // ⚡ OOXOO 최강 패턴일 시 눈에 확 띄도록 붉은 보라색 계열 화살표와 굵기로 강조 변환
                if (topMove.matchCount == 999) {
                    arrowPaint.color = Color.parseColor("#FF00DF")
                    arrowPaint.strokeWidth = 15f
                } else {
                    arrowPaint.color = Color.GREEN
                    arrowPaint.strokeWidth = 10f
                }

                canvas.drawLine(startX, startY, endX, endY, arrowPaint)
                canvas.drawCircle(startX, startY, 15f, arrowPaint.apply { color = Color.RED }) 
                canvas.drawCircle(endX, endY, 15f, arrowPaint.apply { color = Color.GREEN }) 
                
                canvas.drawText(topMove.description, startX - 50f, startY - 30f, arrowPaint.apply { 
                    color = Color.YELLOW
                    style = Paint.Style.FILL
                })
            }
        }
    }

    private fun stopCapturePipeline() {
        isCapturing = false
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        mediaProjection?.stop(); mediaProjection = null
    }

    override fun onDestroy() {
        stopCapturePipeline()
        overlayRootView?.let { 
            try { windowManager.removeView(it) } catch (e: Exception) {}
        }
        backgroundThread?.quitSafely()
        super.onDestroy()
    }
}
