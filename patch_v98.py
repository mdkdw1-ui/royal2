#!/usr/bin/env python3
"""royal2 v98: 새판 자동격자 / 5매치 화살표 / 기믹 판정 개선 / 데이터 축적"""
import os, shutil

BASE = os.path.expanduser("~/royal2")
SVC  = f"{BASE}/app/src/main/java/com/example/helper/service/SolverService.kt"
DB   = f"{BASE}/app/src/main/java/com/example/helper/util/GridSizeDB.kt"

def backup(path):
    bak = path + ".v98.bak"
    if not os.path.exists(bak):
        shutil.copy2(path, bak)
        print(f"  backup: {os.path.basename(bak)}")

def replace_func(s, marker, new_body):
    """marker로 함수 시작 찾고 중괄호 밸런싱으로 끝까지 교체."""
    start = s.find(marker)
    if start == -1: return s, False
    brace = s.find("{", start)
    if brace == -1: return s, False
    depth = 0
    i = brace
    while i < len(s):
        if s[i] == '{': depth += 1
        elif s[i] == '}':
            depth -= 1
            if depth == 0:
                return s[:start] + new_body + s[i+1:], True
        i += 1
    return s, False


# ============================================================
# SolverService.kt
# ============================================================
with open(SVC, encoding="utf-8") as f:
    s = f.read()
ok, fail = [], []

# --- P1: MatchTarget data class 추가 ---
if "data class MatchTarget" not in s:
    old = 'private data class GimmickItem(\n    val file: File,\n    val index: Int\n)\n\nclass SolverService'
    new = ('private data class GimmickItem(\n'
           '    val file: File,\n'
           '    val index: Int\n'
           ')\n\n'
           '// 🔥 v98: 5매치 이동 쌍 (source → target)\n'
           'data class MatchTarget(\n'
           '    val targetRow: Int, val targetCol: Int,\n'
           '    val sourceRow: Int, val sourceCol: Int,\n'
           '    val color: Int\n'
           ')\n\n'
           'class SolverService')
    if old in s:
        s = s.replace(old, new, 1); ok.append("P1_MatchTarget")
    else:
        fail.append("P1_MatchTarget")

# --- P2: foundPositions 타입 변경 ---
if "private var foundPositions = mutableListOf<Pair<Int, Int>>()" in s:
    s = s.replace(
        "private var foundPositions = mutableListOf<Pair<Int, Int>>()",
        "private var foundPositions = mutableListOf<MatchTarget>()", 1)
    ok.append("P2_foundPositions")

