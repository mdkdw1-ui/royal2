package com.example.helper.core

import android.graphics.Bitmap
import kotlin.math.abs

data class AutoGrid(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val rows: Int, val cols: Int,
    val cellW: Float, val cellH: Float,
    val confidence: Float
)

/**
 * v2: 상하 UI 제외 + 타일 간 어두운 경계 에지 & 텍스처 투영 기반 격자 검출
 * (기믹이 많은 줄에서도 칸 간격/위상/개수를 안정적으로 찾음)
 */
object GridAutoDetector {
    private const val STEP = 2

    fun detect(bitmap: Bitmap): AutoGrid? {
        return try { detectInternal(bitmap) } catch (e: Exception) { null }
    }

    private fun detectInternal(bitmap: Bitmap): AutoGrid? {
        val bw = bitmap.width
        val bh = bitmap.height
        val w = (bw + STEP - 1) / STEP
        val h = (bh + STEP - 1) / STEP
        if (w < 80 || h < 120) return null

        // Royal Match 실제 보드 영역만 스캔 (상단 목표 HUD / 하단 부스터 제외)
        val y0 = (h * 0.16f).toInt().coerceIn(2, h - 10)
        val y1 = (h * 0.79f).toInt().coerceIn(y0 + 20, h - 2)
        val x0 = (w * 0.02f).toInt().coerceIn(2, w - 10)
        val x1 = (w * 0.98f).toInt().coerceIn(x0 + 20, w - 2)

        val lum = Array(h) { IntArray(w) }
        val tileMask = Array(h) { ByteArray(w) }
        val line = IntArray(bw)

        for (ys in y0 until y1) {
            val y = (ys * STEP).coerceAtMost(bh - 1)
            bitmap.getPixels(line, 0, bw, 0, y, bw, 1)
            val lRow = lum[ys]
            val mRow = tileMask[ys]
            for (xs in x0 until x1) {
                val p = line[(xs * STEP).coerceAtMost(bw - 1)]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val l = (r * 299 + g * 587 + b * 114) / 1000
                lRow[xs] = l
                val mx = maxOf(r, maxOf(g, b))
                val mn = minOf(r, minOf(g, b))
                // 일반 젬 + 상자/기믹까지 포함하도록 채도 기준 완화
                if ((mx - mn) * 100 >= 32 * maxOf(mx, 1) && mx >= 65) {
                    mRow[xs] = 1
                }
            }
        }

        // 셀 경계 에지(bx, by): 기믹이 있어도 셀 사이 경계선에서 피크 형성
        val bx = Array(h) { FloatArray(w) }
        val by = Array(h) { FloatArray(w) }
        for (ys in (y0 + 1) until y1) {
            val lCur = lum[ys]
            val lPrev = lum[ys - 1]
            val bxRow = bx[ys]
            val byRow = by[ys]
            for (xs in (x0 + 1) until x1) {
                val c = lCur[xs]
                val darkBonus = if (c < 82) 18f else 0f
                bxRow[xs] = abs(c - lCur[xs - 1]).toFloat() + darkBonus
                byRow[xs] = abs(c - lPrev[xs]).toFloat() + darkBonus
            }
        }

        val minL = (bw / 11.5f / STEP).toInt()
        val maxL = (bw / 5.2f / STEP).toInt()
        if (minL < 8 || maxL <= minL + 4) return null

        var colL = x0; var colR = x1
        var rowT = y0; var rowB = y1
        var px = 0f; var py = 0f
        var cx = 0f; var cy = 0f
        var rx: Pair<Float, Int>? = null
        var ry: Pair<Float, Int>? = null

        for (iter in 0 until 3) {
            val ex = FloatArray(w)
            val mx = FloatArray(w)
            for (y in rowT until rowB) {
                val bRow = bx[y]; val mRow = tileMask[y]
                for (x in x0 until x1) {
                    ex[x] += bRow[x]
                    mx[x] += mRow[x].toFloat()
                }
            }
            val ax = acfPitch(ex, x0, x1, minL, maxL)

            val ey0 = FloatArray(h)
            for (y in y0 until y1) {
                var s = 0f
                val bRow = by[y]
                for (x in colL until colR) s += bRow[x]
                ey0[y] = s
            }
            val ay = acfPitch(ey0, y0, y1, minL, maxL)

            cx = ax.second; cy = ay.second
            if (cx < 0.14f && cy < 0.14f) return null

            if (cx >= 0.14f && cy >= 0.14f && abs(ax.first - ay.first) / maxOf(ax.first, ay.first) < 0.08f) {
                px = ax.first; py = ay.first
            } else {
                val p = if (cx >= cy) ax.first else ay.first
                px = p; py = p
            }

            rx = findPhaseExtent(ex, mx, x0, x1, px) ?: return null
            colL = rx.first.toInt().coerceIn(x0, x1 - 5)
            colR = (rx.first + rx.second * px).toInt().coerceIn(colL + 5, x1)

            val ey = FloatArray(h)
            val my = FloatArray(h)
            for (y in y0 until y1) {
                var se = 0f; var sm = 0f
                val bRow = by[y]; val mRow = tileMask[y]
                for (x in colL until colR) {
                    se += bRow[x]
                    sm += mRow[x].toFloat()
                }
                ey[y] = se
                my[y] = sm
            }
            ry = findPhaseExtent(ey, my, y0, y1, py) ?: return null
            rowT = ry.first.toInt().coerceIn(y0, y1 - 5)
            rowB = (ry.first + ry.second * py).toInt().coerceIn(rowT + 5, y1)
        }

        val fr = rx ?: return null
        val fyr = ry ?: return null
        val cols = fr.second
        val rows = fyr.second
        if (cols < 5 || cols > 11 || rows < 5 || rows > 13) return null

        val cellW = px * STEP
        val cellH = py * STEP
        val left = fr.first * STEP
        val top = fyr.first * STEP
        return AutoGrid(
            left, top, left + cols * cellW, top + rows * cellH,
            rows, cols, cellW, cellH, maxOf(cx, cy)
        )
    }

