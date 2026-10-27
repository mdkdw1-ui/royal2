package com.example.helper.core

import android.graphics.Bitmap

data class AutoGrid(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val rows: Int, val cols: Int,
    val cellW: Float, val cellH: Float,
    val confidence: Float
)

/**
 * 채도 높은 "말(piece)" 마스크의 가로/세로 프로파일에서
 *  1) 자기상관으로 칸 크기(피치)를 구하고
 *  2) 칸 중심 vs 경계 대비로 위상(시작 위치)을 맞추고
 *  3) 연속 구간으로 행/열 개수를 센다.
 * 행/열 수를 가로세로 비율로 추측하지 않고 칸 크기로 직접 센다.
 */
object GridAutoDetector {
    private const val STEP = 3

    fun detect(bitmap: Bitmap): AutoGrid? {
        return try { detectInternal(bitmap) } catch (e: Exception) { null }
    }

    private fun detectInternal(bitmap: Bitmap): AutoGrid? {
        val bw = bitmap.width
        val bh = bitmap.height
        val w = (bw + STEP - 1) / STEP
        val h = (bh + STEP - 1) / STEP
        if (w < 50 || h < 50) return null

        // ── 마스크 (채도>=0.45, 명도>=90)
        val m = Array(h) { ByteArray(w) }
        val line = IntArray(bw)
        for (ys in 0 until h) {
            val y = (ys * STEP).coerceAtMost(bh - 1)
            bitmap.getPixels(line, 0, bw, 0, y, bw, 1)
            val row = m[ys]
            for (xs in 0 until w) {
                val p = line[(xs * STEP).coerceAtMost(bw - 1)]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val mx = maxOf(r, maxOf(g, b))
                val mn = minOf(r, minOf(g, b))
                if ((mx - mn) * 100 >= 45 * maxOf(mx, 1) && mx >= 90) row[xs] = 1
            }
        }

        val minL = (bw / 13f / STEP).toInt()
        val maxL = (bw / 6.5f / STEP).toInt()
        if (minL < 4 || maxL <= minL + 2) return null
        val y0 = (h * 0.12f).toInt()
        val y1 = (h * 0.88f).toInt()

        var colL = 0; var colR = w
        var rowT = y0; var rowB = y1
        var px = 0f; var py = 0f
        var cx = 0f; var cy = 0f
        var rx: Pair<Float, Int>? = null
        var ry: Pair<Float, Int>? = null

        for (iter in 0 until 3) {
            val fx = FloatArray(w)
            for (y in rowT until rowB) { val row = m[y]; for (x in 0 until w) fx[x] += row[x].toFloat() }
            val ax = acfPitch(fx, minL, maxL)

            val fy0 = FloatArray(h)
            for (y in y0 until y1) { var s = 0f; val row = m[y]; for (x in colL until colR) s += row[x].toFloat(); fy0[y] = s }
            val ay = acfPitch(fy0, minL, maxL)

            cx = ax.second; cy = ay.second
            if (cx < 0.2f && cy < 0.2f) return null
            if (cx >= 0.2f && cy >= 0.2f && Math.abs(ax.first - ay.first) / maxOf(ax.first, ay.first) < 0.12f) {
                px = ax.first; py = ay.first
            } else {
                val p = if (cx >= cy) ax.first else ay.first
                px = p; py = p
            }

            rx = findPhaseExtent(fx, px) ?: return null
            colL = maxOf(0, rx.first.toInt())
            colR = minOf(w, (rx.first + rx.second * px).toInt())
            if (colR - colL < 10) return null

            val fy = FloatArray(h)
            for (y in y0 until y1) { var s = 0f; val row = m[y]; for (x in colL until colR) s += row[x].toFloat(); fy[y] = s }
            ry = findPhaseExtent(fy, py) ?: return null
            rowT = maxOf(0, ry.first.toInt())
            rowB = minOf(h, (ry.first + ry.second * py).toInt())
            if (rowB - rowT < 10) return null
        }

        val fr = rx ?: return null
        val fyr = ry ?: return null
        val cols = fr.second
        val rows = fyr.second
        if (cols < 4 || cols > 12 || rows < 5 || rows > 16) return null

        val cellW = px * STEP
        val cellH = py * STEP
        val left = fr.first * STEP
        val top = fyr.first * STEP
        return AutoGrid(
            left, top, left + cols * cellW, top + rows * cellH,
            rows, cols, cellW, cellH, maxOf(cx, cy)
        )
    }

