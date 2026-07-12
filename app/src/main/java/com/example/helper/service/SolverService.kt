package com.example.helper.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.util.*
import kotlin.math.*

class SolverService : Service() {
    private val ROWS = 9
    private val COLS = 9
    
    // 설정값
    private var windowManager: WindowManager? = null
    private var hintOverlayView: HintOverlayView? = null
    private var isAnalyzing = true
    private var screenWidth = 1080
    private var screenHeight = 2400

    // [코드 내용 동일: 앞서 제공한 SolverService 클래스 구조 사용]
    // HintOverlayView의 onDraw 부분만 아래 '시각화 가이드' 코드로 교체하세요.

    private class HintOverlayView(context: Context) : View(context) {
        // ... (필드 변수 동일)
        private var debugGrid: Array<IntArray>? = null
        private var debugBounds: Rect? = null
        private var debugVLines: List<Int>? = null
        private var debugHLines: List<Int>? = null

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val grid = debugGrid
            val bounds = debugBounds
            val vLines = debugVLines
            val hLines = debugHLines

            val guidePaint = Paint().apply {
                color = Color.parseColor("#8000FF00") // 반투명 초록색 격자
                strokeWidth = 4f
                style = Paint.Style.STROKE
            }
            
            // [핵심] OpenCV 인식 전이거나 실패 시 화면 중앙 가이드라인 표시
            if (bounds == null) {
                val startX = width * 0.05f
                val endX = width * 0.95f
                val startY = height * 0.25f
                val endY = height * 0.65f
                canvas.drawRect(startX, startY, endX, endY, guidePaint)
                for (i in 1 until 9) {
                    canvas.drawLine(startX + (endX - startX) * i / 9f, startY, startX + (endX - startX) * i / 9f, endY, guidePaint)
                    canvas.drawLine(startX, startY + (endY - startY) * i / 9f, endX, startY + (endY - startY) * i / 9f, guidePaint)
                }
            } else {
                // 인식 성공 시 실제 매칭된 셀 위에 박스와 점 그리기
                for (r in 0 until 9) {
                    for (c in 0 until 9) {
                        if (c + 1 >= vLines!!.size || r + 1 >= hLines!!.size) continue
                        val left = bounds.x + vLines[c]
                        val right = bounds.x + vLines[c + 1]
                        val top = bounds.y + hLines[r]
                        val bottom = bounds.y + hLines[r + 1]
                        
                        canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), guidePaint)
                        
                        val paint = Paint().apply { color = Color.RED; style = Paint.Style.FILL }
                        canvas.drawCircle((left + right) / 2f, (top + bottom) / 2f, 10f, paint)
                    }
                }
            }
            // ... (나머지 화살표 로직 동일)
        }
        
        fun updateHint(...) { /* ... */ invalidate() }
        fun clearAll() { debugGrid = null; invalidate() }
    }
    
    // ... (나머지 메서드 생략)
    override fun onBind(intent: Intent?): IBinder? = null
}
