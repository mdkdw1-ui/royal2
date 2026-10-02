package com.example.helper.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * v93: 판 크기별 좌표 학습
 * - 판 크기(11x9, 10x9 등)를 키로 사용
 * - 각 크기별 평균 좌표 + 개수 저장
 * - 3개 이상 쌓이면 자동 적용
 */
object GridSizeDB {
    private const val PREFS = "grid_size_db"
    private const val KEY = "records"
    private const val MIN_SAMPLES = 3  // 최소 3개 필요

    data class SizeRecord(
        val rows: Int,
        val cols: Int,
        var avgTLx: Float, var avgTLy: Float,
        var avgBRx: Float, var avgBRy: Float,
        var count: Int,
        var timestamp: Long
    )

    /** 사용자 조정 좌표를 크기별로 저장 (평균 업데이트) */
    fun addOrUpdate(
        context: Context,
        rows: Int, cols: Int,
        tlx: Float, tly: Float, brx: Float, bry: Float
    ) {
        val list = loadAll(context).toMutableList()
        val key = "${rows}x${cols}"
        val idx = list.indexOfFirst { it.rows == rows && it.cols == cols }

        if (idx >= 0) {
            val old = list[idx]
            val newCount = old.count + 1
            list[idx] = SizeRecord(
                rows = rows, cols = cols,
                avgTLx = (old.avgTLx * old.count + tlx) / newCount,
                avgTLy = (old.avgTLy * old.count + tly) / newCount,
                avgBRx = (old.avgBRx * old.count + brx) / newCount,
                avgBRy = (old.avgBRy * old.count + bry) / newCount,
                count = newCount,
                timestamp = System.currentTimeMillis()
            )
            AppLogger.d("📏 ${key} 평균 업데이트 (${newCount}개): tl=(${(old.avgTLx).toInt()},${(old.avgTLy).toInt()}) br=(${(old.avgBRx).toInt()},${(old.avgBRy).toInt()})")
        } else {
            list.add(SizeRecord(rows, cols, tlx, tly, brx, bry, 1, System.currentTimeMillis()))
            AppLogger.d("📏 ${key} 신규 저장: tl=(${tlx.toInt()},${tly.toInt()}) br=(${brx.toInt()},${bry.toInt()})")
        }

        save(context, list)
    }

    /** 특정 크기의 평균 좌표 반환 (3개 이상일 때만) */
    fun find(context: Context, rows: Int, cols: Int): SizeRecord? {
        val list = loadAll(context)
        val rec = list.firstOrNull { it.rows == rows && it.cols == cols } ?: return null
        if (rec.count < MIN_SAMPLES) {
            AppLogger.d("📏 ${rows}x${cols}: 데이터 부족 (${rec.count}/${MIN_SAMPLES})")
            return null
        }
        AppLogger.d("📏 ${rows}x${cols} 평균 사용 (${rec.count}개): tl=(${rec.avgTLx.toInt()},${rec.avgTLy.toInt()}) br=(${rec.avgBRx.toInt()},${rec.avgBRy.toInt()})")
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