# --- P3: findOOXOO 교체 (MatchTarget 반환) ---
NEW_FIND = '''private fun findOOXOO(bitmap: Bitmap): List<MatchTarget> {
        val width = bitmap.width; val height = bitmap.height
        val pixels = IntArray(width * height); bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val safeRows = rows.coerceIn(3, 20)
        val safeCols = cols.coerceIn(3, 20)
        if (safeRows != rows || safeCols != cols) {
            AppLogger.d("findOOXOO: rows/cols 조정 ${rows}x${cols} → ${safeRows}x${safeCols}")
            rows = safeRows
            cols = safeCols
        }
        val colorGrid = Array(rows) { IntArray(cols) }

        for (r in 0 until rows) for (c in 0 until cols) {
            val u = (c + 0.5f) / cols; val v = (r + 0.5f) / rows
            val topX = (1 - u) * ptTL.x + u * ptTR.x
            val topY = (1 - u) * ptTL.y + u * ptTR.y
            val bottomX = (1 - u) * ptBL.x + u * ptBR.x
            val bottomY = (1 - u) * ptBL.y + u * ptBR.y
            val cx = (1 - v) * topX + v * bottomX
            val cy = (1 - v) * topY + v * bottomY
            val ix = cx.toInt().coerceIn(0, width - 1); val iy = cy.toInt().coerceIn(0, height - 1)
            val pixel = pixels[iy * width + ix]

            if (isOpenCVInitialized && dynamicTemplates.isNotEmpty()) {
                val cellW = (abs(ptTR.x - ptTL.x) / cols).toInt().coerceAtLeast(20)
                val cellH = (abs(ptBL.y - ptTL.y) / rows).toInt().coerceAtLeast(20)
                val cropSize = minOf(cellW, cellH)
                val half = cropSize / 2
                val startX = (cx - half).toInt().coerceIn(0, width - cropSize)
                val startY = (cy - half).toInt().coerceIn(0, height - cropSize)
                val realSize = minOf(cropSize, width - startX, height - startY).coerceAtLeast(1)
                try {
                    val cellBitmap = Bitmap.createBitmap(bitmap, startX, startY, realSize, realSize)
                    val resizedCell = Bitmap.createScaledBitmap(cellBitmap, 64, 64, true)
                    if (isGimmick(resizedCell)) {
                        colorGrid[r][c] = -1
                        resizedCell.recycle(); cellBitmap.recycle()
                        continue
                    }
                    resizedCell.recycle(); cellBitmap.recycle()
                } catch (e: Exception) {}
            }

            val hsv = FloatArray(3); Color.colorToHSV(pixel, hsv)
            val hue = hsv[0]; val sat = hsv[1]
            colorGrid[r][c] = when {
                sat < 0.2f -> -1
                hue in 0f..30f -> 1
                hue in 31f..70f -> 2
                hue in 71f..160f -> 3
                hue in 161f..230f -> 4
                hue in 231f..360f -> 5
                else -> -1
            }
        }

        val result = mutableListOf<MatchTarget>()

        // 🔥 v98: 가로 OOXOO → (타겟 X, 소스 O) 쌍
        if (cols >= 5) {
            for (r in 0 until rows) for (c in 0 until cols - 4) {
                val color = colorGrid[r][c]
                if (color == -1) continue
                if (colorGrid[r][c] == color &&
                    colorGrid[r][c + 1] == color &&
                    colorGrid[r][c + 2] != color &&
                    colorGrid[r][c + 3] == color &&
                    colorGrid[r][c + 4] == color) {
                    val xCol = c + 2
                    if (r > 0 && colorGrid[r - 1][xCol] == color)
                        result.add(MatchTarget(r, xCol, r - 1, xCol, color))
                    if (r < rows - 1 && colorGrid[r + 1][xCol] == color)
                        result.add(MatchTarget(r, xCol, r + 1, xCol, color))
                }
            }
        }

        // 🔥 v98: 세로 OOXOO
        if (rows >= 5) {
            for (c in 0 until cols) for (r in 0 until rows - 4) {
                val color = colorGrid[r][c]
                if (color == -1) continue
                if (colorGrid[r][c] == color &&
                    colorGrid[r + 1][c] == color &&
                    colorGrid[r + 2][c] != color &&
                    colorGrid[r + 3][c] == color &&
                    colorGrid[r + 4][c] == color) {
                    val xRow = r + 2
                    if (c > 0 && colorGrid[xRow][c - 1] == color)
                        result.add(MatchTarget(xRow, c, xRow, c - 1, color))
                    if (c < cols - 1 && colorGrid[xRow][c + 1] == color)
                        result.add(MatchTarget(xRow, c, xRow, c + 1, color))
                }
            }
        }

        return result.distinctBy { "${it.targetRow},${it.targetCol},${it.sourceRow},${it.sourceCol}" }
    }'''
if "private fun findOOXOO(bitmap: Bitmap): List<Pair<Int, Int>>" in s:
    s, r = replace_func(s, "private fun findOOXOO(bitmap: Bitmap): List<Pair<Int, Int>> {", NEW_FIND)
    if r: ok.append("P3_findOOXOO")
    else: fail.append("P3_findOOXOO")

