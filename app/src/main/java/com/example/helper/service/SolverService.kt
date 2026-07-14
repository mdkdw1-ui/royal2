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
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
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
    
    // 메인 스레드 샌더 및 백그라운드 연산 스레드 분리
    private val mainHandler = Handler(Looper.getMainLooper())
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    private var isCapturing = false

    inner class SolverBinder : Binder() {
        fun getService(): SolverService = this@SolverService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        backgroundThread = HandlerThread("SolverBackgroundWorker").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. Android 정책 준수: 서비스 커넥션 선고지
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
                // 🛠️ 핵심: 인텐트 수신 즉시 비동기로 가상 디스플레이 바인딩 파이프라인 가동
                backgroundHandler?.post {
                    setupScreenCapturePipeline(resultCode, resultData)
                }
            } else {
                showToastOnMainThread("❌ 유효하지 않은 미디어 프로젝션 데이터 변동")
            }
        }
        return START_STICKY
    }

    /**
     * 🎯 실시간 화면 공유 캡처 파이프라인 생성 (VirtualDisplay 구축)
     */
    private fun setupScreenCapturePipeline(resultCode: Int, resultData: Intent) {
        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            
            // 안전한 리소스 초기화
            stopCapturePipeline()

            mediaProjection = mpManager.getMediaProjection(resultCode, resultData)
            
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            // 해상도에 맞는 이미지 리더 매핑
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            
            // 가상 디스플레이 바인딩 수행
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "GridHelperCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, backgroundHandler
            )

            isCapturing = true
            showToastOnMainThread("🎯 분석기가 정상 활성화되었습니다! 게임을 시작하세요.")
            Log.d(TAG, "VirtualDisplay 캡처 루프가 정상적으로 가동되었습니다.")

            // 🔄 실시간 캡처 스캔 리스너 등록
            imageReader?.setOnImageAvailableListener({ reader ->
                if (!isCapturing) return@setOnImageAvailableListener
                val image = reader?.acquireLatestImage() ?: return@setOnImageAvailableListener
                
                try {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    // 비트맵 복사 가동
                    val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(buffer)
                    
                    // 🎯 분석기 작동 파트 연동 영역
                    // val result = analyzeAndSolve(bitmap, gridLeft, gridTop, gridWidth, gridHeight)
                    // mainHandler.post { overlayView.drawArrows(result) }

                } catch (e: Exception) {
                    Log.e(TAG, "프레임 버퍼 파싱 오류: ${e.message}")
                } finally {
                    image.close() // 메모리 누수 방지 차단 핵심
                }
            }, backgroundHandler)

        } catch (e: SecurityException) {
            Log.e(TAG, "시큐리티 타깃 익셉션: 매니페스트 설정 누락 확률 높음", e)
            showToastOnMainThread("❌ 보안 에러: 미디어 프로젝션 권한 거부")
        } catch (e: Exception) {
            Log.e(TAG, "파이프라인 구축 실패", e)
            showToastOnMainThread("❌ 화면 공유 엔진 초기화 실패: ${e.message}")
        }
    }

    private fun startForegroundServiceInternal() {
        val channelId = "solver_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "격자 헬퍼 분석 엔진", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("격자 헬퍼 캡처 엔진 가동 중")
            .setContentText("실시간 게임 화면을 분석하고 있습니다.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    private fun showToastOnMainThread(message: String) {
        mainHandler.post { Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show() }
    }

    /**
     * 이미지 스캔 분석 연산 파트 (색역 복구 알고리즘 완비)
     */
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

    private fun findBestMoves(board: Array<Array<BlockColor>>): List<MatchMove> {
        val moves = mutableListOf<MatchMove>()
        val dr = intArrayOf(-1, 1, 0, 0)
        val dc = intArrayOf(0, 0, -1, 1)

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (board[r][c] == BlockColor.UNKNOWN) continue
                for (i in 0 until 4) {
                    val nr = r + dr[i]; val nc = c + dc[i]
                    if (nr in 0 until rows && nc in 0 until cols) {
                        if (board[nr][nc] == BlockColor.UNKNOWN) continue
                        val temp = board[r][c]; board[r][c] = board[nr][nc]; board[nr][nc] = temp
                        val m1 = checkMaxMatchLength(board, r, c); val m2 = checkMaxMatchLength(board, nr, nc)
                        val maxLen = maxOf(m1, m2)
                        if (maxLen >= 3) {
                            val desc = if (maxLen >= 5) "디스코볼 생성 가능! 🎯" else "${maxLen}개 매칭"
                            moves.add(MatchMove(r, c, nr, nc, maxLen, desc))
                        }
                        board[nr][nc] = board[r][c]; board[r][c] = temp
                    }
                }
            }
        }
        return moves.sortedByDescending { it.matchCount }
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

    private fun stopCapturePipeline() {
        isCapturing = false
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        mediaProjection?.stop(); mediaProjection = null
    }

    override fun onDestroy() {
        stopCapturePipeline()
        backgroundThread?.quitSafely()
        super.onDestroy()
    }
}
