package com.example.helper.core

data class MatchCandidate(
    val row: Int,
    val col: Int,
    val move: String,
    val score: Int
)

object Match3Solver {

    private fun rowRangeClamped(row: Int): Int = minOf(maxOf(0, row), GameConfig.ROWS - 1)
    private fun colRangeClamped(col: Int): Int = minOf(maxOf(0, col), GameConfig.COLS - 1)

    // 3개 이상 연속 매칭 확인
    fun consecutiveMatch(line: IntArray, targetTile: Int): Boolean {
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

    // 특정 셀 기준 가로/세로 매칭 매치 체크
    fun matchCheck(grid: Array<IntArray>, row: Int, col: Int): Boolean {
        val targetTile = grid[row][col]
        val colLine = IntArray(GameConfig.ROWS) { r -> grid[r][col] }
        return consecutiveMatch(grid[row], targetTile) || consecutiveMatch(colLine, targetTile)
    }

    // 판 전체에서 제거할 타일 좌표들(Set) 수집
    fun tileMarkedForElimination(grid: Array<IntArray>): Set<Pair<Int, Int>> {
        val tileEliminated = mutableSetOf<Pair<Int, Int>>()

        // 가로 방향(Rows) 검사
        for (r in 0 until GameConfig.ROWS) {
            val tileCandidates = mutableListOf<Pair<Int, Int>>()
            var tileCache = GameConfig.PIECES
            for (c in 0 until GameConfig.COLS) {
                val currentTile = grid[r][c]
                if (currentTile != tileCache) {
                    if (tileCandidates.size >= 3 && tileCache != GameConfig.PIECES) {
                        tileEliminated.addAll(tileCandidates)
                    }
                    tileCandidates.clear()
                    tileCandidates.add(Pair(r, c))
                    tileCache = currentTile
                } else {
                    tileCandidates.add(Pair(r, c))
                }
            }
            if (tileCandidates.size >= 3 && tileCache != GameConfig.PIECES) {
                tileEliminated.addAll(tileCandidates)
            }
        }

        // 세로 방향(Columns) 검사
        for (c in 0 until GameConfig.COLS) {
            val tileCandidates = mutableListOf<Pair<Int, Int>>()
            var tileCache = GameConfig.PIECES
            for (r in 0 until GameConfig.ROWS) {
                val currentTile = grid[r][c]
                if (currentTile != tileCache) {
                    if (tileCandidates.size >= 3 && tileCache != GameConfig.PIECES) {
                        tileEliminated.addAll(tileCandidates)
                    }
                    tileCandidates.clear()
                    tileCandidates.add(Pair(r, c))
                    tileCache = currentTile
                } else {
                    tileCandidates.add(Pair(r, c))
                }
            }
            if (tileCandidates.size >= 3 && tileCache != GameConfig.PIECES) {
                tileEliminated.addAll(tileCandidates)
            }
        }

        return tileEliminated
    }

    // 타일 제거 처리 (PIECES 값인 5로 채움)
    fun eliminateTiles(grid: Array<IntArray>, tileEliminated: Set<Pair<Int, Int>>): Array<IntArray> {
        for (coord in tileEliminated) {
            grid[coord.first][coord.second] = GameConfig.PIECES
        }
        return grid
    }

    // 중력 작용 시뮬레이션: 빈칸(5)은 위로 올라가고 블록은 아래로 쏠림
    fun collapseGrid(grid: Array<IntArray>): Array<IntArray> {
        val nextGrid = Array(GameConfig.ROWS) { IntArray(GameConfig.COLS) { GameConfig.PIECES } }
        for (c in 0 until GameConfig.COLS) {
            val filteredCol = mutableListOf<Int>()
            for (r in 0 until GameConfig.ROWS) {
                if (grid[r][c] != GameConfig.PIECES) {
                    filteredCol.add(grid[r][c])
                }
            }
            val missingTiles = GameConfig.ROWS - filteredCol.size
            for (r in 0 until GameConfig.ROWS) {
                if (r < missingTiles) {
                    nextGrid[r][c] = GameConfig.PIECES
                } else {
                    nextGrid[r][c] = filteredCol[r - missingTiles]
                }
            }
        }
        return nextGrid
    }

    // 연쇄 반응 콤보 스코어 계산 루프 (핵심 로직 직역)
    fun scoreGrid(initialGrid: Array<IntArray>): Int {
        var totalScore = 0
        // 그리드 깊은 복사
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

    // 단일 스왑 점수 계산
    fun swapScore(direction: String, tile: Int, r: Int, c: Int, grid: Array<IntArray>): Int {
        var targetRow = r
        var targetCol = c

        if (direction == "down") {
            targetRow = rowRangeClamped(r + 1)
        } else if (direction == "right") {
            targetCol = colRangeClamped(c + 1)
        }

        val targetTile = grid[targetRow][targetCol]
        if (tile == targetTile) return 0 // 동일한 블록 교환 무시

        // 복사본 생성 후 교환 시뮬레이션
        val testGrid = Array(GameConfig.ROWS) { row -> grid[row].clone() }
        testGrid[r][c] = targetTile
        testGrid[targetRow][targetCol] = tile

        // 교환 결과 매칭이 유효하게 생겼다면 연쇄 콤보 점수 추적
        if (matchCheck(testGrid, r, c) || matchCheck(testGrid, targetRow, targetCol)) {
            return scoreGrid(testGrid)
        }

        return 0
    }

    // 모든 탐색 가능 후보군 추출 (Down, Right 방향만 중복 없이 체크)
    fun getMatchCandidates(grid: Array<IntArray>): List<MatchCandidate> {
        val candidates = mutableListOf<MatchCandidate>()
        for (r in 0 until GameConfig.ROWS) {
            for (c in 0 until GameConfig.COLS) {
                val tile = grid[r][c]
                for (direction in listOf("down", "right")) {
                    val score = swapScore(direction, tile, r, c, grid)
                    if (score > 0) {
                        candidates.add(MatchCandidate(r, c, direction, score))
                    }
                }
            }
        }
        return candidates
    }
}
