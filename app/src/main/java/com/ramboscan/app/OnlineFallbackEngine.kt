package com.ramboscan.app

import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Zero-key deep-research fallback.
 *
 * This engine is used only when the phone-side cache/knowledge/calculator/LLM path
 * cannot produce a strong answer. It does not scrape Google result pages and does
 * not contain any OpenAI key. Instead it uses documented public APIs that work
 * without a secret key for modest personal use:
 *   - Wikipedia / Wikimedia Action API
 *   - Wikibooks / Wikimedia Action API
 *   - Stack Exchange API (selected technical/professional sites)
 */
class OnlineFallbackEngine {

    suspend fun search(question: String, maxItems: Int = 7): List<OnlineEvidence> = withContext(Dispatchers.IO) {
        coroutineScope {
            val wiki = async { runCatching { searchMediaWiki(WIKIPEDIA_API, "Wikipedia", question, 3) }.getOrDefault(emptyList()) }
            val books = async { runCatching { searchMediaWiki(WIKIBOOKS_API, "Wikibooks", question, 2) }.getOrDefault(emptyList()) }
            val stack = async { runCatching { searchStackExchange(question, 3) }.getOrDefault(emptyList()) }

            (wiki.await() + books.await() + stack.await())
                .distinctBy { it.url.ifBlank { it.source + "|" + it.title } }
                .filter { it.excerpt.length >= 40 }
                .take(maxItems)
        }
    }

    fun fallbackAnswer(evidence: List<OnlineEvidence>): String? {
        if (evidence.isEmpty()) return null
        val top = evidence.take(3)
        return buildString {
            appendLine("Online research found relevant material:")
            top.forEachIndexed { index, item ->
                append(index + 1)
                append(". ")
                append(item.excerpt.take(460))
                append("\nSource: ")
                append(item.source)
                if (item.title.isNotBlank()) append(" — ${item.title}")
                if (index != top.lastIndex) append("\n\n")
            }
        }.trim()
    }

    private fun searchMediaWiki(
        endpoint: String,
        sourceName: String,
        question: String,
        limit: Int
    ): List<OnlineEvidence> {
        val q = enc(question.take(350))
        val url = "$endpoint?action=query&generator=search&gsrsearch=$q&gsrlimit=$limit" +
            "&prop=extracts%7Cinfo&exintro=1&explaintext=1&exchars=900&inprop=url&format=json&formatversion=2"

        val root = JSONObject(get(url))
        val pages = root.optJSONObject("query")?.optJSONArray("pages") ?: return emptyList()
        val out = mutableListOf<OnlineEvidence>()
        for (i in 0 until pages.length()) {
            val page = pages.optJSONObject(i) ?: continue
            val excerpt = clean(page.optString("extract"))
            if (excerpt.isBlank()) continue
            out += OnlineEvidence(
                source = sourceName,
                title = page.optString("title"),
                excerpt = excerpt.take(900),
                url = page.optString("fullurl")
            )
        }
        return out
    }

    private fun searchStackExchange(question: String, maxItems: Int): List<OnlineEvidence> {
        val sites = chooseStackSites(question)
        if (sites.isEmpty()) return emptyList()

        val out = mutableListOf<OnlineEvidence>()
        for (site in sites.take(2)) {
            if (out.size >= maxItems) break
            val q = enc(question.take(300))
            val searchUrl = "$STACK_API/search/advanced?site=${enc(site)}&order=desc&sort=relevance" +
                "&pagesize=2&q=$q&filter=withbody"
            val searchRoot = JSONObject(get(searchUrl))
            val items = searchRoot.optJSONArray("items") ?: continue

            for (i in 0 until items.length()) {
                if (out.size >= maxItems) break
                val item = items.optJSONObject(i) ?: continue
                val qid = item.optLong("question_id", 0L)
                val title = decodeHtml(item.optString("title"))
                val link = item.optString("link")

                val answerText = if (qid > 0L) bestAnswer(site, qid) else null
                val questionBody = htmlToText(item.optString("body"))
                val excerpt = clean(answerText ?: questionBody)
                if (excerpt.length < 40) continue

                out += OnlineEvidence(
                    source = "Stack Exchange/$site",
                    title = title,
                    excerpt = excerpt.take(900),
                    url = link
                )
            }
        }
        return out
    }

    private fun bestAnswer(site: String, questionId: Long): String? {
        val url = "$STACK_API/questions/$questionId/answers?site=${enc(site)}&order=desc&sort=votes&pagesize=2&filter=withbody"
        val root = JSONObject(get(url))
        val items = root.optJSONArray("items") ?: return null
        if (items.length() == 0) return null

        var best: JSONObject? = null
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optBoolean("is_accepted")) return htmlToText(item.optString("body"))
            if (best == null || item.optInt("score") > best!!.optInt("score")) best = item
        }
        return best?.let { htmlToText(it.optString("body")) }
    }

    private fun chooseStackSites(question: String): List<String> {
        val q = question.lowercase(Locale.ROOT)
        return when {
            containsAny(q, "pmp", "project management", "critical path", "stakeholder", "scrum", "project risk", "earned value") -> listOf("pm")
            containsAny(q, "irr", "xirr", "dcf", "npv", "wacc", "valuation", "investment", "finance", "mortgage") -> listOf("money")
            containsAny(q, "hvac", "refriger", "compressor", "evaporator", "condenser", "pump", "boiler", "chiller", "electrical", "motor", "vfd") -> listOf("engineering", "diy")
            containsAny(q, "leed", "sustainability", "emission", "carbon", "energy efficiency") -> listOf("sustainability", "engineering")
            containsAny(q, "probability", "statistics", "regression", "monte carlo") -> listOf("stats", "math")
            containsAny(q, "equation", "calculus", "algebra", "geometry") -> listOf("math")
            else -> emptyList()
        }
    }

    private fun get(urlString: String): String {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "RAMBO-Scan/0.2.5 (private educational Android prototype)")
            instanceFollowRedirects = true
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun htmlToText(html: String): String {
        if (html.isBlank()) return ""
        return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()
    }

    private fun decodeHtml(text: String): String = htmlToText(text)

    private fun clean(text: String): String = text
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun enc(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun containsAny(text: String, vararg needles: String): Boolean = needles.any(text::contains)

    companion object {
        private const val WIKIPEDIA_API = "https://en.wikipedia.org/w/api.php"
        private const val WIKIBOOKS_API = "https://en.wikibooks.org/w/api.php"
        private const val STACK_API = "https://api.stackexchange.com/2.3"
        private const val CONNECT_TIMEOUT_MS = 7_000
        private const val READ_TIMEOUT_MS = 9_000
    }
}

data class OnlineEvidence(
    val source: String,
    val title: String,
    val excerpt: String,
    val url: String
) {
    fun promptSnippet(maxChars: Int = 520): String =
        "[$source | $title] ${excerpt.take(maxChars)}"
}
