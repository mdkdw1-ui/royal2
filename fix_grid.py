import os
import sys

BASE = os.path.expanduser("~/royal2")
SVC = f"{BASE}/app/src/main/java/com/example/helper/service/SolverService.kt"
DB  = f"{BASE}/app/src/main/java/com/example/helper/util/GridSizeDB.kt"

def backup(path):
    bak = path + ".bak"
    if not os.path.exists(bak):
        with open(path, "rb") as f: data = f.read()
        with open(bak, "wb") as f: f.write(data)
        print(f"  backup: {os.path.basename(bak)}")

def patch_svc():
    with open(SVC, "r", encoding="utf-8") as f:
        s = f.read()
    ok = []
    fails = []

    # P1: colThr 0.50 → 0.28
    old = "val colThr = maxCol * 0.50"
    new = "val colThr = maxCol * 0.28  // v97: 관대"
    if old in s: s = s.replace(old, new, 1); ok.append("P1")
    else: fails.append("P1")

    # P2: gapX 60 → 160
    old = "if (boardLeft != -1 && gapCntX > 60) break"
    new = "if (boardLeft != -1 && gapCntX > 160) break  // v97: 관대"
    if old in s: s = s.replace(old, new, 1); ok.append("P2")
    else: fails.append("P2")

    # P3a: boardW 계산에 종횡비 확장 삽입
    old = "            val boardW = boardRight - boardLeft\n"
    new = (
        "            val boardW0 = boardRight - boardLeft\n"
        "            var fLeft = boardLeft\n"
        "            var fRight = boardRight\n"
        "            val aspectCheck = boardW0.toFloat() / boardH.toFloat()\n"
        "            if (aspectCheck < 0.85f) {\n"
        "                val targetW = (boardH * 0.90f).toInt().coerceAtMost(w)\n"
        "                val deficit = targetW - boardW0\n"
        "                if (deficit > 0) {\n"
        "                    val addLeft = minOf(boardLeft, deficit / 2)\n"
        "                    val addRight = minOf(w - boardRight, deficit - addLeft)\n"
        "                    fLeft = boardLeft - addLeft\n"
        "                    fRight = boardRight + addRight\n"
        "                    AppLogger.d(\"🔧 X 보정: ${boardW0} → ${fRight-fLeft} (종횡비 ${\\\"%.2f\\\".format(aspectCheck)} → 0.90)\")\n"
        "                }\n"
        "            }\n"
        "            val boardW = fRight - fLeft\n"
    )
    if old in s: s = s.replace(old, new, 1); ok.append("P3a")
    else: fails.append("P3a")

    # P3b: 로그 및 ptTL/TR/BL/BR을 final 변수로
    for o, n in [
        ('AppLogger.d("보드(proj): ${boardW}x${boardH} @ (${boardLeft},${boardTop})',
         'AppLogger.d("보드(proj): ${boardW}x${boardH} @ (${fLeft},${boardTop})'),
        ("ptTL.set((boardLeft - expandW)", "ptTL.set((fLeft - expandW)"),
        ("ptTR.set((boardRight + expandW)", "ptTR.set((fRight + expandW)"),
        ("ptBL.set((boardLeft - expandW)", "ptBL.set((fLeft - expandW)"),
        ("ptBR.set((boardRight + expandW)", "ptBR.set((fRight + expandW)"),
    ]:
        if o in s: s = s.replace(o, n, 1)
        else: fails.append("P3b:" + o[:30])

    if "P3b:" not in str(fails): ok.append("P3b")

    # P5: ivToOrig 로그 억제
    old = 'AppLogger.d("ivToOrig:'
    if old in s:
        lines = s.split("\n")
        for i, ln in enumerate(lines):
            if 'AppLogger.d("ivToOrig:' in ln:
                lines[i] = ln.replace(
                    'AppLogger.d("ivToOrig:',
                    '// v97-log: AppLogger.d("ivToOrig:'
                )
                break
        s = "\n".join(lines)
        ok.append("P5")
    else:
        fails.append("P5")

    with open(SVC, "w", encoding="utf-8") as f:
        f.write(s)
    print(f"  SolverService.kt: OK={ok} FAIL={fails}")
    return len(fails) == 0

def patch_db():
    with open(DB, "r", encoding="utf-8") as f:
        s = f.read()
    old = "private const val MIN_SAMPLES = 3"
    new = "private const val MIN_SAMPLES = 2  // v97: 2개부터"
    if old in s:
        s = s.replace(old, new, 1)
        with open(DB, "w", encoding="utf-8") as f:
            f.write(s)
        print(f"  GridSizeDB.kt: OK=P4")
        return True
    print(f"  GridSizeDB.kt: FAIL=P4")
    return False

if __name__ == "__main__":
    print("[backup]")
    backup(SVC); backup(DB)
    print("[patch]")
    a = patch_svc()
    b = patch_db()
    print("RESULT:", "OK" if (a and b) else "PARTIAL")
