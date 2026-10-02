package com.example.helper.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object GridCalibrationDB {
    private const val PREFS = "grid_calibration_db"
    private const val KEY = "records"
    private const val MAX_RECORDS = 50

    data class Record(
        val inTLx: Float, val inTLy: Float, val inBRx: Float, val inBRy: Float,
        val outTLx: Float, val outTLy: Float, val outBRx: Float, val outBRy: Float,
        val timestamp: Long
    ) {
        val dTLx get() = outTLx - inTLx
        val dTLy get() = outTLy - inTLy
        val dBRx get() = outBRx - inBRx
        val dBRy get() = outBRy - inBRy
    }

    fun add(context: Context, record: Record) {
        val list = loadAll(context).toMutableList()
        list.add(record)
        val trimmed = list.sortedByDescending { it.timestamp }.take(MAX_RECORDS)
        save(context, trimmed)
    }

    fun loadAll(context: Context): List<Record> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = prefs.getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                Record(
                    o.getDouble("inTLx").toFloat(), o.getDouble("inTLy").toFloat(),
                    o.getDouble("inBRx").toFloat(), o.getDouble("inBRy").toFloat(),
                    o.getDouble("outTLx").toFloat(), o.getDouble("outTLy").toFloat(),
                    o.getDouble("outBRx").toFloat(), o.getDouble("outBRy").toFloat(),
                    o.getLong("t")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun save(context: Context, list: List<Record>) {
        val arr = JSONArray()
        list.forEach { r ->
            val o = JSONObject()
            o.put("inTLx", r.inTLx.toDouble()); o.put("inTLy", r.inTLy.toDouble())
            o.put("inBRx", r.inBRx.toDouble()); o.put("inBRy", r.inBRy.toDouble())
            o.put("outTLx", r.outTLx.toDouble()); o.put("outTLy", r.outTLy.toDouble())
            o.put("outBRx", r.outBRx.toDouble()); o.put("outBRy", r.outBRy.toDouble())
            o.put("t", r.timestamp)
            arr.put(o)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    /** 평균 오프셋 반환 (학습된 값) */
    fun getAverageOffset(context: Context): FloatArray {
        val list = loadAll(context)
        if (list.size < 2) return floatArrayOf(0f, 0f, 0f, 0f)  // 최소 2개 필요

        val dTLx = list.map { it.dTLx }.average().toFloat()
        val dTLy = list.map { it.dTLy }.average().toFloat()
        val dBRx = list.map { it.dBRx }.average().toFloat()
        val dBRy = list.map { it.dBRy }.average().toFloat()
        return floatArrayOf(dTLx, dTLy, dBRx, dBRy)
    }

    fun size(context: Context): Int = loadAll(context).size
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
