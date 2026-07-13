package com.example.helper.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.example.helper.core.MatchCandidate

class GridOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // [핵심] 격자선 표시 여부 플래그 (변경 시 화면 자동 갱신)
    var showGridLines: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    // [핵심] 5개 매칭 힌트 표시 여부 플래그 (변경 시 화면 자동 갱신)
    var showMatchHints: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private var rows = 0
    private var cols = 0
    private var candidates: List<MatchCandidate> = emptyList()

    // 초록색 격자선 페인트 설정
    private val gridPaint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    // 빨간색 힌트 화살표 페인트 설정
    private val hintPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 8f
        style = Paint.Style.FILL_AND_STROKE
        isAntiAlias = true
    }

    /**
     * 게임 화면이 인식되었을 때 데이터 세팅 및 강제 다시그리기(invalidate)
     */
    fun updateData(rows: Int, cols: Int, candidates: List<MatchCandidate>) {
        this.rows = rows
        this.cols = cols
        this.candidates = candidates
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rows <= 0 || cols <= 0) return

        val cellWidth = width.toFloat() / cols
        val cellHeight = height.toFloat() / rows

        // 기능 1: 초록색 격자선 그리기 (showGridLines 플래그 가 독립적으로 제어)
        if (showGridLines) {
            for (i in 1 until cols) {
                val x = i * cellWidth
                canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
            }
            for (i in 1 until rows) {
                val y = i * cellHeight
                canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            }
        }

        // 기능 2: 5개 매칭 힌트 화살표 그리기 (showMatchHints 플래그가 독립적으로 제어)
        if (showMatchHints) {
            for (candidate in candidates) {
                val r = candidate.row
                val c = candidate.col

                // 출발점 블록의 중심 좌표 계산
                val startX = c * cellWidth + cellWidth / 2
                val startY = r * cellHeight + cellHeight / 2

                var endX = startX
                var endY = startY

                // Solver가 계산해준 이동 방향에 맞춰 도착점(X 칸) 계산
                when (candidate.move.lowercase()) {
                    "up" -> endY -= cellHeight
                    "down" -> endY += cellHeight
                    "left" -> endX -= cellWidth
                    "right" -> endX += cellWidth
                }

                // 이동 경로를 나타내는 화살표 직선 그리기
                canvas.drawLine(startX, startY, endX, endY, hintPaint)
                
                // 도달 지점(X 빈칸)에 정밀 타격 표시로 빨간색 원(타겟) 그리기
                canvas.drawCircle(endX, endY, 15f, hintPaint)
            }
        }
    }
}
