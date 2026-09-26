package com.example.helper.util

import android.content.Context

object GridPredictor {
    private const val K = 3
    private const val MAX_DISTANCE = 0.20f
    private const val MIN_SEEDS = 2

    data class Prediction(
        val rows: Int,
        val cols: Int,
        val confidence: Float,
        val nearestDistance: Float,
        val seedCount: Int,
        val position: FloatArray? = null  // 🔥 v26
    )

    fun predict(context: Context, feature: FloatArray): Prediction? {
        val seeds = GridSeedDB.loadAll(context)
        if (seeds.size < MIN_SEEDS) return null

        val distances = seeds.map { seed -> seed to GridFeature.distance(seed.feature, feature) }
            .sortedBy { it.second }

        val nearest = distances.first()
        if (nearest.second > MAX_DISTANCE) return null

        val topK = distances.take(K)
        val votes = mutableMapOf<Pair<Int, Int>, Float>()
        var bestSeedForWinner: GridSeedDB.Seed? = null
        var bestDistForWinner = Float.MAX_VALUE

        topK.forEach { (seed, dist) ->
            val weight = (1f - dist).coerceAtLeast(0.01f) * (if (seed.manual) 5f else 1f)
            val key = seed.rows to seed.cols
            votes[key] = (votes[key] ?: 0f) + weight
        }

        val winner = votes.maxByOrNull { it.value } ?: return null
        val totalWeight = votes.values.sum()
        val confidence = winner.value / totalWeight

        // 🔥 v26: winner 클래스 내 가장 가까운 seed의 위치 반환
        topK.filter { it.first.rows == winner.key.first && it.first.cols == winner.key.second }
            .forEach { (seed, dist) ->
                if (dist < bestDistForWinner && seed.position != null) {
                    bestDistForWinner = dist
                    bestSeedForWinner = seed
                }
            }

        return Prediction(
            rows = winner.key.first,
            cols = winner.key.second,
            confidence = confidence,
            nearestDistance = nearest.second,
            seedCount = seeds.size,
            position = bestSeedForWinner?.position?.copyOf()
        )
    }
}
