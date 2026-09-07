package com.ramboscan.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

/**
 * Offline knowledge layer used before the on-device LLM answers.
 *
 * The built-in pack lives in assets/knowledge_core.tsv. The database uses FTS4,
 * which is broadly available on Android, to retrieve candidate passages quickly.
 * Candidates are then re-ranked in Kotlin with title/tag/domain boosts.
 */
class KnowledgeStore(private val context: Context) :
    SQLiteOpenHelper(context, "rambo_knowledge.db", null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE VIRTUAL TABLE knowledge_fts USING fts4(
                category,
                title,
                tags,
                body,
                source
            )
            """.trimIndent()
        )
        seedBuiltInPack(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 4) {
            db.execSQL("DROP TABLE IF EXISTS knowledge_fts")
            onCreate(db)
        }
    }

    fun search(question: String, limit: Int = 5): List<KnowledgeHit> {
        val expandedQuestion = QueryExpander.expand(question)
        val tokens = queryTokens(expandedQuestion)
        if (tokens.isEmpty()) return emptyList()

        val match = tokens
            .take(10)
            .joinToString(" OR ") { token -> "${escapeFts(token)}*" }

        val candidates = mutableListOf<KnowledgeHit>()
        readableDatabase.query(
            "knowledge_fts",
            arrayOf("rowid", "category", "title", "tags", "body", "source"),
            "knowledge_fts MATCH ?",
            arrayOf(match),
            null,
            null,
            null,
            "30"
        ).use { cursor ->
            val rowId = cursor.getColumnIndexOrThrow("rowid")
            val category = cursor.getColumnIndexOrThrow("category")
            val title = cursor.getColumnIndexOrThrow("title")
            val tags = cursor.getColumnIndexOrThrow("tags")
            val body = cursor.getColumnIndexOrThrow("body")
            val source = cursor.getColumnIndexOrThrow("source")

            while (cursor.moveToNext()) {
                candidates += KnowledgeHit(
                    id = cursor.getLong(rowId),
                    category = cursor.getString(category),
                    title = cursor.getString(title),
                    tags = cursor.getString(tags),
                    body = cursor.getString(body),
                    source = cursor.getString(source),
                    score = 0.0
                )
            }
        }

        val preferredDomains = DomainRouter.detect(expandedQuestion)
        return candidates
            .map { hit -> hit.copy(score = score(expandedQuestion, tokens, hit, preferredDomains)) }
            .filter { it.score >= 1.0 }
            .sortedByDescending { it.score }
            .take(limit)
    }

    /**
     * A database-only answer for phones where Gemini Nano is unavailable or fails.
     * It is intentionally extractive: it does not invent missing facts.
     */
    fun fallbackAnswer(hits: List<KnowledgeHit>): String? {
        val best = hits.firstOrNull() ?: return null
        if (best.score < 2.0) return null

        val primary = best.body.trim().take(720)
        val second = hits.drop(1)
            .firstOrNull { it.category == best.category && it.score >= best.score * 0.72 }

        return if (second != null && primary.length < 500) {
            "$primary\n\nRelated: ${second.body.trim().take(280)}"
        } else {
            primary
        }
    }

    private fun seedBuiltInPack(db: SQLiteDatabase) {
        val packs = context.assets.list("")
            ?.filter { it.startsWith("knowledge_") && it.endsWith(".tsv") }
            ?.sorted()
            .orEmpty()

        for (pack in packs) {
            context.assets.open(pack).bufferedReader().useLines { lines ->
                lines.drop(1).forEach { line ->
                    if (line.isBlank()) return@forEach
                    val cols = line.split('\t', limit = 5)
                    if (cols.size < 5) return@forEach
                    val values = ContentValues().apply {
                        put("category", cols[0])
                        put("title", cols[1])
                        put("tags", cols[2])
                        put("body", cols[3])
                        put("source", cols[4])
                    }
                    db.insert("knowledge_fts", null, values)
                }
            }
        }
    }

    private fun score(
        question: String,
        tokens: List<String>,
        hit: KnowledgeHit,
        preferredDomains: Set<String>
    ): Double {
        val q = normalize(question)
        val title = normalize(hit.title)
        val tags = normalize(hit.tags)
        val body = normalize(hit.body)
        val category = normalize(hit.category)
        val titleWords = wordSet(title)
        val titleInitialism = initialism(title)
        val tagWords = wordSet(tags)
        val bodyWords = wordSet(body)
        val categoryWords = wordSet(category)

        var total = 0.0
        for (token in tokens) {
            if (token in titleWords) total += 5.0
            if (token == titleInitialism && token.length in 2..8) total += 12.0
            if (token in tagWords) total += 5.0
            if (token in categoryWords) total += 2.0
            if (token in bodyWords) total += 1.0
        }

        // Strong phrase boost for questions close to a title/tag phrase.
        if (title.isNotBlank() && q.contains(title)) total += 10.0
        if (hit.title.length >= 6 && tokenJaccard(q, title) >= 0.55) total += 6.0

        if (preferredDomains.any { domain -> category.startsWith(normalize(domain)) }) {
            total += 4.0
        }

        return total
    }

    private fun queryTokens(text: String): List<String> = normalize(text)
        .split(' ')
        .asSequence()
        .filter { it.length >= 2 && it !in STOP_WORDS }
        .distinct()
        .sortedByDescending { it.length }
        .toList()

    private fun wordSet(text: String): Set<String> = text.split(' ').filter { it.length >= 2 }.toSet()

    private fun initialism(text: String): String = text
        .split(' ')
        .filter { it.length >= 2 && it !in STOP_WORDS }
        .joinToString("") { it.take(1) }

    private fun tokenJaccard(a: String, b: String): Double {
        val aa = wordSet(a)
        val bb = wordSet(b)
        if (aa.isEmpty() || bb.isEmpty()) return 0.0
        return aa.intersect(bb).size.toDouble() / aa.union(bb).size.toDouble()
    }

    private fun normalize(text: String): String = text
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun escapeFts(token: String): String = token.replace("\"", "")

    companion object {
        private const val DB_VERSION = 4
        private val STOP_WORDS = setOf(
            "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "is", "are",
            "was", "were", "be", "what", "why", "how", "when", "where", "which", "with",
            "does", "do", "did", "can", "could", "would", "should", "this", "that", "from"
        )
    }
}

data class KnowledgeHit(
    val id: Long,
    val category: String,
    val title: String,
    val tags: String,
    val body: String,
    val source: String,
    val score: Double
) {
    fun promptSnippet(maxChars: Int = 430): String =
        "[${category} | ${title} | Source: ${source}] ${body.take(maxChars)}"
}

/** Lightweight domain routing. No extra AI call is required. */
object DomainRouter {
    fun detect(question: String): Set<String> {
        val q = question.lowercase(Locale.ROOT)
        val out = linkedSetOf<String>()

        if (containsAny(q, "hvac", "refriger", "compressor", "evaporator", "condenser", "suction", "superheat", "subcool")) {
            out += "HVAC/Refrigeration"
        }
        if (containsAny(q, "ahu", "vav", "duct", "airflow", "static pressure", "economizer", "fan")) {
            out += "HVAC/Air Systems"
        }
        if (containsAny(q, "pump", "chilled water", "hot water", "hydronic", "delta t", "cavitation", "npsh")) {
            out += "HVAC/Hydronics"
        }
        if (containsAny(q, "bas", "pid", "sensor", "vfd", "control", "setpoint", "trend", "actuator")) {
            out += "HVAC/Controls"
        }
        if (containsAny(q, "humidity", "dew point", "wet bulb", "psychrometric", "enthalpy", "latent", "sensible")) {
            out += "HVAC/Psychrometrics"
        }
        if (containsAny(q, "boiler", "chiller", "cooling tower", "maintenance", "heat exchanger")) {
            out += "Building Systems"
        }
        if (containsAny(q, "voltage", "current", "resistance", "motor", "contactor", "transformer", "three phase", "power factor")) {
            out += "Electrical Fundamentals"
        }
        if (containsAny(q, "noi", "cap rate", "dscr", "ltv", "irr", "xirr", "npv", "equity multiple", "moic", "dcf", "dcv", "waterfall", "promote", "preferred return", "real estate")) {
            out += "Real Estate Finance"
        }
        if (containsAny(q, "pmp", "project management", "stakeholder", "project risk", "earned value", "critical path", "agile", "scrum", "project charter", "wbs")) {
            out += "PMP 2026"
        }
        if (containsAny(q, "leed", "green associate", "decarbonization", "ecological conservation", "quality of life", "integrative process", "leed v5")) {
            out += "LEED Green Associate v5"
        }
        if (containsAny(q, "fmva", "three statement", "3 statement", "financial model", "forecast", "valuation", "wacc", "enterprise value", "comparable company", "sensitivity analysis", "scenario analysis", "monte carlo", "regression", "terminal value")) {
            out += "FMVA & Financial Modeling"
        }
        if (containsAny(q, "asset management", "leasing", "business plan", "capex", "tenant rollover", "mark to market", "reforecast", "hold sell", "portfolio")) {
            out += "CRE Asset Management"
        }
        if (containsAny(q, "development", "entitlement", "zoning", "hard cost", "soft cost", "construction loan", "yield on cost", "stabilization", "lease up", "residual land value")) {
            out += "Real Estate Development"
        }
        if (containsAny(q, "energy", "kw", "kwh", "demand", "efficiency")) {
            out += "Energy"
        }
        if (containsAny(q, "scope 1", "scope 2", "scope 3", "eui", "sustainability", "emission")) {
            out += "Sustainability"
        }
        return out
    }

    private fun containsAny(text: String, vararg needles: String): Boolean = needles.any(text::contains)
}


/** Expands common finance/HVAC/certification abbreviations and OCR typos before FTS search. */
object QueryExpander {
    private val phrases = linkedMapOf(
        "dcv" to "dcf discounted cash flow",
        "dcf" to "discounted cash flow valuation terminal value wacc free cash flow",
        "irr" to "internal rate of return cash flow npv zero",
        "xirr" to "dated internal rate of return irregular dates cash flows",
        "npv" to "net present value discount rate cash flows",
        "moic" to "multiple on invested capital equity multiple",
        "waterfall" to "waterfall promote preferred return hurdle catch up residual split lp gp",
        "promote" to "waterfall promote carried interest preferred return hurdle split",
        "pref" to "preferred return hurdle waterfall",
        "yoc" to "yield on cost development stabilized noi total project cost",
        "ltv" to "loan to value debt property value",
        "dscr" to "debt service coverage ratio noi debt service",
        "wacc" to "weighted average cost of capital dcf discount rate",
        "cagr" to "compound annual growth rate beginning ending years",
        "ev" to "enterprise value equity value net debt",
        "noi" to "net operating income real estate",
        "fcf" to "free cash flow dcf valuation",
        "evm" to "earned value management pmp pv ev ac cpi spi eac",
        "cpi" to "cost performance index earned value actual cost pmp",
        "spi" to "schedule performance index earned value planned value pmp"
    )

    fun expand(question: String): String {
        val lower = question.lowercase(Locale.ROOT)
        val extras = phrases
            .filter { (key, _) -> Regex("(^|[^a-z0-9])${Regex.escape(key)}([^a-z0-9]|$)").containsMatchIn(lower) }
            .values
            .distinct()
        return if (extras.isEmpty()) question else question + " " + extras.joinToString(" ")
    }
}
