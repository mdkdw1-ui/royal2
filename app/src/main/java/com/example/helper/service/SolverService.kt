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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.helper.MainActivity

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
        
        // 🎯 호환성 문제를 피하기 위해 가장 전통적이고 안전한 방식으로 Intent 데이터를 복원합니다.
        val dataIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("data")
        }
        
        if (resultCode != Activity.RESULT_OK || dataIntent == null) {
            Log.e(TAG, "❌ 오류: 미디어 프로젝션 권한 데이터가 누락되었거나 거부되었습니다. (resultCode: $resultCode)")
            sendFinishSignalToActivity() // 실패하더라도 액티비티를 닫아 사용자 경험 확보
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
            
            // 🎯 이 지점에서 OS 보안 예외가 나는지 검증합니다.
            mediaProjection = mpManager.getMediaProjection(resultCode, dataIntent)
            
            if (mediaProjection == null) {
                Log.e(TAG, "❌ 미디어 프로젝션 객체를 생성하지 못했습니다.")
                sendFinishSignalToActivity()
                stopSelf()
                return START_NOT_STICKY
            }

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

        } catch (e: Exception) {
            Log.e(TAG, "치명적 오류: 프로젝션 레이어 초기화 실패", e)
            // 에러 내용을 토스트로 띄워 개발 중 확인이 가능하도록 조치합니다.
            Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, "캡처 레이어 생성 실패: ${e.message}", Toast.LENGTH_LONG).show()
            }
            stopSelf()
        } finally {
            // 🎯 성공하든 실패하든, 시스템 팝업 작업이 끝났으므로 
            // 무조건 메인 액티비티를 백그라운드로 내려서 사용자가 홈 화면을 보게 만듭니다.
            sendFinishSignalToActivity()
        }

        return START_NOT_STICKY
    }

    private fun sendFinishSignalToActivity() {
        try {
            val finishIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("ACTION_FINISH", true)
            }
            startActivity(finishIntent)
        } catch (e: Exception) {
            Log.e(TAG, "액티비티 백그라운드 시그널 전송 실패", e)
        }
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
