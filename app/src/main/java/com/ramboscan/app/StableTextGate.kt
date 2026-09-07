package com.ramboscan.app

/**
 * Prevents blurry/partial OCR from being sent to the AI.
 * A candidate must be sufficiently readable and remain nearly identical
 * for several consecutive OCR results before it is accepted.
 *
 * After a result is shown, block() prevents the same visible question from
 * immediately retriggering when the 20-second display ends.
 */
class StableTextGate(
    private val requiredStableSamples: Int = 3,
    private val stableSimilarity: Double = 0.86,
    private val blockedSimilarity: Double = 0.82,
    private val unblockSimilarity: Double = 0.58,
    private val minChars: Int = 12,
    private val maxChars: Int = 900,
    private val minWords: Int = 3
) {
    private var previousKey: String = ""
    private var stableSamples: Int = 0
    private var blockedKey: String? = null

    fun offer(raw: String): String? {
        val cleaned = cleanForModel(raw)
        val key = normalizeForMatch(cleaned)

        if (!isEligible(cleaned, key)) {
            resetStability()
            return null
        }

        blockedKey?.let { blocked ->
            val blockedScore = similarity(key, blocked)
            when {
                blockedScore >= blockedSimilarity -> {
                    resetStability()
                    return null
                }
                blockedScore <= unblockSimilarity -> {
                    blockedKey = null
                    resetStability()
                }
                else -> {
                    // Scene is changing, but not enough to safely call it a new question yet.
                    resetStability()
                    return null
                }
            }
        }

        stableSamples = if (previousKey.isNotBlank() && similarity(previousKey, key) >= stableSimilarity) {
            stableSamples + 1
        } else {
            1
        }
        previousKey = key

        return if (stableSamples >= requiredStableSamples) {
            resetStability()
            cleaned
        } else {
            null
        }
    }

    fun block(raw: String) {
        val key = normalizeForMatch(raw)
        blockedKey = key.takeIf { it.isNotBlank() }
        resetStability()
    }

    fun clearAll() {
        blockedKey = null
        resetStability()
    }

    private fun resetStability() {
        previousKey = ""
        stableSamples = 0
    }

    private fun isEligible(cleaned: String, key: String): Boolean {
        if (cleaned.length !in minChars..maxChars) return false
        if (key.count { it.isLetter() } < 7) return false
        if (key.split(' ').count { it.isNotBlank() } < minWords) return false
        return true
    }

    companion object {
        fun cleanForModel(raw: String): String = raw
            .replace(Regex("[\\t\\r\\n]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .take(900)

        fun normalizeForMatch(raw: String): String = cleanForModel(raw)
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

        /** Character-bigram Dice coefficient. Robust to small OCR substitutions. */
        fun similarity(aRaw: String, bRaw: String): Double {
            val a = aRaw.trim()
            val b = bRaw.trim()
            if (a == b) return 1.0
            if (a.isEmpty() || b.isEmpty()) return 0.0
            if (a.length < 2 || b.length < 2) return if (a == b) 1.0 else 0.0

            val counts = HashMap<String, Int>()
            for (i in 0 until a.length - 1) {
                val pair = a.substring(i, i + 2)
                counts[pair] = (counts[pair] ?: 0) + 1
            }

            var intersection = 0
            for (i in 0 until b.length - 1) {
                val pair = b.substring(i, i + 2)
                val count = counts[pair] ?: 0
                if (count > 0) {
                    intersection++
                    counts[pair] = count - 1
                }
            }

            return (2.0 * intersection) / ((a.length - 1) + (b.length - 1))
        }
    }
}
