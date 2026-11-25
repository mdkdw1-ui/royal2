package com.example.helper.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream

/**
 * 일반 젬 4종(빨/노/초/파 + 선택 보라) 화이트리스트 템플릿 검증기.
 * 등록된 젬과 모양+RGB 색 분포가 일치할 때만 일반 젬으로 인정하고 나머지는 100% 기믹 처리.
 */
object GemWhitelist {

    private class GemEntry(
        val innerScales: List<Mat>, // scales 0.90, 1.00, 1.08 (기준 40x40)
        val outerScales: List<Mat>  // scales 0.90, 1.00, 1.08 (기준 52x52)
    ) {
        fun release() {
            innerScales.forEach { it.release() }
            outerScales.forEach { it.release() }
        }
    }

    private val lock = Any()
    private val entries = mutableMapOf<Int, GemEntry>()
    private val scales = doubleArrayOf(0.90, 1.00, 1.08)

    private fun dir(context: Context): File {
        val d = File(context.getExternalFilesDir(null), "gem_whitelist")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun buildEntry(bitmap64: Bitmap): GemEntry {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap64, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()

        // 64x64 기준 중앙 62%(40x40) / 외곽 포함 81%(52x52)
        val innerBase = Mat(rgb, Rect(12, 12, 40, 40))
        val outerBase = Mat(rgb, Rect(6, 6, 52, 52))

        val inList = scales.map { sc ->
            val m = Mat()
            Imgproc.resize(innerBase, m, Size((40 * sc).toInt().toDouble(), (40 * sc).toInt().toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            m
        }
        val outList = scales.map { sc ->
            val m = Mat()
            Imgproc.resize(outerBase, m, Size((52 * sc).toInt().toDouble(), (52 * sc).toInt().toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            m
        }
        innerBase.release()
        outerBase.release()
        rgb.release()
        return GemEntry(inList, outList)
    }

    fun load(context: Context) {
        val d = dir(context)
        val loaded = mutableMapOf<Int, GemEntry>()
        for (color in 1..5) {
            val file = File(d, "gem_$color.png")
            if (file.exists()) {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) {
                    val scaled = if (bmp.width != 64 || bmp.height != 64) {
                        Bitmap.createScaledBitmap(bmp, 64, 64, true).also { bmp.recycle() }
                    } else bmp
                    loaded[color] = buildEntry(scaled)
                    scaled.recycle()
                }
            }
        }
        synchronized(lock) {
            entries.values.forEach { it.release() }
            entries.clear()
            entries.putAll(loaded)
        }
    }

    fun saveGem(context: Context, color: Int, rawCell: Bitmap) {
        val bmp64 = Bitmap.createScaledBitmap(rawCell, 64, 64, true)
        val file = File(dir(context), "gem_$color.png")
        FileOutputStream(file).use { out ->
            bmp64.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val entry = buildEntry(bmp64)
        bmp64.recycle()
        synchronized(lock) {
            entries.remove(color)?.release()
            entries[color] = entry
        }
    }

    fun clearAll(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
        synchronized(lock) {
            entries.values.forEach { it.release() }
            entries.clear()
        }
    }

    fun hasColor(color: Int): Boolean = synchronized(lock) { entries.containsKey(color) }

    fun registeredBaseCount(): Int = synchronized(lock) { (1..4).count { entries.containsKey(it) } }

    fun isReady(): Boolean = synchronized(lock) { (1..4).all { entries.containsKey(it) } }

    fun nextMissingColor(): Int? = synchronized(lock) { (1..4).firstOrNull { !entries.containsKey(it) } }

    /**
     * 반환: Pair(일반젬 여부, 이동가능 여부)
     */
    fun verifyCell(
        rawCell: Bitmap,
        candidateColor: Int,
        innerThresh: Double = 0.62,
        outerThresh: Double = 0.48
    ): Pair<Boolean, Boolean> {
        val bmp64 = if (rawCell.width != 64 || rawCell.height != 64) {
            Bitmap.createScaledBitmap(rawCell, 64, 64, true)
        } else rawCell

        val rgba = Mat()
        Utils.bitmapToMat(bmp64, rgba)
        if (bmp64 !== rawCell) bmp64.recycle()

        val searchOuter = Mat()
        Imgproc.cvtColor(rgba, searchOuter, Imgproc.COLOR_RGBA2RGB)
        rgba.release()

        // 중앙 56x56 탐색창 (±8px 격자 오차 흡수)
        val searchInner = Mat(searchOuter, Rect(4, 4, 56, 56))
        val res = Mat()

        var bestInner = -1.0
        var bestScaleIdx = 1
        var outerScore = -1.0

        synchronized(lock) {
            val entry = entries[candidateColor]
            if (entry != null) {
                for (i in entry.innerScales.indices) {
                    val ti = entry.innerScales[i]
                    Imgproc.matchTemplate(searchInner, ti, res, Imgproc.TM_CCOEFF_NORMED)
                    val v = Core.minMaxLoc(res).maxVal
                    if (v > bestInner) {
                        bestInner = v
                        bestScaleIdx = i
                    }
                }
                if (bestInner >= innerThresh) {
                    val to = entry.outerScales[bestScaleIdx]
                    Imgproc.matchTemplate(searchOuter, to, res, Imgproc.TM_CCOEFF_NORMED)
                    outerScore = Core.minMaxLoc(res).maxVal
                }
            }
        }

        res.release()
        searchInner.release()
        searchOuter.release()

        if (bestInner < innerThresh) return Pair(false, false)
        return Pair(true, outerScore >= outerThresh)
    }

    fun release() {
        synchronized(lock) {
            entries.values.forEach { it.release() }
            entries.clear()
        }
    }
}