    private fun acfPitch(f: FloatArray, start: Int, end: Int, minL: Int, maxL: Int): Pair<Float, Float> {
        val len = end - start
        if (len <= maxL + 4) return Pair(0f, -1f)

        val d = FloatArray(len)
        val half = maxL
        val pre = DoubleArray(len + 1)
        for (i in 0 until len) pre[i + 1] = pre[i] + f[start + i]
        for (i in 0 until len) {
            val lo = maxOf(0, i - half)
            val hi = minOf(len - 1, i + half)
            d[i] = (f[start + i] - (pre[hi + 1] - pre[lo]) / (hi - lo + 1)).toFloat()
        }

        var den = 1e-9
        for (v in d) den += v.toDouble() * v

        val scores = DoubleArray(maxL + 2)
        var best = -1.0
        var bestL = -1
        for (l in minL..maxL) {
            var s = 0.0
            for (i in 0 until len - l) s += d[i].toDouble() * d[i + l]
            s /= den
            scores[l] = s
            if (s > best) { best = s; bestL = l }
        }
        if (bestL <= minL || bestL >= maxL) return Pair(bestL.toFloat(), best.toFloat())

        // 2배수 고조파 방지
        val halfL = (bestL / 2.0).toInt()
        for (cand in maxOf(minL, halfL - 3)..minOf(maxL, halfL + 3)) {
            if (scores[cand] > best * 0.56) {
                bestL = cand
                best = scores[cand]
                break
            }
        }
        if (bestL <= minL || bestL >= maxL) return Pair(bestL.toFloat(), best.toFloat())

        val a = scores[bestL - 1]; val b = scores[bestL]; val c = scores[bestL + 1]
        val dd = a - 2 * b + c
        val off = if (abs(dd) > 1e-9) 0.5 * (a - c) / dd else 0.0
        return Pair(bestL + off.coerceIn(-1.0, 1.0).toFloat(), best.toFloat())
    }

    private fun smoothAt(f: FloatArray, x: Float, half: Float): Float {
        val lo = maxOf(0, Math.round(x - half))
        val hi = minOf(f.size - 1, Math.round(x + half))
        if (hi < lo) return 0f
        var s = 0f
        for (i in lo..hi) s += f[i]
        return s / (hi - lo + 1)
    }

    /** 경계 에지(edge)는 선 위에서 높고, 내부 채움(fill)은 칸 중앙에서 높음을 이용 */
    private fun findPhaseExtent(
        edge: FloatArray,
        fill: FloatArray,
        start: Int,
        end: Int,
        p: Float
    ): Pair<Float, Int>? {
        if (p < 8f || end - start < p * 4) return null

        val steps = maxOf(12, p.toInt())
        var bestPhi = start.toFloat()
        var bestG = -1e30f

        for (i in 0 until steps) {
            val phi = start + p * i / steps
            var g = 0f
            var k = 0
            while (phi + (k + 1) * p < end) {
                val lineE = smoothAt(edge, phi + k * p, p * 0.08f)
                val centerE = smoothAt(edge, phi + (k + 0.5f) * p, p * 0.16f)
                val centerF = smoothAt(fill, phi + (k + 0.5f) * p, p * 0.22f)
                val lineF = smoothAt(fill, phi + k * p, p * 0.10f)
                g += (lineE - centerE * 0.3f) + (centerF - lineF) * 1.5f
                k++
            }
            if (g > bestG) { bestG = g; bestPhi = phi }
        }

        // 시작 위치 앞으로 확장 가능한지 정렬
        var phi = bestPhi
        while (phi - p >= start) phi -= p

        val ncell = ((end - phi) / p).toInt()
        if (ncell < 4) return null

        // 각 칸의 활성도 = 내부 채도 + 내부 에지(나무상자/얼음 등 기믹도 에지 높음)
        val activity = FloatArray(ncell) { k ->
            val cx = phi + (k + 0.5f) * p
            smoothAt(fill, cx, p * 0.25f) * 2.0f + smoothAt(edge, cx, p * 0.25f) * 0.25f
        }
        val mx = activity.maxOrNull() ?: 0f
        if (mx <= 0f) return null
        val thr = mx * 0.24f

        var bestSum = -1f; var bestA = 0; var bestB = 0
        var k = 0
        while (k < ncell) {
            if (activity[k] >= thr) {
                val a = k
                while (k + 1 < ncell && activity[k + 1] >= thr) k++
                val b = k
                var sm = 0f
                for (j in a..b) sm += activity[j]
                if (sm > bestSum) { bestSum = sm; bestA = a; bestB = b }
            }
            k++
        }
        if (bestSum <= 0f) return null
        return Pair(phi + bestA * p, bestB - bestA + 1)
    }
}
