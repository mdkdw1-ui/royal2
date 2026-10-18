#!/usr/bin/env python3
import os, shutil

BASE = os.path.expanduser("~/royal2")
SVC  = f"{BASE}/app/src/main/java/com/example/helper/service/SolverService.kt"
PRED = f"{BASE}/app/src/main/java/com/example/helper/util/GridPredictor.kt"

def backup(path):
    bak = path + ".v101.bak"
    if not os.path.exists(bak):
        shutil.copy2(path, bak)
        print(f"  backup: {os.path.basename(bak)}")

# ============================================================
# 1. SolverService.kt 수정
# ============================================================
with open(SVC, encoding="utf-8") as f:
    s = f.read()

ok, fail = [], []

# --- P1: Recycled Bitmap 방지 (recycle 제거) ---
s = s.replace("try { latestFullBitmap?.recycle(); latestFullBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false) } catch (e: Exception) { }",
              "try { latestFullBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false) } catch (e: Exception) { } // v101: recycle 제거")
s = s.replace("try {\n                    latestThumbnail?.recycle()\n                    val tw = 500",
              "try {\n                    val tw = 500 // v101: recycle 제거")
ok.append("P1_recycle_fix")

# --- P2: NEW_GAME 무한 루프 방지 (5초 쿨다운) ---
if "private var lastNewGameTime = 0L" not in s:
    s = s.replace("private var lastFingerprintForGameChange = \"\"",
                  "private var lastFingerprintForGameChange = \"\"\n    private var lastNewGameTime = 0L // 🔥 v101 쿨다운용")

old_ng = '''                    if (diff > len * 0.25 && !isCalibrationMode) {
                        AppLogger.d("NEW_GAME: diff=$diff/$len → 자동 격자 재적용")'''
new_ng = '''                    val now = System.currentTimeMillis()
                    if (diff > len * 0.25 && !isCalibrationMode && (now - lastNewGameTime > 5000L)) {
                        lastNewGameTime = now
                        AppLogger.d("NEW_GAME: diff=$diff/$len → 자동 격자 재적용")'''
if old_ng in s:
    s = s.replace(old_ng, new_ng, 1)
    ok.append("P2_cooldown")
else:
    fail.append("P2_cooldown")

# --- P3: autoDetectBoard 음수 방어 ---
old_cur = '''            val curW = ptBR.x - ptTL.x
            val curH = ptBL.y - ptTL.y
            
            // 셀 크기 오차가 3% 이상 발생하면 학습된 크기로 강제 조정
            if (Math.abs(curW - targetW) > targetW * 0.03f || Math.abs(curH - targetH) > targetH * 0.03f) {'''
new_cur = '''            val curW = ptBR.x - ptTL.x
            val curH = ptBL.y - ptTL.y
            
            // 🔥 v101: curW, curH가 양수일 때만 정렬 작동
            if (curW > 0 && curH > 0 && (Math.abs(curW - targetW) > targetW * 0.03f || Math.abs(curH - targetH) > targetH * 0.03f)) {'''
if old_cur in s:
    s = s.replace(old_cur, new_cur, 1)
    ok.append("P3_autoDetect_negative")
else:
    fail.append("P3_autoDetect_negative")

# --- P4: findOOXOO 로컬 스냅샷 적용 (크래시 방지) ---
old_find = '''    private fun findOOXOO(bitmap: Bitmap): List<MatchTarget> {
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

        for (r in 0 until rows) for (c in 0 until cols) {'''
new_find = '''    private fun findOOXOO(bitmap: Bitmap): List<MatchTarget> {
        val width = bitmap.width; val height = bitmap.height
        val pixels = IntArray(width * height); bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // 🔥 v101: 로컬 스냅샷 (백그라운드 중 rows/cols 변경으로 인한 크래시 방지)
        val rCnt = rows.coerceIn(3, 20)
        val cCnt = cols.coerceIn(3, 20)
        
        val colorGrid = Array(rCnt) { IntArray(cCnt) }

        for (r in 0 until rCnt) for (c in 0 until cCnt) {'''
if old_find in s:
    s = s.replace(old_find, new_find, 1)
    
    # 내부 변수명 일괄 변경 (rows -> rCnt, cols -> cCnt)
    find_start = s.find("private fun findOOXOO")
    find_end = s.find("// 🔥 기믹 인식", find_start)
    if find_end != -1:
        block = s[find_start:find_end]
        block = block.replace("val u = (c + 0.5f) / cols", "val u = (c + 0.5f) / cCnt")
        block = block.replace("val v = (r + 0.5f) / rows", "val v = (r + 0.5f) / rCnt")
        block = block.replace("/ cols).toInt()", "/ cCnt).toInt()")
        block = block.replace("/ rows).toInt()", "/ rCnt).toInt()")
        block = block.replace("if (cols >= 5)", "if (cCnt >= 5)")
        block = block.replace("for (r in 0 until rows)", "for (r in 0 until rCnt)")
        block = block.replace("for (c in 0 until cols - 4)", "for (c in 0 until cCnt - 4)")
        block = block.replace("if (rows >= 5)", "if (rCnt >= 5)")
        block = block.replace("for (c in 0 until cols)", "for (c in 0 until cCnt)")
        block = block.replace("for (r in 0 until rows - 4)", "for (r in 0 until rCnt - 4)")
        block = block.replace("r < rows - 1", "r < rCnt - 1")
        block = block.replace("c < cols - 1", "c < cCnt - 1")
        s = s[:find_start] + block + s[find_end:]
    ok.append("P4_findOOXOO_snapshot")
else:
    fail.append("P4_findOOXOO_snapshot")

# --- P5: 라벨 저장 시 Recycled 확인 ---
old_learn = '''                            val learnFeat = GridFeature.extract(fullBmp, ptTL, ptTR, ptBL, ptBR)
                            if (learnFeat != null && learnFeat.size == GridFeature.DIM) {'''
new_learn = '''                            val learnFeat = if (!fullBmp.isRecycled) GridFeature.extract(fullBmp, ptTL, ptTR, ptBL, ptBR) else currentFeature
                            if (learnFeat != null && learnFeat.size == GridFeature.DIM) {'''
if old_learn in s:
    s = s.replace(old_learn, new_learn, 1)
    ok.append("P5_recycled_check")
else:
    fail.append("P5_recycled_check")

backup(SVC)
with open(SVC, "w", encoding="utf-8") as f:
    f.write(s)
print(f"  SolverService.kt: OK={ok} FAIL={fail}")

# ============================================================
# 2. GridPredictor.kt 수정 (잘못된 크기 무시)
# ============================================================
with open(PRED, encoding="utf-8") as f:
    p = f.read()

old_pred = '''    fun predict(context: Context, feature: FloatArray): Prediction? {
        val allSeeds = GridSeedDB.loadAll(context)
        // 🔥 v41: manual만 사용 (auto seed는 완전 무시)
        val seeds = allSeeds.filter { it.manual }'''
new_pred = '''    fun predict(context: Context, feature: FloatArray): Prediction? {
        val allSeeds = GridSeedDB.loadAll(context)
        // 🔥 v41: manual만 사용 / v101: 8열 이하 오학습 데이터 무시
        val seeds = allSeeds.filter { it.manual && it.cols >= 9 }'''
if old_pred in p:
    p = p.replace(old_pred, new_pred, 1)
    backup(PRED)
    with open(PRED, "w", encoding="utf-8") as f:
        f.write(p)
    print("  GridPredictor.kt: OK=P6_filter_11x8")
else:
    print("  GridPredictor.kt: FAIL=P6_filter_11x8")

print("[DONE] patch_v101.py")