# --- P4: isGimmick 교체 (멀티스케일 + edge 매칭) ---
NEW_GIMMICK = '''private fun isGimmick(cellBitmap: Bitmap): Boolean {
        if (!isOpenCVInitialized || dynamicTemplates.isEmpty()) return false

        val cellMat = Mat()
        Utils.bitmapToMat(cellBitmap, cellMat)
        Imgproc.cvtColor(cellMat, cellMat, Imgproc.COLOR_RGBA2GRAY)

        val cellEdges = Mat()
        Imgproc.Canny(cellMat, cellEdges, 50.0, 150.0)

        var bestScore = 0.0
        val resultMat = Mat()

        synchronized(dynamicTemplates) {
            for (template in dynamicTemplates) {
                for (scale in listOf(0.75, 0.9, 1.0, 1.1, 1.25)) {
                    val tw = (template.cols() * scale).toInt()
                    val th = (template.rows() * scale).toInt()
                    if (tw < 8 || th < 8) continue
                    if (cellMat.cols() < tw || cellMat.rows() < th) continue

                    val resized = Mat()
                    Imgproc.resize(template, resized, Size(tw.toDouble(), th.toDouble()))

                    Imgproc.matchTemplate(cellMat, resized, resultMat, Imgproc.TM_CCOEFF_NORMED)
                    val grayScore = Core.minMaxLoc(resultMat).maxVal
                    if (grayScore > bestScore) bestScore = grayScore

                    val resizedEdges = Mat()
                    Imgproc.Canny(resized, resizedEdges, 50.0, 150.0)
                    if (resizedEdges.cols() > 0 && resizedEdges.rows() > 0 &&
                        cellEdges.cols() >= resizedEdges.cols() && cellEdges.rows() >= resizedEdges.rows()) {
                        Imgproc.matchTemplate(cellEdges, resizedEdges, resultMat, Imgproc.TM_CCOEFF_NORMED)
                        val edgeScore = Core.minMaxLoc(resultMat).maxVal
                        if (edgeScore > bestScore) bestScore = edgeScore
                    }
                    resized.release(); resizedEdges.release()

                    if (bestScore >= 0.80) break
                }
                if (bestScore >= 0.80) break
            }
        }

        cellMat.release(); cellEdges.release(); resultMat.release()

        return bestScore >= 0.62
    }'''
if "private fun isGimmick(cellBitmap: Bitmap): Boolean" in s and "bestScore" not in s:
    s, r = replace_func(s, "private fun isGimmick(cellBitmap: Bitmap): Boolean {", NEW_GIMMICK)
    if r: ok.append("P4_isGimmick")
    else: fail.append("P4_isGimmick")

# --- P5: OverlayView 필드/시그니처 변경 ---
if "private var positions = listOf<Pair<Int, Int>>()" in s:
    s = s.replace("private var positions = listOf<Pair<Int, Int>>()",
                  "private var positions = listOf<MatchTarget>()", 1)
    ok.append("P5_field")

if "fun updatePositions(newPositions: List<Pair<Int, Int>>)" in s:
    s = s.replace("fun updatePositions(newPositions: List<Pair<Int, Int>>)",
                  "fun updatePositions(newPositions: List<MatchTarget>)", 1)
    ok.append("P5_sig")

# --- P6: 오버레이 페인트 추가 ---
PAINT_OLD = "        private val cornerNamePaint = Paint().apply { color = Color.WHITE; textSize = 18f; textAlign = Paint.Align.CENTER }"
PAINT_NEW = PAINT_OLD + '''
        // 🔥 v98: 5매치 화살표 페인트
        private val targetPaint = Paint().apply {
            color = Color.parseColor("#FFFF00"); style = Paint.Style.FILL; alpha = 200
        }
        private val targetStrokePaint = Paint().apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f
        }
        private val sourcePaint = Paint().apply {
            color = Color.parseColor("#00E5FF"); style = Paint.Style.FILL; alpha = 220
        }
        private val sourceStrokePaint = Paint().apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f
        }
        private val arrowPaint = Paint().apply {
            color = Color.parseColor("#00FF88"); style = Paint.Style.STROKE
            strokeWidth = 8f; strokeCap = Paint.Cap.ROUND; isAntiAlias = true
        }
        private val arrowHeadPaint = Paint().apply {
            color = Color.parseColor("#00FF88"); style = Paint.Style.FILL; isAntiAlias = true
        }'''
if "arrowHeadPaint" not in s and PAINT_OLD in s:
    s = s.replace(PAINT_OLD, PAINT_NEW, 1)
    ok.append("P6_paints")

# --- P7: onDraw 화살표 루프 교체 ---
LOOP_OLD = '''            for ((row, col) in positions) {
                val u = (col + 0.5f) / cols
                val v = (row + 0.5f) / rows
                val topX = (1 - u) * ptTL.x + u * ptTR.x
                val topY = (1 - u) * ptTL.y + u * ptTR.y
                val bottomX = (1 - u) * ptBL.x + u * ptBR.x
                val bottomY = (1 - u) * ptBL.y + u * ptBR.y
                val cx = (1 - v) * topX + v * bottomX
                val cy = (1 - v) * topY + v * bottomY
                canvas.drawCircle(cx, cy, 40f, circlePaint)
                canvas.drawCircle(cx, cy, 40f, borderPaint)
                canvas.drawText("X", cx, cy + 10f, textPaint)
            }'''
