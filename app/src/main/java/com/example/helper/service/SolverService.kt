package com.example.helper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
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
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.helper.MainActivity // 🎯 [무결점 수정] 실제 MainActivity 패키지 주소로 정밀 매칭

class SolverService : Service() {
    private val TAG = "SolverService"
    
    private var windowManager: WindowManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    private var reusableBitmap: Bitmap? = null
    private var screenWidth = 1080
    private var screenHeight = 2400

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        promoteToForeground("서비스 초기화 중...")
    }

    private fun promoteToForeground(message: String) {
        try {
            createNotificationChannel()
            val notification: Notification = NotificationCompat.Builder(this, "solver_service_channel")
                .setContentTitle("Match-3 Solver")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("solver_service_channel", "Solver Capture Layer", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground("실시간 화면 캡처 세션 준비 중")

        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        
        val dataIntent = intent?.let {
            androidx.core.content.IntentCompat.getParcelableExtra(it, "data", Intent::class.java)
        }
        
        if (resultCode == -1 || dataIntent == null) {
            Log.e(TAG, "❌ 오류: 미디어 프로젝션 권한 데이터가 누락되었습니다. (resultCode: $resultCode)")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            
            var densityDpi = 420
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager?.currentWindowMetrics?.bounds
                screenWidth = bounds?.width() ?: 1080
                screenHeight = bounds?.height() ?: 2400
                densityDpi = resources.displayMetrics.densityDpi
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager?.defaultDisplay?.getRealMetrics(metrics)
                screenWidth = metrics.widthPixels
                screenHeight = metrics.heightPixels
                densityDpi = metrics.densityDpi
            }

            if (screenWidth <= 0) screenWidth = 1080
            if (screenHeight <= 0) screenHeight = 2400

            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, dataIntent)
            
            backgroundThread = HandlerThread("Grid_Scanner").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.e(TAG, "⚠️ 시스템 정책 또는 사용자에 의해 캡처 세션이 닫혔습니다.")
                    stopSelf()
                }
            }, backgroundHandler)

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture", screenWidth, screenHeight, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, backgroundHandler
            )
            
            backgroundHandler?.post(analyzeRunnable)

            promoteToForeground("백그라운드에서 실시간 화면 분석 중")

            val finishIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("ACTION_FINISH", true)
            }
            startActivity(finishIntent)

        } catch (e: Exception) {
            Log.e(TAG, "치명적 오류: 프로젝션 레이어 초기화 실패", e)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private val analyzeRunnable = object : Runnable {
        override fun run() {
            try { 
                analyzeScreenFast() 
            } catch (e: Exception) { 
                Log.e(TAG, "루프 예외 무시 및 스킵", e) 
            }
            backgroundHandler?.postDelayed(this, 1000)
        }
    }

    private fun analyzeScreenFast() {
        val reader = imageReader ?: return
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return
        
        try {
            val planes = image.planes
            if (planes.isNullOrEmpty() || planes[0].buffer == null) {
                return
            }

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

            val stepX = 25
            val stepY = 25
            val scanLeft = (screenWidth * 0.03f).toInt()
            val scanRight = (screenWidth * 0.97f).toInt()
            val scanTop = (screenHeight * 0.30f).toInt()
            val scanBottom = (screenHeight * 0.88f).toInt()

            for (y in scanTop until scanBottom step stepY) {
                for (x in scanLeft until scanRight step stepX) {
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

            if (detectedBlockCount < 15 || (maxBlockX - minBlockX) < screenWidth * 0.5) {
                return
            }

            val boardLeft = minBlockX
            val boardRight = maxBlockX
            val boardTop = minBlockY
            val boardBottom = maxBlockY
            
            val boardWidth = boardRight - boardLeft
            val boardHeight = boardBottom - boardTop

            val approxBlockSize = screenWidth / 8.5f
            val currentGridCols = Math.round(boardWidth.toFloat() / approxBlockSize + 0.3f).coerceIn(5, 11)
            val currentGridRows = Math.round(boardHeight.toFloat() / approxBlockSize + 0.3f).coerceIn(3, 11)

            val strideX = if (currentGridCols > 1) boardWidth.toFloat() / (currentGridCols - 1) else approxBlockSize
            val strideY = if (currentGridRows > 1) boardHeight.toFloat() / (currentGridRows - 1) else approxBlockSize

            val topLeftAnchorX = boardLeft.toFloat()
            val topLeftAnchorY = boardTop.toFloat()

            val colorGrid = Array(currentGridRows) { IntArray(currentGridCols) }
            for (r in 0 until currentGridRows) {
                for (c in 0 until currentGridCols) {
                    val cx = (topLeftAnchorX + (c * strideX)).toInt().coerceIn(0, bitmap.width - 1)
                    val cy = (topLeftAnchorY + (r * strideY)).toInt().coerceIn(0, bitmap.height - 1)
                    colorGrid[r][c] = getPixelBlockColor(bitmap, cx, cy)
                }
            }

            val hint = findExactOOXOOMatch5(colorGrid, currentGridRows, currentGridCols)
            if (hint != null) {
                Log.d(TAG, "🎯 [백그라운드 스캔 성공] 5연속 매칭 후보: From(${hint.fromR}, ${hint.fromC}) -> To(${hint.toR}, ${hint.toC})")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "프레임 핵심 연산 예외 보호", t)
        } finally {
            try { image.close() } catch (e: Exception) {}
        }
    }

    private fun getPixelBlockColor(bitmap: Bitmap, cx: Int, cy: Int): Int {
        val votes = IntArray(6)
        votes[identifyColorHSV(bitmap.getPixel(cx, cy))]++
        votes[identifyColorHSV(bitmap.getPixel((cx - 8).coerceIn(0, bitmap.width - 1), cy))]++
        votes[identifyColorHSV(bitmap.getPixel((cx + 8).coerceIn(0, bitmap.width - 1), cy))]++
        votes[identifyColorHSV(bitmap.getPixel(cx, (cy - 8).coerceIn(0, bitmap.height - 1)))]++
        votes[identifyColorHSV(bitmap.getPixel(cx, (cy + 8).coerceIn(0, bitmap.height - 1)))]++
        
        var maxVote = 0
        var winner = 0
        for (i in 1..5) {
            if (votes[i] > maxVote) {
                maxVote = votes[i]
                winner = i
            }
        }
        return if (maxVote >= 2) winner else 0
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
            (hue in 40f..65f) -> 3            
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
            backgroundHandler?.removeCallbacks(analyzeRunnable)
            backgroundThread?.quitSafely() 
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            reusableBitmap?.recycle()
            reusableBitmap = null
            Log.d(TAG, "SolverService 정상 자원 해제 완료")
        } catch (e: Exception) {
            Log.e(TAG, "자원 해제 예외 발생", e)
        }
    }

    data class MatchHint(val fromR: Int, val fromC: Int, val toR: Int, val toC: Int)
}
