package com.example.helper.core

object GameConfig {
    const val COLS = 7
    const val ROWS = 9
    const val PIECES = 5 // 블록 색상 종류 수(0~4), 5는 빈 칸(Empty)을 의미

    // OpenCV HSV 분석용 색상 매핑 기준값
    val PIECE_COLORS = mapOf(
        "brown" to 10,
        "yellow" to 25,
        "green" to 35,
        "blue" to 95,
        "purple" to 120
    )
}