LOOP_NEW = '''            // 🔥 v98: 5매치 화살표 (source O → target X)
            for (t in positions) {
                fun cellCenter(rr: Int, cc: Int): Pair<Float, Float> {
                    val u = (cc + 0.5f) / cols; val v = (rr + 0.5f) / rows
                    val tX = (1 - u) * ptTL.x + u * ptTR.x
                    val tY = (1 - u) * ptTL.y + u * ptTR.y
                    val bX = (1 - u) * ptBL.x + u * ptBR.x
                    val bY = (1 - u) * ptBL.y + u * ptBR.y
                    return Pair((1 - v) * tX + v * bX, (1 - v) * tY + v * bY)
                }
                val (sx, sy) = cellCenter(t.sourceRow, t.sourceCol)
                val (tx, ty) = cellCenter(t.targetRow, t.targetCol)

                // source O (시안)
                canvas.drawCircle(sx, sy, 45f, sourcePaint)
                canvas.drawCircle(sx, sy, 45f, sourceStrokePaint)
                canvas.drawText("O", sx, sy + 12f, textPaint)

                // arrow: source → target
                canvas.drawLine(sx, sy, tx, ty, arrowPaint)
                val ang = Math.atan2((ty - sy).toDouble(), (tx - sx).toDouble())
                val aLen = 30f; val aAng = Math.PI / 6
                val path = android.graphics.Path().apply {
                    moveTo(tx, ty)
                    lineTo((tx - aLen * Math.cos(ang - aAng)).toFloat(), (ty - aLen * Math.sin(ang - aAng)).toFloat())
                    lineTo((tx - aLen * Math.cos(ang + aAng)).toFloat(), (ty - aLen * Math.sin(ang + aAng)).toFloat())
                    close()
                }
                canvas.drawPath(path, arrowHeadPaint)

                // target X (노랑)
                canvas.drawCircle(tx, ty, 50f, targetPaint)
                canvas.drawCircle(tx, ty, 50f, targetStrokePaint)
                canvas.drawText("!", tx, ty + 14f, textPaint)
            }'''
if LOOP_OLD in s:
    s = s.replace(LOOP_OLD, LOOP_NEW, 1)
    ok.append("P7_onDraw")

# --- P8: NEW_GAME_AUTO 블록 교체 (manual seed 무관) ---
OLD_NEWGAME = '''                // 🔥 v55: 자동 판바뀜 감지 - manual seed가 있으면 스킵
                val hasManualSeed = GridSeedDB.loadAll(applicationContext).any { it.manual }
                if (!hasManualSeed && lastFingerprintForGameChange.isNotEmpty() && currentFingerprint.isNotEmpty()) {
                    var diff = 0
                    val len = minOf(lastFingerprintForGameChange.length, currentFingerprint.length)
                    for (i in 0 until len) {
                        if (lastFingerprintForGameChange[i] != currentFingerprint[i]) diff++
                    }
                    if (diff > len * 0.3 && !isAutoDetectEnabled && !isCalibrationMode) {
                        AppLogger.d("NEW_GAME_AUTO: 판 바뀜 (diff=$diff/$len) → auto 재개")
                        isAutoDetectEnabled = true
                        savePreferences()
                        mainHandler.post { refreshControlUI() }
                    }
                }
                lastFingerprintForGameChange = currentFingerprint'''
NEW_NEWGAME = '''                // 🔥 v98: 새 판 자동 감지 (manual seed 있어도 실행)
                if (lastFingerprintForGameChange.isNotEmpty() && currentFingerprint.isNotEmpty()) {
                    var diff = 0
                    val len = minOf(lastFingerprintForGameChange.length, currentFingerprint.length)
                    for (i in 0 until len) {
                        if (lastFingerprintForGameChange[i] != currentFingerprint[i]) diff++
                    }
                    if (diff > len * 0.25 && !isCalibrationMode) {
                        AppLogger.d("NEW_GAME: diff=$diff/$len → 자동 격자 재적용")
                        isAutoDetectEnabled = true
                        // 학습된 크기별 코너 즉시 복원
                        val sizeRec = GridSizeDB.find(applicationContext, rows, cols)
                        if (sizeRec != null) {
                            ptTL.set(sizeRec.avgTLx, sizeRec.avgTLy)
                            ptTR.set(sizeRec.avgBRx, sizeRec.avgTLy)
                            ptBL.set(sizeRec.avgTLx, sizeRec.avgBRy)
                            ptBR.set(sizeRec.avgBRx, sizeRec.avgBRy)
                            AppLogger.d("📏 ${rows}x${cols} 학습코너 적용 (${sizeRec.count}개 평균)")
                        }
                        savePreferences()
                        mainHandler.post { refreshControlUI() }
                    }
                }
                lastFingerprintForGameChange = currentFingerprint'''
