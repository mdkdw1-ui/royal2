package com.example.helper.core

import android.graphics.Point

class Match3Solver {

    /**
     * OOXOO 형태에서 가운데 X를 O로 치환하여
     * OOOOO를 만들 수 있는 위치만 반환한다.
     *
     * 반환되는 Point:
     *   x = 열(column)
     *   y = 행(row)
     *
     * 조건:
     *   가로: O O X O O
     *   세로: O O X O O
     *
     * 그리고 X의 바로 옆(상/하/좌/우)에
     * 같은 O가 하나라도 있어야 한다.
     *
     * 즉, 다른 매치나 3연쇄/4연쇄/점수 계산은 하지 않는다.
     */
    fun findFiveLineSetups(board: Array<IntArray>): List<Point> {
        if (board.isEmpty()) {
            return emptyList()
        }

        val rows = board.size
        val cols = board.firstOrNull()?.size ?: return emptyList()

        if (cols == 0) {
            return emptyList()
        }

        val targets = LinkedHashSet<Point>()

        // ---------------------------------------------------------
        // 1. 가로 O O X O O
        // ---------------------------------------------------------
        if (cols >= 5) {
            for (r in 0 until rows) {
                for (c in 0..cols - 5) {

                    val color = board[r][c]

                    // 첫 번째 O가 빈칸이면 무시
                    if (color == 0) {
                        continue
                    }

                    // O O X O O
                    val firstO = board[r][c]
                    val secondO = board[r][c + 1]
                    val x = board[r][c + 2]
                    val fourthO = board[r][c + 3]
                    val fifthO = board[r][c + 4]

                    // 양쪽의 O 4개가 모두 같은 색이어야 한다.
                    if (
                        firstO == color &&
                        secondO == color &&
                        fourthO == color &&
                        fifthO == color &&
                        x != color
                    ) {
                        /*
                         * 가운데 X의 바로 위 또는 아래에
                         * 같은 O가 있는지 확인한다.
                         *
                         * 있으면 그 O를 X 위치로 옮길 수 있으므로
                         * OOOOO 완성이 가능하다고 판단한다.
                         */
                        val xColumn = c + 2

                        val hasAdjacentO =
                            (r > 0 && board[r - 1][xColumn] == color) ||
                            (r < rows - 1 && board[r + 1][xColumn] == color)

                        if (hasAdjacentO) {
                            targets.add(Point(xColumn, r))
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------
        // 2. 세로 O
        //    O
        //    O
        //    X
        //    O
        //    O
        // ---------------------------------------------------------
        if (rows >= 5) {
            for (c in 0 until cols) {
                for (r in 0..rows - 5) {

                    val color = board[r][c]

                    // 첫 번째 O가 빈칸이면 무시
                    if (color == 0) {
                        continue
                    }

                    // O O X O O
                    val firstO = board[r][c]
                    val secondO = board[r + 1][c]
                    val x = board[r + 2][c]
                    val fourthO = board[r + 3][c]
                    val fifthO = board[r + 4][c]

                    // 양쪽의 O 4개가 모두 같은 색이어야 한다.
                    if (
                        firstO == color &&
                        secondO == color &&
                        fourthO == color &&
                        fifthO == color &&
                        x != color
                    ) {
                        /*
                         * 가운데 X의 바로 왼쪽 또는 오른쪽에
                         * 같은 O가 있는지 확인한다.
                         */
                        val xRow = r + 2

                        val hasAdjacentO =
                            (c > 0 && board[xRow][c - 1] == color) ||
                            (c < cols - 1 && board[xRow][c + 1] == color)

                        if (hasAdjacentO) {
                            targets.add(Point(c, xRow))
                        }
                    }
                }
            }
        }

        return targets.toList()
    }
}
