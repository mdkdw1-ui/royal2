package com.example.helper.core

import com.example.helper.core.GameConfig.HOLE // 프로젝트에 맞는 HOLE(빈공간) 상수 지정 필요

data class MatchCandidate(
    val row: Int,
    val col: Int,
    val move: String, // "up", "down", "left", "right"
    val score: Int
)

object Match3Solver {

    /**
     * 보드판 전체에서 오직 OO X OO 패턴만 찾아내어
     * 빈칸(X)을 채워 디스코볼(5개 매칭)을 만드는 신의 한 수만 추출합니다.
     */
    fun getMatchCandidates(grid: Array<IntArray>): List<MatchCandidate> {
        val candidates = mutableListOf<MatchCandidate>()
        val rows = grid.size
        val cols = if (rows > 0) grid[0].size else 0

        // 1. 가로 방향 [O O X O O] 패턴 스캔
        for (r in 0 until rows) {
            for (c in 0 until cols - 4) {
                val color = grid[r][c]
                if (color == HOLE) continue
                
                // O O X O O 구조 조건 검사
                if (grid[r][c + 1] == color && grid[r][c + 3] == color && grid[r][c + 4] == color) {
                    val gapRow = r
                    val gapCol = c + 2
                    
                    // 빈칸(X)의 바로 위 칸에 같은 색이 있다면 -> 위 블록을 아래로(down) 이동
                    if (gapRow - 1 >= 0 && grid[gapRow - 1][gapCol] == color) {
                        candidates.add(MatchCandidate(gapRow - 1, gapCol, "down", 5000000))
                    }
                    // 빈칸(X)의 바로 아래 칸에 같은 색이 있다면 -> 아래 블록을 위로(up) 이동
                    if (gapRow + 1 < rows && grid[gapRow + 1][gapCol] == color) {
                        candidates.add(MatchCandidate(gapRow + 1, gapCol, "up", 5000000))
                    }
                }
            }
        }

        // 2. 세로 방향 [O O X O O]' 패턴 스캔
        for (c in 0 until cols) {
            for (r in 0 until rows - 4) {
                val color = grid[r][c]
                if (color == HOLE) continue

                // 세로형 O O X O O 구조 조건 검사
                if (grid[r + 1][c] == color && grid[r + 3][c] == color && grid[r + 4][c] == color) {
                    val gapRow = r + 2
                    val gapCol = c

                    // 빈칸(X)의 바로 왼쪽 칸에 같은 색이 있다면 -> 왼쪽 블록을 오른쪽으로(right) 이동
                    if (gapCol - 1 >= 0 && grid[gapRow][gapCol - 1] == color) {
                        candidates.add(MatchCandidate(gapRow, gapCol - 1, "right", 5000000))
                    }
                    // 빈칸(X)의 바로 오른쪽 칸에 같은 색이 있다면 -> 오른쪽 블록을 왼쪽으로(left) 이동
                    if (gapCol + 1 < cols && grid[gapRow][gapCol + 1] == color) {
                        candidates.add(MatchCandidate(gapRow, gapCol + 1, "left", 5000000))
                    }
                }
            }
        }

        return candidates
    }
}
