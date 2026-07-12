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

import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.util.ArrayList
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SolverService : Service() {
    private val TAG = "SolverService"
    
    // 1️⃣ [수정] GameConfig(7x9) 보드판 규격과 일치하도록 가로 크기(COLS)를 7로 변경합니다.
    private val ROWS = 9
    private val COLS = 7

    // ... (중간의 MediaProjection 세팅, OnStartCommand, 이미지 캡처 루프 등 
    // 기존에 구현해두신 본문 코드는 여기에 그대로 유지해 주세요) ...


    // --------------------------------------------------------------------
    // 2️⃣ [수정] 최하단의 HintOverlayView 클래스를 아래 코드로 통째로 덮어씌웁니다.
    // --------------------------------------------------------------------
    private inner class HintOverlayView(context: Context) : View(context) {
        // 기존 Paint 선언부 유지
        private val guidePaint = Paint().apply {
            color = Color.parseColor("#5500FF00")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val debugPaint = Paint().apply {
            style = Paint.Style.FILL
        }
        private val linePaint = Paint().apply {
            color = Color.RED
            strokeWidth = 10f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        private val arrowPaint = Paint().apply {
            color = Color.RED
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // 분석된 격자 보드판 및 디버그 서클 렌더링
            if (grid != null && bounds != null) {
                val cellWidth = (bounds.right - bounds.left) / COLS
                val cellHeight = (bounds.bottom - bounds.top) / ROWS

                for (r in 0 until ROWS) {
                    for (c in 0 until COLS) {
                        val left = bounds.left + c * cellWidth
                        val top = bounds.top + r * cellHeight
                        val right = left + cellWidth
                        val bottom = top + cellHeight

                        val cx = (left + right) / 2f
                        val cy = (top + bottom) / 2f

                        canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), guidePaint)

                        // [수정] GridDetector.kt가 분류하는 실제 인덱스(0~4)에 맞게 
                        // 디버그 색상 매핑을 교정합니다. (0번 브라운 계열 추가 및 밀림 현상 해결)
                        debugPaint.color = when (grid[r][c]) {
                            0 -> Color.parseColor("#998B4513") // 0: Brown (갈색)
                            1 -> Color.parseColor("#99FFFF00") // 1: Yellow (노란색)
                            2 -> Color.parseColor("#9900FF00") // 2: Green (초록색)
                            3 -> Color.parseColor("#990000FF") // 3: Blue (파란색)
                            4 -> Color.parseColor("#99AA00FF") // 4: Purple (보라색)
                            else -> Color.parseColor("#99888888") // -1(HOLE 벽) 또는 5(EMPTY 공백)는 회색 처리
                        }
                        canvas.drawCircle(cx, cy, 15f, debugPaint)
                    }
                }
            }

            // 스왑 힌트 화살표 그리기 로직 (기존 코드 유지)
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
