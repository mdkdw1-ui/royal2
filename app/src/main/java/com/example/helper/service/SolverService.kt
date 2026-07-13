package com.example.helper.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
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

    private lateinit var windowManager: WindowManager
    private var panelView: View? = null
    private var gridOverlayView: GridOverlayView? = null
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

            gridParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            gridOverlayView = inflater.inflate(R.layout.grid_overlay_layout, null) as GridOverlayView
            windowManager.addView(gridOverlayView, gridParams)

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

    private fun startCaptureAndAnalysisLoop() {
        try {
            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val currentProjection = mediaProjection ?: throw NullPointerException("MediaProjection 토큰 공백")
            
            virtualDisplay = currentProjection.createVirtualDisplay(
                "ScreenCaptureEngine", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null
            )

            imageReader?.setOnImageAvailableListener({ reader ->
                val image = try {
                    reader.acquireLatestImage()
                } catch (e: IllegalStateException) {
                    return@setOnImageAvailableListener 
                } ?: return@setOnImageAvailableListener

                try {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width
                    
                    val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(buffer)

                    synchronized(bitmapLock) {
                        latestBitmap?.recycle()
                        latestBitmap = bitmap
                    }

                    val gov = gridOverlayView
                    if (gov != null) {
                        if (snapRequested.getAndSet(false)) {
                            val snapTL = getMagneticSnappedPoint(bitmap, gov.tlX, gov.tlY)
                            val snapTR = getMagneticSnappedPoint(bitmap, gov.trX, gov.trY)
                            val snapBL = getMagneticSnappedPoint(bitmap, gov.blX, gov.blY)
                            val snapBR = getMagneticSnappedPoint(bitmap, gov.brX, gov.brY)

                            Handler(Looper.getMainLooper()).post {
                                gov.tlX = snapTL.x; gov.tlY = snapTL.y
                                gov.trX = snapTR.x; gov.trY = snapTR.y
                                gov.blX = snapBL.x; gov.blY = snapBL.y
                                gov.brX = snapBR.x; gov.brY = snapBR.y
                                gov.invalidate()
                                Toast.makeText(applicationContext, "🧲 마그네틱 자석 락온 완료!", Toast.LENGTH_SHORT).show()
                            }
                        }

                        if (!isEditMode) {
                            runMatchEngine(bitmap, gov)
                        }
                    }

                } catch (e: Exception) {
                    if (e !is IllegalStateException) handleException("ImageProcessingLoop", e)
                } finally {
                    try { image.close() } catch (e: Exception) {}
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            handleException("CreateVirtualDisplay", e)
        }
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

                if (sat < 0.25f || value < 0.25f) continue

                when {
                    (hue in 0f..12f) || (hue in 345f..360f) -> redCount++
                    hue in 190f..245f -> blueCount++
                    hue in 40f..65f -> yellowCount++
                    hue in 85f..140f -> {
                        if (sat > 0.45f) greenCount++
                    }
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

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val currentColor = board[r][c]
                if (currentColor == BlockColor.UNKNOWN) continue

                if (c + 1 < cols && board[r][c + 1] != BlockColor.UNKNOWN && board[r][c + 1] != currentColor) {
                    val rightColor = board[r][c + 1]
                    
                    board[r][c] = rightColor
                    board[r][c + 1] = currentColor

                    val matchLenLeft = checkVerticalMatchScore(board, r, c, rows)
                    val matchLenRight = checkVerticalMatchScore(board, r, c + 1, rows)
                    val matchLenHoriz1 = checkHorizontalMatchScore(board, r, c, cols)
                    val matchLenHoriz2 = checkHorizontalMatchScore(board, r, c + 1, cols)

                    val maxMatchFound = listOf(matchLenLeft, matchLenRight, matchLenHoriz1, matchLenHoriz2).maxOrNull() ?: 0

                    if (maxMatchFound >= 3) {
                        triggerHintHighlight(r, c, r, c + 1, maxMatchFound)
                        board[r][c] = currentColor
                        board[r][c + 1] = rightColor
                        return
                    }
                    board[r][c] = currentColor
                    board[r][c + 1] = rightColor
                }

                if (r + 1 < rows && board[r + 1][c] != BlockColor.UNKNOWN && board[r + 1][c] != currentColor) {
                    val downColor = board[r + 1][c]

                    board[r][c] = downColor
                    board[r + 1][c] = currentColor

                    val matchLenUp = checkHorizontalMatchScore(board, r, c, cols)
                    val matchLenDown = checkHorizontalMatchScore(board, r + 1, c, cols)
                    val matchLenVert1 = checkVerticalMatchScore(board, r, c, rows)
                    val matchLenVert2 = checkVerticalMatchScore(board, r + 1, c, rows)

                    val maxMatchFound = listOf(matchLenUp, matchLenDown, matchLenVert1, matchLenVert2).maxOrNull() ?: 0

                    if (maxMatchFound >= 3) {
                        triggerHintHighlight(r, c, r + 1, c, maxMatchFound)
                        board[r][c] = currentColor
                        board[r + 1][c] = downColor
                        return
                    }
                    board[r][c] = currentColor
                    board[r + 1][c] = downColor
                }
            }
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

    private fun triggerHintHighlight(r1: Int, c1: Int, r2: Int, c2: Int, score: Int) {
        Handler(Looper.getMainLooper()).post {
            val specialTag = if(score >= 5) "🔥 [최강 5연속 매칭 발견!] " else ""
            tvGridInfo.text = "${specialTag}추천: ($r1 행, $c1 열) ↔️ ($r2 행, $c2 열) 이동"
        }
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
