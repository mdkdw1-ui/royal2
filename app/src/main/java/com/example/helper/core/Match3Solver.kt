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
     * 연속 매칭 검사 (공백이나 벽은 매칭에서 원천 제외)
     */
    fun consecutiveMatch(line: IntArray, targetTile: Int): Boolean {
        if (targetTile == HOLE || targetTile == EMPTY) return false
        
        var consecutive = 0
        for (tile in line) {
            if (tile == targetTile) {
                consecutive++
                if (consecutive >= 3) return true
            } else {
                consecutive = 0
            }
        }
        return false
    }

    /**
     * 특정 셀 기준 주변 매칭 여부 체크
     */
    fun matchCheck(grid: Array<IntArray>, row: Int, col: Int): Boolean {
        val targetTile = grid[row][col]
        if (targetTile == HOLE || targetTile == EMPTY) return false
        
        val colLine = IntArray(GameConfig.ROWS) { r -> grid[r][col] }
        return consecutiveMatch(grid[row], targetTile) || consecutiveMatch(colLine, targetTile)
    }

    /**
     * 전체 판에서 매칭되어 제거될 타일 탐색 (HOLE 구조물 우회)
     */
    fun tileMarkedForElimination(grid: Array<IntArray>): Set<Pair<Int, Int>> {
        val tileEliminated = mutableSetOf<Pair<Int, Int>>()

        // 1. 가로 스캔
        for (r in 0 until GameConfig.ROWS) {
            var c = 0
            while (c < GameConfig.COLS) {
                val current = grid[r][c]
                if (current == HOLE || current == EMPTY) {
                    c++
                    continue
                }
                var matchLen = 1
                while (c + matchLen < GameConfig.COLS && grid[r][c + matchLen] == current) {
                    matchLen++
                }
                if (matchLen >= 3) {
                    for (i in 0 until matchLen) tileEliminated.add(Pair(r, c + i))
                }
                c += matchLen
            }
        }

        // 2. 세로 스캔
        for (c in 0 until GameConfig.COLS) {
            var r = 0
            while (r < GameConfig.ROWS) {
                val current = grid[r][c]
                if (current == HOLE || current == EMPTY) {
                    r++
                    continue
                }
                var matchLen = 1
                while (r + matchLen < GameConfig.ROWS && grid[r + matchLen][c] == current) {
                    matchLen++
                }
                if (matchLen >= 3) {
                    for (i in 0 until matchLen) tileEliminated.add(Pair(r + i, c))
                }
                r += matchLen
            }
        }

        return tileEliminated
    }

    /**
     * 비정형 격자 전용 중력 시뮬레이터 (핵심 연산)
     * HOLE(-1) 구조물 좌표는 그대로 유지하고, 플레이 가능한 칸의 블록들만 아래로 쏠림
     */
    fun collapseGrid(grid: Array<IntArray>): Array<IntArray> {
        val nextGrid = Array(GameConfig.ROWS) { r -> grid[r].clone() }

        for (c in 0 until GameConfig.COLS) {
            // 해당 열에서 HOLE이 아닌 칸의 원래 블록들만 순서대로 추출 (EMPTY 제외)
            val availableBlocks = mutableListOf<Int>()
            for (r in 0 until GameConfig.ROWS) {
                if (grid[r][c] != HOLE && grid[r][c] != EMPTY) {
                    availableBlocks.add(grid[r][c])
                }
            }

            // 아래에서 위로 올라가면서 HOLE이 아닌 공간에만 블록을 아래 빽빽하게 채움
            var blockIdx = availableBlocks.size - 1
            for (r in GameConfig.ROWS - 1 downTo 0) {
                if (grid[r][c] != HOLE) {
                    if (blockIdx >= 0) {
                        nextGrid[r][c] = availableBlocks[blockIdx]
                        blockIdx--
                    } else {
                        nextGrid[r][c] = EMPTY // 위쪽 남은 빈 공간은 EMPTY 처리
                    }
                }
            }
        }
        return nextGrid
    }

    /**
     * 타일 폭파 처리
     */
    fun eliminateTiles(grid: Array<IntArray>, tileEliminated: Set<Pair<Int, Int>>): Array<IntArray> {
        for (coord in tileEliminated) {
            if (grid[coord.first][coord.second] != HOLE) { // HOLE은 파괴 불가
                grid[coord.first][coord.second] = EMPTY
            }
        }
        return grid
    }

    /**
     * 연쇄 반응 점수 측정
     */
    fun scoreGrid(initialGrid: Array<IntArray>): Int {
        var totalScore = 0
        var currentGrid = Array(GameConfig.ROWS) { r -> initialGrid[r].clone() }
        
        while (true) {
            val tileEliminated = tileMarkedForElimination(currentGrid)
            if (tileEliminated.isEmpty()) break
            
            totalScore += tileEliminated.size
            currentGrid = eliminateTiles(currentGrid, tileEliminated)
            currentGrid = collapseGrid(currentGrid)
        }
        return totalScore
    }

    /**
     * 단일 스왑 예측 점수 계산
     */
    fun swapScore(direction: String, r: Int, c: Int, grid: Array<IntArray>): Int {
        val tile = grid[r][c]
        if (tile == HOLE || tile == EMPTY) return 0 // 구조물이나 빈칸은 조작 불가능

        var targetRow = r
        var targetCol = c

        if (direction == "down") targetRow = rowRangeClamped(r + 1)
        if (direction == "right") targetCol = colRangeClamped(c + 1)

        val targetTile = grid[targetRow][targetCol]
        // 대상 타일이 벽이거나, 공백이거나, 같은 종류면 스왑 불가
        if (targetTile == HOLE || targetTile == EMPTY || tile == targetTile) return 0

        val testGrid = Array(GameConfig.ROWS) { row -> grid[row].clone() }
        testGrid[r][c] = targetTile
        testGrid[targetRow][targetCol] = tile

        if (matchCheck(testGrid, r, c) || matchCheck(testGrid, targetRow, targetCol)) {
            return scoreGrid(testGrid)
        }

        return 0
    }

    /**
     * 유효 후보군 산출 루프
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
