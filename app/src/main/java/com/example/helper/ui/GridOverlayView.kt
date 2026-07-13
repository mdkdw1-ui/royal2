package com.example.helper.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 실시간 동적 변경 변수들
    var rows: Int = 9
    var cols: Int = 11
    var offsetX: Float = 50f
    var offsetY: Float = 500f
    var gridSize: Float = 900f // 격자 전체 가로/세로 네모 박스 크기

    var showGridLines: Boolean = true
    var showMatchHints: Boolean = true

    private val gridPaint = Paint().apply {
        color = Color.CYAN
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val borderPaint = Paint().apply {
        color = Color.YELLOW
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }

    private val hintPaint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 8f
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cellWidth = gridSize / cols
        val cellHeight = gridSize / rows

        // 1. 외곽 경계선 그리기 (위치 잡기용)
        if (showGridLines) {
            canvas.drawRect(offsetX, offsetY, offsetX + gridSize, offsetY + gridSize, borderPaint)

            // 내부 가로선/세로선 동적 드로잉
            for (i in 1 until cols) {
                val x = offsetX + (i * cellWidth)
                canvas.drawLine(x, offsetY, x, offsetY + gridSize, gridPaint)
            }
            for (i in 1 until rows) {
                val y = offsetY + (i * cellHeight)
                canvas.drawLine(offsetX, y, offsetX + gridSize, y, gridPaint)
            }
        }

        // 2. 힌트 및 OOXOO 매칭 결과 드로잉 구역
        if (showMatchHints) {
            // [테스트 예시] 로직이 가동되어 OOXOO 패턴을 감지했을 때 특정 칸에 힌트를 띄우는 예시 박스
            // 3행 4열 위치에 매칭 힌트 사각형 시각화
            val sampleRow = 2 
            val sampleCol = 3
            val hLeft = offsetX + (sampleCol * cellWidth) + 5
            val hTop = offsetY + (sampleRow * cellHeight) + 5
            val hRight = hLeft + cellWidth - 10
            val hBottom = hTop + cellHeight - 10
            canvas.drawRect(hLeft, hTop, hRight, hBottom, hintPaint)
        }
    }
}
