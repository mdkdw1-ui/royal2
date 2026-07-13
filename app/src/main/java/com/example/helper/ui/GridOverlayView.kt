package com.example.helper.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 🎯 [핵심 수정] @JvmOverloads constructor(..., attrs: AttributeSet?, ...)가 
 * 반드시 있어야 XML 파일(overlay_layout.xml)에서 이 뷰를 크래시 없이 로드할 수 있습니다.
 */
class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var showGridLines: Boolean = true
        set(value) {
            field = value
            invalidate() // 변수 변경 시 화면을 다시 그리도록 갱신
        }

    var showMatchHints: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private val gridPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val hintPaint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 8f
        style = Paint.Style.STROKE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val w = width.toFloat()
        val h = height.toFloat()

        // 1. 격자선 그리기 테스트
        if (showGridLines && w > 0 && h > 0) {
            // 세로선 2개 (3분할)
            canvas.drawLine(w / 3f, 0f, w / 3f, h, gridPaint)
            canvas.drawLine(w * 2f / 3f, 0f, w * 2f / 3f, h, gridPaint)
            // 가로선 2개 (3분할)
            canvas.drawLine(0f, h / 3f, w, h / 3f, gridPaint)
            canvas.drawLine(0f, h * 2f / 3f, w, h * 2f / 3f, gridPaint)
        }

        // 2. 매칭 힌트 사각형 그리기 테스트 (중앙에 임시 박스)
        if (showMatchHints && w > 0 && h > 0) {
            canvas.drawRect(w / 4f, h / 4f, w * 3f / 4f, h * 3f / 4f, hintPaint)
        }
    }
}
