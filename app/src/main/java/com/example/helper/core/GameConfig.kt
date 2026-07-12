package com.example.helper.core

object GameConfig {
    // 🟢 const val에서 var로 변경하여 실시간 조절이 가능하도록 합니다.
    var COLS = 7   
    var ROWS = 9   
    
    const val HOLE = -1   
    const val EMPTY = 5   
    const val PIECES = 5  

    val PIECE_COLORS = mapOf(
        "brown" to 10,
        "yellow" to 25,
        "green" to 35,
        "blue" to 95,
        "purple" to 120
    )
}
