package com.example.helper.core

import android.graphics.Bitmap
import android.graphics.Color

data class GridBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

data class GridResult(
    val success: Boolean,
    val categoryGrid: Array<IntArray>?,
    val bounds: GridBounds?
)

object GridDetector {

    // 🟢 외부(SolverService)에서 사용자가 조절한 위치 값을 주입받아 처리합니다.
    fun getGridDataFromBitmap(bitmap: Bitmap?, customBounds: GridBounds): GridResult {
        if (bitmap == null) {
            return GridResult(false, null, null)
        }

        val width = bitmap.width
        val height = bitmap.height
        
        // 주입받은 경계값을 사용하되 비트맵 크기를 벗어나지 않도록 안전장치 적용
        val left = customBounds.left.coerceIn(0, width - 10)
        val right = customBounds.right.coerceIn(left + 10, width)
        val top = customBounds.top.coerceIn(0, height - 10)
        val bottom = customBounds.bottom.coerceIn(top + 10, height)
        val bounds = GridBounds(left, top, right, bottom)

        val cellW = (right - left) / GameConfig.COLS.toFloat()
        val cellH = (bottom - top) / GameConfig.ROWS.toFloat()

        val categoryGrid = Array(GameConfig.ROWS) { IntArray(GameConfig.COLS) }

        for (r in 0 until GameConfig.ROWS) {
            for (c in 0 until GameConfig.COLS) {
                val centerX = (left + c * cellW + cellW / 2f).toInt().coerceIn(0, width - 1)
                val centerY = (top + r * cellH + cellH / 2f).toInt().coerceIn(0, height - 1)

                val pixel = bitmap.getPixel(centerX, centerY)
                val hsv = FloatArray(3)
                Color.colorToHSV(pixel, hsv)
                
                val hue = hsv[0]
                val saturation = hsv[1]
                val value = hsv[2]

                if (saturation < 0.18f || value < 0.18f) {
                    categoryGrid[r][c] = GameConfig.HOLE
                    continue
                }

                categoryGrid[r][c] = when {
                    hue in 0.0f..35.0f   -> 0 // Brown
                    hue in 35.0f..65.0f  -> 1 // Yellow
                    hue in 65.0f..160.0f -> 2 // Green
                    hue in 160.0f..200.0f -> 3 // Blue
                    hue in 200.0f..280.0f -> 4 // Purple
                    else -> GameConfig.HOLE
                }
            }
        }

        return GridResult(true, categoryGrid, bounds)
    }
}
