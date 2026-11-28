package com.example.helper.core

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class DetectedGem(
    val x: Float,
    val y: Float,
    val color: Int,
    val movable: Boolean
)

data class FiveMatchMove(
    val targetX: Float,
    val targetY: Float,
    val sourceX: Float,
    val sourceY: Float,
    val color: Int,
    val lineCoords: List<PointF>
)

data class EngineResult(
    val moving: Boolean,
    val rows: Int,
    val cols: Int,
    val cellW: Float,
    val cellH: Float,
    val gems: List<DetectedGem>,
    val moves: List<FiveMatchMove>
)

object ZeroMistakeEngine {

    private const val WORK_W = 480
    private val SCALE_CANDIDATES = floatArrayOf(0.76f, 0.84f, 0.92f, 1.00f, 1.10f, 1.20f, 1.30f)

    private var prevWorkGray: Mat? = null
    private var prevMoves: List<FiveMatchMove> = emptyList()

    fun resetTemporal() {
        prevWorkGray?.release()
        prevWorkGray = null
        prevMoves = emptyList()
    }

    private data class ScaledTemplate(
        val color: Int,
        val inSz: Int,
        val outSz: Int,
        val innerGray: Mat,
        val innerRgbBytes: ByteArray,
        val innerRVec: FloatArray,
        val innerGVec: FloatArray,
        val innerBVec: FloatArray,
        val innerEdgeVec: FloatArray,
        val outerRgbBytes: ByteArray,
        val outerRVec: FloatArray,
        val outerGVec: FloatArray,
        val outerBVec: FloatArray
    ) {
        fun release() {
            innerGray.release()
        }
    }

    private data class CandidatePeak(val px: Int, val py: Int, val color: Int, val score: Float)
    private data class VerifiedGem(val cx: Float, val cy: Float, val color: Int, val movable: Boolean, val score: Float, var rRaw: Int = 0, var cRaw: Int = 0)

