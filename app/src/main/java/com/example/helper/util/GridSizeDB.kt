package com.example.helper.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * v99: 판 크기별 좌표 및 셀 크기 학습
 */
object GridSizeDB {
    private const val PREFS = "grid_size_db"
    private const val KEY = "records"
    private const val MIN_SAMPLES = 2

    data class SizeRecord(
        val rows: Int,
        val cols: Int,
        var avgTLx: Float, var avgTLy: Float,
        var avgBRx: Float, var avgBRy: Float,
        var avgCellW: Float, var avgCellH: Float, // 🔥 v99 추가
        var count: Int,
        var timestamp: Long
    )

    fun addOrUpdate(
        context: Context,
        rows: Int, cols: Int,
        tlx: Float, tly: Float, brx: Float, bry: Float
    ) {
        val list = loadAll(context).toMutableList()
        val idx = list.indexOfFirst { it.rows == rows && it.cols == cols }

        val currentCellW = (brx - tlx) / cols
        val currentCellH = (bry - tly) / rows

        if (idx >= 0) {
            val old = list[idx]
            val newCount = old.count + 1
            val alpha = 0.3f // 최근 데이터 가중치
            
            list[idx] = SizeRecord(
                rows = rows, cols = cols,
                avgTLx = old.avgTLx * (1 - alpha) + tlx * alpha,
                avgTLy = old.avgTLy * (1 - alpha) + tly * alpha,
                avgBRx = old.avgBRx * (1 - alpha) + brx * alpha,
                avgBRy = old.avgBRy * (1 - alpha) + bry * alpha,
                avgCellW = if (old.avgCellW > 0) old.avgCellW * (1 - alpha) + currentCellW * alpha else currentCellW,
                avgCellH = if (old.avgCellH > 0) old.avgCellH * (1 - alpha) + currentCellH * alpha else currentCellH,
                count = newCount,
                timestamp = System.currentTimeMillis()
            )
            AppLogger.d("📏 ${rows}x${cols} EMA 업데이트 (${newCount}개) | 셀: ${list[idx].avgCellW.toInt()}x${list[idx].avgCellH.toInt()}")
        } else {
            list.add(SizeRecord(rows, cols, tlx, tly, brx, bry, currentCellW, currentCellH, 1, System.currentTimeMillis()))
            AppLogger.d("📏 ${rows}x${cols} 신규 저장 | 셀: ${currentCellW.toInt()}x${currentCellH.toInt()}")
        }

        save(context, list)
    }

    fun find(context: Context, rows: Int, cols: Int): SizeRecord? {
        val list = loadAll(context)
        val rec = list.firstOrNull { it.rows == rows && it.cols == cols } ?: return null
        if (rec.count < MIN_SAMPLES) return null
        return rec
    }

    fun loadAll(context: Context): List<SizeRecord> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = prefs.getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                SizeRecord(
                    o.getInt("r"), o.getInt("c"),
                    o.getDouble("tLx").toFloat(), o.getDouble("tLy").toFloat(),
                    o.getDouble("bRx").toFloat(), o.getDouble("bRy").toFloat(),
                    o.optDouble("cW", 0.0).toFloat(), o.optDouble("cH", 0.0).toFloat(),
                    o.getInt("n"), o.getLong("t")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun save(context: Context, list: List<SizeRecord>) {
        val arr = JSONArray()
        list.forEach { r ->
            val o = JSONObject()
            o.put("r", r.rows); o.put("c", r.cols)
            o.put("tLx", r.avgTLx.toDouble()); o.put("tLy", r.avgTLy.toDouble())
            o.put("bRx", r.avgBRx.toDouble()); o.put("bRy", r.avgBRy.toDouble())
            o.put("cW", r.avgCellW.toDouble()); o.put("cH", r.avgCellH.toDouble())
            o.put("n", r.count); o.put("t", r.timestamp)
            arr.put(o)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
