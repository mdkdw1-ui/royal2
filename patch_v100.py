#!/usr/bin/env python3
import os, shutil

BASE = os.path.expanduser("~/royal2")
SVC  = f"{BASE}/app/src/main/java/com/example/helper/service/SolverService.kt"

def backup(path):
    bak = path + ".v100.bak"
    if not os.path.exists(bak):
        shutil.copy2(path, bak)
        print(f"  backup: {os.path.basename(bak)}")

with open(SVC, encoding="utf-8") as f:
    s = f.read()

ok, fail = [], []

# --- P1: isGimmick 임계값 상향 (0.62 -> 0.78) ---
old_gimmick = "return bestScore >= 0.62"
new_gimmick = "return bestScore >= 0.78  // v100: 일반 타일 오탐 방지 (기존 0.62)"
if old_gimmick in s:
    s = s.replace(old_gimmick, new_gimmick, 1)
    ok.append("P1_isGimmick")
else:
    fail.append("P1_isGimmick")

# --- P2: findOOXOO에 OOXOO 발견 로그 추가 ---
old_h_log = '''                    if (r > 0 && colorGrid[r - 1][xCol] == color)
                        result.add(MatchTarget(r, xCol, r - 1, xCol, color))
                    if (r < rows - 1 && colorGrid[r + 1][xCol] == color)
                        result.add(MatchTarget(r, xCol, r + 1, xCol, color))'''
new_h_log = '''                    if (r > 0 && colorGrid[r - 1][xCol] == color) {
                        result.add(MatchTarget(r, xCol, r - 1, xCol, color))
                        AppLogger.d("🔍 OOXOO(가로) 발견: Target($r,$xCol) <- Source(${r-1},$xCol) Color:$color")
                    }
                    if (r < rows - 1 && colorGrid[r + 1][xCol] == color) {
                        result.add(MatchTarget(r, xCol, r + 1, xCol, color))
                        AppLogger.d("🔍 OOXOO(가로) 발견: Target($r,$xCol) <- Source(${r+1},$xCol) Color:$color")
                    }'''
if old_h_log in s:
    s = s.replace(old_h_log, new_h_log, 1)
    ok.append("P2_h_log")
else:
    fail.append("P2_h_log")

old_v_log = '''                    if (c > 0 && colorGrid[xRow][c - 1] == color)
                        result.add(MatchTarget(xRow, c, xRow, c - 1, color))
                    if (c < cols - 1 && colorGrid[xRow][c + 1] == color)
                        result.add(MatchTarget(xRow, c, xRow, c + 1, color))'''
new_v_log = '''                    if (c > 0 && colorGrid[xRow][c - 1] == color) {
                        result.add(MatchTarget(xRow, c, xRow, c - 1, color))
                        AppLogger.d("🔍 OOXOO(세로) 발견: Target($xRow,$c) <- Source($xRow,${c-1}) Color:$color")
                    }
                    if (c < cols - 1 && colorGrid[xRow][c + 1] == color) {
                        result.add(MatchTarget(xRow, c, xRow, c + 1, color))
                        AppLogger.d("🔍 OOXOO(세로) 발견: Target($xRow,$c) <- Source($xRow,${c+1}) Color:$color")
                    }'''
if old_v_log in s:
    s = s.replace(old_v_log, new_v_log, 1)
    ok.append("P2_v_log")
else:
    fail.append("P2_v_log")

# --- P3: autoDetectBoard 강제 정렬 시 화면 밖으로 나가지 않게 클램핑 ---
old_clamp = '''                ptTL.set(cx - targetW / 2f, cy - targetH / 2f)
                ptTR.set(cx + targetW / 2f, cy - targetH / 2f)
                ptBL.set(cx - targetW / 2f, cy + targetH / 2f)
                ptBR.set(cx + targetW / 2f, cy + targetH / 2f)'''
new_clamp = '''                val wMax = bitmap.width.toFloat()
                val hMax = bitmap.height.toFloat()
                val nTLx = (cx - targetW / 2f).coerceIn(0f, wMax - targetW)
                val nTLy = (cy - targetH / 2f).coerceIn(0f, hMax - targetH)
                
                ptTL.set(nTLx, nTLy)
                ptTR.set(nTLx + targetW, nTLy)
                ptBL.set(nTLx, nTLy + targetH)
                ptBR.set(nTLx + targetW, nTLy + targetH)'''
if old_clamp in s:
    s = s.replace(old_clamp, new_clamp, 1)
    ok.append("P3_clamp")
else:
    fail.append("P3_clamp")

backup(SVC)
with open(SVC, "w", encoding="utf-8") as f:
    f.write(s)

print(f"  SolverService.kt: OK={ok} FAIL={fail}")
print("[DONE] patch_v100.py")