    fun analyze(fullBitmap: Bitmap): EngineResult {
        val templates = GemTemplateStore.snapshot()
        if (templates.size < 4) {
            return EngineResult(moving = false, rows = 0, cols = 0, cellW = 0f, cellH = 0f, gems = emptyList(), moves = emptyList())
        }

        val fullW = fullBitmap.width
        val fullH = fullBitmap.height
        val roiTop = (fullH * 0.14f).toInt().coerceIn(0, fullH - 50)
        val roiBot = (fullH * 0.84f).toInt().coerceIn(roiTop + 50, fullH)
        val roiH = roiBot - roiTop
        val scale = WORK_W.toFloat() / fullW.toFloat()
        val workH = (roiH * scale).roundToInt().coerceAtLeast(80)

        val roiBmp = Bitmap.createBitmap(fullBitmap, 0, roiTop, fullW, roiH)
        val workBmp = Bitmap.createScaledBitmap(roiBmp, WORK_W, workH, true)
        roiBmp.recycle()

        val rgba = Mat()
        val workRgb = Mat()
        val workGray = Mat()
        val halfGray = Mat()
        val gx = Mat()
        val gy = Mat()
        val workEdge = Mat()
        val diffMat = Mat()

        val scaledTpls = mutableMapOf<Int, ScaledTemplate>()

        try {
            Utils.bitmapToMat(workBmp, rgba)
            workBmp.recycle()
            Imgproc.cvtColor(rgba, workRgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(workRgb, workGray, Imgproc.COLOR_RGB2GRAY)

            val prev = prevWorkGray
            var mad = 0.0
            if (prev != null && prev.rows() == workGray.rows() && prev.cols() == workGray.cols()) {
                Core.absdiff(workGray, prev, diffMat)
                mad = Core.mean(diffMat).`val`[0]
            }
            prevWorkGray?.release()
            prevWorkGray = workGray.clone()

            if (mad > 3.5) {
                prevMoves = emptyList()
                return EngineResult(moving = true, rows = 0, cols = 0, cellW = 0f, cellH = 0f, gems = emptyList(), moves = emptyList())
            }

            val halfW = WORK_W / 2
            val halfH = (workH / 2).coerceAtLeast(40)
            Imgproc.resize(workGray, halfGray, Size(halfW.toDouble(), halfH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)

            val basePitchWork = (GemTemplateStore.pitchRatio * WORK_W).coerceAtLeast(24f)
            var bestScale = 1.0f
            var bestScaleScore = -1.0
            var bestHits = 0

            val tmpTpl = Mat()
            val tmpRes = Mat()
            try {
                for (s in SCALE_CANDIDATES) {
                    val inSzH = (basePitchWork * s * 0.5f * (40f / 64f)).roundToInt().coerceAtLeast(10)
                    if (inSzH >= minOf(halfW, halfH) - 4) continue
                    val minDist = (inSzH * 1.1f).roundToInt().coerceAtLeast(6)
                    var scScore = 0.0
                    var hits = 0
                    for ((_, tpl) in templates) {
                        val sub = Mat(tpl.gray64, Rect(12, 12, 40, 40))
                        Imgproc.resize(sub, tmpTpl, Size(inSzH.toDouble(), inSzH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                        sub.release()
                        Imgproc.matchTemplate(halfGray, tmpTpl, tmpRes, Imgproc.TM_CCOEFF_NORMED)
                        val peaks = extractNmsPeaks(tmpRes, 0.70f, minDist, 100)
                        hits += peaks.size
                        for (p in peaks) scScore += (p.score - 0.65)
                    }
                    if (scScore > bestScaleScore) {
                        bestScaleScore = scScore
                        bestScale = s
                        bestHits = hits
                    }
                }
            } finally {
                tmpTpl.release()
                tmpRes.release()
            }

            if (bestHits < 6) {
                prevMoves = emptyList()
                return EngineResult(moving = false, rows = 0, cols = 0, cellW = 0f, cellH = 0f, gems = emptyList(), moves = emptyList())
            }

            val cellW = (basePitchWork * bestScale).roundToInt().coerceIn(24, 96)
            val inSz = (cellW * 40f / 64f).roundToInt().coerceAtLeast(16)
            val outSz = (cellW * 54f / 64f).roundToInt().coerceAtLeast(inSz + 4)

            Imgproc.Sobel(workGray, gx, CvType.CV_32F, 1, 0, 3)
            Imgproc.Sobel(workGray, gy, CvType.CV_32F, 0, 1, 3)
            Core.magnitude(gx, gy, workEdge)

            for ((c, tpl) in templates) {
                scaledTpls[c] = buildScaledTemplate(c, tpl, inSz, outSz)
            }

            val minDistWork = (cellW * 0.58f).roundToInt().coerceAtLeast(10)
            val rawCands = mutableListOf<CandidatePeak>()
            val resMat = Mat()
            try {
                for ((c, st) in scaledTpls) {
                    Imgproc.matchTemplate(workGray, st.innerGray, resMat, Imgproc.TM_CCOEFF_NORMED)
                    val peaks = extractNmsPeaks(resMat, 0.72f, minDistWork, 120)
                    for (p in peaks) rawCands.add(CandidatePeak(p.px, p.py, c, p.score))
                }
            } finally {
                resMat.release()
            }

            rawCands.sortByDescending { it.score }
            val dedupCands = mutableListOf<CandidatePeak>()
            val minSep = cellW * 0.55f
            for (cand in rawCands) {
                val cx = cand.px + inSz / 2f
                val cy = cand.py + inSz / 2f
                if (dedupCands.none { hypot((cx - (it.px + inSz / 2f)).toDouble(), (cy - (it.py + inSz / 2f)).toDouble()) < minSep }) {
                    dedupCands.add(cand)
                }
            }

            val rgbBytes = ByteArray(WORK_W * workH * 3)
            workRgb.get(0, 0, rgbBytes)
            val edgeFloats = FloatArray(WORK_W * workH)
            workEdge.get(0, 0, edgeFloats)

            val margin = ((outSz - inSz) / 2).coerceAtLeast(2)
            val verified = mutableListOf<VerifiedGem>()

            for (cand in dedupCands) {
                val px = cand.px
                val py = cand.py
                if (px < 0 || py < 0 || px + inSz > WORK_W || py + inSz > workH) continue

                var bestC = -1
                var bestRgbCorr = -1f
                var secondRgbCorr = -1f
                var bestRgbDiff = 999f

                for ((c, st) in scaledTpls) {
                    val (corr, diff) = evalPatchRgb(rgbBytes, WORK_W, px, py, inSz, st)
                    if (corr > bestRgbCorr) {
                        secondRgbCorr = bestRgbCorr
                        bestRgbCorr = corr
                        bestC = c
                        bestRgbDiff = diff
                    } else if (corr > secondRgbCorr) {
                        secondRgbCorr = corr
                    }
                }

                if (bestC == -1 || bestRgbCorr < 0.72f || bestRgbDiff > 40f) continue
                if (scaledTpls.size > 1 && (bestRgbCorr - secondRgbCorr) < 0.05f) continue

                val stBest = scaledTpls[bestC] ?: continue
                val edgeCorr = evalPatchEdge(edgeFloats, WORK_W, px, py, inSz, stBest.innerEdgeVec)
                if (edgeCorr < 0.45f) continue

                val cx = px + inSz / 2f
                val cy = py + inSz / 2f
                val ox0 = (cx - outSz / 2f).roundToInt()
                val oy0 = (cy - outSz / 2f).roundToInt()
                val movable = if (ox0 >= 0 && oy0 >= 0 && ox0 + outSz <= WORK_W && oy0 + outSz <= workH) {
                    val (oCorr, ringDiff) = evalOuterRim(rgbBytes, WORK_W, ox0, oy0, outSz, margin, stBest)
                    oCorr >= 0.58f && ringDiff <= 46f
                } else false

                verified.add(VerifiedGem(cx, cy, bestC, movable, bestRgbCorr))
            }

            if (verified.size < 8) {
                prevMoves = emptyList()
                return EngineResult(moving = false, rows = 0, cols = 0, cellW = 0f, cellH = 0f, gems = emptyList(), moves = emptyList())
            }

            val dxs = mutableListOf<Float>()
            val dys = mutableListOf<Float>()
            for (i in verified.indices) {
                val gi = verified[i]
                for (j in i + 1 until verified.size) {
                    val gj = verified[j]
                    val dx = abs(gi.cx - gj.cx)
                    val dy = abs(gi.cy - gj.cy)
                    if (dy < cellW * 0.25f && dx in (cellW * 0.75f)..(cellW * 1.25f)) dxs.add(dx)
                    if (dx < cellW * 0.25f && dy in (cellW * 0.75f)..(cellW * 1.25f)) dys.add(dy)
                }
            }
            val pitchX = if (dxs.size >= 3) median(dxs) else cellW.toFloat()
            val pitchY = if (dys.size >= 3) median(dys) else cellW.toFloat()

            val phaseX = circularPhase(verified.map { it.cx }, pitchX)
            val phaseY = circularPhase(verified.map { it.cy }, pitchY)

            val snapped = mutableListOf<VerifiedGem>()
            for (g in verified) {
                val cIdx = ((g.cx - phaseX) / pitchX).roundToInt()
                val rIdx = ((g.cy - phaseY) / pitchY).roundToInt()
                val ex = abs(g.cx - (phaseX + cIdx * pitchX))
                val ey = abs(g.cy - (phaseY + rIdx * pitchY))
                if (ex <= pitchX * 0.18f && ey <= pitchY * 0.18f) {
                    g.rRaw = rIdx
                    g.cRaw = cIdx
                    snapped.add(g)
                }
            }

            if (snapped.size < 8) {
                prevMoves = emptyList()
                return EngineResult(moving = false, rows = 0, cols = 0, cellW = 0f, cellH = 0f, gems = emptyList(), moves = emptyList())
            }

            val minR = snapped.minOf { it.rRaw }
            val maxR = snapped.maxOf { it.rRaw }
            val minC = snapped.minOf { it.cRaw }
            val maxC = snapped.maxOf { it.cRaw }
            val rows = (maxR - minR + 1).coerceIn(1, 15)
            val cols = (maxC - minC + 1).coerceIn(1, 15)

            val colorGrid = Array(rows) { IntArray(cols) { -1 } }
            val movableGrid = Array(rows) { BooleanArray(cols) }
            val bestGridScore = Array(rows) { FloatArray(cols) { -1f } }

            for (g in snapped) {
                val r = g.rRaw - minR
                val c = g.cRaw - minC
                if (r in 0 until rows && c in 0 until cols && g.score > bestGridScore[r][c]) {
                    colorGrid[r][c] = g.color
                    movableGrid[r][c] = g.movable
                    bestGridScore[r][c] = g.score
                }
            }

            val rawMoves = mutableListOf<FiveMatchMove>()
            if (!hasExistingMatch3(colorGrid)) {
                val originX = phaseX + minC * pitchX
                val originY = phaseY + minR * pitchY
                fun toScreen(rr: Int, cc: Int): PointF {
                    val wx = originX + cc * pitchX
                    val wy = originY + rr * pitchY
                    return PointF(wx / scale, roiTop + wy / scale)
                }

                val sim = Array(rows) { r -> IntArray(cols) { c -> colorGrid[r][c] } }
                val dirs = arrayOf(intArrayOf(0, 1), intArrayOf(1, 0))
                val seen = HashSet<String>()

                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val c1 = sim[r][c]
                        if (c1 <= 0 || !movableGrid[r][c]) continue
                        for (d in dirs) {
                            val nr = r + d[0]
                            val nc = c + d[1]
                            if (nr >= rows || nc >= cols) continue
                            val c2 = sim[nr][nc]
                            if (c2 <= 0 || !movableGrid[nr][nc] || c1 == c2) continue

                            sim[r][c] = c2
                            sim[nr][nc] = c1

                            val l1 = collectFiveLine(sim, r, c)
                            if (l1.isNotEmpty() && seen.add("$r,$c,$nr,$nc")) {
                                val tgt = toScreen(r, c)
                                val src = toScreen(nr, nc)
                                rawMoves.add(FiveMatchMove(tgt.x, tgt.y, src.x, src.y, c2, l1.map { toScreen(it.first, it.second) }))
                            }
                            val l2 = collectFiveLine(sim, nr, nc)
                            if (l2.isNotEmpty() && seen.add("$nr,$nc,$r,$c")) {
                                val tgt = toScreen(nr, nc)
                                val src = toScreen(r, c)
                                rawMoves.add(FiveMatchMove(tgt.x, tgt.y, src.x, src.y, c1, l2.map { toScreen(it.first, it.second) }))
                            }

                            sim[r][c] = c1
                            sim[nr][nc] = c2
                        }
                    }
                }
            }

            val tol = (pitchX / scale) * 0.35f
            val confirmed = rawMoves.filter { m ->
                prevMoves.any { pm ->
                    pm.color == m.color &&
                        hypot((pm.targetX - m.targetX).toDouble(), (pm.targetY - m.targetY).toDouble()) < tol &&
                        hypot((pm.sourceX - m.sourceX).toDouble(), (pm.sourceY - m.sourceY).toDouble()) < tol
                }
            }
            prevMoves = rawMoves

            val gemsOut = snapped.map {
                DetectedGem(it.cx / scale, roiTop + it.cy / scale, it.color, it.movable)
            }
            return EngineResult(
                moving = false,
                rows = rows,
                cols = cols,
                cellW = pitchX / scale,
                cellH = pitchY / scale,
                gems = gemsOut,
                moves = confirmed
            )
        } finally {
            rgba.release()
            workRgb.release()
            workGray.release()
            halfGray.release()
            gx.release()
            gy.release()
            workEdge.release()
            diffMat.release()
            scaledTpls.values.forEach { it.release() }
        }
    }

    private fun buildScaledTemplate(color: Int, tpl: GemTemplateData, inSz: Int, outSz: Int): ScaledTemplate {
        val subGray = Mat(tpl.gray64, Rect(12, 12, 40, 40))
        val inGray = Mat()
        Imgproc.resize(subGray, inGray, Size(inSz.toDouble(), inSz.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        subGray.release()

        val subRgb = Mat(tpl.rgb64, Rect(12, 12, 40, 40))
        val inRgb = Mat()
        Imgproc.resize(subRgb, inRgb, Size(inSz.toDouble(), inSz.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        subRgb.release()

        val subEdge = Mat(tpl.edge64, Rect(12, 12, 40, 40))
        val inEdge = Mat()
        Imgproc.resize(subEdge, inEdge, Size(inSz.toDouble(), inSz.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        subEdge.release()

        val subOutRgb = Mat(tpl.rgb64, Rect(5, 5, 54, 54))
        val outRgb = Mat()
        Imgproc.resize(subOutRgb, outRgb, Size(outSz.toDouble(), outSz.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        subOutRgb.release()

        val nIn = inSz * inSz
        val inRgbBytes = ByteArray(nIn * 3)
        inRgb.get(0, 0, inRgbBytes)
        val inEdgeArr = FloatArray(nIn)
        inEdge.get(0, 0, inEdgeArr)

        val nOut = outSz * outSz
        val outRgbBytes = ByteArray(nOut * 3)
        outRgb.get(0, 0, outRgbBytes)

        inRgb.release()
        inEdge.release()
        outRgb.release()

        fun normChannel(bytes: ByteArray, ch: Int, n: Int): FloatArray {
            val v = FloatArray(n)
            var sum = 0.0
            for (i in 0 until n) {
                val x = (bytes[i * 3 + ch].toInt() and 0xFF).toFloat()
                v[i] = x
                sum += x
            }
            val mean = (sum / n).toFloat()
            var norm2 = 0.0
            for (i in 0 until n) {
                v[i] -= mean
                norm2 += v[i] * v[i]
            }
            val norm = sqrt(norm2).toFloat()
            if (norm > 1e-6f) for (i in 0 until n) v[i] /= norm
            return v
        }

        fun normFloats(src: FloatArray): FloatArray {
            val n = src.size
            val v = FloatArray(n)
            var sum = 0.0
            for (i in 0 until n) sum += src[i]
            val mean = (sum / n).toFloat()
            var norm2 = 0.0
            for (i in 0 until n) {
                v[i] = src[i] - mean
                norm2 += v[i] * v[i]
            }
            val norm = sqrt(norm2).toFloat()
            if (norm > 1e-6f) for (i in 0 until n) v[i] /= norm
            return v
        }

        return ScaledTemplate(
            color = color,
            inSz = inSz,
            outSz = outSz,
            innerGray = inGray,
            innerRgbBytes = inRgbBytes,
            innerRVec = normChannel(inRgbBytes, 0, nIn),
            innerGVec = normChannel(inRgbBytes, 1, nIn),
            innerBVec = normChannel(inRgbBytes, 2, nIn),
            innerEdgeVec = normFloats(inEdgeArr),
            outerRgbBytes = outRgbBytes,
            outerRVec = normChannel(outRgbBytes, 0, nOut),
            outerGVec = normChannel(outRgbBytes, 1, nOut),
            outerBVec = normChannel(outRgbBytes, 2, nOut)
        )
    }

    private fun evalPatchRgb(
        imgRgb: ByteArray, imgW: Int, px: Int, py: Int, sz: Int, st: ScaledTemplate
    ): Pair<Float, Float> {
        val n = sz * sz
        var sumR = 0.0; var sumG = 0.0; var sumB = 0.0
        var sqR = 0.0; var sqG = 0.0; var sqB = 0.0
        var dotR = 0.0; var dotG = 0.0; var dotB = 0.0
        var diffSum = 0L

        var ti = 0
        for (dy in 0 until sz) {
            val rowBase = ((py + dy) * imgW + px) * 3
            for (dx in 0 until sz) {
                val ii = rowBase + dx * 3
                val r = imgRgb[ii].toInt() and 0xFF
                val g = imgRgb[ii + 1].toInt() and 0xFF
                val b = imgRgb[ii + 2].toInt() and 0xFF

                val tr = st.innerRgbBytes[ti * 3].toInt() and 0xFF
                val tg = st.innerRgbBytes[ti * 3 + 1].toInt() and 0xFF
                val tb = st.innerRgbBytes[ti * 3 + 2].toInt() and 0xFF
                diffSum += abs(r - tr) + abs(g - tg) + abs(b - tb)

                sumR += r; sumG += g; sumB += b
                sqR += r * r; sqG += g * g; sqB += b * b
                dotR += r * st.innerRVec[ti]
                dotG += g * st.innerGVec[ti]
                dotB += b * st.innerBVec[ti]
                ti++
            }
        }
        val varR = sqR - (sumR * sumR) / n
        val varG = sqG - (sumG * sumG) / n
        val varB = sqB - (sumB * sumB) / n
        val cR = if (varR > 1e-4) (dotR / sqrt(varR)).toFloat() else 0f
        val cG = if (varG > 1e-4) (dotG / sqrt(varG)).toFloat() else 0f
        val cB = if (varB > 1e-4) (dotB / sqrt(varB)).toFloat() else 0f
        return Pair(minOf(cR, minOf(cG, cB)), diffSum.toFloat() / (n * 3f))
    }

    private fun evalPatchEdge(
        imgEdge: FloatArray, imgW: Int, px: Int, py: Int, sz: Int, tplEdgeVec: FloatArray
    ): Float {
        val n = sz * sz
        var sum = 0.0; var sq = 0.0; var dot = 0.0
        var ti = 0
        for (dy in 0 until sz) {
            val rowBase = (py + dy) * imgW + px
            for (dx in 0 until sz) {
                val e = imgEdge[rowBase + dx].toDouble()
                sum += e
                sq += e * e
                dot += e * tplEdgeVec[ti++]
            }
        }
        val v = sq - (sum * sum) / n
        return if (v > 1e-4) (dot / sqrt(v)).toFloat() else 0f
    }

    private fun evalOuterRim(
        imgRgb: ByteArray, imgW: Int, ox0: Int, oy0: Int, outSz: Int, margin: Int, st: ScaledTemplate
    ): Pair<Float, Float> {
        val n = outSz * outSz
        var sumR = 0.0; var sumG = 0.0; var sumB = 0.0
        var sqR = 0.0; var sqG = 0.0; var sqB = 0.0
        var dotR = 0.0; var dotG = 0.0; var dotB = 0.0
        var ringDiffSum = 0L
        var ringCount = 0

        var ti = 0
        for (dy in 0 until outSz) {
            val rowBase = ((oy0 + dy) * imgW + ox0) * 3
            val inY = dy in margin until (outSz - margin)
            for (dx in 0 until outSz) {
                val ii = rowBase + dx * 3
                val r = imgRgb[ii].toInt() and 0xFF
                val g = imgRgb[ii + 1].toInt() and 0xFF
                val b = imgRgb[ii + 2].toInt() and 0xFF

                sumR += r; sumG += g; sumB += b
                sqR += r * r; sqG += g * g; sqB += b * b
                dotR += r * st.outerRVec[ti]
                dotG += g * st.outerGVec[ti]
                dotB += b * st.outerBVec[ti]

                if (!(inY && dx in margin until (outSz - margin))) {
                    val tr = st.outerRgbBytes[ti * 3].toInt() and 0xFF
                    val tg = st.outerRgbBytes[ti * 3 + 1].toInt() and 0xFF
                    val tb = st.outerRgbBytes[ti * 3 + 2].toInt() and 0xFF
                    ringDiffSum += abs(r - tr) + abs(g - tg) + abs(b - tb)
                    ringCount++
                }
                ti++
            }
        }
        val varR = sqR - (sumR * sumR) / n
        val varG = sqG - (sumG * sumG) / n
        val varB = sqB - (sumB * sumB) / n
        val cR = if (varR > 1e-4) (dotR / sqrt(varR)).toFloat() else 0f
        val cG = if (varG > 1e-4) (dotG / sqrt(varG)).toFloat() else 0f
        val cB = if (varB > 1e-4) (dotB / sqrt(varB)).toFloat() else 0f
        val ringMeanDiff = if (ringCount > 0) ringDiffSum.toFloat() / (ringCount * 3f) else 0f
        return Pair(minOf(cR, minOf(cG, cB)), ringMeanDiff)
    }

    private fun extractNmsPeaks(res: Mat, thr: Float, minDist: Int, limit: Int): List<CandidatePeak> {
        val h = res.rows()
        val w = res.cols()
        val buf = FloatArray(w * h)
        res.get(0, 0, buf)

        val cands = mutableListOf<CandidatePeak>()
        for (y in 0 until h) {
            val rowOff = y * w
            for (x in 0 until w) {
                val v = buf[rowOff + x]
                if (v >= thr) cands.add(CandidatePeak(x, y, 0, v))
            }
        }
        if (cands.isEmpty()) return emptyList()
        cands.sortByDescending { it.score }

        val suppressed = BooleanArray(w * h)
        val out = mutableListOf<CandidatePeak>()
        for (c in cands) {
            if (suppressed[c.py * w + c.px]) continue
            out.add(c)
            if (out.size >= limit) break
            val y0 = (c.py - minDist).coerceAtLeast(0)
            val y1 = (c.py + minDist).coerceAtMost(h - 1)
            val x0 = (c.px - minDist).coerceAtLeast(0)
            val x1 = (c.px + minDist).coerceAtMost(w - 1)
            for (yy in y0..y1) {
                val off = yy * w
                for (xx in x0..x1) suppressed[off + xx] = true
            }
        }
        return out
    }

    private fun median(list: List<Float>): Float {
        val s = list.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2f
    }

    private fun circularPhase(vals: List<Float>, pitch: Float): Float {
        if (vals.isEmpty() || pitch <= 1e-3f) return 0f
        var sx = 0.0
        var sy = 0.0
        for (v in vals) {
            val m = ((v % pitch) + pitch) % pitch
            val ang = 2.0 * PI * m / pitch
            sx += cos(ang)
            sy += sin(ang)
        }
        var theta = atan2(sy, sx)
        if (theta < 0) theta += 2.0 * PI
        return (theta * pitch / (2.0 * PI)).toFloat()
    }

    private fun hasExistingMatch3(grid: Array<IntArray>): Boolean {
        val rows = grid.size
        val cols = grid[0].size
        for (r in 0 until rows) {
            var run = 1
            for (c in 1 until cols) {
                if (grid[r][c] > 0 && grid[r][c] == grid[r][c - 1]) {
                    if (++run >= 3) return true
                } else run = 1
            }
        }
        for (c in 0 until cols) {
            var run = 1
            for (r in 1 until rows) {
                if (grid[r][c] > 0 && grid[r][c] == grid[r - 1][c]) {
                    if (++run >= 3) return true
                } else run = 1
            }
        }
        return false
    }

    private fun collectFiveLine(grid: Array<IntArray>, r: Int, c: Int): List<Pair<Int, Int>> {
        val color = grid[r][c]
        if (color <= 0) return emptyList()
        val rows = grid.size
        val cols = grid[0].size

        var cc = c
        while (cc > 0 && grid[r][cc - 1] == color) cc--
        val hLine = mutableListOf<Pair<Int, Int>>()
        while (cc < cols && grid[r][cc] == color) {
            hLine.add(r to cc)
            cc++
        }
        if (hLine.size >= 5) return hLine

        var rr = r
        while (rr > 0 && grid[rr - 1][c] == color) rr--
        val vLine = mutableListOf<Pair<Int, Int>>()
        while (rr < rows && grid[rr][c] == color) {
            vLine.add(rr to c)
            rr++
        }
        return if (vLine.size >= 5) vLine else emptyList()
    }
}
