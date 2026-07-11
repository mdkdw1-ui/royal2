package com.example.helper

import kotlin.math.abs

/**
 * 매치3 퍼즐의 매칭 탐지, 중력 시뮬레이션, 최선의 수 탐색을 담당하는 핵심 알고리즘 클래스
 */
object Match3Solver {

    // 후보군 구조 정의 (Row, Col, 이동방향, 매칭되어 터지는 블록 수)
    data class MatchCandidate(
        val row: Int,
        val col: Int,
        val direction: String,
        val score: Int
    )

    /**
     * 1차원 리스트에서 특정 블록(targetValue)이 3개 이상 연속으로 매칭되는지 확인
     */
    fun consecutiveMatch(line: List<Int>, targetValue: Int): Boolean {
        var consecutiveCount = 0
        for (value in line) {
            if (value == targetValue) {
                consecutiveCount++
                if (consecutiveCount >= 3) return true
            } else {
                consecutiveCount = 0
            }
        }
        return false
    }

    /**
     * 2차원 그리드 판에서 가로/세로 3개 이상 매칭되어 제거될 블록들의 좌표(Pair) 세트를 수집
     */
    fun tileMarkedForElimination(grid: Array<IntArray>): Set<Pair<Int, Int>> {
        val eliminated = mutableSetOf<Pair<Int, Int>>()
        val rows = grid.size
        if (rows == 0) return eliminated
        val cols = grid[0].size

        // 1. 가로 방향 매칭 검사
        for (r in 0 until rows) {
            var c = 0
            while (c < cols) {
                val currentValue = grid[r][c]
                if (currentValue == 5) { // 5는 빈 공간(Empty)이므로 매칭 제외
                    c++
                    continue
                }
                var matchLen = 1
                while (c + matchLen < cols && grid[r][c + matchLen] == currentValue) {
                    matchLen++
                }
                if (matchLen >= 3) {
                    for (i in 0 until matchLen) {
                        eliminated.add(Pair(r, c + i))
                    }
                }
                c += matchLen
            }
        }

        // 2. 세로 방향 매칭 검사
        for (c in 0 until cols) {
            var r = 0
            while (r < rows) {
                val currentValue = grid[r][c]
                if (currentValue == 5) {
                    r++
                    continue
                }
                var matchLen = 1
                while (r + matchLen < rows && grid[r + matchLen][c] == currentValue) {
                    matchLen++
                }
                if (matchLen >= 3) {
                    for (i in 0 until matchLen) {
                        eliminated.add(Pair(r + i, c))
                    }
                }
                r += matchLen
            }
        }

        return eliminated
    }

    /**
     * 블록이 터진 후 중력 작용 시뮬레이션 (5는 빈 칸)
     * 실제 블록들은 순서를 유지한 채 아래로 떨어지고 빈 공간은 최상단으로 이동
     */
    fun collapseGrid(grid: Array<IntArray>): Array<IntArray> {
        val rows = grid.size
        if (rows == 0) return grid
        val cols = grid[0].size
        val collapsed = Array(rows) { IntArray(cols) { 5 } }

        for (c in 0 until cols) {
            var writeRow = rows - 1
            // 아래에서 위로 올라가며 빈 칸(5)이 아닌 블록들을 순서대로 채움
            for (r in rows - 1 downTo 0) {
                if (grid[r][c] != 5) {
                    collapsed[writeRow][c] = grid[r][c]
                    writeRow--
                }
            }
        }
        return collapsed
    }

    /**
     * 현재 보드판 상태에서 유효한 스왑(오른쪽, 아래)을 모두 시도하고 점수가 높은 매칭 후보 반환
     */
    fun getMatchCandidates(grid: Array<IntArray>): List<MatchCandidate> {
        val candidates = mutableListOf<MatchCandidate>()
        val rows = grid.size
        if (rows == 0) return candidates
        val cols = grid[0].size

        // 그리드 깊은 복사 함수
        fun copyGrid(src: Array<IntArray>): Array<IntArray> = Array(src.size) { src[it].clone() }

        // 스왑 테스트 진행 및 가치(Score) 평가
        fun trySwap(r1: Int, c1: Int, r2: Int, c2: Int, direction: String) {
            if (r2 in 0 until rows && c2 in 0 until cols) {
                val testGrid = copyGrid(grid)
                // 값 맞바꾸기
                val temp = testGrid[r1][c1]
                testGrid[r1][c1] = testGrid[r2][c2]
                testGrid[r2][c2] = temp

                // 제거 가능한 타일 개수 계산
                val matchCount = tileMarkedForElimination(testGrid).size
                if (matchCount > 0) {
                    candidates.add(MatchCandidate(r1, c1, direction, matchCount))
                }
            }
        }

        // 전체 격자를 돌며 가로(right) 및 세로(down) 스왑 검사
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                trySwap(r, c, r, c + 1, "right")
                trySwap(r, c, r + 1, c, "down")
            }
        }

        // 점수(터지는 개수)가 높은 순으로 정렬하여 반환
        return candidates.sortedByDescending { it.score }
    }
}