    /** 자기상관 피치. 반환: (피치, 점수) */
    private fun acfPitch(f: FloatArray, minL: Int, maxL: Int): Pair<Float, Float> {
        val n = f.size
        if (n <= maxL + 2) return Pair(0f, -1f)
        // 느린 추세 제거 (제로 패딩 이동평균, convolve 'same'과 동일)
        val half = maxL
        val pre = DoubleArray(n + 1)
        for (i in 0 until n) pre[i + 1] = pre[i] + f[i]
        val d = FloatArray(n)
        val win = (2 * half + 1).toDouble()
        for (i in 0 until n) {
            val lo = maxOf(0, i - half)
            val hi = minOf(n - 1, i + half)
            d[i] = (f[i] - (pre[hi + 1] - pre[lo]) / win).toFloat()
        }
        var den = 1e-9
        for (i in 0 until n) den += d[i].toDouble() * d[i]
        val scores = DoubleArray(maxL + 2)
        var best = -1.0
        var bestL = -1
        for (l in minL..maxL) {
            var s = 0.0
            for (i in 0 until n - l) s += d[i].toDouble() * d[i + l]
            s /= den
            scores[l] = s
            if (s > best) { best = s; bestL = l }
        }
        if (bestL <= minL || bestL >= maxL) return Pair(bestL.toFloat(), best.toFloat())
        val a = scores[bestL - 1]; val b = scores[bestL]; val c = scores[bestL + 1]
        val dd = a - 2 * b + c
        val off = if (Math.abs(dd) > 1e-9) 0.5 * (a - c) / dd else 0.0
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

    /** 반환: (첫 칸 시작 좌표(샘플 단위), 칸 개수) */
    private fun findPhaseExtent(f: FloatArray, p: Float): Pair<Float, Int>? {
        val n = f.size
        if (p < 4f) return null
        val steps = maxOf(8, p.toInt())
        var bestPhi = 0f
        var bestG = -1e30f
        for (i in 0 until steps) {
            val phi = p * i / steps
            var g = 0f
            var k = 0
            while (phi + (k + 1) * p < n) {
                g += smoothAt(f, phi + (k + 0.5f) * p, p * 0.22f) - smoothAt(f, phi + k * p, p * 0.12f)
                k++
            }
            if (g > bestG) { bestG = g; bestPhi = phi }
        }
        val phi = bestPhi
        val ncell = ((n - phi) / p).toInt()
        if (ncell <= 0) return null
        val fill = FloatArray(ncell) { k -> smoothAt(f, phi + (k + 0.5f) * p, p * 0.15f) }
        var mx = 0f
        for (v in fill) if (v > mx) mx = v
        if (mx <= 0f) return null
        val thr = mx * 0.15f

        // thr 이상 연속 구간 중 합이 최대인 구간 = 보드
        var bestSum = -1f; var bestA = 0; var bestB = 0
        var k = 0
        while (k < ncell) {
            if (fill[k] >= thr) {
                val a = k
                while (k + 1 < ncell && fill[k + 1] >= thr) k++
                val b = k
                var sm = 0f
                for (j in a..b) sm += fill[j]
                if (sm > bestSum) { bestSum = sm; bestA = a; bestB = b }
            }
            k++
        }
        if (bestSum <= 0f) return null
        return Pair(phi + bestA * p, bestB - bestA + 1)
    }
}
