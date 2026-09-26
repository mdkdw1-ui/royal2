package com.example.helper.util

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object GridSeedDB {
    private const val PREFS = "grid_seed_db"
    private const val KEY = "seeds"
    private const val MAX_SEEDS = 500
    private const val DATASET_DIR = "ml_dataset"

    data class Seed(
        val feature: FloatArray,
        val rows: Int,
        val cols: Int,
        val manual: Boolean,
        val timestamp: Long,
        val imagePath: String? = null,
        val position: FloatArray? = null  // 🔥 v26: [TLx,TLy,TRx,TRy,BLx,BLy,BRx,BRy]
    )

    fun add(context: Context, feature: FloatArray, rows: Int, cols: Int, manual: Boolean, crop: Bitmap? = null) {
        if (feature.size != GridFeature.DIM) return
        val list = loadAll(context).toMutableList()

        if (!manual) {
            val hasManual = list.any {
                it.manual && GridFeature.distance(it.feature, feature) < 0.08f
            }
            if (hasManual) return
        }

        val dupIdx = list.indexOfFirst {
            it.manual == manual && GridFeature.distance(it.feature, feature) < 0.05f
        }
        if (dupIdx >= 0) {
            val old = list[dupIdx]
            if (old.rows == rows && old.cols == cols) return
            list[dupIdx] = Seed(feature, rows, cols, manual, System.currentTimeMillis(), old.imagePath, old.position)
        } else {
            var imagePath: String? = null
            if (crop != null) {
                try {
                    val dir = File(context.getExternalFilesDir(null), "$DATASET_DIR/${rows}x${cols}")
                    if (!dir.exists()) dir.mkdirs()
                    val filename = "sample_${System.currentTimeMillis()}_${if (manual) "manual" else "auto"}.png"
                    val file = File(dir, filename)
                    FileOutputStream(file).use { out -> crop.compress(Bitmap.CompressFormat.PNG, 90, out) }
                    imagePath = file.absolutePath
                } catch (e: Exception) { }
            }
            list.add(Seed(feature, rows, cols, manual, System.currentTimeMillis(), imagePath, null))
        }

        val trimmed = list.sortedByDescending { it.timestamp }.take(MAX_SEEDS)
        save(context, trimmed)
    }

    /** 🔥 v26: 정정 (크기 + 위치 모두 저장) */
    fun addManualCorrection(
        context: Context,
        feature: FloatArray,
        correctRows: Int,
        correctCols: Int,
        crop: Bitmap? = null,
        position: FloatArray? = null
    ): Pair<Int, List<String>> {
        if (feature.size != GridFeature.DIM) return 0 to emptyList()
        val list = loadAll(context).toMutableList()

        val conflicting = list.filter {
            GridFeature.distance(it.feature, feature) < 0.08f &&
            (it.rows != correctRows || it.cols != correctCols)
        }
        val removedLabels = conflicting.map { "${it.rows}x${it.cols}(${if (it.manual) "수동" else "자동"})" }
        list.removeAll(conflicting)

        var imagePath: String? = null
        if (crop != null) {
            try {
                val dir = File(context.getExternalFilesDir(null), "$DATASET_DIR/${correctRows}x${correctCols}")
                if (!dir.exists()) dir.mkdirs()
                val filename = "sample_${System.currentTimeMillis()}_manual.png"
                val file = File(dir, filename)
                FileOutputStream(file).use { out -> crop.compress(Bitmap.CompressFormat.PNG, 90, out) }
                imagePath = file.absolutePath
            } catch (e: Exception) { }
        }

        list.add(Seed(feature, correctRows, correctCols, true, System.currentTimeMillis(), imagePath, position))
        val trimmed = list.sortedByDescending { it.timestamp }.take(MAX_SEEDS)
        save(context, trimmed)

        return conflicting.size to removedLabels
    }

    fun getHistory(context: Context, feature: FloatArray, threshold: Float = 0.08f): List<Seed> {
        return loadAll(context)
            .filter { GridFeature.distance(it.feature, feature) < threshold }
            .sortedByDescending { it.timestamp }
    }

    fun loadAll(context: Context): List<Seed> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = prefs.getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val fArr = o.getJSONArray("f")
                val f = FloatArray(fArr.length()) { fArr.getDouble(it).toFloat() }
                val pos = o.optJSONArray("pos")?.let { pArr ->
                    FloatArray(pArr.length()) { pArr.getDouble(it).toFloat() }
                }
                Seed(f, o.getInt("r"), o.getInt("c"), o.getBoolean("m"), o.getLong("t"),
                    o.optString("img", "").takeIf { it.isNotEmpty() && it != "null" }, pos)
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun save(context: Context, list: List<Seed>) {
        val arr = JSONArray()
        list.forEach { s ->
            val o = JSONObject()
            val fArr = JSONArray(); s.feature.forEach { fArr.put(it.toDouble()) }
            o.put("f", fArr); o.put("r", s.rows); o.put("c", s.cols); o.put("m", s.manual); o.put("t", s.timestamp)
            o.put("img", s.imagePath ?: "")
            s.position?.let { pArr -> val j = JSONArray(); pArr.forEach { j.put(it.toDouble()) }; o.put("pos", j) }
            arr.put(o)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun getClassDistribution(context: Context): Map<String, Int> =
        loadAll(context).groupBy { "${it.rows}x${it.cols}" }.mapValues { it.value.size }

    fun exportDataset(context: Context): String? {
        return try {
            val datasetDir = File(context.getExternalFilesDir(null), DATASET_DIR)
            if (!datasetDir.exists()) return null
            val csv = StringBuilder("filename,rows,cols,manual,timestamp\n")
            loadAll(context).forEach { seed ->
                seed.imagePath?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        csv.append("${file.name},${seed.rows},${seed.cols},${if (seed.manual) 1 else 0},${seed.timestamp}\n")
                    }
                }
            }
            File(datasetDir, "labels.csv").writeText(csv.toString())
            val zipFile = File(context.getExternalFilesDir(null), "ml_dataset_${System.currentTimeMillis()}.zip")
            java.util.zip.ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                datasetDir.walkTopDown().filter { it.isFile }.forEach { f ->
                    zos.putNextEntry(java.util.zip.ZipEntry(f.relativeTo(datasetDir).path))
                    f.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            zipFile.absolutePath
        } catch (e: Exception) { null }
    }

    fun clear(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply() }
    fun size(context: Context): Int = loadAll(context).size
}
