package com.ramboscan.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.util.Locale

/**
 * Local answer memory.
 * Exact matches are instant. Very close paraphrases can reuse a recent answer too.
 */
class AnswerCache(context: Context) : SQLiteOpenHelper(context, "rambo_scan.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE answers (
                question_hash TEXT PRIMARY KEY,
                question_text TEXT NOT NULL,
                answer_text TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun get(question: String): String? {
        val hash = hash(question)
        readableDatabase.query(
            "answers",
            arrayOf("answer_text"),
            "question_hash = ?",
            arrayOf(hash),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    /** Reuses only highly similar recent questions to avoid wrong-answer cache hits. */
    fun getSimilar(question: String, threshold: Double = 0.90): String? {
        val q = tokens(question)
        if (q.size < 3) return null

        var bestScore = 0.0
        var bestAnswer: String? = null

        readableDatabase.query(
            "answers",
            arrayOf("question_text", "answer_text"),
            null,
            null,
            null,
            null,
            "updated_at DESC",
            "120"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val other = tokens(cursor.getString(0))
                val score = jaccard(q, other)
                if (score > bestScore) {
                    bestScore = score
                    bestAnswer = cursor.getString(1)
                }
            }
        }
        return bestAnswer?.takeIf { bestScore >= threshold }
    }

    fun put(question: String, answer: String) {
        if (answer.isBlank()) return
        val values = ContentValues().apply {
            put("question_hash", hash(question))
            put("question_text", StableTextGate.cleanForModel(question))
            put("answer_text", answer)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "answers",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun hash(question: String): String {
        val normalized = StableTextGate.normalizeForMatch(question)
        val bytes = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun tokens(text: String): Set<String> = text
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .split(' ')
        .filter { it.length >= 2 }
        .toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / a.union(b).size.toDouble()
    }
}
