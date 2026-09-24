package com.example.helper.util

import android.content.Context
import android.graphics.Bitmap

/**
 * 화면 지문(fingerprint)으로 격자 크기를 기억.
 * 같은 레벨(화면)이 다시 나오면 저장된 rows/cols를 복원.
 */
object LevelGridMemory {
    private const val PREFS = "level_grid_memory"
    private const val MAX_ENTRIES = 100
    private const val MATCH_THRESHOLD = 35  // hamming distance (256bit 중)

    data class GridEntry(
        val hash: String,
        val rows: Int,
        val cols: Int,
        val timestamp: Long,
        val manual: Boolean
    )

    /** 16x16 다운스케일 → 평균 해시(256bit) → 64자 hex */
    fun computeFingerprint(bitmap: Bitmap): String {
        val small = Bitmap.createScaledBitmap(bitmap, 16, 16, true)
        val pixels = IntArray(256)
        small.getPixels(pixels, 0, 16, 0, 0, 16, 16)
        small.recycle()

        val gray = IntArray(256) { i ->
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            (r * 299 + g * 587 + b * 114) / 1000
        }
        val avg = gray.average().toInt()
        val sb = StringBuilder()
        for (i in 0 until 256 step 4) {
            var nibble = 0
            for (j in 0 until 4) {
                if (i + j < 256 && gray[i + j] >= avg) {
                    nibble = nibble or (1 shl (3 - j))
                }
            }
            sb.append(Integer.toHexString(nibble))
        }
        return sb.toString()
    }

    private fun hamming(a: String, b: String): Int {
        if (a.length != b.length) return 999
        var d = 0
        for (i in a.indices) {
            val x = Character.digit(a[i], 16) xor Character.digit(b[i], 16)
            d += Integer.bitCount(x)
        }
        return d
    }

    fun save(context: Context, hash: String, rows: Int, cols: Int, manual: Boolean) {
        if (hash.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = loadAll(prefs)

        // 매칭되는 기존 항목 찾기
        val matched = existing.firstOrNull { hamming(it.hash, hash) <= MATCH_THRESHOLD }

        val newList = mutableListOf<GridEntry>()
        newList.add(GridEntry(hash, rows, cols, System.currentTimeMillis(), manual))
        newList.addAll(existing.filter { it.hash != matched?.hash })
        val trimmed = newList.take(MAX_ENTRIES)

        val sb = StringBuilder()
        trimmed.forEach { e ->
            sb.append("${e.hash}|${e.rows}|${e.cols}|${e.timestamp}|${if (e.manual) 1 else 0}\n")
        }
        prefs.edit().putString("entries", sb.toString()).apply()
    }

    fun find(context: Context, hash: String): GridEntry? {
        if (hash.isEmpty()) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = loadAll(prefs)
        return all
            .filter { hamming(it.hash, hash) <= MATCH_THRESHOLD }
            .minByOrNull { hamming(it.hash, hash) }
    }

    fun getAll(context: Context): List<GridEntry> {
        return loadAll(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun loadAll(prefs: android.content.SharedPreferences): List<GridEntry> {
        val text = prefs.getString("entries", "") ?: return emptyList()
        if (text.isBlank()) return emptyList()
        return text.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size != 5) return@mapNotNull null
            try {
                GridEntry(
                    parts[0],
                    parts[1].toInt(),
                    parts[2].toInt(),
                    parts[3].toLong(),
                    parts[4] == "1"
                )
            } catch (e: Exception) { null }
        }
    }
}
