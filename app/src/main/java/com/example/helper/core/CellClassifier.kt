package com.example.helper.core

import android.graphics.Color
import kotlin.math.roundToInt

data class CellClass(
    val color: Int,      // -1: 기믹/부스터/빈칸, 1:빨강, 2:노랑, 3:초록, 4:파랑, 5:보라
    val movable: Boolean // false면 얼음/쇠사슬 등으로 이동 불가(고정 O로만 활용 가능)
)

object CellClassifier {

    /**
     * 셀 내부 7x7(49점) 샘플링 + 반사광 면역 + 나무상자/얼음/다색 부스터 차단
     */
    fun classify(
        pixels: IntArray,
        imgW: Int,
        imgH: Int,
        cx: Float,
        cy: Float,
        cellW: Float,
        cellH: Float
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

                if (v < 0.22f) {
                    darkVoid++
                    continue
                }
                if (s < 0.24f) {
                    if (v > 0.76f) whiteHighlight++ else greyStone++
                    continue
                }

                when {
                    // 빨간 사과 (갈색 상자 17~33 구간 철저히 분리)
                    (h <= 16f || h >= 338f) && s >= 0.45f && v >= 0.34f -> red++
                    // 갈색/황토색 나무상자·항아리·테두리
                    (h in 16.01f..33.99f && s < 0.78f) || (h in 16.01f..31f && v < 0.72f) -> brownWood++
                    // 노란 왕관 (밝고 진한 노랑)
                    h in 34f..63f && s >= 0.44f && v >= 0.52f -> yellow++
                    // 초록 나뭇잎
                    h in 72f..158f && s >= 0.38f && v >= 0.32f -> green++
                    // 얼음/물 하늘색 (낮은 채도의 시안)
                    h in 162f..187f && s < 0.78f -> cyanIce++
                    // 파란 방패
                    h in 188f..244f && s >= 0.42f && v >= 0.35f -> blue++
                    // 보라색
                    h in 252f..334f && s >= 0.36f && v >= 0.30f -> purple++
                    else -> otherChroma++
                }
            }
        }

        // 어두운 빈칸/배경, 흰색 과다(로켓 몸체/얼음/알), 나무/돌/얼음 기믹 차단
        if (darkVoid > 24) return CellClass(-1, false)
        if (whiteHighlight > 21) return CellClass(-1, false)
        if (brownWood + greyStone + cyanIce > 14) return CellClass(-1, false)

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
        if (bestIdx == -1 || bestCnt < 11 || chromaSum == 0) {
            return CellClass(-1, false)
        }

        // 단일 색상 순도 검사 (전구/TNT/프로펠러 등 다색 부스터 오인식 차단)
        val purity = bestCnt.toFloat() / chromaSum.toFloat()
        if (purity < 0.62f || secondCnt > 6) {
            return CellClass(-1, false)
        }

        // 외곽 8점 검사: 얼음/쇠사슬로 덮인 타일은 이동 불가(고정 O로만 사용)
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
            val s = hsv[1]
            val v = hsv[2]
            val h = hsv[0]
            val isIceOrChain = (s < 0.22f && v > 0.72f) || (s < 0.20f && v in 0.28f..0.62f) || (h in 165f..192f && s < 0.55f && v > 0.65f)
            if (isIceOrChain) rimBlocked++
        }

        val movable = rimBlocked < 5
        return CellClass(bestIdx + 1, movable)
    }
}
