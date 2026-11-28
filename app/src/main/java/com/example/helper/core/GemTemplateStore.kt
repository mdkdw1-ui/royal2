package com.example.helper.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

class GemTemplateData(
    val color: Int,
    val rgb64: Mat,
    val gray64: Mat,
    val edge64: Mat,
    val pngBytes: ByteArray
) {
    fun release() {
        rgb64.release()
        gray64.release()
        edge64.release()
    }
}

object GemTemplateStore {
    private const val PREFS = "zero_mistake_gems"
    private const val KEY_PITCH_RATIO = "pitch_ratio"

    private val lock = Any()
    private val templates = mutableMapOf<Int, GemTemplateData>()
    var pitchRatio: Float = 0.105f
        private set

    private fun dir(context: Context): File {
        val d = File(context.getExternalFilesDir(null), "gem_templates_v2")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun buildData(color: Int, bmp64: Bitmap, pngBytes: ByteArray): GemTemplateData {
        val rgba = Mat()
        Utils.bitmapToMat(bmp64, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)

        val gx = Mat()
        val gy = Mat()
        val edge = Mat()
        Imgproc.Sobel(gray, gx, CvType.CV_32F, 1, 0, 3)
        Imgproc.Sobel(gray, gy, CvType.CV_32F, 0, 1, 3)
        Core.magnitude(gx, gy, edge)

        rgba.release()
        gx.release()
        gy.release()
        return GemTemplateData(color, rgb, gray, edge, pngBytes)
    }

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedRatio = prefs.getFloat(KEY_PITCH_RATIO, 0.105f)
        val loaded = mutableMapOf<Int, GemTemplateData>()
        val d = dir(context)
        for (c in 1..5) {
            val f = File(d, "gem_$c.png")
            if (f.exists()) {
                val bytes = f.readBytes()
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                val bmp64 = if (bmp.width != 64 || bmp.height != 64) {
                    Bitmap.createScaledBitmap(bmp, 64, 64, true).also { bmp.recycle() }
                } else bmp
                loaded[c] = buildData(c, bmp64, bytes)
                bmp64.recycle()
            }
        }
        synchronized(lock) {
            pitchRatio = savedRatio
            templates.values.forEach { it.release() }
            templates.clear()
            templates.putAll(loaded)
        }
    }

    fun registerFromTap(context: Context, fullBitmap: Bitmap, color: Int, tapX: Float, tapY: Float): GemTemplateData {
        val estPitch = estimatePitch(fullBitmap)
        val (cx, cy, cellSize) = snapTapToCenter(fullBitmap, tapX, tapY, estPitch)

        val half = (cellSize / 2f).roundToInt()
        val x0 = (cx.roundToInt() - half).coerceIn(0, fullBitmap.width - cellSize)
        val y0 = (cy.roundToInt() - half).coerceIn(0, fullBitmap.height - cellSize)
        val realSize = minOf(cellSize, fullBitmap.width - x0, fullBitmap.height - y0).coerceAtLeast(24)

        val crop = Bitmap.createBitmap(fullBitmap, x0, y0, realSize, realSize)
        val bmp64 = Bitmap.createScaledBitmap(crop, 64, 64, true)
        crop.recycle()

        val f = File(dir(context), "gem_$color.png")
        FileOutputStream(f).use { out -> bmp64.compress(Bitmap.CompressFormat.PNG, 100, out) }
        val pngBytes = f.readBytes()
        val ratio = realSize.toFloat() / fullBitmap.width.toFloat()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_PITCH_RATIO, ratio).apply()

