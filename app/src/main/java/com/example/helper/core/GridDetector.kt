package com.example.helper.core

import android.graphics.Bitmap
import android.graphics.Color

// 격자 경계 좌표를 저장하는 데이터 클래스
data class GridBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

// 분석 결과를 SolverService에 전달할 구조체
data class GridResult(
    val success: Boolean,
    val categoryGrid: Array<IntArray>?,
    val bounds: GridBounds?
)

object GridDetector {

    /**
     * 비트맵 이미지를 분석하여 7x9 격자의 블록 상태를 추출합니다.
     * 비정형 격자(구멍 뚫린 구조)에 대응하기 위해 블록이 없는 배경 칸은 GameConfig.HOLE(-1)로 채웁니다.
     */
    fun getGridDataFromBitmap(bitmap: Bitmap?): GridResult {
        if (bitmap == null) {
            return GridResult(false, null, null)
        }

        val width = bitmap.width
        val height = bitmap.height
        
        // 1. 기본 게임판 영역 지정 (화면 해상도 비율에 맞춰 중앙 하단 영역 타겟팅)
        // 실제 운영 시 필요에 따라 비율을 조정하거나 OpenCV Edge Detection으로 고도화 가능합니다.
        val left = (width * 0.05).toInt()
        val right = (width * 0.95).toInt()
        val top = (height * 0.25).toInt()
        val bottom = (height * 0.75).toInt()
        val bounds = GridBounds(left, top, right, bottom)

        val gridRows = GameConfig.ROWS // 9
        val gridCols = GameConfig.COLS // 7
        val categoryGrid = Array(gridRows) { IntArray(gridCols) }

        val cellWidth = (right - left) / gridCols
        val cellHeight = (bottom - top) / gridRows

        // 2. 7x9 격자의 각 셀 중심점을 순회하며 색상 검사
        for (r in 0 until gridRows) {
            for (c in 0 until gridCols) {
                // 셀의 중심점 계산
                val centerX = left + (c * cellWidth) + (cellWidth / 2)
                val centerY = top + (r * cellHeight) + (cellHeight / 2)

                if (centerX >= width || centerY >= height || centerX < 0 || centerY < 0) {
                    categoryGrid[r][c] = GameConfig.HOLE
                    continue
                }

                // 중심점 픽셀 추출 및 HSV 컬러 공간 변환
                val pixel = bitmap.getPixel(centerX, centerY)
                val hsv = FloatArray(3)
                Color.colorToHSV(pixel, hsv)
                
                val hue = hsv[0]        // 0.0 ~ 360.0 도 (색상)
                val saturation = hsv[1] // 0.0 ~ 1.0 (채도)
                val value = hsv[2]      // 0.0 ~ 1.0 (명도)

                // [핵심] 비정형 격자(판마다 다른 구조 배치) 판별 규칙
                // 채도나 명도가 너무 낮으면 블록이 없는 어두운 배경/공백 구멍(HOLE)으로 간주합니다.
                if (saturation < 0.18f || value < 0.18f) {
                    categoryGrid[r][c] = GameConfig.HOLE
                    continue
                }

                // 파이썬 config.py의 OpenCV HSV(0~180) 스펙을 안드로이드 표준 HSV(0~360) 규격으로 치환 매핑
                // Brown(~10->20도), Yellow(~25->50도), Green(~35->70도), Blue(~95->190도), Purple(~120->240도)
                categoryGrid[r][c] = when {
                    hue in 0.0f..35.0f   -> 0 // Brown
                    hue in 35.0f..65.0f  -> 1 // Yellow
                    hue in 65.0f..140.0f -> 2 // Green
                    hue in 160.0f..215.0f -> 3 // Blue
                    hue in 215.0f..280.0f -> 4 // Purple
                    else -> GameConfig.HOLE // 어떤 색상도 아니면 빈 공간(벽/구조물) 처리
                }
            }
        }

        return GridResult(
            success = true,
            categoryGrid = categoryGrid,
            bounds = bounds
        )
    }
}