if OLD_NEWGAME in s:
    s = s.replace(OLD_NEWGAME, NEW_NEWGAME, 1)
    ok.append("P8_new_game")
else:
    fail.append("P8_new_game")

# --- P9: autoChanged 시 GridSizeDB 자동 학습 ---
AUTO_OLD = '''                val autoRows = rows
                val autoCols = cols
                val autoChanged = (beforeRows != autoRows || beforeCols != autoCols)'''
AUTO_NEW = AUTO_OLD + '''

                // 🔥 v98: 크기 변경 시 자동 학습 (데이터 축적)
                if (autoChanged) {
                    try {
                        GridSizeDB.addOrUpdate(
                            applicationContext, rows, cols,
                            ptTL.x, ptTL.y, ptBR.x, ptBR.y
                        )
                    } catch (e: Exception) { }
                }'''
if AUTO_OLD in s and "크기 변경 시 자동 학습" not in s:
    s = s.replace(AUTO_OLD, AUTO_NEW, 1)
    ok.append("P9_autolearn")

# --- P10: 스캔 성공 시 LevelGridMemory 자동 저장 ---
SCAN_OLD = '''                val positions = findOOXOO(bitmap)

                mainHandler.post {
                    foundPositions.clear(); foundPositions.addAll(positions)
                    overlayView?.updatePositions(positions)
                    refreshControlUI()
                    isScanning = false
                }
                bitmap.recycle()'''
SCAN_NEW = '''                val positions = findOOXOO(bitmap)

                // 🔥 v98: 성공 스캔을 지문별로 저장 (다음 같은 판에서 복원)
                if (positions.isNotEmpty() && currentFingerprint.isNotEmpty()) {
                    try {
                        LevelGridMemory.save(
                            applicationContext, currentFingerprint,
                            rows, cols, manual = false
                        )
                    } catch (e: Exception) { }
                }

                mainHandler.post {
                    foundPositions.clear(); foundPositions.addAll(positions)
                    overlayView?.updatePositions(positions)
                    refreshControlUI()
                    isScanning = false
                }
                bitmap.recycle()'''
if SCAN_OLD in s and "지문별로 저장" not in s:
    s = s.replace(SCAN_OLD, SCAN_NEW, 1)
    ok.append("P10_memory")

# 저장
backup(SVC)
with open(SVC, "w", encoding="utf-8") as f:
    f.write(s)
print(f"  SolverService.kt: OK={ok}")
if fail: print(f"  SolverService.kt: FAIL={fail}")


# ============================================================
# GridSizeDB.kt — EMA 평균
# ============================================================
with open(DB, encoding="utf-8") as f:
    d = f.read()

EMA_OLD = '''        if (idx >= 0) {
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
            AppLogger.d("📏 ${key} 평균 업데이트 (${newCount}개): tl=(${(old.avgTLx).toInt()},${(old.avgTLy).toInt()}) br=(${(old.avgBRx).toInt()},${(old.avgBRy).toInt()})")'''
EMA_NEW = '''        if (idx >= 0) {
            val old = list[idx]
            val newCount = old.count + 1
            // 🔥 v98: EMA (최근 30% 가중) — 오래된 데이터 영향 축소
            val alpha = 0.3f
            list[idx] = SizeRecord(
                rows = rows, cols = cols,
                avgTLx = old.avgTLx * (1 - alpha) + tlx * alpha,
                avgTLy = old.avgTLy * (1 - alpha) + tly * alpha,
                avgBRx = old.avgBRx * (1 - alpha) + brx * alpha,
                avgBRy = old.avgBRy * (1 - alpha) + bry * alpha,
                count = newCount,
                timestamp = System.currentTimeMillis()
            )
            AppLogger.d("📏 ${key} EMA 업데이트 (${newCount}개)")'''
if EMA_OLD in d:
    d = d.replace(EMA_OLD, EMA_NEW, 1)
    backup(DB)
    with open(DB, "w", encoding="utf-8") as f:
        f.write(d)
    print(f"  GridSizeDB.kt: OK=P11_EMA")
else:
    print(f"  GridSizeDB.kt: FAIL=P11_EMA (already patched or mismatch)")

print("[DONE] patch_v98.py")