        val data = buildData(color, bmp64, pngBytes)
        bmp64.recycle()
        synchronized(lock) {
            pitchRatio = ratio
            templates.remove(color)?.release()
            templates[color] = data
        }
        return data
    }

    fun clearAll(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
        synchronized(lock) {
            templates.values.forEach { it.release() }
            templates.clear()
        }
    }

    fun hasColor(color: Int): Boolean = synchronized(lock) { templates.containsKey(color) }
    fun baseCount(): Int = synchronized(lock) { (1..4).count { templates.containsKey(it) } }
    fun isReady(): Boolean = synchronized(lock) { (1..4).all { templates.containsKey(it) } }
    fun nextMissingColor(): Int? = synchronized(lock) { (1..4).firstOrNull { !templates.containsKey(it) } }

    fun snapshot(): Map<Int, GemTemplateData> = synchronized(lock) { HashMap(templates) }

    fun release() {
        synchronized(lock) {
            templates.values.forEach { it.release() }
            templates.clear()
        }
    }

    private fun estimatePitch(bitmap: Bitmap): Float {
        val bw = bitmap.width
        val bh = bitmap.height
        val step = 2
        val w = bw / step
        val h = bh / step
        val y0 = (h * 0.20f).toInt()
        val y1 = (h * 0.76f).toInt()
        val x0 = (w * 0.04f).toInt()
        val x1 = (w * 0.96f).toInt()
        if (x1 - x0 < 60 || y1 - y0 < 60) return bw / 9.2f

        val projX = FloatArray(w)
        val line = IntArray(bw)
        val prevLine = IntArray(w)
        for (ys in y0 until y1) {
            bitmap.getPixels(line, 0, bw, 0, ys * step, bw, 1)
            for (xs in x0 + 1 until x1) {
                val p = line[xs * step]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val l = (r * 299 + g * 587 + b * 114) / 1000
                val darkBonus = if (l < 80) 16f else 0f
                projX[xs] += abs(l - prevLine[xs - 1]).toFloat() + darkBonus
                prevLine[xs] = l
            }
        }

        val minL = (bw / 11.5f / step).toInt().coerceAtLeast(8)
        val maxL = (bw / 6.5f / step).toInt().coerceAtLeast(minL + 4)
        val len = x1 - x0
        val mean = (x0 until x1).sumOf { projX[it].toDouble() }.toFloat() / len
        val d = FloatArray(len) { projX[x0 + it] - mean }
        var den = 1e-6
        for (v in d) den += v * v

        var bestL = ((bw / 9.2f) / step).roundToInt()
        var bestScore = -1.0
        for (l in minL..maxL) {
            var s = 0.0
            for (i in 0 until len - l) s += d[i].toDouble() * d[i + l]
            s /= den
            if (s > bestScore) {
                bestScore = s
                bestL = l
            }
        }
        return if (bestScore > 0.14) (bestL * step).toFloat() else bw / 9.2f
    }

    private fun snapTapToCenter(bitmap: Bitmap, tapX: Float, tapY: Float, pitch: Float): Triple<Float, Float, Int> {
        val w = bitmap.width
        val h = bitmap.height
        val minD = (pitch * 0.25f).roundToInt().coerceAtLeast(8)
        val maxD = (pitch * 0.72f).roundToInt().coerceAtLeast(minD + 6)
        val tx = tapX.roundToInt().coerceIn(0, w - 1)
        val ty = tapY.roundToInt().coerceIn(0, h - 1)

        fun lumAt(x: Int, y: Int): Float {
            val p = bitmap.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            return (r * 0.299f + g * 0.587f + b * 0.114f)
        }

        fun findBorder(dxSign: Int, dySign: Int): Int {
            var bestD = (pitch * 0.5f).roundToInt()
            var bestVal = Float.MAX_VALUE
            for (d in minD..maxD) {
                var sum = 0f
                for (k in -4..4 step 2) {
                    val px = tx + dxSign * d + (if (dxSign == 0) k else 0)
                    val py = ty + dySign * d + (if (dySign == 0) k else 0)
                    sum += lumAt(px, py)
                }
                if (sum < bestVal) {
                    bestVal = sum
                    bestD = d
                }
            }
            return bestD
        }

        val dLeft = findBorder(-1, 0)
        val dRight = findBorder(1, 0)
        val dTop = findBorder(0, -1)
        val dBot = findBorder(0, 1)

        val cx = ((tx - dLeft) + (tx + dRight)) / 2f
        val cy = ((ty - dTop) + (ty + dBot)) / 2f
        val spanW = dLeft + dRight
        val spanH = dTop + dBot
        val spanAvg = (spanW + spanH) / 2f
        val cellSize = if (spanAvg in (pitch * 0.78f)..(pitch * 1.22f)) spanAvg.roundToInt() else pitch.roundToInt()
        return Triple(cx, cy, cellSize.coerceIn(32, minOf(w, h) / 4))
    }
}
