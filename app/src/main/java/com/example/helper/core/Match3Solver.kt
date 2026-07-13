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
     * 그리드 전체를 탐색하여 오직 '5개 이상 연속 매칭'을 유발하는 스왑 후보만 추출합니다.
     */
    fun getMatchCandidates(grid: Array<IntArray>): List<MatchCandidate> {
        val candidates = mutableListOf<MatchCandidate>()
        val rows = grid.size
        val cols = if (rows > 0) grid[0].size else 0
        
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                for (moveType in listOf("down", "right")) {
                    val score = swapScore(moveType, r, c, grid)
                    // score가 0보다 큰 경우(즉, 5개 이상 매칭이 성공한 경우)에만 힌트로 추가
                    if (score > 0) {
                        candidates.add(MatchCandidate(r, c, moveType, score))
                    }
                }
            }
        }
        return candidates
    }

    /**
     * 특정 위치를 스왑했을 때의 결과를 예측하고 5개 매칭 여부를 판별합니다.
     */
    fun swapScore(moveType: String, r: Int, c: Int, grid: Array<IntArray>): Int {
        val rows = grid.size
        val cols = if (rows > 0) grid[0].size else 0

        val tile = grid[r][c]
        if (tile == HOLE) return 0 

        var targetRow = r
        var targetCol = c

        if (moveType == "down") targetRow = minOf(maxOf(0, r + 1), rows - 1)
        if (moveType == "right") targetCol = minOf(maxOf(0, c + 1), cols - 1)

        if (targetRow == r && targetCol == c) return 0
        
        val targetTile = grid[targetRow][targetCol]
        if (targetTile == HOLE || tile == targetTile) return 0

        // 가상 스왑 진행
        val testGrid = Array(rows) { row -> grid[row].clone() }
        testGrid[r][c] = targetTile
        testGrid[targetRow][targetCol] = tile

        val eliminated = findEliminatedTiles(testGrid)
        if (eliminated.isEmpty()) return 0

        return calculateAdvancedScore(testGrid, eliminated)
    }

    /**
     * 보드판에서 3개 이상 연속된 모든 타일의 좌표를 찾아냅니다.
     */
    private fun findEliminatedTiles(grid: Array<IntArray>): Set<Pair<Int, Int>> {
        val eliminated = mutableSetOf<Pair<Int, Int>>()
        val rows = grid.size
        val cols = if (rows > 0) grid[0].size else 0

        // 가로축 스캔
        for (r in 0 until rows) {
            var c = 0
            while (c < cols) {
                val tile = grid[r][c]
                if (tile != HOLE) {
                    var matchLen = 1
                    while (c + matchLen < cols && grid[r][c + matchLen] == tile) {
                        matchLen++
                    }
                    if (matchLen >= 3) {
                        for (i in 0 until matchLen) {
                            eliminated.add(Pair(r, c + i))
                        }
                    }
                    c += matchLen
                } else {
                    c++
                }
            }
        }

        // 세로축 스캔
        for (c in 0 until cols) {
            var r = 0
            while (r < rows) {
                val tile = grid[r][c]
                if (tile != HOLE) {
                    var matchLen = 1
                    while (r + matchLen < rows && grid[r + matchLen][c] == tile) {
                        matchLen++
                    }
                    if (matchLen >= 3) {
                        for (i in 0 until matchLen) {
                            eliminated.add(Pair(r + i, c))
                        }
                    }
                    r += matchLen
                } else {
                    r++
                }
            }
        }

        return eliminated
    }

    /**
     * ⚠️ [핵심 수정 항목] 한 줄 연속 매칭 길이가 5 미만(3개, 4개)이면 완전히 무시합니다.
     */
    private fun calculateAdvancedScore(grid: Array<IntArray>, eliminated: Set<Pair<Int, Int>>): Int {
        val rows = grid.size
        val cols = if (rows > 0) grid[0].size else 0
        var maxMatchLen = 0
        
        // 가로축 최대 연속 길이 측정
        for (r in 0 until rows) {
            var currentLen = 0
            var lastTile = -2
            for (c in 0 until cols) {
                if (eliminated.contains(Pair(r, c))) {
                    val tile = grid[r][c]
                    if (tile == lastTile) {
                        currentLen++
                    } else {
                        if (currentLen > maxMatchLen) maxMatchLen = currentLen
                        currentLen = 1
                        lastTile = tile
                    }
                } else {
                    if (currentLen > maxMatchLen) maxMatchLen = currentLen
                    currentLen = 0
                    lastTile = -2
                }
            }
            if (currentLen > maxMatchLen) maxMatchLen = currentLen
        }

        // 세로축 최대 연속 길이 측정
        for (c in 0 until cols) {
            var currentLen = 0
            var lastTile = -2
            for (r in 0 until rows) {
                if (eliminated.contains(Pair(r, c))) {
                    val tile = grid[r][c]
                    if (tile == lastTile) {
                        currentLen++
                    } else {
                        if (currentLen > maxMatchLen) maxMatchLen = currentLen
                        currentLen = 1
                        lastTile = tile
                    }
                } else {
                    if (currentLen > maxMatchLen) maxMatchLen = currentLen
                    currentLen = 0
                    lastTile = -2
                }
            }
            if (currentLen > maxMatchLen) maxMatchLen = currentLen
        }

        // 🎯 5개 미만(3개 매칭, 4개 매칭)은 가차없이 점수를 0점으로 주어 탈락시킵니다.
        if (maxMatchLen < 5) {
            return 0
        }

        // 오직 5개 연속 일렬 매칭(OO-OO 완성 포함)이 성공했을 때만 엄청난 보너스 점수 부여
        return eliminated.size + 5000000
    }
}
