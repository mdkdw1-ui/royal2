package com.example.helper.core

import com.example.helper.core.GameConfig.HOLE

data class MatchCandidate(
    val row: Int,
    val col: Int,
    val move: String, // SolverService 연동 규격
    val score: Int
)

object Match3Solver {

    /**
     * 그리드 전체에서 오직 OO-OO 패턴(가로/세로)만 찾아내어 
     * 빈칸을 채워 5개 연속 매칭을 완성하는 신의 한 수만 추출합니다.
     */
    fun getMatchCandidates(grid: Array<IntArray>): List<MatchCandidate> {
        val candidates = mutableListOf<MatchCandidate>()
        val rows = grid.size
        val cols = if (rows > 0) grid[0].size else 0

        // 1. 가로 방향 OO X OO 패턴 스캔
        for (r in 0 until rows) {
            for (c in 0 until cols - 4) {
                val color = grid[r][c]
                if (color == HOLE) continue
                
                // O O X O O 구조 확인
                if (grid[r][c + 1] == color && grid[r][c + 3] == color && grid[r][c + 4] == color) {
                    val gapRow = r
                    val gapCol = c + 2
                    
                    // 빈칸(X)의 위쪽 칸 확인 -> 위쪽 블록을 아래로(down) 내리기
                    if (gapRow - 1 >= 0 && grid[gapRow - 1][gapCol] == color) {
                        candidates.add(MatchCandidate(gapRow - 1, gapCol, "down", 5000000))
                    }
                    // 빈칸(X)의 아래쪽 칸 확인 -> 빈칸 위치에서 아래로(down) 스왑 (아래 블록을 위로 올리기)
                    if (gapRow + 1 < rows && grid[gapRow + 1][gapCol] == color) {
                        candidates.add(MatchCandidate(gapRow, gapCol, "down", 5000000))
                    }
                }
            }
        }

        // 2. 세로 방향 OO X OO 패턴 스캔
        for (c in 0 until cols) {
            for (r in 0 until rows - 4) {
                val color = grid[r][c]
                if (color == HOLE) continue

                // O
                // O
                // X  구조 확인
                // O
                // O
                if (grid[r + 1][c] == color && grid[r + 3][c] == color && grid[r + 4][c] == color) {
                    val gapRow = r + 2
                    val gapCol = c

                    // 빈칸(X)의 왼쪽 칸 확인 -> 왼쪽 블록을 오른쪽으로(right) 밀기
                    if (gapCol - 1 >= 0 && grid[gapRow][gapCol - 1] == color) {
                        candidates.add(MatchCandidate(gapRow, gapCol - 1, "right", 5000000))
                    }
                    // 빈칸(X)의 오른쪽 칸 확인 -> 빈칸 위치에서 오른쪽으로(right) 스왑 (오른쪽 블록을 왼쪽으로 당기기)
                    if (gapCol + 1 < cols && grid[gapRow][gapCol + 1] == color) {
                        candidates.add(MatchCandidate(gapRow, gapCol, "right", 5000000))
                    }
                }
            }
        }

        return candidates
    }
}
