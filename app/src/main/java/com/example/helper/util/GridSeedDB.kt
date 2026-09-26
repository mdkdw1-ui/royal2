package com.example.helper.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object GridSeedDB {
    private const val PREFS = "grid_seed_db"
    private const val KEY = "seeds"
    private const val MAX_SEEDS = 500

    data class Seed(
        val feature: FloatArray,
        val rows: Int,
        val cols: Int,
        val manual: Boolean,
        val timestamp: Long
    )

    fun add(context: Context, feature: FloatArray, rows: Int, cols: Int, manual: Boolean) {
        if (feature.size != GridFeature.DIM) return
        val list = loadAll(context).toMutableList()

        // 중복 제거: 매우 가까운 기존 seed 있으면 대체
        val dupIdx = list.indexOfFirst { GridFeature.distance(it.feature, feature) < 0.05f }
        if (dupIdx >= 0) {
            val old = list[dupIdx]
            // manual seed는 자동 seed보다 우선
            if (manual || !old.manual || old.rows != rows || old.cols != cols) {
                list[dupIdx] = Seed(feature, rows, cols, manual, System.currentTimeMillis())
            }
        } else {
            list.add(Seed(feature, rows, cols, manual, System.currentTimeMillis()))
        }

        val trimmed = list.sortedByDescending { it.timestamp }.take(MAX_SEEDS)
        save(context, trimmed)
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
                Seed(f, o.getInt("r"), o.getInt("c"), o.getBoolean("m"), o.getLong("t"))
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun save(context: Context, list: List<Seed>) {
        val arr = JSONArray()
        list.forEach { s ->
            val o = JSONObject()
            val fArr = JSONArray()
            s.feature.forEach { fArr.put(it.toDouble()) }
            o.put("f", fArr)
            o.put("r", s.rows)
            o.put("c", s.cols)
            o.put("m", s.manual)
            o.put("t", s.timestamp)
            arr.put(o)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun size(context: Context): Int = loadAll(context).size
}
