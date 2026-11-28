package com.example.helper.core

import android.graphics.Bitmap
import android.graphics.PointF
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

object RemoteSolverClient {

    fun uploadGem(serverUrl: String, color: Int, pitchRatio: Float, pngBytes: ByteArray): Boolean {
        val base = serverUrl.trimEnd('/')
        if (base.isEmpty()) return false
        val boundary = "----Royal2Boundary${System.currentTimeMillis()}"
        val conn = (URL("$base/upload_gem").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 3000
            readTimeout = 4000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        return try {
            DataOutputStream(conn.outputStream).use { out ->
                writeField(out, boundary, "color", color.toString())
                writeField(out, boundary, "pitch_ratio", pitchRatio.toString())
                writeFile(out, boundary, "file", "gem_$color.png", "image/png", pngBytes)
                out.writeBytes("--$boundary--\r\n")
            }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    fun clearGems(serverUrl: String): Boolean {
        val base = serverUrl.trimEnd('/')
        if (base.isEmpty()) return false
        val conn = (URL("$base/clear_gems").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 3000
            readTimeout = 3000
        }
        return try {
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    fun analyze(serverUrl: String, fullBitmap: Bitmap): EngineResult? {
        val base = serverUrl.trimEnd('/')
        if (base.isEmpty()) return null

        val fullW = fullBitmap.width
        val fullH = fullBitmap.height
        val roiTop = (fullH * 0.14f).toInt().coerceIn(0, fullH - 50)
        val roiBot = (fullH * 0.84f).toInt().coerceIn(roiTop + 50, fullH)
        val roiH = roiBot - roiTop

        val workW = 480
        val workH = (roiH * (workW.toFloat() / fullW)).roundToInt().coerceAtLeast(80)

        val roiBmp = Bitmap.createBitmap(fullBitmap, 0, roiTop, fullW, roiH)
        val workBmp = Bitmap.createScaledBitmap(roiBmp, workW, workH, true)
        roiBmp.recycle()

        val jpegBytes = ByteArrayOutputStream().use { bos ->
            workBmp.compress(Bitmap.CompressFormat.JPEG, 82, bos)
            workBmp.recycle()
            bos.toByteArray()
        }

        val boundary = "----Royal2Analyze${System.currentTimeMillis()}"
        val conn = (URL("$base/analyze").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 2000
            readTimeout = 3000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        return try {
            DataOutputStream(conn.outputStream).use { out ->
                writeField(out, boundary, "screen_w", fullW.toString())
                writeField(out, boundary, "screen_h", fullH.toString())
                writeField(out, boundary, "roi_top", roiTop.toString())
                writeFile(out, boundary, "file", "frame.jpg", "image/jpeg", jpegBytes)
                out.writeBytes("--$boundary--\r\n")
            }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            parseResult(body)
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun parseResult(body: String): EngineResult {
        val json = JSONObject(body)
        val moving = json.optBoolean("moving", false)
        val rows = json.optInt("rows", 0)
        val cols = json.optInt("cols", 0)
        val cellW = json.optDouble("cellW", 0.0).toFloat()
        val cellH = json.optDouble("cellH", 0.0).toFloat()

        val gemsArr = json.optJSONArray("gems")
        val gems = mutableListOf<DetectedGem>()
        if (gemsArr != null) {
            for (i in 0 until gemsArr.length()) {
                val g = gemsArr.getJSONObject(i)
                gems.add(
                    DetectedGem(
                        x = g.getDouble("x").toFloat(),
                        y = g.getDouble("y").toFloat(),
                        color = g.getInt("color"),
                        movable = g.getBoolean("movable")
                    )
                )
            }
        }

        val movesArr = json.optJSONArray("moves")
        val moves = mutableListOf<FiveMatchMove>()
        if (movesArr != null) {
            for (i in 0 until movesArr.length()) {
                val m = movesArr.getJSONObject(i)
                val lineJson = m.optJSONArray("lineCoords")
                val linePts = mutableListOf<PointF>()
                if (lineJson != null) {
                    for (j in 0 until lineJson.length()) {
                        val pt = lineJson.getJSONArray(j)
                        linePts.add(PointF(pt.getDouble(0).toFloat(), pt.getDouble(1).toFloat()))
                    }
                }
                moves.add(
                    FiveMatchMove(
                        targetX = m.getDouble("targetX").toFloat(),
                        targetY = m.getDouble("targetY").toFloat(),
                        sourceX = m.getDouble("sourceX").toFloat(),
                        sourceY = m.getDouble("sourceY").toFloat(),
                        color = m.getInt("color"),
                        lineCoords = linePts
                    )
                )
            }
        }
        return EngineResult(moving, rows, cols, cellW, cellH, gems, moves)
    }

    private fun writeField(out: DataOutputStream, boundary: String, name: String, value: String) {
        out.writeBytes("--$boundary\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        out.writeBytes("$value\r\n")
    }

    private fun writeFile(out: DataOutputStream, boundary: String, name: String, filename: String, mime: String, data: ByteArray) {
        out.writeBytes("--$boundary\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\n")
        out.writeBytes("Content-Type: $mime\r\n\r\n")
        out.write(data)
        out.writeBytes("\r\n")
    }
}
