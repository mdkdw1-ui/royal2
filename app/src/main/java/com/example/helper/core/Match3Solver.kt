package com.example.helper.core

import android.graphics.Point

class Match3Solver {

    /**
     * OOXOO → X 자리에 위/아래(세로는 좌/우) O를 끌어와 OOOOO 완성 가능한 X 위치만 반환.
     * Point.x = 열, Point.y = 행. (HOLE(-1)은 색으로 취급하지 않음)
     */
    fun findFiveLineSetups(board: Array<IntArray>): List<Point> {
        if (board.isEmpty()) return emptyList()
        val rows = board.size
        val cols = board[0].size
        val targets = LinkedHashSet<Point>()

        fun valid(v: Int) = v >= 0 && v < GameConfig.PIECES

        if (cols >= 5) {
            for (r in 0 until rows) for (c in 0..cols - 5) {
                val color = board[r][c]
                if (!valid(color)) continue
                val x = board[r][c + 2]
                if (board[r][c + 1] == color && board[r][c + 3] == color && board[r][c + 4] == color &&
                    x != color && x != GameConfig.HOLE) {
                    val xc = c + 2
                    if ((r > 0 && board[r - 1][xc] == color) || (r < rows - 1 && board[r + 1][xc] == color)) {
                        targets.add(Point(xc, r))
                    }
                }
            }
        }
        if (rows >= 5) {
            for (c in 0 until cols) for (r in 0..rows - 5) {
                val color = board[r][c]
                if (!valid(color)) continue
                val x = board[r + 2][c]
                if (board[r + 1][c] == color && board[r + 3][c] == color && board[r + 4][c] == color &&
                    x != color && x != GameConfig.HOLE) {
                    val xr = r + 2
                    if ((c > 0 && board[xr][c - 1] == color) || (c < cols - 1 && board[xr][c + 1] == color)) {
                        targets.add(Point(c, xr))
                    }
                }
            }
        }
        return targets.toList()
    }
}
