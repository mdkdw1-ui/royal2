package com.example.helper.util

import android.graphics.Bitmap
import android.graphics.PointF
import kotlin.math.abs

object GridFeature {
    const val DIM = 66

    /**
     * 66차원 특징 벡터:
     *  - Board 8x8 grayscale (64)
     *  - aspect ratio (1)
     *  - tile 크기 추정 (1)
     */
    fun extract(
        bitmap: Bitmap,
        ptTL: PointF, ptTR: PointF, ptBL: PointF, ptBR: PointF
    ): FloatArray {
        val feature = FloatArray(DIM)
        val boardW = ((ptTR.x - ptTL.x) + (ptBR.x - ptBL.x)) / 2f
        val boardH = ((ptBL.y - ptTL.y) + (ptBR.y - ptTR.y)) / 2f
        if (boardW < 50f || boardH < 50f) return feature

        val x0 = ptTL.x.toInt().coerceIn(0, bitmap.width - 1)
        val y0 = ptTL.y.toInt().coerceIn(0, bitmap.height - 1)
        val x1 = ptBR.x.toInt().coerceIn(0, bitmap.width)
        val y1 = ptBR.y.toInt().coerceIn(0, bitmap.height)

        val w = x1 - x0
        val h = y1 - y0
        if (w < 30 || h < 30) return feature

        // 8x8 다운샘플
        val blockW = (w / 8).coerceAtLeast(1)
        val blockH = (h / 8).coerceAtLeast(1)
        var idx = 0
        for (by in 0 until 8) {
            for (bx in 0 until 8) {
                var sum = 0L
                var cnt = 0
                val sx = x0 + bx * blockW
                val sy = y0 + by * blockH
                var yy = sy
                while (yy < sy + blockH && yy < y1) {
                    var xx = sx
                    while (xx < sx + blockW && xx < x1) {
                        val p = bitmap.getPixel(xx, yy)
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        sum += (r * 299 + g * 587 + b * 114) / 1000
                        cnt++
                        xx += 2
                    }
                    yy += 2
                }
                feature[idx++] = if (cnt > 0) (sum / cnt).toFloat() / 255f else 0f
            }
        }
        // aspect ratio
        feature[64] = (boardH / boardW).coerceIn(0.5f, 2.0f)
        // tileW 추정 (보드폭 / 9)
        feature[65] = (boardW / 9f / 200f).coerceIn(0.1f, 2.0f)
        return feature
    }

    /** 정규화된 유클리드 거리 (0~1) */
    fun distance(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 1f
        var sum = 0f
        for (i in a.indices) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return Math.sqrt(sum.toDouble()).toFloat() / Math.sqrt(DIM.toDouble()).toFloat()
    }
}
