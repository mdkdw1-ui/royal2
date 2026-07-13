package com.example.helper.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View

class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var rows: Int = 9
    var cols: Int = 11

    // 🎯 [핵심 변경] 4개 모서리의 독립 좌표 (초기값은 화면 중앙 적당한 사각형)
    var tlX: Float = 100f;  var tlY: Float = 600f
    var trX: Float = 980f;  var trY: Float = 600f
    var blX: Float = 100f;  var blY: Float = 1600f
    var brX: Float = 980f;  var brY: Float = 1600f

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

    private val cornerPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    private val hintPaint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 8f
        style = Paint.Style.STROKE
    }

    // 🧮 쌍선형 보간법(Bilinear Interpolation) 격자 점 생성 함수
    // u(가로 비율 0~1), v(세로 비율 0~1)를 넣으면 비틀어진 좌표계의 정확한 XY를 반환합니다.
    fun getInterpolatedPoint(u: Float, v: Float): PointF {
        val x = (1 - u) * (1 - v) * tlX + u * (1 - v) * trX + (1 - u) * v * blX + u * v * brX
        val y = (1 - u) * (1 - v) * tlY + u * (1 - v) * trY + (1 - u) * v * blY + u * v * brY
        return PointF(x, y)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (showGridLines) {
            // 1. 세로선 그리기 (왼쪽 경계부터 오른쪽 경계까지)
            for (i in 0..cols) {
                val u = i.toFloat() / cols
                val pTop = getInterpolatedPoint(u, 0f)
                val pBot = getInterpolatedPoint(u, 1f)
                canvas.drawLine(pTop.x, pTop.y, pBot.x, pBot.y, if (i == 0 || i == cols) borderPaint else gridPaint)
            }

            // 2. 가로선 그리기 (상단 경계부터 하단 경계까지)
            for (j in 0..rows) {
                val v = j.toFloat() / rows
                val pLeft = getInterpolatedPoint(0f, v)
                val pRight = getInterpolatedPoint(1f, v)
                canvas.drawLine(pLeft.x, pLeft.y, pRight.x, pRight.y, if (j == 0 || j == rows) borderPaint else gridPaint)
            }

            // 3. 모서리 조절점 시각화 점 4개 그리기
            canvas.drawCircle(tlX, tlY, 12f, cornerPaint)
            canvas.drawCircle(trX, trY, 12f, cornerPaint)
            canvas.drawCircle(blX, blY, 12f, cornerPaint)
            canvas.drawCircle(brX, brY, 12f, cornerPaint)
        }

        // 로직 매칭 시 힌트 박스 투사 예시 (예: 4행 5열 위치)
        if (showMatchHints) {
            val r = 3; val c = 4
            val p00 = getInterpolatedPoint(c.toFloat() / cols, r.toFloat() / rows)
            val p10 = getInterpolatedPoint((c + 1).toFloat() / cols, r.toFloat() / rows)
            val p01 = getInterpolatedPoint(c.toFloat() / cols, (r + 1).toFloat() / rows)
            val p11 = getInterpolatedPoint((c + 1).toFloat() / cols, (r + 1).toFloat() / rows)

            // 약간 안쪽으로 패딩을 주어 힌트 박스 렌더링
            canvas.drawRect(p00.x + 6, p00.y + 6, p11.x - 6, p11.y - 6, hintPaint)
        }
    }
}
