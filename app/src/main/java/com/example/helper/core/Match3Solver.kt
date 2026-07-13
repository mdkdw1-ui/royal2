package com.example.helper.core

import com.example.helper.core.GameConfig.HOLE

data class MatchCandidate(
    val row: Int,
    val col: Int,
    val direction: String, // SolverService 연동 규격 준수
    val score: Int
)

object Match3Solver {

    /**
     * 9x11 그리드 전체를 탐색하여 모든 유효한 스왑 후보군을 점수 높은 순으로 추출합니다.
     */
    fun getMatchCandidates(grid: Array<IntArray>): List<MatchCandidate> {
        val candidates = mutableListOf<MatchCandidate>()
        val rows = grid.size
        val cols = if (rows > 0) grid[0].size else 0
        
        // 9행 11열 전체를 한 칸도 빠짐없이 전수조사합니다.
        for (r in 0 until rows) {
            for (c in 0 until cols) {
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

    /**
     * 특정 위치를 스왑했을 때 터지는 블록 개수와 5개 연속 매칭(OO-OO 완성) 보너스를 통합 계산합니다.
     */
    fun swapScore(direction: String, r: Int, c: Int, grid: Array<IntArray>): Int {
        val rows = grid.size
        val cols = if (rows > 0) grid[0].size else 0

        val tile = grid[r][c]
        if (tile == HOLE) return 0 // 장애물(벽)은 스왑 대상에서 제외

        var targetRow = r
        var targetCol = c

        // 9x11 경계 안에서만 움직이도록 안전 바운더리 체크
        if (direction == "down") targetRow = minOf(maxOf(0, r + 1), rows - 1)
        if (direction == "right") targetCol = minOf(maxOf(0, c + 1), cols - 1)

        // 제자리 걸음이거나 같은 색상의 블록이면 스왑 무효
        if (targetRow == r && targetCol == c) return 0
        
        val targetTile = grid[targetRow][targetCol]
        if (targetTile == HOLE || tile == targetTile) return 0

        // 가상 스왑 매트릭스 생성 (9x11 크기 동적 복사)
        val testGrid = Array(rows) { row -> grid[row].clone() }
        testGrid[r][c] = targetTile
        testGrid[targetRow][targetCol] = tile

        // 이 스왑으로 터지게 되는 모든 타일들의 좌표 수집
        val eliminated = findEliminatedTiles(testGrid)
        if (eliminated.isEmpty()) return 0

        // 5개 연속 매칭 정밀 측정 및 초고전역 가중치 부여
        return calculateAdvancedScore(testGrid, eliminated)
    }

    /**
     * 확장된 9x11 보드판에서 3개 이상 연속된 모든 타일의 좌표를 찾아냅니다.
     */
    private fun findEliminatedTiles(grid: Array<IntArray>): Set<Pair<Int, Int>> {
        val eliminated = mutableSetOf<Pair<Int, Int>>()
        val rows = grid.size
        val cols = if (rows > 0) grid[0].size else 0

        // 가로 방향 정밀 스캔 (11열 끝까지 탐색)
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

        // 세로 방향 정밀 스캔 (9행 끝까지 탐색)
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
     * 터지는 타일 중 '한 줄로 정렬된 최대 길이'를 판별하여, 5개 매칭에 무조건적인 최우선 순위를 부여합니다.
     */
    private fun calculateAdvancedScore(grid: Array<IntArray>, eliminated: Set<Pair<Int, Int>>): Int {
        val rows = grid.size
        val cols = if (rows > 0) grid[0].size else 0
        var maxMatchLen = 0
        
        // 가로축 단일 컴포넌트 최대 길이 측정
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

        // 세로축 단일 컴포넌트 최대 길이 측정
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

        // 🎯 [핵심] OO-OO 사이를 매꿔 5개 매칭(특수 블록 생성)이 되는 순간 다른 모든 매칭을 제치고 1순위로 띄웁니다.
        if (maxMatchLen >= 5) {
            finalScore += 5000000 // 점수를 5백만 점으로 대폭 상향하여 무조건 선점
        } else if (maxMatchLen == 4) {
            finalScore += 10000
        }

        return finalScore
    }
}
