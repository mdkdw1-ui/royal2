package com.example.helper.core

import android.graphics.Color
import kotlin.math.roundToInt

data class CellClass(
    val color: Int,      // -1: 기믹/부스터/빈칸, 1:빨강, 2:노랑, 3:초록, 4:파랑, 5:보라
    val movable: Boolean // false면 얼음/쇠사슬 등으로 이동 불가(고정 O로만 활용 가능)
)

object CellClassifier {

    /**
     * 셀 내부 7x7(49점) 샘플링.
     * relaxed=true (화이트리스트 가동 시): 후보 색만 넓게 골라 OpenCV 템플릿 검증으로 넘김
     * relaxed=false (미등록 시 fallback): 엄격한 색상+순도 필터로 기믹 차단
     */
    fun classify(
        pixels: IntArray,
        imgW: Int,
        imgH: Int,
        cx: Float,
        cy: Float,
        cellW: Float,
        cellH: Float,
        relaxed: Boolean = false
    ): CellClass {
        val rx = cellW * 0.26f
        val ry = cellH * 0.26f
        val hsv = FloatArray(3)

        var red = 0
        var yellow = 0
        var green = 0
        var blue = 0
        var purple = 0

        var brownWood = 0
        var cyanIce = 0
        var whiteHighlight = 0
        var greyStone = 0
        var darkVoid = 0
        var otherChroma = 0

        for (dy in -3..3) {
            val py = (cy + (dy / 3f) * ry).roundToInt().coerceIn(0, imgH - 1)
            val rowOff = py * imgW
            for (dx in -3..3) {
                val px = (cx + (dx / 3f) * rx).roundToInt().coerceIn(0, imgW - 1)
                val p = pixels[rowOff + px]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                Color.RGBToHSV(r, g, b, hsv)

                val h = hsv[0]
                val s = hsv[1]
                val v = hsv[2]

                if (v < 0.20f) {
                    darkVoid++
                    continue
                }
                if (s < 0.22f) {
                    if (v > 0.76f) whiteHighlight++ else greyStone++
                    continue
                }

                when {
                    (h <= 16f || h >= 338f) && s >= 0.42f && v >= 0.32f -> red++
                    (h in 16.01f..33.99f && s < 0.78f) || (h in 16.01f..31f && v < 0.72f) -> brownWood++
                    h in 34f..64f && s >= 0.42f && v >= 0.48f -> yellow++
                    h in 72f..158f && s >= 0.36f && v >= 0.30f -> green++
                    h in 162f..187f && s < 0.78f -> cyanIce++
                    h in 188f..244f && s >= 0.40f && v >= 0.32f -> blue++
                    h in 252f..334f && s >= 0.34f && v >= 0.28f -> purple++
                    else -> otherChroma++
                }
            }
        }

        if (darkVoid > (if (relaxed) 28 else 24)) return CellClass(-1, false)
        if (whiteHighlight > (if (relaxed) 26 else 21)) return CellClass(-1, false)
        if (brownWood + greyStone + cyanIce > (if (relaxed) 19 else 14)) return CellClass(-1, false)

        val counts = intArrayOf(red, yellow, green, blue, purple)
        var bestIdx = -1
        var bestCnt = 0
        var secondCnt = 0
        for (i in counts.indices) {
            val c = counts[i]
            if (c > bestCnt) {
                secondCnt = bestCnt
                bestCnt = c
                bestIdx = i
            } else if (c > secondCnt) {
                secondCnt = c
            }
        }

        val chromaSum = red + yellow + green + blue + purple + brownWood + cyanIce + otherChroma
        val minBest = if (relaxed) 8 else 11
        val minPurity = if (relaxed) 0.45f else 0.62f
        val maxSecond = if (relaxed) 10 else 6

        if (bestIdx == -1 || bestCnt < minBest || chromaSum == 0) {
            return CellClass(-1, false)
        }
        val purity = bestCnt.toFloat() / chromaSum.toFloat()
        if (purity < minPurity || secondCnt > maxSecond) {
            return CellClass(-1, false)
        }

        // 외곽 8점 검사 (얼음/쇠사슬 여부)
        var rimBlocked = 0
        val ox = cellW * 0.39f
        val oy = cellH * 0.39f
        val offsets = arrayOf(
            -ox to -oy, 0f to -oy, ox to -oy,
            -ox to 0f,             ox to 0f,
            -ox to oy,  0f to oy,  ox to oy
        )
        for ((dx, dy) in offsets) {
            val px = (cx + dx).roundToInt().coerceIn(0, imgW - 1)
            val py = (cy + dy).roundToInt().coerceIn(0, imgH - 1)
            val p = pixels[py * imgW + px]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            Color.RGBToHSV(r, g, b, hsv)
            val s = hsv[1]; val v = hsv[2]; val h = hsv[0]
            val isIceOrChain = (s < 0.22f && v > 0.72f) || (s < 0.20f && v in 0.28f..0.62f) || (h in 165f..192f && s < 0.55f && v > 0.65f)
            if (isIceOrChain) rimBlocked++
        }

        return CellClass(bestIdx + 1, rimBlocked < 5)
    }
}
