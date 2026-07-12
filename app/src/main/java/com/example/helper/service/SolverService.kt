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
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

class SolverService : Service() {
    private val TAG = "SolverService"
    
    private var windowManager: WindowManager? = null
    private var overlayContainer: LinearLayout? = null
    private var statusTextView: TextView? = null       
    private var toggleButton: Button? = null           
    private var killButton: Button? = null             
    
    private var hintOverlayView: HintOverlayView? = null
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var reusableBitmap: Bitmap? = null
    private var screenWidth = 1080
    private var screenHeight = 2400
    private var lastAnalyzeTime = 0L

    @Volatile
    private var isAnalyzing = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        promoteToForeground("실시간 화면 분석 엔진 준비 중")
        createOverlayLayout()
        createHintOverlayCanvas() 
    }

    private fun createHintOverlayCanvas() {
        if (!Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        hintOverlayView = HintOverlayView(this)
        
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            else 
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_BOUNDS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        
        try {
            windowManager?.addView(hintOverlayView, layoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "힌트 오버레이 캔버스 주입 실패", e)
        }
    }

    private fun createOverlayLayout() {
        if (!Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        overlayContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#CC000000")) 
            setPadding(30, 15, 30, 15)
        }

        statusTextView = TextView(this).apply {
            text = "🧩 엔진 시동 중..."
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(0, 0, 30, 0)
        }
        
        toggleButton = Button(this).apply {
            text = "정지"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#44FFFFFF")) 
            
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 15, 0) }
            
            setOnClickListener {
                isAnalyzing = !isAnalyzing
                if (isAnalyzing) {
                    text = "정지"
                    setBackgroundColor(Color.parseColor("#44FFFFFF"))
                    statusTextView?.text = "🔍 분석 재개됨..."
                } else {
                    text = "시작"
                    setBackgroundColor(Color.parseColor("#AAFF9800")) 
                    statusTextView?.text = "⏸️ 분석 일시정지"
                    hintOverlayView?.clearHint() 
                }
            }
        }

        killButton = Button(this).apply {
            text = "종료"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#AAD32F2F")) 
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { stopSelf() }
        }

        overlayContainer?.addView(statusTextView)
        overlayContainer?.addView(toggleButton)
        overlayContainer?.addView(killButton)

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            else 
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 150 
        }

        try {
            windowManager?.addView(overlayContainer, layoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "컨트롤 패널 주입 실패", e)
        }
    }

    private fun promoteToForeground(message: String) {
        try {
            val channelId = "solver_service_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(channelId, "Solver Capture Layer", NotificationManager.IMPORTANCE_LOW)
                getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            }

            val notification: Notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("Match-3 Solver")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(8888, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(8888, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Foreground 서비스 승격 실패", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
        val dataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra("data")
        }
        
        if (resultCode != Activity.RESULT_OK || dataIntent == null) {
            statusTextView?.text = "❌ 오류: 권한 거부됨"
            return START_NOT_STICKY
        }

        try {
            val metrics = resources.displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels

            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, dataIntent)
            
            backgroundThread = HandlerThread("Grid_Scanner").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post { statusTextView?.text = "🧩 스캔 엔진 종료됨" }
                }
            }, backgroundHandler)

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                if (!isAnalyzing) {
                    try { reader.acquireLatestImage()?.close() } catch (e: Exception) {}
                    return@setOnImageAvailableListener
                }

                val currentTime = System.currentTimeMillis()
                if (currentTime - lastAnalyzeTime >= 700L) { 
                    lastAnalyzeTime = currentTime
                    try {
                        analyzeScreenFast(reader)
                    } catch (e: Exception) {
                        Log.e(TAG, "분석 예외", e)
                    }
                } else {
                    try { reader.acquireLatestImage()?.close() } catch (e: Exception) {}
                }
            }, backgroundHandler)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture", screenWidth, screenHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, backgroundHandler
            )
            
            statusTextView?.text = "🧩 게임 화면 스캔 대기 중..."

        } catch (e: Exception) {
            Log.e(TAG, "미디어 프로젝션 시동 실패", e)
        }

        return START_STICKY
    }

    private fun analyzeScreenFast(reader: ImageReader) {
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return
        
        try {
            val planes = image.planes
            if (planes.isNullOrEmpty() || planes[0].buffer == null) return

            val buffer = planes[0].buffer          
            val pixelStride = planes[0].pixelStride  
            val rowStride = planes[0].rowStride      
            if (pixelStride == 0) return
            
            val rowPadding = rowStride - pixelStride * screenWidth
            val adjustedWidth = screenWidth + rowPadding / pixelStride

            if (reusableBitmap == null || reusableBitmap!!.width != adjustedWidth || reusableBitmap!!.height != screenHeight) {
                reusableBitmap?.recycle()
                reusableBitmap = Bitmap.createBitmap(adjustedWidth, screenHeight, Bitmap.Config.ARGB_8888)
            }
            
            val bitmap = reusableBitmap!!
            buffer.rewind() 
            bitmap.copyPixelsFromBuffer(buffer)

            var minBlockX = screenWidth
            var maxBlockX = 0
            var minBlockY = screenHeight
            var maxBlockY = 0
            var detectedBlockCount = 0

            val scanLeft = (screenWidth * 0.05f).toInt()
            val scanRight = (screenWidth * 0.95f).toInt()
            val scanTop = (screenHeight * 0.33f).toInt()    
            val scanBottom = (screenHeight * 0.82f).toInt() 

            for (y in scanTop until scanBottom step 25) {
                for (x in scanLeft until scanRight step 25) {
                    val colorId = identifyColorHSV(bitmap.getPixel(x, y))
                    if (colorId in 1..5) {
                        if (x < minBlockX) minBlockX = x
                        if (x > maxBlockX) maxBlockX = x
                        if (y < minBlockY) minBlockY = y
                        if (y > maxBlockY) maxBlockY = y
                        detectedBlockCount++
                    }
                }
            }

            if (!isAnalyzing) return

            if (detectedBlockCount < 12 || (maxBlockX - minBlockX) < screenWidth * 0.5) {
                mainHandler.post {
                    if (isAnalyzing) {
                        statusTextView?.text = "🧩 퍼즐 판 탐색 중..."
                        hintOverlayView?.clearHint()
                    }
                }
                return
            }

            val boardWidth = maxBlockX - minBlockX
            val boardHeight = maxBlockY - minBlockY
            val approxBlockSize = screenWidth / 9.0f
            val currentGridCols = Math.round(boardWidth.toFloat() / approxBlockSize + 0.3f).coerceIn(5, 11)
            val currentGridRows = Math.round(boardHeight.toFloat() / approxBlockSize + 0.3f).coerceIn(3, 11)

            val strideX = if (currentGridCols > 1) boardWidth.toFloat() / (currentGridCols - 1) else approxBlockSize
            val strideY = if (currentGridRows > 1) boardHeight.toFloat() / (currentGridRows - 1) else approxBlockSize

            val colorGrid = Array(currentGridRows) { IntArray(currentGridCols) }
            for (r in 0 until currentGridRows) {
                for (c in 0 until currentGridCols) {
                    val cx = (minBlockX + (c * strideX)).toInt().coerceIn(0, bitmap.width - 1)
                    val cy = (minBlockY + (r * strideY)).toInt().coerceIn(0, bitmap.height - 1)
                    colorGrid[r][c] = getPixelBlockColor(bitmap, cx, cy)
                }
            }

            val hint = findExactOOXOOMatch5(colorGrid, currentGridRows, currentGridCols)
            
            mainHandler.post {
                if (!isAnalyzing) return@post
                if (hint != null) {
                    statusTextView?.text = "🔥 5매칭 발견! 화면의 화살표를 따라 미세요!"
                    
                    val fromPixelX = minBlockX + (hint.fromC * strideX)
                    val fromPixelY = minBlockY + (hint.fromR * strideY)
                    val toPixelX = minBlockX + (hint.toC * strideX)
                    val toPixelY = minBlockY + (hint.toR * strideY)
                    
                    hintOverlayView?.updateHint(fromPixelX, fromPixelY, toPixelX, toPixelY)
                } else {
                    statusTextView?.text = "🔍 5매칭 구조 탐색 중..."
                    hintOverlayView?.clearHint() 
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "연산 예외", t)
        } finally { // 🎯 [교정 완료] final -> finally 수정됨
            try { image.close() } catch (e: Exception) {}
        }
    }

    private fun getPixelBlockColor(bitmap: Bitmap, cx: Int, cy: Int): Int {
        val votes = IntArray(6)
        val offsets = intArrayOf(-12, 0, 12) 
        for (dy in offsets) {
            for (dx in offsets) {
                val px = (cx + dx).coerceIn(0, bitmap.width - 1)
                val py = (cy + dy).coerceIn(0, bitmap.height - 1)
                votes[identifyColorHSV(bitmap.getPixel(px, py))]++
            }
        }
        var maxVote = 0
        var winner = 0
        for (i in 1..5) {
            if (votes[i] > maxVote) {
                maxVote = votes[i]
                winner = i
            }
        }
        return if (maxVote >= 3) winner else 0
    }

    private fun identifyColorHSV(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        if (r + g + b < 100 || r + g + b > 710) return 0

        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        val hue = hsv[0]
        val sat = hsv[1]
        val value = hsv[2]

        if (sat < 0.16f || value < 0.16f) return 0

        return when {
            (hue >= 345f || hue <= 15f) -> 1  
            (hue in 195f..245f) -> 2          
            (hue in 38f..65f) -> 3            
            (hue in 90f..145f) -> 4           
            (hue in 265f..330f) -> 5          
            else -> 0
        }
    }

    private fun findExactOOXOOMatch5(grid: Array<IntArray>, rows: Int, cols: Int): MatchHint? {
        for (r in 0 until rows) {
            for (c in 0..cols - 5) {
                val t = grid[r][c]
                if (t == 0) continue
                if (grid[r][c+1] == t && grid[r][c+3] == t && grid[r][c+4] == t && grid[r][c+2] != t) {
                    val targetRow = r
                    val targetCol = c + 2 
                    if (targetRow - 1 >= 0 && grid[targetRow - 1][targetCol] == t) {
                        return MatchHint(targetRow - 1, targetCol, targetRow, targetCol)
                    }
                    if (targetRow + 1 < rows && grid[targetRow + 1][targetCol] == t) {
                        return MatchHint(targetRow + 1, targetCol, targetRow, targetCol)
                    }
                }
            }
        }
        for (c in 0 until cols) {
            for (r in 0..rows - 5) {
                val t = grid[r][c]
                if (t == 0) continue
                if (grid[r+1][c] == t && grid[r+3][c] == t && grid[r+4][c] == t && grid[r+2][c] != t) {
                    val targetRow = r + 2 
                    val targetCol = c
                    if (targetCol - 1 >= 0 && grid[targetRow][targetCol - 1] == t) {
                        return MatchHint(targetRow, targetCol - 1, targetRow, targetCol)
                    }
                    if (targetCol + 1 < cols && grid[targetRow][targetCol + 1] == t) {
                        return MatchHint(targetRow, targetCol + 1, targetRow, targetCol)
                    }
                }
            }
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (overlayContainer != null) {
                windowManager?.removeView(overlayContainer)
                overlayContainer = null
            }
            if (hintOverlayView != null) {
                windowManager?.removeView(hintOverlayView)
                hintOverlayView = null
            }
            imageReader?.setOnImageAvailableListener(null, null)
            backgroundThread?.quitSafely() 
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            reusableBitmap?.recycle()
            reusableBitmap = null
        } catch (e: Exception) {
            Log.e(TAG, "자원 해제 예외", e)
        }
    }

    data class MatchHint(val fromR: Int, val fromC: Int, val toR: Int, val toC: Int)

    private class HintOverlayView(context: Context) : View(context) {
        private var showDrawing = false
        private var fx = 0f
        private var fy = 0f
        private var tx = 0f
        private var ty = 0f

        private val linePaint = Paint().apply {
            color = Color.parseColor("#FFFF1744") 
            strokeWidth = 14f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
            setShadowLayer(10f, 0f, 0f, Color.RED) 
        }

        private val arrowPaint = Paint().apply {
            color = Color.parseColor("#FFFF1744")
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        fun updateHint(fromX: Float, fromY: Float, toX: Float, toY: Float) {
            this.fx = fromX
            this.fy = fromY
            this.tx = toX
            this.ty = toY
            this.showDrawing = true
            invalidate() 
        }

        fun clearHint() {
            if (showDrawing) {
                this.showDrawing = false
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!showDrawing) return

            canvas.drawLine(fx, fy, tx, ty, linePaint)

            val angle = Math.atan2((ty - fy).toDouble(), (tx - fx).toDouble())
            val arrowLength = 36f
            val arrowWidthAngle = Math.PI / 6.0 

            val path = Path().apply {
                moveTo(tx, ty) 
                lineTo(
                    (tx - arrowLength * Math.cos(angle - arrowWidthAngle)).toFloat(),
                    (ty - arrowLength * Math.sin(angle - arrowWidthAngle)).toFloat()
                )
                lineTo(
                    (tx - arrowLength * Math.cos(angle + arrowWidthAngle)).toFloat(),
                    (ty - arrowLength * Math.sin(angle + arrowWidthAngle)).toFloat()
                )
                close()
            }
            canvas.drawPath(path, arrowPaint)
        }
    }
}
