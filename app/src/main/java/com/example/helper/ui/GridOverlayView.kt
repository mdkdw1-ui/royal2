package com.example.helper.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View

class GridOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var rows = 5
    var cols = 5
    var showGridLines = true

    // 격자 좌표 (기본값 설정 또는 기존 변수 유지)
    var tlX = 100f; var tlY = 400f
    var trX = 1000f; var trY = 400f
    var blX = 100f; var blY = 1500f
    var brX = 1000f; var brY = 1500f

    // 🎯 [통합] 화살표 드로잉을 위한 변수 추가
    private var arrowStartX = 0f; private var arrowStartY = 0f
    private var arrowEndX = 0f; private var arrowEndY = 0f
    private var shouldDrawArrow = false

    private val gridPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private val cornerPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
    }

    private val arrowLinePaint = Paint().apply {
        color = Color.parseColor("#00FFCC")
        strokeWidth = 14f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
        setShadowLayer(10f, 0f, 0f, Color.GREEN)
    }

    private val arrowHeadPaint = Paint().apply {
        color = Color.parseColor("#00FFCC")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun getInterpolatedPoint(u: Float, v: Float): PointF {
        val x = (1 - u) * ((1 - v) * tlX + v * blX) + u * ((1 - v) * trX + v * brX)
        val y = (1 - u) * ((1 - v) * tlY + v * blY) + u * ((1 - v) * trY + v * brY)
        return PointF(x, y)
    }

    // 🎯 [통합] 서비스에서 화살표를 업데이트할 때 호출할 메서드
    fun updateArrow(sx: Float, sy: Float, ex: Float, ey: Float) {
        arrowStartX = sx; arrowStartY = sy; arrowEndX = ex; arrowEndY = ey
        shouldDrawArrow = true
        invalidate()
    }

    fun clearArrow() {
        shouldDrawArrow = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. 격자선 및 제어점 그리기
        if (showGridLines) {
            for (i in 0..rows) {
                val v = i.toFloat() / rows
                val pStart = getInterpolatedPoint(0f, v)
                val pEnd = getInterpolatedPoint(1f, v)
                canvas.drawLine(pStart.x, pStart.y, pEnd.x, pEnd.y, gridPaint)
            }
            for (j in 0..cols) {
                val u = j.toFloat() / cols
                val pStart = getInterpolatedPoint(u, 0f)
                val pEnd = getInterpolatedPoint(u, 1f)
                canvas.drawLine(pStart.x, pStart.y, pEnd.x, pEnd.y, gridPaint)
            }
            // 꼭짓점 핸들
            canvas.drawCircle(tlX, tlY, 30f, cornerPaint)
            canvas.drawCircle(trX, trY, 30f, cornerPaint)
            canvas.drawCircle(blX, blY, 30f, cornerPaint)
            canvas.drawCircle(brX, brY, 30f, cornerPaint)
        }

        // 2. 🎯 [통합] 추천 화살표 그리기
        if (shouldDrawArrow) {
            canvas.drawLine(arrowStartX, arrowStartY, arrowEndX, arrowEndY, arrowLinePaint)
            val angle = Math.atan2((arrowEndY - arrowStartY).toDouble(), (arrowEndX - arrowStartX).toDouble())
            val arrowLength = 28f
            val arrowAngle = Math.PI / 6
            val path = Path().apply {
                moveTo(arrowEndX, arrowEndY)
                lineTo(
                    (arrowEndX - arrowLength * Math.cos(angle - arrowAngle)).toFloat(),
                    (arrowEndY - arrowLength * Math.sin(angle - arrowAngle)).toFloat()
                )
                lineTo(
                    (arrowEndX - arrowLength * Math.cos(angle + arrowAngle)).toFloat(),
                    (arrowEndY - arrowLength * Math.sin(angle + arrowAngle)).toFloat()
                )
                close()
            }
            canvas.drawPath(path, arrowHeadPaint)
        }
    }
}
