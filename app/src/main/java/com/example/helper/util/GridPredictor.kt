package com.example.helper.util

import android.content.Context

object GridPredictor {
    private const val K = 3
    private const val MAX_DISTANCE = 0.15f
    private const val MIN_SEEDS = 3

    data class Prediction(
        val rows: Int,
        val cols: Int,
        val confidence: Float,
        val nearestDistance: Float,
        val seedCount: Int
    )

    /**
     * k-NN 예측.
     * DB가 부족하면 null 반환 → 자동검출로 폴백.
     */
    fun predict(context: Context, feature: FloatArray): Prediction? {
        val seeds = GridSeedDB.loadAll(context)
        if (seeds.size < MIN_SEEDS) return null

        val distances = seeds.map { seed ->
            seed to GridFeature.distance(seed.feature, feature)
        }.sortedBy { it.second }

        val nearest = distances.first()
        if (nearest.second > MAX_DISTANCE) return null

        val topK = distances.take(K)

        // manual seed 가중치 (2배)
        val votes = mutableMapOf<Pair<Int, Int>, Float>()
        topK.forEach { (seed, dist) ->
            val weight = (1f - dist).coerceAtLeast(0.01f) * (if (seed.manual) 2f else 1f)
            val key = seed.rows to seed.cols
            votes[key] = (votes[key] ?: 0f) + weight
        }

        val winner = votes.maxByOrNull { it.value } ?: return null
        val totalWeight = votes.values.sum()
        val confidence = winner.value / totalWeight

        return Prediction(
            rows = winner.key.first,
            cols = winner.key.second,
            confidence = confidence,
            nearestDistance = nearest.second,
            seedCount = seeds.size
        )
    }
}
