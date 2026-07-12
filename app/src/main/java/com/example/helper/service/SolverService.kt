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

// OpenCV 관련 임포트
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.util.ArrayList
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SolverService : Service() {
    private val TAG = "SolverService"
    
    // 퍼즐 판 고정 설정 상수 (9x9)
    private val ROWS = 9
    private val COLS = 9

    // OpenCV HSV 기반 색상 타겟 정의 (0~180 범위)
    private val pieceColors = mapOf(
        "red" to 5.0,     // 인덱스 0 -> 결과 코드 1
        "blue" to 115.0,  // 인덱스 1 -> 결과 코드 2
        "yellow" to 25.0, // 인덱스 2 -> 결과 코드 3
        "green" to 60.0,  // 인덱스 3 -> 결과 코드 4
        "purple" to 150.0 // 인덱스 4 -> 결과 코드 5
    )
    private val colorNames = pieceColors.keys.toList()
    
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

    // OpenCV 데이터 융합을 위한 구조체
    private data class BoardAnalysisResult(
        val grid: Array<IntArray>,
        val bounds: Rect,
        val verticalLines: List<Int>,
        val horizontalLines: List<Int>
    )

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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
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
            setPadding(25, 12, 25, 12)
        }

        statusTextView = TextView(this).apply {
            text = "🧩 엔진 시동 중..."
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(0, 0, 20, 0)
        }
        
        toggleButton = Button(this).apply {
            text = "정지"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#44FFFFFF")) 
            
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 10, 0) }
            
            setOnClickListener {
                isAnalyzing = !isAnalyzing
                if (isAnalyzing) {
                    text = "정지"
                    setBackgroundColor(Color.parseColor("#44FFFFFF"))
                    statusTextView?.text = "🔍 분석 재개됨..."
                } else {
                    text = "시작"
                    setBackgroundColor(Color.parseColor("#AAFF9800")) 
                    statusTextView?.text = "⏸️ 일시정지"
                    hintOverlayView?.clearHint() 
                }
            }
        }

        killButton = Button(this).apply {
            text = "종료"
            textSize = 11f
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
            gravity = Gravity.TOP or Gravity.START
            x = 40  
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
            statusTextView?.text = "❌ 권한 거부됨"
            return START_NOT_STICKY
        }

        try {
            val metrics = resources.displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels

            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, dataIntent)
            
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post { statusTextView?.text = "🧩 스캔 엔진 종료됨" }
                }
            }, mainHandler)

            backgroundThread = HandlerThread("Grid_Scanner").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                if (!isAnalyzing) {
                    try { reader.acquireLatestImage()?.close() } catch (e: Exception) {}
                    return@setOnImageAvailableListener
                }

                val currentTime = System.currentTimeMillis()
                if (currentTime - lastAnalyzeTime >= 500L) { 
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
            
            statusTextView?.text = "🧩 화면 스캔 대기 중..."

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

            if (!isAnalyzing) return

            val srcMat = Mat()
            var analysisResult: BoardAnalysisResult? = null
            try {
                Utils.bitmapToMat(bitmap, srcMat)
                analysisResult = processOpenCVGrid(srcMat)
            } catch (ex: Exception) {
                Log.e(TAG, "OpenCV Matrix 변환 또는 파이프라인 처리 오류", ex)
            } finally {
                srcMat.release() 
            }

            if (analysisResult == null) {
                mainHandler.post {
                    if (isAnalyzing) {
                        statusTextView?.text = "🧩 퍼즐 판 탐색 중 (OpenCV)..."
                        hintOverlayView?.clearHint()
                    }
                }
                return
            }

            val hint = findSimulatedMatch5(analysisResult.grid, ROWS, COLS)
            
            mainHandler.post {
                if (!isAnalyzing) return@post
                if (hint != null) {
                    statusTextView?.text = "🔥 5매칭 발견!"
                    
                    val bounds = analysisResult.bounds
                    val vLines = analysisResult.verticalLines
                    val hLines = analysisResult.horizontalLines

                    val fromLeft = bounds.x + vLines[hint.fromC]
                    val fromRight = bounds.x + vLines[hint.fromC + 1]
                    val fromTop = bounds.y + hLines[hint.fromR]
                    val fromBottom = bounds.y + hLines[hint.fromR + 1]

                    val toLeft = bounds.x + vLines[hint.toC]
                    val toRight = bounds.x + vLines[hint.toC + 1]
                    val toTop = bounds.y + hLines[hint.toR]
                    val toBottom = bounds.y + hLines[hint.toR + 1]

                    val fromPixelX = (fromLeft + fromRight) / 2f
                    val fromPixelY = (fromTop + fromBottom) / 2f
                    val toPixelX = (toLeft + toRight) / 2f
                    val toPixelY = (toTop + toBottom) / 2f
                    
                    hintOverlayView?.updateHint(fromPixelX, fromPixelY, toPixelX, toPixelY)
                } else {
                    statusTextView?.text = "🔍 5매칭 구조 탐색 중..."
                    hintOverlayView?.clearHint() 
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "연산 예외", t)
        } finally { 
            try { image.close() } catch (e: Exception) {}
        }
    }

    private fun processOpenCVGrid(img: Mat): BoardAnalysisResult? {
        if (img.empty()) return null

        val hsv = Mat()
        val blueMask = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val invertedMask = Mat()
        var backgroundMask: Mat? = null

        try {
            Imgproc.cvtColor(img, hsv, Imgproc.COLOR_RGBA2BGR) 
            Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_BGR2HSV)
            
            Core.inRange(hsv, Scalar(95.0, 40.0, 40.0), Scalar(135.0, 220.0, 240.0), blueMask)
            
            Imgproc.morphologyEx(blueMask, blueMask, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)
            Imgproc.morphologyEx(blueMask, blueMask, Imgproc.MORPH_OPEN, kernel, Point(-1.0, -1.0), 1)
            Core.bitwise_not(blueMask, invertedMask)

            val bounds = findGridBounds(invertedMask) ?: return null
            backgroundMask = createBackgroundMask(img, bounds) ?: return null

            val (verticalLines, horizontalLines) = detectGridLinesFromBackground(backgroundMask, bounds)
            val grid = extractAndCategorizeGrid(hsv, bounds, verticalLines, horizontalLines)

            return BoardAnalysisResult(grid, bounds, verticalLines, horizontalLines)

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            hsv.release()
            blueMask.release()
            kernel.release()
            invertedMask.release()
            backgroundMask?.release()
        }
    }

    private fun findGridBounds(mask: Mat): Rect? {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        var bestBox: Rect? = null
        var maxArea = 0.0
        val width = mask.width()
        val height = mask.height()

        for (cnt in contours) {
            val rect = Imgproc.boundingRect(cnt)
            val area = rect.area()
            
            if (rect.width > width * 0.5 && rect.height > height * 0.3 && rect.y > height * 0.15) {
                if (area > maxArea) {
                    maxArea = area
                    bestBox = rect
                }
            }
            cnt.release()
        }
        hierarchy.release()
        return bestBox
    }

    private fun createBackgroundMask(img: Mat, bounds: Rect): Mat? {
        val h = img.height()
        val w = img.width()
        if (bounds.x < 0 || bounds.y < 0 || bounds.x + bounds.width > w || bounds.y + bounds.height > h) return null

        val gridRegion = img.submat(bounds)
        if (gridRegion.empty()) {
            gridRegion.release()
            return null
        }

        val hsv = Mat()
        val saturation = Mat()
        val backgroundMask = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))

        try {
            Imgproc.cvtColor(gridRegion, hsv, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_BGR2HSV)
            Core.extractChannel(hsv, saturation, 1)

            val meanMat = MatOfDouble()
            val stdMat = MatOfDouble()
            Core.meanStdDev(saturation, meanMat, stdMat)
            
            // [수정] 플랫폼 타입 뒤 안전성 향상을 위해 엘비스 처리 명시
            val meanSaturation = meanMat.get(0, 0)?.get(0) ?: 0.0
            val stdSaturation = stdMat.get(0, 0)?.get(0) ?: 0.0
            
            meanMat.release()
            stdMat.release()

            // [수정] 확실한 Double 연산 범위를 위해 결합 연산자 괄호 추가
            var saturationThreshold = (meanSaturation + (0.4 * stdSaturation)).toInt()
            saturationThreshold = max(saturationThreshold, 45)

            Core.inRange(saturation, Scalar(0.0), Scalar(saturationThreshold.toDouble()), backgroundMask)
            
            Imgproc.morphologyEx(backgroundMask, backgroundMask, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)
            Imgproc.morphologyEx(backgroundMask, backgroundMask, Imgproc.MORPH_OPEN, kernel, Point(-1.0, -1.0), 1)

            return backgroundMask
        } finally {
            gridRegion.release()
            hsv.release()
            saturation.release()
            kernel.release()
        }
    }

    private fun detectGridLinesFromBackground(backgroundMask: Mat, bounds: Rect): Pair<List<Int>, List<Int>> {
        val height = backgroundMask.height()
        val width = backgroundMask.width()

        val idealXs = DoubleArray(COLS + 1) { it * width.toDouble() / COLS }
        val idealYs = DoubleArray(ROWS + 1) { it * height.toDouble() / ROWS }

        val colCounts = IntArray(width)
        val rowCounts = IntArray(height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                // [수정] Nullable 대응 및 플랫폼 타입 처리 분리
                val bgPixel = backgroundMask.get(y, x)
                if (bgPixel != null && bgPixel[0] == 255.0) {
                    colCounts[x]++
                    rowCounts[y]++
                }
            }
        }

        val refinedVerticalLines = ArrayList<Int>()
        val refinedHorizontalLines = ArrayList<Int>()

        val searchWindowX = max(4, (width.toDouble() / COLS * 0.15).toInt())
        for (i in 0..COLS) {
            val idealX = idealXs[i].toInt()
            if (i == 0 || i == COLS) {
                refinedVerticalLines.add(idealX)
                continue
            }
            val startX = max(0, idealX - searchWindowX)
            val endX = min(width - 1, idealX + searchWindowX)

            var maxVal = -1
            var peakX = idealX
            for (x in startX..endX) {
                if (colCounts[x] > maxVal) {
                    maxVal = colCounts[x]
                    peakX = x
                }
            }
            refinedVerticalLines.add(peakX)
        }

        val searchWindowY = max(4, (height.toDouble() / ROWS * 0.15).toInt())
        for (i in 0..ROWS) {
            val idealY = idealYs[i].toInt()
            if (i == 0 || i == ROWS) {
                refinedHorizontalLines.add(idealY)
                continue
            }
            val startY = max(0, idealY - searchWindowY)
            val endY = min(height - 1, idealY + searchWindowY)

            var maxVal = -1
            var peakY = idealY
            for (y in startY..endY) {
                if (rowCounts[y] > maxVal) {
                    maxVal = rowCounts[y]
                    peakY = y
                }
            }
            refinedHorizontalLines.add(peakY)
        }

        return Pair(refinedVerticalLines, refinedHorizontalLines)
    }

    private fun extractAndCategorizeGrid(
        hsv: Mat, bounds: Rect, 
        verticalLines: List<Int>, horizontalLines: List<Int>
    ): Array<IntArray> {
        
        val gridRegionHsv = hsv.submat(bounds)
        val categoryGrid = Array(ROWS) { IntArray(COLS) }
        val targetHues = colorNames.map { pieceColors[it]!! }

        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                val cellLeft = verticalLines[col]
                val cellRight = verticalLines[col + 1]
                val cellTop = horizontalLines[row]
                val cellBottom = horizontalLines[row + 1]

                val cellW = cellRight - cellLeft
                val cellH = cellBottom - cellTop

                val innerLeft = cellLeft + (cellW * 0.3).toInt()
                val innerRight = cellRight - (cellW * 0.3).toInt()
                val innerTop = cellTop + (cellH * 0.3).toInt()
                val innerBottom = cellBottom - (cellH * 0.3).toInt()

                val votes = IntArray(colorNames.size)

                val stepY = max(1, (innerBottom - innerTop) / 4)
                val stepX = max(1, (innerRight - innerLeft) / 4)

                for (py in innerTop until innerBottom step stepY) {
                    for (px in innerLeft until innerRight step stepX) {
                        if (py >= 0 && py < gridRegionHsv.height() && px >= 0 && px < gridRegionHsv.width()) {
                            
                            // [수정] ?: continue 제거 후 명시적 null 및 사이즈 체크로 분리 (Type variable V 추론 오류 방지)
                            val pixel = gridRegionHsv.get(py, px)
                            if (pixel == null || pixel.size < 3) continue
                            
                            val h = pixel[0]
                            val s = pixel[1]
                            val v = pixel[2]

                            if (s < 40.0 || v < 40.0) continue

                            if (s > 70.0 && v > 50.0) {
                                var minIndex = 0
                                var minDist = 999.0

                                for (i in targetHues.indices) {
                                    val diff = abs(h - targetHues[i])
                                    val wrapDist = min(diff, 180.0 - diff)
                                    
                                    if (wrapDist < minDist) {
                                        minDist = wrapDist
                                        minIndex = i
                                    }
                                }
                                val weight = if (s > 100.0) 2 else 1
                                votes[minIndex] += weight
                            }
                        }
                    }
                }

                var maxVotes = 0
                var winningCategory = 0 
                for (i in votes.indices) {
                    if (votes[i] > maxVotes) {
                        maxVotes = votes[i]
                        winningCategory = i + 1 
                    }
                }
                
                categoryGrid[row][col] = if (maxVotes >= 5) winningCategory else 0
            }
        }

        gridRegionHsv.release()
        return categoryGrid
    }

    private fun findSimulatedMatch5(grid: Array<IntArray>, rows: Int, cols: Int): MatchHint? {
        for (r in 0 until rows) {
            for (c in 0 until cols - 1) {
                if (grid[r][c] == 0 || grid[r][c+1] == 0) continue
                
                val temp = grid[r][c]
                grid[r][c] = grid[r][c+1]
                grid[r][c+1] = temp
                
                val success = verify5MatchLine(grid, rows, cols)
                
                grid[r][c+1] = grid[r][c]
                grid[r][c] = temp
                
                if (success) return MatchHint(r, c, r, c + 1)
            }
        }
        
        for (r in 0 until rows - 1) {
            for (c in 0 until cols) {
                if (grid[r][c] == 0 || grid[r+1][c] == 0) continue
                
                val temp = grid[r][c]
                grid[r][c] = grid[r+1][c]
                grid[r+1][c] = temp
                
                val success = verify5MatchLine(grid, rows, cols)
                
                grid[r+1][c] = grid[r][c]
                grid[r][c] = temp
                
                if (success) return MatchHint(r, c, r + 1, c)
            }
        }
        return null
    }

    private fun verify5MatchLine(grid: Array<IntArray>, rows: Int, cols: Int): Boolean {
        for (r in 0 until rows) {
            var chain = 1
            var prev = -1
            for (c in 0 until cols) {
                val current = grid[r][c]
                if (current != 0 && current == prev) {
                    chain++
                    if (chain >= 5) return true
                } else {
                    chain = 1
                    prev = current
                }
            }
        }
        for (c in 0 until cols) {
            var chain = 1
            var prev = -1
            for (r in 0 until rows) {
                val current = grid[r][c]
                if (current != 0 && current == prev) {
                    chain++
                    if (chain >= 5) return true
                } else {
                    chain = 1
                    prev = current
                }
            }
        }
        return false
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
