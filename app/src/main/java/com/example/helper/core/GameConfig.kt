package com.example.helper.core

object GameConfig {
    const val COLS = 7
    const val ROWS = 9
    
    // 타일 상태 정의
    const val HOLE = -1   // ★ 판마다 다른 고정 공백/벽 구조 (매칭X, 이동X, 중력고정)
    const val EMPTY = 5   // 블록이 터져서 일시적으로 비어버린 공간 (중력으로 채워짐)
    const val PIECES = 5  // 정상 블록 종류 개수 (0~4)

    val PIECE_COLORS = mapOf(
        "brown" to 10,
        "yellow" to 25,
        "green" to 35,
        "blue" to 95,
        "purple" to 120
    )
}
