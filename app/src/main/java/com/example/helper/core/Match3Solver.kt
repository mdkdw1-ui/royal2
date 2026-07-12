package com.example.helper.core

import com.example.helper.core.GameConfig.EMPTY
import com.example.helper.core.GameConfig.HOLE

data class MatchCandidate(
    val row: Int,
    val col: Int,
    val move: String,
    val score: Int
)

object Match3Solver {
    private fun rowRangeClamped(row: Int): Int = minOf(maxOf(0, row), GameConfig.ROWS - 1)
    private fun colRangeClamped(col: Int): Int = minOf(maxOf(0, col), GameConfig.COLS - 1)

    /**
     * 보드판 상태에서 특정 위치를 스왑했을 때 터지는 블록 개수와 대박 매칭 보너스 점수를 통합 계산합니다.
     */
    fun swapScore(direction: String, r: Int, c: Int, grid: Array<IntArray>): Int {
        val tile = grid[r][c]
        if (tile == HOLE || tile == EMPTY) return 0

        var targetRow = r
        var targetCol = c

        if (direction == "down") targetRow = rowRangeClamped(r + 1)
        if (direction == "right") targetCol = colRangeClamped(c + 1)

        // 경계를 벗어나 제자리 걸음이거나 벽/공백 타일인 경우 스왑 불가
        if (targetRow == r && targetCol == c) return 0
        
        val targetTile = grid[targetRow][targetCol]
        if (targetTile == HOLE || targetTile == EMPTY || tile == targetTile) return 0

        // 1. 가상 스왑 매트릭스 복사 생성
        val testGrid = Array(GameConfig.ROWS) { row -> grid[row].clone() }
        testGrid[r][c] = targetTile
        testGrid[targetRow][targetCol] = tile

        // 2. 이 스왑으로 인해 연쇄 파괴되는 타일들의 좌표 뭉치 추출
        val eliminated = findEliminatedTiles(testGrid)
        if (eliminated.isEmpty()) return 0

        // 3. 4개/5개 연속 매칭 길이를 정밀 측정하여 가중치 점수 부여
        return calculateAdvancedScore(testGrid, eliminated)
    }

    /**
     * 가로 및 세로 방향을 전수조사하여 3개 이상 연속된 모든 타일의 좌표(Pair)를 찾아냅니다.
     */
    private fun findEliminatedTiles(grid: Array<IntArray>): Set<Pair<Int, Int>> {
        val eliminated = mutableSetOf<Pair<Int, Int>>()

        // 가로 스캔
        for (r in 0 until GameConfig.ROWS) {
            var c = 0
            while (c < GameConfig.COLS) {
                val tile = grid[r][c]
                if (tile != HOLE && tile != EMPTY) {
                    var matchLen = 1
                    while (c + matchLen < GameConfig.COLS && grid[r][c + matchLen] == tile) {
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

        // 세로 스캔
        for (c in 0 until GameConfig.COLS) {
            var r = 0
            while (r < GameConfig.ROWS) {
                val tile = grid[r][c]
                if (tile != HOLE && tile != EMPTY) {
                    var matchLen = 1
                    while (r + matchLen < GameConfig.ROWS && grid[r + matchLen][c] == tile) {
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
     * 매칭된 타일 그룹 중 단일 라인의 '최대 연속 길이'를 측정하고, 4/5개 매칭에 압도적인 가중치를 선사합니다.
     */
    private fun calculateAdvancedScore(grid: Array<IntArray>, eliminated: Set<Pair<Int, Int>>): Int {
        var maxMatchLen = 3
        
        // 가로방향 최대 단일 컴포넌트 길이 측정
        for (r in 0 until GameConfig.ROWS) {
            var currentLen = 0
            var lastTile = -2
            for (c in 0 until GameConfig.COLS) {
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

        // 세로방향 최대 단일 컴포넌트 길이 측정
        for (c in 0 until GameConfig.COLS) {
            var currentLen = 0
            var lastTile = -2
            for (r in 0 until GameConfig.ROWS) {
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

        // 기본 점수 = 순수 터진 블록 개수
        var finalScore = eliminated.size

        // 🎯 [핵심 교정] 4개 매칭은 +1000점, 5개 매칭은 +50000점의 초고배율 보너스 지급!
        when (maxMatchLen) {
            5 -> finalScore += 50000 
            4 -> finalScore += 1000
        }

        return finalScore
    }

    /**
     * 보드 전체를 탐색하여 모든 유효한 스왑 후보군을 점수 순서대로 추출합니다.
     */
    fun getMatchCandidates(grid: Array<IntArray>): List<MatchCandidate> {
        val candidates = mutableListOf<MatchCandidate>()
        for (r in 0 until GameConfig.ROWS) {
            for (c in 0 until GameConfig.COLS) {
                for (direction in listOf("down", "right")) {
                    val score = swapScore(direction, r, c, grid)
                    if (score > 0) {
                        candidates.add(MatchCandidate(r, c, direction, score))
                    }
                }
            }
        }
        return candidates
    }
}
