package com.example.helper.core

import com.example.helper.core.GameConfig.HOLE

data class MatchCandidate(
    val row: Int,
    val col: Int,
    val move: String, // 🎯 SolverService의 기존 연동 변수명(move)과 완벽히 일치하도록 수정
    val score: Int
)

object Match3Solver {

    /**
     * 그리드 전체를 탐색하여 모든 유효한 스왑 후보군을 점수 높은 순으로 추출합니다.
     * 8x8, 9x11 등 유동적인 모든 격자 크기에 맞춰 실시간 대응합니다.
     */
    fun getMatchCandidates(grid: Array<IntArray>): List<MatchCandidate> {
        val candidates = mutableListOf<MatchCandidate>()
        val rows = grid.size
        val cols = if (rows > 0) grid[0].size else 0
        
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                // 변수명 충돌 방지를 위해 내부 루프는 moveType으로 지정
                for (moveType in listOf("down", "right")) {
                    val score = swapScore(moveType, r, c, grid)
                    if (score > 0) {
                        candidates.add(MatchCandidate(r, c, moveType, score))
                    }
                }
            }
        }
        return candidates
    }

    /**
     * 특정 위치를 스왑했을 때 터지는 블록 개수와 5개 연속 매칭 보너스를 통합 계산합니다.
     */
    fun swapScore(moveType: String, r: Int, c: Int, grid: Array<IntArray>): Int {
        val rows = grid.size
        val cols = if (rows > 0) grid[0].size else 0

        val tile = grid[r][c]
        if (tile == HOLE) return 0 // 벽 제외

        var targetRow = r
        var targetCol = c

        // 격자 크기에 맞춰 안전 바운더리 체크
        if (moveType == "down") targetRow = minOf(maxOf(0, r + 1), rows - 1)
        if (moveType == "right") targetCol = minOf(maxOf(0, c + 1), cols - 1)

        if (targetRow == r && targetCol == c) return 0
        
        val targetTile = grid[targetRow][targetCol]
        if (targetTile == HOLE || tile == targetTile) return 0

        // 가상 스왑 매트릭스 동적 생성
        val testGrid = Array(rows) { row -> grid[row].clone() }
        testGrid[r][c] = targetTile
        testGrid[targetRow][targetCol] = tile

        val eliminated = findEliminatedTiles(testGrid)
        if (eliminated.isEmpty()) return 0

        return calculateAdvancedScore(testGrid, eliminated)
    }

    /**
     * 동적 그리드 판에서 3개 이상 연속된 모든 타일의 좌표를 찾아냅니다.
     */
    private fun findEliminatedTiles(grid: Array<IntArray>): Set<Pair<Int, Int>> {
        val eliminated = mutableSetOf<Pair<Int, Int>>()
        val rows = grid.size
        val cols = if (rows > 0) grid[0].size else 0

        // 가로 방향 동적 스캔
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

        // 세로 방향 동적 스캔
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
     * 터지는 타일 중 '한 줄로 정렬된 최대 길이'를 판별하여 5개 매칭에 무조건적인 최우선 순위를 부여합니다.
     */
    private fun calculateAdvancedScore(grid: Array<IntArray>, eliminated: Set<Pair<Int, Int>>): Int {
        val rows = grid.size
        val cols = if (rows > 0) grid[0].size else 0
        var maxMatchLen = 0
        
        // 가로축 최대 길이 측정
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

        // 세로축 최대 길이 측정
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

        var finalScore = eliminated.size

        // 🎯 5개 매칭(특수 블록 생성)이 되는 최우선 타겟 선점 가중치
        if (maxMatchLen >= 5) {
            finalScore += 5000000 
        } else if (maxMatchLen == 4) {
            finalScore += 10000
        }

        return finalScore
    }
}
