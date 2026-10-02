package com.example.helper.util

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject

object GridCalibrationDB {
    private const val PREFS = "grid_calibration_db"
    private const val KEY = "records"
    private const val MAX_RECORDS = 100
    private const val MATCH_THRESHOLD = 0.15f  // feature 유사도

    data class Record(
        val feature: FloatArray,   // v85: fingerprint 대신 feature
        val deltaTLx: Float, val deltaTLy: Float,
        val deltaBRx: Float, val deltaBRy: Float,
        val timestamp: Long
    )

    /** v85: fingerprint(feature) + 오프셋 저장 */
    fun addWithFeature(
        context: Context,
        feature: FloatArray,
        autoTLx: Float, autoTLy: Float, autoBRx: Float, autoBRy: Float,
        manualTLx: Float, manualTLy: Float, manualBRx: Float, manualBRy: Float
    ) {
        if (feature.size != GridFeature.DIM) return
        val list = loadAll(context).toMutableList()

        val newRec = Record(
            feature = feature.copyOf(),
            deltaTLx = manualTLx - autoTLx,
            deltaTLy = manualTLy - autoTLy,
            deltaBRx = manualBRx - autoBRx,
            deltaBRy = manualBRy - autoBRy,
            timestamp = System.currentTimeMillis()
        )

        // 기존 유사 feature 있으면 평균으로 업데이트
        val dupIdx = list.indexOfFirst {
            GridFeature.distance(it.feature, feature) < 0.05f
        }
        if (dupIdx >= 0) {
            val old = list[dupIdx]
            list[dupIdx] = Record(
                feature = feature.copyOf(),
                deltaTLx = (old.deltaTLx + newRec.deltaTLx) / 2f,
                deltaTLy = (old.deltaTLy + newRec.deltaTLy) / 2f,
                deltaBRx = (old.deltaBRx + newRec.deltaBRx) / 2f,
                deltaBRy = (old.deltaBRy + newRec.deltaBRy) / 2f,
                timestamp = System.currentTimeMillis()
            )
            AppLogger.d("📚 보정 평균 업데이트 (총 ${list.size}개): ΔTL=(${(list[dupIdx].deltaTLx).toInt()},${(list[dupIdx].deltaTLy).toInt()})")
        } else {
            list.add(newRec)
            AppLogger.d("📚 보정 신규 학습 (총 ${list.size}개): ΔTL=(${newRec.deltaTLx.toInt()},${newRec.deltaTLy.toInt()})")
        }

        val trimmed = list.sortedByDescending { it.timestamp }.take(MAX_RECORDS)
        save(context, trimmed)
    }

    /** v85: 현재 feature와 가장 유사한 학습된 오프셋 반환 */
    fun findOffset(context: Context, feature: FloatArray): FloatArray {
        val list = loadAll(context)
        if (list.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f)

        val best = list.map { it to GridFeature.distance(it.feature, feature) }
            .filter { it.second < MATCH_THRESHOLD }
            .minByOrNull { it.second } ?: return floatArrayOf(0f, 0f, 0f, 0f)

        val r = best.first
        AppLogger.d("📚 유사 판 감지 (거리=${"%.2f".format(best.second)}): ΔTL=(${r.deltaTLx.toInt()},${r.deltaTLy.toInt()})")
        return floatArrayOf(r.deltaTLx, r.deltaTLy, r.deltaBRx, r.deltaBRy)
    }

    fun loadAll(context: Context): List<Record> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = prefs.getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val fArr = o.getJSONArray("f")
                val f = FloatArray(fArr.length()) { fArr.getDouble(it).toFloat() }
                Record(
                    f,
                    o.getDouble("dTLx").toFloat(), o.getDouble("dTLy").toFloat(),
                    o.getDouble("dBRx").toFloat(), o.getDouble("dBRy").toFloat(),
                    o.getLong("t")
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun save(context: Context, list: List<Record>) {
        val arr = JSONArray()
        list.forEach { r ->
            val o = JSONObject()
            val fArr = JSONArray(); r.feature.forEach { fArr.put(it.toDouble()) }
            o.put("f", fArr)
            o.put("dTLx", r.deltaTLx.toDouble()); o.put("dTLy", r.deltaTLy.toDouble())
            o.put("dBRx", r.deltaBRx.toDouble()); o.put("dBRy", r.deltaBRy.toDouble())
            o.put("t", r.timestamp)
            arr.put(o)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun size(context: Context): Int = loadAll(context).size
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
