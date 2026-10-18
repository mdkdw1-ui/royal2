#!/usr/bin/env python3
import os, shutil, re

BASE = os.path.expanduser("~/royal2")
DB   = f"{BASE}/app/src/main/java/com/example/helper/util/GridSizeDB.kt"
SVC  = f"{BASE}/app/src/main/java/com/example/helper/service/SolverService.kt"

def backup(path):
    bak = path + ".v99.bak"
    if not os.path.exists(bak):
        shutil.copy2(path, bak)
        print(f"  backup: {os.path.basename(bak)}")

# ============================================================
# 1. GridSizeDB.kt 전면 재작성 (셀 크기 저장 추가)
# ============================================================
NEW_DB = '''package com.example.helper.util

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
'''
backup(DB)
with open(DB, "w", encoding="utf-8") as f:
    f.write(NEW_DB)
print("  ✅ GridSizeDB.kt 재작성 완료 (셀 크기 저장 추가)")

# ============================================================
# 2. SolverService.kt - autoDetectBoard 로직 수정
# ============================================================
with open(SVC, encoding="utf-8") as f:
    s = f.read()

OLD_BLOCK = '''        val points = bestContour.toArray()
        val sortedPoints = sortCorners(points)
        ptTL.set(sortedPoints[0].x.toFloat(), sortedPoints[0].y.toFloat())
        ptTR.set(sortedPoints[1].x.toFloat(), sortedPoints[1].y.toFloat())
        ptBL.set(sortedPoints[2].x.toFloat(), sortedPoints[2].y.toFloat())
        ptBR.set(sortedPoints[3].x.toFloat(), sortedPoints[3].y.toFloat())

        // 🔥 격자선 프로젝션 방식으로 크기 검출
        val detected = detectGridDimensions(bitmap)'''

NEW_BLOCK = '''        val points = bestContour.toArray()
        val sortedPoints = sortCorners(points)
        ptTL.set(sortedPoints[0].x.toFloat(), sortedPoints[0].y.toFloat())
        ptTR.set(sortedPoints[1].x.toFloat(), sortedPoints[1].y.toFloat())
        ptBL.set(sortedPoints[2].x.toFloat(), sortedPoints[2].y.toFloat())
        ptBR.set(sortedPoints[3].x.toFloat(), sortedPoints[3].y.toFloat())

        // 🔥 v99: 학습된 셀 크기를 기반으로 격자 강제 정렬 (중심점 기준)
        val sizeRec = GridSizeDB.find(applicationContext, rows, cols)
        if (sizeRec != null && sizeRec.avgCellW > 0 && sizeRec.avgCellH > 0) {
            val targetW = sizeRec.avgCellW * cols
            val targetH = sizeRec.avgCellH * rows
            
            val curW = ptBR.x - ptTL.x
            val curH = ptBL.y - ptTL.y
            
            // 셀 크기 오차가 3% 이상 발생하면 학습된 크기로 강제 조정
            if (Math.abs(curW - targetW) > targetW * 0.03f || Math.abs(curH - targetH) > targetH * 0.03f) {
                val cx = (ptTL.x + ptBR.x) / 2f
                val cy = (ptTL.y + ptBL.y) / 2f
                
                ptTL.set(cx - targetW / 2f, cy - targetH / 2f)
                ptTR.set(cx + targetW / 2f, cy - targetH / 2f)
                ptBL.set(cx - targetW / 2f, cy + targetH / 2f)
                ptBR.set(cx + targetW / 2f, cy + targetH / 2f)
                
                AppLogger.d("🔧 셀 크기 기반 강제 정렬: ${curW.toInt()}x${curH.toInt()} -> ${targetW.toInt()}x${targetH.toInt()}")
            }
        }

        // 🔥 격자선 프로젝션 방식으로 크기 검출
        val detected = detectGridDimensions(bitmap)'''

if OLD_BLOCK in s:
    s = s.replace(OLD_BLOCK, NEW_BLOCK, 1)
    print("  ✅ SolverService.kt - autoDetectBoard 셀 크기 정렬 로직 삽입 완료")
else:
    print("  ❌ SolverService.kt - 앵커 블록을 찾지 못했습니다. (이미 수정되었거나 코드가 다름)")

backup(SVC)
with open(SVC, "w", encoding="utf-8") as f:
    f.write(s)

print("[DONE] patch_v99.py")
