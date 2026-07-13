package com.example.helper.service

import android.app.Activity
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
import android.graphics.PointF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.helper.R
import com.example.helper.ui.GridOverlayView
import java.util.concurrent.atomic.AtomicBoolean

class SolverService : Service() {

    enum class BlockColor { RED, BLUE, YELLOW, GREEN, PURPLE, UNKNOWN }

    // 🎨 화면에 직접 화살표 가이드를 그리기 위한 커스텀 뷰 클래스 생성
    private class HintArrowView(context: Context) : View(context) {
        var startX = 0f; var startY = 0f; var endX = 0f; var endY = 0f
        var shouldDraw = false

        private val linePaint = Paint().apply {
            color = Color.parseColor("#00FFCC") // 눈에 확 띄는 네온 민트색
            strokeWidth = 14f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
            setShadowLayer(10f, 0f, 0f, Color.GREEN) // 글로우 효과
        }

        private val headPaint = Paint().apply {
            color = Color.parseColor("#00FFCC")
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        fun updateArrow(sx: Float, sy: Float, ex: Float, ey: Float) {
            startX = sx; startY = sy; endX = ex; endY = ey
            shouldDraw = true
            invalidate()
        }

        fun clearArrow() {
            shouldDraw = false
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!shouldDraw) return
            
            // 1. 추천 이동 경로 중심선 그리기
            canvas.drawLine(startX, startY, endX, endY, linePaint)
            
            // 2. 이동 방향을 나타내는 화살표 촉(삼각형) 그리기
            val angle = Math.atan2((endY - startY).toDouble(), (endX - startX).toDouble())
            val arrowLength = 28f
            val arrowAngle = Math.PI / 6

            val path = Path().apply {
                moveTo(endX, endY)
                lineTo(
                    (endX - arrowLength * Math.cos(angle - arrowAngle)).toFloat(),
                    (endY - arrowLength * Math.sin(angle - arrowAngle)).toFloat()
                )
                lineTo(
                    (endX - arrowLength * Math.cos(angle + arrowAngle)).toFloat(),
                    (endY - arrowLength * Math.sin(angle + arrowAngle)).toFloat()
                )
                close()
            }
            canvas.drawPath(path, headPaint)
        }
    }

    private lateinit var windowManager: WindowManager
    private var panelView: View? = null
    private var gridOverlayView: GridOverlayView? = null
    private var hintArrowView: HintArrowView? = null // 시각적 가이드 오버레이 뷰 변수
    private lateinit var tvGridInfo: TextView
    
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var gridParams: WindowManager.LayoutParams

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var isEditMode = false 
    
    @Volatile
    private var latestBitmap: Bitmap? = null
    private val bitmapLock = Any()
    
    private val snapRequested = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { 
            Toast.makeText(this, "❌ 권한이 없습니다.", Toast.LENGTH_SHORT).show()
            stopSelf()
            return 
        }

        backgroundThread = HandlerThread("ScreenCaptureThread").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val inflater = LayoutInflater.from(this)

            // 격자 그리기 뷰 배치
            gridParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            gridOverlayView = inflater.inflate(R.layout.grid_overlay_layout, null) as GridOverlayView
            windowManager.addView(gridOverlayView, gridParams)

            // 🎯 화살표 그리기 전용 투명 레이어 배치
            hintArrowView = HintArrowView(this)
            val hintParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            windowManager.addView(hintArrowView, hintParams)

            panelParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 150
            }

            panelView = inflater.inflate(R.layout.overlay_layout, null)
            windowManager.addView(panelView, panelParams)
            
            val pView = panelView ?: return
            tvGridInfo = pView.findViewById(R.id.tvGridInfo)

            initControlPanelListeners(pView)
            setupAdvancedIndirectTouchListener() 
            updateInfoText()

        } catch (e: Exception) { 
            handleException("ServiceOnCreate", e) 
        }
    }

    private fun initControlPanelListeners(view: View) {
        val layoutHeader = view.findViewById<LinearLayout>(R.id.layoutHeader)
        val layoutExpandedBody = view.findViewById<LinearLayout>(R.id.layoutExpandedBody)
        val btnMinimize = view.findViewById<Button>(R.id.btnMinimize)
        val btnKillService = view.findViewById<Button>(R.id.btnKillService)
        val btnEditMode = view.findViewById<Button>(R.id.btnEditMode)
        val btnGridVisibility = view.findViewById<Button>(R.id.btnGridVisibility)

        layoutHeader.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    layoutHeader.setTag(R.id.btnEditMode, Pair(panelParams.x, panelParams.y))
                    layoutHeader.setTag(R.id.btnGridVisibility, Pair(event.rawX, event.rawY))
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val initCoords = layoutHeader.getTag(R.id.btnEditMode) as? Pair<*, *>
                    val touchCoords = layoutHeader.getTag(R.id.btnGridVisibility) as? Pair<*, *>
                    if (initCoords != null && touchCoords != null) {
                        panelParams.x = (initCoords.first as Int) + (event.rawX - (touchCoords.first as Float)).toInt()
                        panelParams.y = (initCoords.second as Int) + (event.rawY - (touchCoords.second as Float)).toInt()
                        panelView?.let { windowManager.updateViewLayout(it, panelParams) }
                    }
                    true
                }
                else -> false
            }
        }

        btnGridVisibility.setOnClickListener {
            gridOverlayView?.let { gov ->
                gov.showGridLines = !gov.showGridLines
                btnGridVisibility.text = if (gov.showGridLines) "👁️ 격자 보임" else "👁️ 격자 숨김"
                btnGridVisibility.setBackgroundColor(Color.parseColor(if (gov.showGridLines) "#2196F3" else "#E91E63"))
                gov.invalidate()
            }
        }

        btnEditMode.setOnClickListener {
            isEditMode = !isEditMode
            if (isEditMode) {
                btnEditMode.text = "🛠️ 조절 중"
                btnEditMode.setBackgroundColor(Color.parseColor("#4CAF50"))
                gridParams.flags = gridParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                btnEditMode.text = "🛠️ 격자 고정"
                btnEditMode.setBackgroundColor(Color.parseColor("#757575"))
                gridParams.flags = gridParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                snapRequested.set(true)
            }
            gridOverlayView?.let { windowManager.updateViewLayout(it, gridParams) }
        }

        btnMinimize.setOnClickListener {
            if (layoutExpandedBody.visibility == View.VISIBLE) {
                layoutExpandedBody.visibility = View.GONE
                btnMinimize.text = "행렬 ▼"
            } else {
                layoutExpandedBody.visibility = View.VISIBLE
                btnMinimize.text = "행렬 ▲"
            }
            panelView?.let { windowManager.updateViewLayout(it, panelParams) }
        }

        btnKillService.setOnClickListener { stopSelf() }

        view.findViewById<Button>(R.id.btnRowPlus).setOnClickListener { gridOverlayView?.let { it.rows++; updateInfoText(); it.invalidate() } }
        view.findViewById<Button>(R.id.btnRowMinus).setOnClickListener { gridOverlayView?.let { if(it.rows > 1) it.rows--; updateInfoText(); it.invalidate() } }
        view.findViewById<Button>(R.id.btnColPlus).setOnClickListener { gridOverlayView?.let { it.cols++; updateInfoText(); it.invalidate() } }
        view.findViewById<Button>(R.id.btnColMinus).setOnClickListener { gridOverlayView?.let { if(it.cols > 1) it.cols--; updateInfoText(); it.invalidate() } }
    }

    private fun setupAdvancedIndirectTouchListener() {
        var lockedCorner = -1 
        var startStartX = 0f; var startStartY = 0f
        var origX = 0f; var origY = 0f

        gridOverlayView?.setOnTouchListener { _, event ->
            if (!isEditMode) return@setOnTouchListener false
            val gov = gridOverlayView ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startStartX = event.x
                    startStartY = event.y
                    
                    val dTL = Math.hypot((startStartX - gov.tlX).toDouble(), (startStartY - gov.tlY).toDouble())
                    val dTR = Math.hypot((startStartX - gov.trX).toDouble(), (startStartY - gov.trY).toDouble())
                    val dBL = Math.hypot((startStartX - gov.blX).toDouble(), (startStartY - gov.blY).toDouble())
                    val dBR = Math.hypot((startStartX - gov.brX).toDouble(), (startStartY - gov.brY).toDouble())

                    val minVal = listOf(dTL, dTR, dBL, dBR).minOrNull() ?: return@setOnTouchListener false
                    lockedCorner = listOf(dTL, dTR, dBL, dBR).indexOf(minVal)

                    when(lockedCorner) {
                        0 -> { origX = gov.tlX; origY = gov.tlY }
                        1 -> { origX = gov.trX; origY = gov.trY }
                        2 -> { origX = gov.blX; origY = gov.blY }
                        3 -> { origX = gov.brX; origY = gov.brY }
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (lockedCorner == -1) return@setOnTouchListener false
                    val dx = event.x - startStartX
                    val dy = event.y - startStartY

                    when(lockedCorner) {
                        0 -> { gov.tlX = origX + dx; gov.tlY = origY + dy }
                        1 -> { gov.trX = origX + dx; gov.trY = origY + dy }
                        2 -> { gov.blX = origX + dx; gov.blY = origY + dy }
                        3 -> { gov.brX = origX + dx; gov.brY = origY + dy }
                    }
                    gov.invalidate()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { 
                    lockedCorner = -1 
                    snapRequested.set(true)
                    true 
                }
                else -> false
            }
        }
    }

    private fun updateInfoText() {
        gridOverlayView?.let { tvGridInfo.text = "크기: ${it.rows}행 x ${it.cols}열" }
    }

    private fun getMagneticSnappedPoint(bitmap: Bitmap, startX: Float, startY: Float, radius: Int = 40): PointF {
        val bWidth = bitmap.width
        val bHeight = bitmap.height
        val centerX = startX.toInt()
        val centerY = startY.toInt()

        var bestX = startX
        var bestY = startY
        var maxGradient = -1f

        for (y in (centerY - radius)..(centerY + radius)) {
            for (x in (centerX - radius)..(centerX + radius)) {
                if (x <= 1 || x >= bWidth - 2 || y <= 1 || y >= bHeight - 2) continue

                val getLuminance = { px: Int, py: Int ->
                    val c = bitmap.getPixel(px, py)
                    (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c))
                }

                val dx = getLuminance(x + 1, y) - getLuminance(x - 1, y)
                val dy = getLuminance(x, y + 1) - getLuminance(x, y - 1)
                val gradientMagnitude = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

                if (gradientMagnitude > maxGradient) {
                    maxGradient = gradientMagnitude
                    bestX = x.toFloat()
                    bestY = y.toFloat()
                }
            }
        }
        return PointF(bestX, bestY)
    }

    private fun detectCellColorROI(bitmap: Bitmap, centerX: Float, centerY: Float): BlockColor {
        val radius = 12 
        val cX = centerX.toInt()
        val cY = centerY.toInt()
        
        var redCount = 0; var blueCount = 0; var yellowCount = 0; var greenCount = 0; var purpleCount = 0
        val hsv = FloatArray(3)

        for (y in (cY - radius)..(cY + radius)) {
            for (x in (cX - radius)..(cX + radius)) {
                if (x < 0 || x >= bitmap.width || y < 0 || y >= bitmap.height) continue
                
                val pixel = bitmap.getPixel(x, y)
                Color.colorToHSV(pixel, hsv)
                
                val hue = hsv[0]
                val sat = hsv[1]
                val value = hsv[2]

                if (sat < 0.22f || value < 0.22f) continue

                when {
                    (hue in 0f..22f) || (hue in 345f..360f) -> redCount++
                    hue in 23f..65f -> yellowCount++
                    hue in 190f..245f -> blueCount++
                    hue in 85f..140f -> greenCount++
                    hue in 260f..310f -> purpleCount++
                }
            }
        }

        val totalSamples = ((radius * 2) + 1) * ((radius * 2) + 1)
        val threshold = totalSamples * 0.15f 

        val counts = mapOf(
            BlockColor.RED to redCount, BlockColor.BLUE to blueCount,
            BlockColor.YELLOW to yellowCount, BlockColor.GREEN to greenCount, BlockColor.PURPLE to purpleCount
        )

        val maxEntry = counts.maxByOrNull { it.value } ?: return BlockColor.UNKNOWN
        return if (maxEntry.value > threshold) maxEntry.key else BlockColor.UNKNOWN
    }

    private fun runMatchEngine(bitmap: Bitmap, gov: GridOverlayView) {
        val rows = gov.rows
        val cols = gov.cols
        val board = Array(rows) { Array(cols) { BlockColor.UNKNOWN } }

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val uMid = (c + 0.5f) / cols
                val vMid = (r + 0.5f) / rows
                val p = gov.getInterpolatedPoint(uMid, vMid)
                board[r][c] = detectCellColorROI(bitmap, p.x, p.y)
            }
        }

        var bestMatchScore = 0
        var bestMoveText = "분석 중..."
        
        // 화살표 좌표 저장용 변수
        var arrowStartX = 0f; var arrowStartY = 0f; var arrowEndX = 0f; var arrowEndY = 0f
        var hasBestMove = false

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val currentColor = board[r][c]
                if (currentColor == BlockColor.UNKNOWN) continue

                // 1. 오른쪽 스왑 시뮬레이션
                if (c + 1 < cols && board[r][c + 1] != BlockColor.UNKNOWN && board[r][c + 1] != currentColor) {
                    val rightColor = board[r][c + 1]
                    board[r][c] = rightColor
                    board[r][c + 1] = currentColor

                    val score1 = calculateComboScore(board, r, c, rows, cols)
                    val score2 = calculateComboScore(board, r, c + 1, rows, cols)
                    val totalScore = Math.max(score1, score2)

                    if (totalScore >= 3 && totalScore > bestMatchScore) {
                        bestMatchScore = totalScore
                        hasBestMove = true
                        
                        val tag = when {
                            totalScore >= 100 -> "🔮 [최고 존엄 디스코볼 생성!!] "
                            totalScore >= 70 -> "🔥 [대폭발 특수 크로스 콤보!!] "
                            totalScore >= 40 -> "⭐ [폭탄/로켓 생성 매칭] "
                            else -> ""
                        }
                        bestMoveText = "${tag}추천: (${r + 1}행, ${c + 1}열) ↔️ (${r + 1}행, ${c + 2}열) 이동"
                        
                        val p1 = gov.getInterpolatedPoint((c + 0.5f) / cols, (r + 0.5f) / rows)
                        val p2 = gov.getInterpolatedPoint((c + 1.5f) / cols, (r + 0.5f) / rows)
                        arrowStartX = p1.x; arrowStartY = p1.y; arrowEndX = p2.x; arrowEndY = p2.y
                    }

                    board[r][c] = currentColor
                    board[r][c + 1] = rightColor
                }

                // 2. 아래쪽 스왑 시뮬레이션
                if (r + 1 < rows && board[r + 1][c] != BlockColor.UNKNOWN && board[r + 1][c] != currentColor) {
                    val downColor = board[r + 1][c]
                    board[r][c] = downColor
                    board[r + 1][c] = currentColor

                    val score1 = calculateComboScore(board, r, c, rows, cols)
                    val score2 = calculateComboScore(board, r + 1, c, rows, cols)
                    val totalScore = Math.max(score1, score2)

                    if (totalScore >= 3 && totalScore > bestMatchScore) {
                        bestMatchScore = totalScore
                        hasBestMove = true
                        
                        val tag = when {
                            totalScore >= 100 -> "🔮 [최고 존엄 디스코볼 생성!!] "
                            totalScore >= 70 -> "🔥 [대폭발 특수 크로스 콤보!!] "
                            totalScore >= 40 -> "⭐ [폭탄/로켓 생성 매칭] "
                            else -> ""
                        }
                        bestMoveText = "${tag}추천: (${r + 1}행, ${c + 1}열) ↔️ (${r + 2}행, ${c + 1}열) 이동"
                        
                        val p1 = gov.getInterpolatedPoint((c + 0.5f) / cols, (r + 0.5f) / rows)
                        val p2 = gov.getInterpolatedPoint((c + 0.5f) / cols, (r + 1.5f) / rows)
                        arrowStartX = p1.x; arrowStartY = p1.y; arrowEndX = p2.x; arrowEndY = p2.y
                    }

                    board[r][c] = currentColor
                    board[r + 1][c] = downColor
                }
            }
        }

        Handler(Looper.getMainLooper()).post {
            tvGridInfo.text = bestMoveText
            if (hasBestMove) {
                // 🎯 실시간으로 계산된 픽셀 좌표를 전달하여 화면에 화살표 그리기
                hintArrowView?.updateArrow(arrowStartX, arrowStartY, arrowEndX, arrowEndY)
            } else {
                hintArrowView?.clearArrow()
            }
        }
    }

    /**
     * 🧠 [교정 포인트 2] 특수 매칭 아이템 생성 우선순위에 맞추어 정교화된 가치 부여 알고리즘
     */
    private fun calculateComboScore(board: Array<Array<BlockColor>>, r: Int, c: Int, rows: Int, cols: Int): Int {
        val hScore = checkHorizontalMatchScore(board, r, c, cols)
        val vScore = checkVerticalMatchScore(board, r, c, rows)
        
        val hValid = if (hScore >= 3) hScore else 0
        val vValid = if (vScore >= 3) vScore else 0

        return when {
            // 1순위: 직선 5연속 이상 매칭 (최고 등급 디스코볼 생성!) -> 100점 부여
            hValid >= 5 || vValid >= 5 -> 100
            
            // 2순위: 가로/세로 복합 크로스 매칭 (T자, L자 폭탄 생성!) -> 70점 부여
            hValid >= 3 && vValid >= 3 -> 70
            
            // 3순위: 직선 4연속 매칭 (가로/세로 로켓 아이템 생성!) -> 40점 부여
            hValid == 4 || vValid == 4 -> 40
            
            // 4순위: 일반적인 3개 매칭
            else -> Math.max(hValid, vValid)
        }
    }

    private fun checkVerticalMatchScore(board: Array<Array<BlockColor>>, row: Int, col: Int, maxRows: Int): Int {
        val color = board[row][col]
        if (color == BlockColor.UNKNOWN) return 0
        var up = 0; var down = 0
        var r = row - 1
        while (r >= 0 && board[r][col] == color) { up++; r-- }
        r = row + 1
        while (r < maxRows && board[r][col] == color) { down++; r++ }
        return up + down + 1
    }

    private fun checkHorizontalMatchScore(board: Array<Array<BlockColor>>, row: Int, col: Int, maxCols: Int): Int {
        val color = board[row][col]
        if (color == BlockColor.UNKNOWN) return 0
        var left = 0; var right = 0
        var c = col - 1
        while (c >= 0 && board[row][c] == color) { left++; c-- }
        c = col + 1
        while (c < maxCols && board[row][c] == color) { right++; c++ }
        return left + right + 1
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        val resultCode = intent.getIntExtra("RESULT_CODE", -1)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>("RESULT_DATA")
        
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Toast.makeText(this, "❌ 화면 공유 승인이 취소되었습니다.", Toast.LENGTH_SHORT).show()
            return START_NOT_STICKY
        }
        
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("HelperEngine", "Grid Engine", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, "HelperEngine")
            .setContentTitle("격자 보석 추적 엔진이 동작 중입니다.")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .build()
            
        startForeground(8888, notification)

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
                startCaptureAndAnalysisLoop()
            } catch (e: Exception) {
                handleException("MediaProjectionInit", e)
            }
        }, 250)

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            imageReader?.setOnImageAvailableListener(null, null) 
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.stop()
            mediaProjection = null
            
            backgroundThread?.quitSafely()
            panelView?.let { windowManager.removeView(it) }
            gridOverlayView?.let { windowManager.removeView(it) }
            hintArrowView?.let { windowManager.removeView(it) } // 신설 레이어 해제
            
            synchronized(bitmapLock) {
                latestBitmap?.recycle()
                latestBitmap = null
            }
        } catch (e: Exception) {
            Log.e("SolverService", "Destroy 에러", e)
        }
    }

    private fun handleException(location: String, e: Exception) {
        Log.e("SolverService_CRASH", "[$location] 예외 감지", e)
    }
}
