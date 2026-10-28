import os
BASE = os.path.expanduser("~/royal2")
SVC = f"{BASE}/app/src/main/java/com/example/helper/service/SolverService.kt"

with open(SVC, "r", encoding="utf-8") as f:
    s = f.read()

ok, fails = [], []

# ── P1: rows 화이트리스트 강제 (10 or 11만 허용)
old_p1 = '''            var rows = when {
                rowsRaw < 9.3f -> 9
                rowsRaw < 10.5f -> 10   // 🔥 v15: 9.3~10.5 → 10행 (이번 케이스)
                rowsRaw < 13.0f -> 11   // 10.5~13.0 → 11행
                rowsRaw < 14.5f -> 13
                else -> Math.round(rowsRaw).toInt()
            }'''
new_p1 = '''            // v98: Royal Match는 10x9 / 11x9만 존재
            val rows = when {
                rowsRaw < 9.7f -> 10
                rowsRaw < 11.5f -> 11
                else -> {
                    AppLogger.d("⚠️ 비정상 rowsRaw=${"%.2f".format(rowsRaw)} → 검출 실패")
                    return null
                }
            }'''
if old_p1 in s:
    s = s.replace(old_p1, new_p1, 1); ok.append("P1-rows")
else:
    fails.append("P1-rows")

# ── P2: 뒤따르는 clamp 제거 (rows val이라 재할당 불가)
old_p2 = '''            if (rows < 9) rows = 9
            if (rows > 13) rows = 13
'''
if old_p2 in s:
    s = s.replace(old_p2, "            // v98: rows clamp 제거 (val)\n", 1)
    ok.append("P2-clamp")
else:
    fails.append("P2-clamp(무시가능)")

# ── P3: detectBoardSize 신뢰도 임계값
old_p3 = '''    if (bestScore == Double.NEGATIVE_INFINITY) {
        return null
    }

    return Pair(bestRows, bestCols)'''
new_p3 = '''    // v98: 신뢰도 낮으면 거부
    if (bestScore < 40.0) {
        AppLogger.d("⚠️ detectBoardSize 신뢰도 부족: ${"%.1f".format(bestScore)}")
        return null
    }
    return Pair(bestRows, bestCols)'''
if old_p3 in s:
    s = s.replace(old_p3, new_p3, 1); ok.append("P3-신뢰도")
else:
    fails.append("P3-신뢰도")

# ── P4: GridSizeDB MIN_SAMPLES 2 → 3 복원 (오염 방지)
DB = f"{BASE}/app/src/main/java/com/example/helper/util/GridSizeDB.kt"
with open(DB, "r", encoding="utf-8") as f:
    d = f.read()
d = d.replace("private const val MIN_SAMPLES = 2  // v97: 2개부터",
              "private const val MIN_SAMPLES = 5  // v98: 5개 이상")
with open(DB, "w", encoding="utf-8") as f:
    f.write(d)
ok.append("P4-MIN5")

with open(SVC, "w", encoding="utf-8") as f:
    f.write(s)

print(f"OK={ok}")
print(f"FAIL={fails}")
