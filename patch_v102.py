#!/usr/bin/env python3
import os, shutil

BASE = os.path.expanduser("~/royal2")
SVC  = f"{BASE}/app/src/main/java/com/example/helper/service/SolverService.kt"
PRED = f"{BASE}/app/src/main/java/com/example/helper/util/GridPredictor.kt"

def backup(path):
    bak = path + ".v102.bak"
    if not os.path.exists(bak):
        shutil.copy2(path, bak)
        print(f"  backup: {os.path.basename(bak)}")

# ============================================================
# 1. SolverService.kt 수정
# ============================================================
with open(SVC, encoding="utf-8") as f:
    s = f.read()

ok, fail = [], []

# --- P1: findOOXOO 색상 판정 시 5x5 평균 사용 (특수 타일 방지) ---
old_pixel = '''            val pixel = pixels[iy * width + ix]
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
            }'''
new_pixel = '''            // 🔥 v102: 5x5 영역 평균 색상 계산 (특수 타일 오인식 방지)
            var rSum = 0; var gSum = 0; var bSum = 0; var cnt = 0
            for (dy in -2..2) {
                for (dx in -2..2) {
                    val nx = (ix + dx).coerceIn(0, width - 1)
                    val ny = (iy + dy).coerceIn(0, height - 1)
                    val p = pixels[ny * width + nx]
                    rSum += (p shr 16) and 0xFF
                    gSum += (p shr 8) and 0xFF
                    bSum += p and 0xFF
                    cnt++
                }
            }
            val avgColor = Color.rgb(rSum / cnt, gSum / cnt, bSum / cnt)
            val hsv = FloatArray(3); Color.colorToHSV(avgColor, hsv)
            val hue = hsv[0]; val sat = hsv[1]; val valColor = hsv[2]
            
            colorGrid[r][c] = when {
                sat < 0.25f || valColor < 0.25f -> -1 // 채도/명도 낮으면 무시
                hue in 0f..30f -> 1
                hue in 31f..70f -> 2
                hue in 71f..160f -> 3
                hue in 161f..230f -> 4
                hue in 231f..360f -> 5
                else -> -1
            }'''
if old_pixel in s:
    s = s.replace(old_pixel, new_pixel, 1)
    ok.append("P1_5x5_avg")
else:
    fail.append("P1_5x5_avg")

# --- P2: detectGridDimensions 에서 10행을 11행으로 스냅 ---
old_rows = '''            var rows = when {
                rowsRaw < 9.3f -> 9
                rowsRaw < 10.5f -> 10   // 🔥 v15: 9.3~10.5 → 10행 (이번 케이스)
                rowsRaw < 13.0f -> 11   // 10.5~13.0 → 11행
                rowsRaw < 14.5f -> 13
                else -> Math.round(rowsRaw).toInt()
            }'''
new_rows = '''            var rows = when {
                rowsRaw < 9.5f -> 9
                rowsRaw < 11.5f -> 11   // 🔥 v102: 10.x 행은 11행으로 강제 보정
                rowsRaw < 13.5f -> 13
                else -> Math.round(rowsRaw).toInt()
            }'''
if old_rows in s:
    s = s.replace(old_rows, new_rows, 1)
    ok.append("P2_rows_snap")
else:
    fail.append("P2_rows_snap")

backup(SVC)
with open(SVC, "w", encoding="utf-8") as f:
    f.write(s)
print(f"  SolverService.kt: OK={ok} FAIL={fail}")


# ============================================================
# 2. GridPredictor.kt 수정 (10x9 예측 완전 차단)
# ============================================================
with open(PRED, encoding="utf-8") as f:
    p = f.read()

old_pred = '''    fun predict(context: Context, feature: FloatArray): Prediction? {
        val allSeeds = GridSeedDB.loadAll(context)
        // 🔥 v41: manual만 사용 / v101: 8열 이하 오학습 데이터 무시
        val seeds = allSeeds.filter { it.manual && it.cols >= 9 }'''
new_pred = '''    fun predict(context: Context, feature: FloatArray): Prediction? {
        val allSeeds = GridSeedDB.loadAll(context)
        // 🔥 v102: 11행 이상의 manual 데이터만 신뢰 (10x9 오예측 원천 차단)
        val seeds = allSeeds.filter { it.manual && it.rows >= 11 && it.cols >= 9 }'''
if old_pred in p:
    p = p.replace(old_pred, new_pred, 1)
    backup(PRED)
    with open(PRED, "w", encoding="utf-8") as f:
        f.write(p)
    print("  GridPredictor.kt: OK=P3_11rows_only")
else:
    print("  GridPredictor.kt: FAIL=P3_11rows_only (already patched?)")

print("[DONE] patch_v102.py")
