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
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat

enum class BlockColor {
    RED, BLUE, YELLOW, GREEN, PURPLE, UNKNOWN
}

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

    // 🎯 화면 공유 객체 관리 변수
    private var mediaProjection: MediaProjection? = null
    
    inner class SolverBinder : Binder() {
        fun getService(): SolverService = this@SolverService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. ⚠️ Android 최신 보안 정책: 반드시 MediaProjection을 초기화하기 전에 포그라운드 고지를 완료해야 함.
        startForegroundServiceInternal()

        // 2. MainActivity로부터 넘겨받은 데이터 안전하게 파싱 및 예외처리
        if (intent != null) {
            val resultCode = intent.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED)
            val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("RESULT_DATA", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("RESULT_DATA")
            }

            if (resultCode == Activity.RESULT_OK && resultData != null) {
                // 🎯 최신 OS 대응 핵심 구간: Try-Catch로 묶어 에러 발생 시 토스트 알림
                try {
                    val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    
                    // 기존 연결이 있다면 명시적으로 해제 후 재생성
                    mediaProjection?.stop()
                    mediaProjection = mpManager.getMediaProjection(resultCode, resultData)
                    
                    showToastOnMainThread("🎯 화면 공유 인프라 연결 성공!")
                    Log.d(TAG, "MediaProjection이 성공적으로 생성되었습니다.")
                    
                } catch (e: SecurityException) {
                    val errorMsg = "보안 에러: Manifest에 mediaProjection 타입이 누락되었거나 허용되지 않았습니다."
                    Log.e(TAG, errorMsg, e)
                    showToastOnMainThread("❌ $errorMsg")
                } catch (e: Exception) {
                    val errorMsg = "화면 공유 인스턴스 생성 실패: ${e.message} (${e.javaClass.simpleName})"
                    Log.e(TAG, errorMsg, e)
                    showToastOnMainThread("❌ 에러 발생: $errorMsg")
                }
            } else {
                showToastOnMainThread("❌ 전달받은 화면 공유 데이터가 유효하지 않습니다. Code: $resultCode")
            }
        }
        
        return START_STICKY
    }

    /**
     * 포그라운드 알림창 등록 (Android 규격 세분화 적용 버전)
     */
    private fun startForegroundServiceInternal() {
        val channelId = "solver_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "격자 헬퍼 분석 엔진",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("격자 헬퍼 동작 중")
            .setContentText("화면 분석 엔진 및 매칭 캡처 장치가 활성화 상태입니다.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        // Android 10(Q) 이상은 미디어 프로젝션 타입임을 시스템에 명확히 명시해야 차단당하지 않습니다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }
    }

    /**
     * 백그라운드 스레드에서도 안전하게 화면에 토스트를 띄우기 위한 유틸리티 함수
     */
    private fun showToastOnMainThread(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
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
                
                val hue = hsv[0]
                val sat = hsv[1]
                val value = hsv[2]

                if (sat < 0.15f || value < 0.15f) continue

                when {
                    (hue in 0f..20f) || (hue in 340f..360f) -> redCount++    
                    hue in 21f..70f -> yellowCount++                         
                    hue in 71f..165f -> greenCount++ // 연두색 사각지대 차단 영역
                    hue in 166f..255f -> blueCount++                         
                    hue in 256f..339f -> purpleCount++                       
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
                    val nr = r + dr[i]
                    val nc = c + dc[i]

                    if (nr in 0 until rows && nc in 0 until cols) {
                        if (board[nr][nc] == BlockColor.UNKNOWN) continue
                        
                        val temp = board[r][c]
                        board[r][c] = board[nr][nc]
                        board[nr][nc] = temp

                        val matchLen1 = checkMaxMatchLength(board, r, c)
                        val matchLen2 = checkMaxMatchLength(board, nr, nc)
                        val maxLen = maxOf(matchLen1, matchLen2)

                        if (maxLen >= 3) {
                            val desc = if (maxLen >= 5) "디스코볼 생성 가능! 🎯" else "${maxLen}개 매칭"
                            moves.add(MatchMove(r, c, nr, nc, maxLen, desc))
                        }

                        board[nr][nc] = board[r][c]
                        board[r][c] = temp
                    }
                }
            }
        }
        return moves.sortedByDescending { it.matchCount }
    }

    private fun checkMaxMatchLength(board: Array<Array<BlockColor>>, r: Int, c: Int): Int {
        val color = board[r][c]
        if (color == BlockColor.UNKNOWN) return 0

        var horizontalCount = 1
        var cc = c + 1
        while (cc < cols && board[r][cc] == color) { horizontalCount++; cc++ }
        cc = c - 1
        while (cc >= 0 && board[r][cc] == color) { horizontalCount++; cc-- }

        var verticalCount = 1
        var rr = r + 1
        while (rr < rows && board[rr][c] == color) { verticalCount++; rr++ }
        rr = r - 1
        while (rr >= 0 && board[rr][c] == color) { verticalCount++; rr-- }

        return maxOf(horizontalCount, verticalCount)
    }

    override fun onDestroy() {
        // 서비스 종료 시 화면 공유 리소스 청소
        mediaProjection?.stop()
        mediaProjection = null
        super.onDestroy()
    }
}
