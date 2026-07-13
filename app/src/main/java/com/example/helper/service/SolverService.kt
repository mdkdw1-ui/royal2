package com.example.helper.service // 👈 실제 폴더 위치에 맞게 패키지 경로를 수정했습니다.

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 로열 매치 블록 색상 정의
 */
enum class BlockColor {
    RED, BLUE, YELLOW, GREEN, PURPLE, UNKNOWN
}

/**
 * 매칭 추천 이동 경로 구조체
 */
data class MatchMove(
    val fromRow: Int, val fromCol: Int,
    val toRow: Int, val toCol: Int,
    val matchCount: Int,
    val description: String
)

class SolverService : Service() {

    private val binder = SolverBinder()
    
    var rows = 11
    var cols = 9
    
    inner class SolverBinder : Binder() {
        fun getService(): SolverService = this@SolverService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startForegroundService() {
        val channelId = "solver_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "격자 헬퍼 분석 엔진",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("격자 헬퍼 동작 중")
            .setContentText("디스코볼 생성 매칭 검출 엔진이 실행 중입니다.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        startForeground(1, notification)
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
                
                val hue = hsv[0]
                val sat = hsv[1]
                val value = hsv[2]

                if (sat < 0.15f || value < 0.15f) continue

                when {
                    (hue in 0f..20f) || (hue in 340f..360f) -> redCount++ 
                    hue in 21f..70f -> yellowCount++                      
                    hue in 71f..165f -> greenCount++ // 🎯 연두색 공백 해결 범위 유지
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
        super.onDestroy()
    }
}
