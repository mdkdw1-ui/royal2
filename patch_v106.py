#!/usr/bin/env python3
import os, shutil

BASE = os.path.expanduser("~/royal2")
SVC  = f"{BASE}/app/src/main/java/com/example/helper/service/SolverService.kt"

def backup(path):
    bak = path + ".v106.bak"
    if not os.path.exists(bak):
        shutil.copy2(path, bak)
        print(f"  backup: {os.path.basename(bak)}")

def replace_func(s, marker, new_body):
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

with open(SVC, encoding="utf-8") as f:
    s = f.read()

ok, fail = [], []

# --- P1: findOOXOO 완전 교체 (오직 5개 매치만 시뮬레이션) ---
NEW_FIND = '''private fun findOOXOO(bitmap: Bitmap): List<MatchTarget> {
        val width = bitmap.width; val height = bitmap.height
        val pixels = IntArray(width * height); bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        val rCnt = rows.coerceIn(3, 20)
        val cCnt = cols.coerceIn(3, 20)
        
        val colorGrid = Array(rCnt) { IntArray(cCnt) }

        for (r in 0 until rCnt) for (c in 0 until cCnt) {
            val u = (c + 0.5f) / cCnt; val v = (r + 0.5f) / rCnt
            val topX = (1 - u) * ptTL.x + u * ptTR.x
            val topY = (1 - u) * ptTL.y + u * ptTR.y
            val bottomX = (1 - u) * ptBL.x + u * ptBR.x
            val bottomY = (1 - u) * ptBL.y + u * ptBR.y
            val cx = (1 - v) * topX + v * bottomX
            val cy = (1 - v) * topY + v * bottomY
            val ix = cx.toInt().coerceIn(0, width - 1); val iy = cy.toInt().coerceIn(0, height - 1)

            if (isOpenCVInitialized && dynamicTemplates.isNotEmpty()) {
                val cellW = (abs(ptTR.x - ptTL.x) / cCnt).toInt().coerceAtLeast(20)
                val cellH = (abs(ptBL.y - ptTL.y) / rCnt).toInt().coerceAtLeast(20)
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

            // 🔥 v102: 5x5 평균 색상 계산
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
                sat < 0.25f || valColor < 0.25f -> -1
                hue in 0f..30f -> 1
                hue in 31f..70f -> 2
                hue in 71f..160f -> 3
                hue in 161f..230f -> 4
                hue in 231f..360f -> 5
                else -> -1
            }
        }

        // 🔥 v106: 스왑 시뮬레이션으로 '오직 5개 매치'만 완벽 탐지
        val result = mutableListOf<MatchTarget>()
        val simGrid = Array(rCnt) { r -> IntArray(cCnt) { c -> colorGrid[r][c] } }
        val dirs = arrayOf(intArrayOf(0, 1), intArrayOf(1, 0)) // 오른쪽, 아래

        for (r in 0 until rCnt) {
            for (c in 0 until cCnt) {
                val color1 = simGrid[r][c]
                if (color1 == -1) continue

                for (dir in dirs) {
                    val nr = r + dir[0]
                    val nc = c + dir[1]
                    if (nr >= rCnt || nc >= cCnt) continue
                    
                    val color2 = simGrid[nr][nc]
                    if (color2 == -1 || color1 == color2) continue

                    // 시뮬레이션 스왑
                    simGrid[r][c] = color2
                    simGrid[nr][nc] = color1

                    // 🔥 오직 길이가 5 이상인 경우만 체크 (3,4개 무시)
                    val isMatch1_5 = checkMatchLength(simGrid, r, c) >= 5
                    val isMatch2_5 = checkMatchLength(simGrid, nr, nc) >= 5

                    if (isMatch1_5) {
                        result.add(MatchTarget(r, c, nr, nc, color2))
                        AppLogger.d("🌟 5매치 발견: Target($r,$c) <- Source($nr,$nc) Color:$color2")
                    }
                    if (isMatch2_5) {
                        result.add(MatchTarget(nr, nc, r, c, color1))
                        AppLogger.d("🌟 5매치 발견: Target($nr,$nc) <- Source($r,$c) Color:$color1")
                    }

                    // 원상 복구
                    simGrid[r][c] = color1
                    simGrid[nr][nc] = color2
                }
            }
        }

        // 디버그 로그 (10초에 한 번만)
        val nowLog = System.currentTimeMillis()
        if (nowLog - lastSeedLogTime > 10000L) {
            lastSeedLogTime = nowLog
            var gridStr = ""
            for (r in 0 until rCnt) {
                var rowStr = ""
                for (c in 0 until cCnt) {
                    val v = colorGrid[r][c]
                    rowStr += if (v == -1) ". " else "$v "
                }
                gridStr += "  $rowStr\\n"
            }
            AppLogger.d("🎨 색상 그리드:\\n$gridStr")
        }

        return result.distinctBy { "${it.targetRow},${it.targetCol},${it.sourceRow},${it.sourceCol}" }
    }
    
    // 매치 길이 반환 헬퍼 함수
    private fun checkMatchLength(grid: Array<IntArray>, r: Int, c: Int): Int {
        val color = grid[r][c]
        if (color == -1) return 0
        val rows = grid.size
        val cols = grid[0].size
        var maxLen = 0

        // 가로 길이
        var hCount = 1
        var cLeft = c - 1
        while (cLeft >= 0 && grid[r][cLeft] == color) { hCount++; cLeft-- }
        var cRight = c + 1
        while (cRight < cols && grid[r][cRight] == color) { hCount++; cRight++ }
        if (hCount > maxLen) maxLen = hCount

        // 세로 길이
        var vCount = 1
        var rUp = r - 1
        while (rUp >= 0 && grid[rUp][c] == color) { vCount++; rUp-- }
        var rDown = r + 1
        while (rDown < rows && grid[rDown][c] == color) { vCount++; rDown++ }
        if (vCount > maxLen) maxLen = vCount

        return maxLen
    }'''

s, r = replace_func(s, "private fun findOOXOO(bitmap: Bitmap): List<MatchTarget> {", NEW_FIND)
if r: ok.append("P1_5match_only")
else: fail.append("P1_5match_only")

# 기존 checkMatchAt 함수가 있다면 제거 (v106에서 checkMatchLength로 대체됨)
if "private fun checkMatchAt(" in s:
    s, _ = replace_func(s, "private fun checkMatchAt(", "")
    ok.append("P2_cleanup_old")

backup(SVC)
with open(SVC, "w", encoding="utf-8") as f:
    f.write(s)
print(f"  SolverService.kt: OK={ok} FAIL={fail}")
print("[DONE] patch_v106.py")
