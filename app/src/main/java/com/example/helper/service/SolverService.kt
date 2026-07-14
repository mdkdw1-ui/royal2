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
import kotlin.math.abs

enum class BlockColor { RED, BLUE, YELLOW, GREEN, PURPLE, UNKNOWN }

data class MatchMove(
    val fromRow: Int, val fromCol: Int,
    val toRow: Int, val toCol: Int,
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
    
    private var floatingControlView: LinearLayout? = null 
    private var visualOverlayView: VisualOverlayView? = null 

    private val mainHandler = Handler(Looper.getMainLooper())
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    private var isCapturing = false
    private var isManualAnalysisRequested = false 

    private var isLogicEnabled = true
    private var isLargeMode = true

    private var isAutoScanEnabled = true
    private var lastAnalysisTime = 0L
    private val AUTO_SCAN_INTERVAL_MS = 1000L 
    private var miniScanButton: Button? = null 

    private val ptTL = PointF()
    private val ptTR = PointF()
    private val ptBL = PointF()
    private val ptBR = PointF()
    private var isCalibrationMode = false 

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
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        
        ptTL.set(w * 0.05f, h * 0.25f)
        ptTR.set(w * 0.95f, h * 0.25f)
        ptBL.set(w * 0.05f, h * 0.75f)
        ptBR.set(w * 0.95f, h * 0.75f)
        
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

    /**
     * 🛡️ 포그라운드 서비스 시작 프로세스 선언 (컴파일 오류 수정처)
     */
    private fun startForegroundServiceInternal() {
        val channelId = "GridHelperServiceChannel"
        val channelName = "Grid Helper Service"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                channelId, 
                channelName, 
                NotificationManager.IMPORTANCE_LOW
            )
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
            startForeground(
                1001, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            windowManager.addView(visualOverlayView, params)
        }
    }

    private fun createFloatingControlWindow() {
        mainHandler.post {
            val context = applicationContext
            floatingControlView = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#EE111111"))
                setPadding(20, 15, 20, 15)
            }

            val floatParams = WindowManager.LayoutParams(
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

            floatingControlView?.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = floatParams.x
                            initialY = floatParams.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            floatParams.x = initialX + (event.rawX - initialTouchX).toInt()
                            floatParams.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager.updateViewLayout(floatingControlView, floatParams)
                            return true
                        }
                    }
                    return false
                }
            })

            refreshFloatingPanelUI()
            windowManager.addView(floatingControlView, floatParams)
        }
    }

    private fun refreshFloatingPanelUI() {
        val view = floatingControlView ?: return
        view.removeAllViews()
        val context = applicationContext

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
                text = "엔진: ${if(isLogicEnabled) "ON" else "OFF"} / 자동화: ${if(isAutoScanEnabled) "ON" else "OFF"}\n격자: ${rows}x${cols}"
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
                setBackgroundColor(if (isCalibrationMode) Color.parseColor("#FF00DF") else Color.parseColor("#444444"))
                setTextColor(Color.WHITE)
                setOnClickListener { toggleCalibrationMode() }
            }
            view.addView(btnCalibrate)

            val matrixBox = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val btnAddRow = Button(context).apply { text = "행+"; setOnClickListener { rows++; refreshFloatingPanelUI(); visualOverlayView?.invalidate() } }
            val btnSubRow = Button(context).apply { text = "행-"; setOnClickListener { if(rows>3) rows--; refreshFloatingPanelUI(); visualOverlayView?.invalidate() } }
            val btnAddCol = Button(context).apply { text = "열+"; setOnClickListener { cols++; refreshFloatingPanelUI(); visualOverlayView?.invalidate() } }
            val btnSubCol = Button(context).apply { text = "열-"; setOnClickListener { if(cols>3) cols--; refreshFloatingPanelUI(); visualOverlayView?.invalidate() } }
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
    }

    private fun toggleCalibrationMode() {
        isCalibrationMode = !isCalibrationMode
        val overlay = visualOverlayView ?: return
        val params = overlay.layoutParams as WindowManager.LayoutParams
        if (isCalibrationMode) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            showToastOnMainThread("📐 격자점을 조절해 주세요. 자석 기능 활성화 상태입니다.")
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            showToastOnMainThread("💾 수동 격자 배치 저장 완료!")
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
                                        val shortMsg = bestMove.description
                                            .replace("⚡[", "")
                                            .replace(" 절대강자 OOXOO] 디스코볼 확정 배치! 💣", "")
                                        
                                        miniBtn.text = "⚡ $shortMsg!"
                                        miniBtn.setBackgroundColor(Color.parseColor("#FF00DF")) 
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
                } catch (e: java.lang.IllegalStateException) {
                    // 비동기 이미지 취득 레이턴시 방어
                } finally {
                    try {
                        image.close()
                    } catch (e: Exception) {}
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "캡처 서비스 구축 에러", e)
        }
    }

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
                        
                        val temp = board[r][c]
                        board[r][c] = board[nr][nc]
                        board[nr][nc] = temp

                        val hasOoxoo = isStrictOoxooPattern(board, r, c) || isStrictOoxooPattern(board, nr, nc)

                        if (hasOoxoo) {
                            val targetColor = board[r][c]
                            val korColor = getKoreanColorName(targetColor)
                            val desc = "⚡[$korColor 절대강자 OOXOO] 디스코볼 확정 배치! 💣"
                            moves.add(MatchMove(r, c, nr, nc, desc))
                        }

                        board[nr][nc] = board[r][c]
                        board[r][c] = temp
                    }
                }
            }
        }
        return moves
    }

    private fun isStrictOoxooPattern(board: Array<Array<BlockColor>>, r: Int, c: Int): Boolean {
        val color = board[r][c]
        if (color == BlockColor.UNKNOWN) return false

        if (c >= 2 && c < cols - 2) {
            if (board[r][c - 2] == color && board[r][c - 1] == color &&
                board[r][c + 1] == color && board[r][c + 2] == color) return true
        }
        if (r >= 2 && r < rows - 2) {
            if (board[r - 2][c] == color && board[r - 1][c] == color &&
                board[r + 1][c] == color && board[r + 2][c] == color) return true
        }
        return false
    }

    private fun analyzeAndSolvePerspective(bitmap: Bitmap): List<MatchMove> {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val board = Array(rows) { Array(cols) { BlockColor.UNKNOWN } }

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

                board[r][c] = detectCellColorROI(pixels, width, height, targetX, targetY)
            }
        }
        return findBestMoves(board)
    }

    private fun detectCellColorROI(pixels: IntArray, width: Int, height: Int, centerX: Float, centerY: Float): BlockColor {
        val radius = 10
        val cX = centerX.toInt()
        val cY = centerY.toInt()
        var rCnt = 0; var bCnt = 0; var yCnt = 0; var gCnt = 0; var pCnt = 0
        val hsv = FloatArray(3)

        for (y in (cY - radius)..(cY + radius)) {
            for (x in (cX - radius)..(cX + radius)) {
                if (x < 0 || x >= width || y < 0 || y >= height) continue
                val pixel = pixels[y * width + x]
                Color.colorToHSV(pixel, hsv)
                if (hsv[1] < 0.2f || hsv[2] < 0.2f) continue
                when {
                    (hsv[0] in 0f..22f) || (hsv[0] in 338f..360f) -> rCnt++
                    hsv[0] in 23f..72f -> yCnt++
                    hsv[0] in 73f..160f -> gCnt++
                    hsv[0] in 161f..250f -> bCnt++
                    hsv[0] in 251f..337f -> pCnt++
                }
            }
        }

        val threshold = 50
        val maxMap = mapOf(BlockColor.RED to rCnt, BlockColor.BLUE to bCnt, BlockColor.YELLOW to yCnt, BlockColor.GREEN to gCnt, BlockColor.PURPLE to pCnt)
        val best = maxMap.maxByOrNull { it.value } ?: return BlockColor.UNKNOWN
        return if (best.value > threshold) best.key else BlockColor.UNKNOWN
    }

    private fun getKoreanColorName(color: BlockColor): String {
        return when(color) {
            BlockColor.RED -> "빨강"; BlockColor.BLUE -> "파랑"; BlockColor.YELLOW -> "노랑"
            BlockColor.GREEN -> "초록"; BlockColor.PURPLE -> "보라"; else -> "미정"
        }
    }

    inner class VisualOverlayView(context: Context) : View(context) {
        private val linePaint = Paint().apply { color = Color.parseColor("#8000FF00"); style = Paint.Style.STROKE; strokeWidth = 3f }
        private val textPaint = Paint().apply { color = Color.YELLOW; textSize = 42f; style = Paint.Style.FILL }
        private val handlePaint = Paint().apply { color = Color.parseColor("#FF00DF"); style = Paint.Style.FILL }
        private val arrowPaint = Paint().apply { color = Color.parseColor("#FF00DF"); strokeWidth = 15f; style = Paint.Style.FILL_AND_STROKE }

        private var currentMoves = listOf<MatchMove>()
        private var selectedCorner: PointF? = null
        private val touchRadius = 70f
        private val magnetThreshold = 35f 

        fun updateMoves(moves: List<MatchMove>) {
            this.currentMoves = moves
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

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

            if (isCalibrationMode) {
                canvas.drawCircle(ptTL.x, ptTL.y, 30f, handlePaint)
                canvas.drawCircle(ptTR.x, ptTR.y, 30f, handlePaint)
                canvas.drawCircle(ptBL.x, ptBL.y, 30f, handlePaint)
                canvas.drawCircle(ptBR.x, ptBR.y, 30f, handlePaint)
            }

            if (isLogicEnabled && currentMoves.isNotEmpty()) {
                val move = currentMoves.first()
                
                val uFrom = (move.fromCol + 0.5f) / cols
                val vFrom = (move.fromRow + 0.5f) / rows
                val startX = (1-vFrom)*((1-uFrom)*ptTL.x + uFrom*ptTR.x) + vFrom*((1-uFrom)*ptBL.x + uFrom*ptBR.x)
                val startY = (1-vFrom)*((1-uFrom)*ptTL.y + uFrom*ptTR.y) + vFrom*((1-uFrom)*ptBL.y + uFrom*ptBR.y)

                val uTo = (move.toCol + 0.5f) / cols
                val vTo = (move.toRow + 0.5f) / rows
                val endX = (1-vTo)*((1-uTo)*ptTL.x + uTo*ptTR.x) + vTo*((1-uTo)*ptBL.x + uTo*ptBR.x)
                val endY = (1-vTo)*((1-uTo)*ptTL.y + uTo*ptTR.y) + vTo*((1-uTo)*ptBR.y + uTo*ptBR.y)

                canvas.drawLine(startX, startY, endX, endY, arrowPaint)
                canvas.drawCircle(startX, startY, 20f, Paint().apply { color = Color.RED })
                canvas.drawCircle(endX, endY, 20f, Paint().apply { color = Color.GREEN })
                canvas.drawText(move.description, startX - 100f, startY - 40f, textPaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isCalibrationMode) return false

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
                    return selectedCorner != null
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
        floatingControlView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        visualOverlayView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        backgroundThread?.quitSafely()
        super.onDestroy()
    }
}
