package com.example.helper.core

import android.graphics.Point

class Match3Solver {

    // 분석 루프에서 호출할 5연쇄 유도 감지기
    fun findFiveLineSetups(board: Array<IntArray>): List<Point> {
        val targets = mutableListOf<Point>()
        val rows = board.size
        val cols = board[0].size

        // 1. 가로축 OOXOO (A A X A A) 탐색 필터
        for (r in 0 until rows) {
            for (c in 0 until cols - 4) {
                val color = board[r][c]
                if (color == 0) continue // 빈칸 패스

                // 패턴 매칭: 0,1,3,4번 칸의 색상이 일치하고 2번(가운데 X)만 다른 색인가?
                if (board[r][c+1] == color && board[r][c+3] == color && board[r][c+4] == color && board[r][c+2] != color) {
                    
                    // 완성 유도 조건 검증: 위나 아래 칸에 일치하는 메인 색상이 대기 중인가?
                    if (r > 0 && board[r-1][c+2] == color) {
                        targets.add(Point(c+2, r)) // 위 블록을 아래로 내리라고 중앙 좌표 수집!
                    } else if (r < rows - 1 && board[r+1][c+2] == color) {
                        targets.add(Point(c+2, r)) // 아래 블록을 위로 올리라고 중앙 좌표 수집!
                    }
                }
            }
        }

        // 2. 세로축 OOXOO (수직방향 A A X A A) 탐색 필터
        for (c in 0 until cols) {
            for (r in 0 until rows - 4) {
                val color = board[r][c]
                if (color == 0) continue

                // 수직 패턴 검증
                if (board[r+1][c] == color && board[r+3][c] == color && board[r+4][c] == color && board[r+2][c] != color) {
                    
                    // 완성 유도 조건 검증: 좌측이나 우측 칸에 일치하는 색상이 대기 중인가?
                    if (c > 0 && board[r+2][c-1] == color) {
                        targets.add(Point(c, r+2))
                    } else if (c < cols - 1 && board[r+2][c+1] == color) {
                        targets.add(Point(c, r+2))
                    }
                }
            }
        }

        return targets // 오직 5연쇄 유도 박스 위치들만 반환 (3, 4 연쇄용 리스트는 아예 수집하지 않음)
    }
}
