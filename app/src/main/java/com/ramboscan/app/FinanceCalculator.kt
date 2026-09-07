package com.ramboscan.app

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Deterministic finance/math layer for RAMBO Scan.
 *
 * The local language model is useful for interpreting and explaining questions,
 * but it should not be trusted to perform high-stakes arithmetic from scratch.
 * This object computes supported formulas in normal Kotlin Double arithmetic and
 * returns calculation evidence that is injected into the AI prompt.
 */
object FinanceQuestionSolver {

    fun trySolve(rawQuestion: String): CalculationEvidence? {
        val q = rawQuestion.replace('−', '-').replace('–', '-').replace('—', '-').trim()
        val lower = q.lowercase()

        return when {
            containsAny(lower, "cpi", "cost performance index") && containsAny(lower, "earned value", " ev ", "actual cost", " ac ") -> solveCpi(q)
            containsAny(lower, "spi", "schedule performance index") && containsAny(lower, "earned value", " ev ", "planned value", " pv ") -> solveSpi(q)
            containsAny(lower, "cost variance", " cv ") && containsAny(lower, "earned value", "actual cost") -> solveCostVariance(q)
            containsAny(lower, "schedule variance", " sv ") && containsAny(lower, "earned value", "planned value") -> solveScheduleVariance(q)
            containsAny(lower, "waterfall", "promote", "preferred return", "pref return") -> solveWaterfall(q)
            containsAny(lower, "xirr", "dated irr") -> solveXirr(q)
            containsAny(lower, "irr", "internal rate of return") -> solveIrr(q)
            containsAny(lower, "npv", "net present value") -> solveNpv(q)
            containsAny(lower, "dcf", "dcv", "discounted cash flow") -> solveDcf(q)
            containsAny(lower, "cap rate", "capitalization rate") -> solveCapRate(q)
            containsAny(lower, "dscr", "debt service coverage") -> solveDscr(q)
            containsAny(lower, "ltv", "loan to value") -> solveLtv(q)
            containsAny(lower, "equity multiple", "moic", "multiple on invested capital") -> solveEquityMultiple(q)
            containsAny(lower, "cagr", "compound annual growth") -> solveCagr(q)
            containsAny(lower, "future value", " fv ") -> solveFutureValue(q)
            else -> null
        }
    }

    private fun solveIrr(q: String): CalculationEvidence? {
        val flows = extractCashFlows(q)
        if (flows.size < 2 || flows.none { it < 0 } || flows.none { it > 0 }) {
            return CalculationEvidence(
                kind = "IRR",
                summary = "IRR detected, but a reliable calculation needs an ordered cash-flow series containing at least one negative and one positive cash flow.",
                details = "Do not invent missing cash flows or timing. If the question supplies them, preserve their order from time 0 onward.",
                exact = false
            )
        }

        val roots = FinanceMath.irrRoots(flows)
        if (roots.isEmpty()) {
            return CalculationEvidence(
                kind = "IRR",
                summary = "No IRR root was found for the extracted cash flows ${formatFlows(flows)} within the solver range.",
                details = "IRR is the rate r for which NPV = Σ CF_t/(1+r)^t = 0. Some cash-flow patterns have no real IRR.",
                exact = true
            )
        }

        val rootText = roots.joinToString { formatPercent(it) }
        val multipleWarning = if (roots.size > 1) {
            " Multiple sign changes can create multiple IRRs, so IRR is not unique here."
        } else ""
        return CalculationEvidence(
            kind = "IRR",
            summary = "Deterministic IRR result: $rootText for cash flows ${formatFlows(flows)}.$multipleWarning",
            details = "Computed by numerically solving NPV(r)=0; do not replace this result with mental arithmetic.",
            exact = true
        )
    }

    private fun solveNpv(q: String): CalculationEvidence? {
        val flows = extractCashFlows(q)
        val rate = extractPercentNear(q, listOf("discount rate", "required return", "wacc", "rate"))
        if (flows.size < 2 || rate == null) {
            return CalculationEvidence(
                kind = "NPV",
                summary = "NPV detected, but a deterministic result requires an ordered cash-flow series and a discount rate.",
                details = "Formula: NPV = Σ CF_t/(1+r)^t. Confirm whether the initial investment is time 0.",
                exact = false
            )
        }
        val npv = FinanceMath.npv(rate, flows)
        return CalculationEvidence(
            kind = "NPV",
            summary = "Deterministic NPV result: ${formatMoneyLike(npv)} at ${formatPercent(rate)} for cash flows ${formatFlows(flows)}.",
            details = "Initial cash flow is treated as time 0 and is not discounted.",
            exact = true
        )
    }

    private fun solveDcf(q: String): CalculationEvidence? {
        val fcfs = extractSeriesAfterLabels(q, listOf("free cash flows", "free cash flow", "fcfs", "fcf"))
        val wacc = extractPercentNear(q, listOf("wacc", "discount rate", "required return"))
        val g = extractPercentNear(q, listOf("terminal growth", "perpetual growth", "growth rate", " g "))

        if (fcfs.isEmpty() || wacc == null) {
            return CalculationEvidence(
                kind = "DCF",
                summary = "DCF detected, but a deterministic valuation needs explicit forecast free cash flows and a discount rate/WACC.",
                details = "If a Gordon-growth terminal value is requested, terminal growth g is also required and must be below WACC. DCF/\"DCV\" typo is routed to the same topic.",
                exact = false
            )
        }

        val pvForecast = fcfs.mapIndexed { index, cf -> cf / (1.0 + wacc).pow(index + 1) }.sum()
        if (g == null) {
            return CalculationEvidence(
                kind = "DCF",
                summary = "Deterministic present value of the explicit forecast cash flows: ${formatMoneyLike(pvForecast)} using WACC ${formatPercent(wacc)}.",
                details = "No terminal growth rate was reliably extracted, so no terminal value was invented.",
                exact = true
            )
        }
        if (g >= wacc) {
            return CalculationEvidence(
                kind = "DCF",
                summary = "DCF inputs are mathematically invalid for Gordon growth because terminal growth ${formatPercent(g)} is not below WACC ${formatPercent(wacc)}.",
                details = "For TV = FCF_(n+1)/(WACC-g), WACC must exceed g.",
                exact = true
            )
        }

        val terminalFcf = fcfs.last() * (1.0 + g)
        val terminalValueAtN = terminalFcf / (wacc - g)
        val pvTerminal = terminalValueAtN / (1.0 + wacc).pow(fcfs.size)
        val enterpriseValue = pvForecast + pvTerminal
        return CalculationEvidence(
            kind = "DCF",
            summary = "Deterministic DCF: PV of forecast FCFs ${formatMoneyLike(pvForecast)} + PV of terminal value ${formatMoneyLike(pvTerminal)} = ${formatMoneyLike(enterpriseValue)}.",
            details = "Inputs: ${fcfs.size} forecast periods, WACC ${formatPercent(wacc)}, terminal growth ${formatPercent(g)}. Gordon-growth terminal value is based on next-period FCF.",
            exact = true
        )
    }

    private fun solveCapRate(q: String): CalculationEvidence? {
        val noi = extractNamedAmount(q, listOf("noi", "net operating income"))
        val value = extractNamedAmount(q, listOf("property value", "asset value", "purchase price", "price", "value"))
        if (noi == null || value == null || value == 0.0) return incomplete("Cap rate", "NOI and property value/price")
        return CalculationEvidence("Cap rate", "Deterministic cap rate: ${formatPercent(noi / value)}.", "NOI ÷ property value.", true)
    }

    private fun solveDscr(q: String): CalculationEvidence? {
        val noi = extractNamedAmount(q, listOf("noi", "net operating income"))
        val debtService = extractNamedAmount(q, listOf("annual debt service", "debt service"))
        if (noi == null || debtService == null || debtService == 0.0) return incomplete("DSCR", "NOI and debt service")
        return CalculationEvidence("DSCR", "Deterministic DSCR: ${formatNumber(noi / debtService)}x.", "NOI ÷ debt service.", true)
    }

    private fun solveLtv(q: String): CalculationEvidence? {
        val loan = extractNamedAmount(q, listOf("loan amount", "loan", "debt"))
        val value = extractNamedAmount(q, listOf("property value", "asset value", "purchase price", "value"))
        if (loan == null || value == null || value == 0.0) return incomplete("LTV", "loan amount and property value")
        return CalculationEvidence("LTV", "Deterministic LTV: ${formatPercent(loan / value)}.", "Loan amount ÷ property value.", true)
    }

    private fun solveEquityMultiple(q: String): CalculationEvidence? {
        val distributions = extractNamedAmount(q, listOf("total distributions", "distributions", "cash returned", "proceeds"))
        val invested = extractNamedAmount(q, listOf("equity invested", "invested equity", "initial equity", "investment", "equity"))
        if (distributions == null || invested == null || invested == 0.0) return incomplete("Equity multiple", "total equity distributions and invested equity")
        return CalculationEvidence("Equity multiple", "Deterministic equity multiple: ${formatNumber(distributions / invested)}x.", "Total equity distributions ÷ total equity invested.", true)
    }

    private fun solveCagr(q: String): CalculationEvidence? {
        val start = extractNamedAmount(q, listOf("beginning value", "starting value", "start value", "initial value"))
        val end = extractNamedAmount(q, listOf("ending value", "end value", "final value"))
        val years = extractNamedNumber(q, listOf("years", "year"))
        if (start == null || end == null || years == null || start <= 0 || end <= 0 || years <= 0) {
            return incomplete("CAGR", "positive starting value, ending value, and number of years")
        }
        val cagr = (end / start).pow(1.0 / years) - 1.0
        return CalculationEvidence("CAGR", "Deterministic CAGR: ${formatPercent(cagr)}.", "(Ending / Beginning)^(1/years) - 1.", true)
    }

    private fun solveFutureValue(q: String): CalculationEvidence? {
        val pv = extractNamedAmount(q, listOf("present value", "starting value", "initial amount", "principal"))
        val rate = extractPercentNear(q, listOf("growth rate", "interest rate", "rate", "return"))
        val years = extractNamedNumber(q, listOf("years", "year"))
        if (pv == null || rate == null || years == null || years < 0) return incomplete("Future value", "present value, annual rate, and years")
        val fv = pv * (1.0 + rate).pow(years)
        return CalculationEvidence("Future value", "Deterministic future value: ${formatMoneyLike(fv)}.", "PV × (1+r)^n using the stated rate; this is a scenario calculation, not a prediction.", true)
    }

    /**
     * Basic no-catch-up real-estate waterfall. We only calculate when the wording
     * explicitly supplies the economic terms. Waterfall documents vary materially,
     * so missing catch-up/compounding conventions are never guessed.
     */
    private fun solveWaterfall(q: String): CalculationEvidence {
        val lp = extractNamedAmount(q, listOf("lp contribution", "lp equity", "lp investment", "lp invested"))
        val gp = extractNamedAmount(q, listOf("gp contribution", "gp equity", "gp investment", "gp invested"))
        val cash = extractNamedAmount(q, listOf("distributable cash", "cash available", "total distributions", "distribution"))
        val pref = extractPercentNear(q, listOf("preferred return", "pref return", "pref"))
        val years = extractNamedNumber(q, listOf("years", "year"))
        val split = extractSplit(q)
        val qLower = q.lowercase()
        val explicitlyNoCatchUp = qLower.contains("no catch-up") || qLower.contains("no catch up") || qLower.contains("without catch-up") || qLower.contains("without catch up")
        val hasCatchUp = !explicitlyNoCatchUp && (qLower.contains("catch-up") || qLower.contains("catch up"))
        val compounded = containsAny(q.lowercase(), "compounded", "compound pref", "compound preferred")
        val simple = containsAny(q.lowercase(), "simple pref", "simple preferred", "simple preferred return")

        if (hasCatchUp) {
            return CalculationEvidence(
                "Waterfall",
                "Waterfall detected with a catch-up provision. The current deterministic engine will not guess catch-up mechanics.",
                "A unique answer requires the exact catch-up percentage, hurdle definitions, capital-return ordering, and residual splits from the governing waterfall.",
                false
            )
        }
        if (lp == null || gp == null || cash == null || pref == null || years == null || split == null || (!compounded && !simple)) {
            return CalculationEvidence(
                "Waterfall",
                "Waterfall detected, but a unique numeric result requires LP contribution, GP contribution, distributable cash, preferred-return rate, years, residual LP/GP split, and whether the preferred return is simple or compounded.",
                "Waterfalls are contract-specific. The app deliberately refuses to invent missing hurdles, catch-ups, compounding, or promote mechanics.",
                false
            )
        }

        val totalCapital = lp + gp
        if (totalCapital <= 0 || cash < 0) return CalculationEvidence("Waterfall", "Invalid capital/distribution inputs.", "Contributions must be positive and distributable cash non-negative.", true)

        // Stage 1: return capital pro rata to contributed capital.
        val capitalReturned = minOf(cash, totalCapital)
        var remaining = cash - capitalReturned
        val lpCapital = capitalReturned * lp / totalCapital
        val gpCapital = capitalReturned * gp / totalCapital

        // Stage 2: LP preferred return on contributed LP capital. This simplified
        // calculator does not model accrual timing within a year or interim distributions.
        val prefAccrued = if (compounded) lp * ((1.0 + pref).pow(years) - 1.0) else lp * pref * years
        val prefPaid = minOf(remaining, prefAccrued)
        remaining -= prefPaid

        // Stage 3: residual split after capital + pref; no catch-up.
        val (lpSplit, gpSplit) = split
        val lpResidual = remaining * lpSplit
        val gpResidual = remaining * gpSplit
        val lpTotal = lpCapital + prefPaid + lpResidual
        val gpTotal = gpCapital + gpResidual

        return CalculationEvidence(
            "Waterfall",
            "Deterministic basic no-catch-up waterfall: LP ${formatMoneyLike(lpTotal)}, GP ${formatMoneyLike(gpTotal)} from ${formatMoneyLike(cash)} distributable cash.",
            "Stages used: pro-rata return of capital; ${if (compounded) "compounded" else "simple"} LP pref ${formatPercent(pref)} for ${formatNumber(years)} years (${formatMoneyLike(prefPaid)} paid); remaining cash split ${formatPercent(lpSplit)} LP / ${formatPercent(gpSplit)} GP. No catch-up or additional hurdles.",
            true
        )
    }

    private fun solveXirr(q: String): CalculationEvidence {
        val points = extractDatedCashFlows(q)
        if (points.size < 2 || points.none { it.amount < 0 } || points.none { it.amount > 0 }) {
            return CalculationEvidence(
                "XIRR",
                "XIRR detected, but exact XIRR requires at least two dated cash flows with both an outflow and an inflow.",
                "Supported date forms include YYYY-MM-DD and MM/DD/YYYY. The app will not silently substitute equally spaced IRR.",
                false
            )
        }
        val roots = FinanceMath.xirrRoots(points)
        if (roots.isEmpty()) {
            return CalculationEvidence("XIRR", "No XIRR root was found for the extracted dated cash flows.", "Some cash-flow patterns have no real XIRR or can have multiple roots.", true)
        }
        val multiple = if (roots.size > 1) " Multiple XIRR roots were found; the result is not unique." else ""
        return CalculationEvidence(
            "XIRR",
            "Deterministic XIRR result: ${roots.joinToString { formatPercent(it) }}.$multiple",
            "Computed from actual day differences using a 365-day year basis; ${points.size} dated cash flows were extracted.",
            true
        )
    }

    private fun solveCpi(q: String): CalculationEvidence? {
        val ev = extractNamedAmount(q, listOf("earned value", "ev"))
        val ac = extractNamedAmount(q, listOf("actual cost", "ac"))
        if (ev == null || ac == null || ac == 0.0) return incomplete("CPI", "earned value (EV) and actual cost (AC)")
        return CalculationEvidence("CPI", "Deterministic CPI: ${formatNumber(ev / ac)}.", "CPI = EV / AC.", true)
    }

    private fun solveSpi(q: String): CalculationEvidence? {
        val ev = extractNamedAmount(q, listOf("earned value", "ev"))
        val pv = extractNamedAmount(q, listOf("planned value", "pv"))
        if (ev == null || pv == null || pv == 0.0) return incomplete("SPI", "earned value (EV) and planned value (PV)")
        return CalculationEvidence("SPI", "Deterministic SPI: ${formatNumber(ev / pv)}.", "SPI = EV / PV.", true)
    }

    private fun solveCostVariance(q: String): CalculationEvidence? {
        val ev = extractNamedAmount(q, listOf("earned value", "ev"))
        val ac = extractNamedAmount(q, listOf("actual cost", "ac"))
        if (ev == null || ac == null) return incomplete("Cost variance", "earned value (EV) and actual cost (AC)")
        return CalculationEvidence("Cost variance", "Deterministic cost variance: ${formatMoneyLike(ev - ac)}.", "CV = EV - AC.", true)
    }

    private fun solveScheduleVariance(q: String): CalculationEvidence? {
        val ev = extractNamedAmount(q, listOf("earned value", "ev"))
        val pv = extractNamedAmount(q, listOf("planned value", "pv"))
        if (ev == null || pv == null) return incomplete("Schedule variance", "earned value (EV) and planned value (PV)")
        return CalculationEvidence("Schedule variance", "Deterministic schedule variance: ${formatMoneyLike(ev - pv)}.", "SV = EV - PV.", true)
    }

    private fun incomplete(kind: String, needs: String) = CalculationEvidence(
        kind,
        "$kind detected, but a deterministic result requires $needs.",
        "The AI must not invent missing numeric inputs.",
        false
    )

    private fun extractCashFlows(q: String): List<Double> {
        val explicit = extractSeriesAfterLabels(q, listOf("cash flows", "cash flow", "cashflows"))
        if (explicit.size >= 2) return explicit

        // Bracket/list fallback for IRR/NPV questions. Percentages are excluded.
        val bracket = Regex("[\\[\\(]([^\\]\\)]{3,180})[\\]\\)]").find(q)?.groupValues?.getOrNull(1)
        val source = bracket ?: q.substringAfter(':', missingDelimiterValue = "")
        if (source.isBlank()) return emptyList()
        return extractPlainNumbers(source).take(30)
    }

    private fun extractSeriesAfterLabels(q: String, labels: List<String>): List<Double> {
        for (label in labels) {
            val idx = q.indexOf(label, ignoreCase = true)
            if (idx < 0) continue
            var tail = q.substring(idx + label.length)
            val lowerTail = tail.lowercase()
            val stops = listOf(
                lowerTail.indexOf(';'),
                lowerTail.indexOf(" wacc"),
                lowerTail.indexOf(" discount rate"),
                lowerTail.indexOf(" terminal")
            ).filter { it >= 0 }
            if (stops.isNotEmpty()) tail = tail.substring(0, stops.min())
            val nums = extractPlainNumbers(tail)
            if (nums.isNotEmpty()) return nums.take(30)
        }
        return emptyList()
    }

    private fun extractPlainNumbers(text: String): List<Double> {
        val out = mutableListOf<Double>()
        val regex = Regex("(?<![A-Za-z0-9])([-+]?\\$?\\s*\\d[\\d,]*(?:\\.\\d+)?\\s*[kKmMbB]?)(?!\\s*%)")
        for (m in regex.findAll(text)) {
            parseAmountToken(m.groupValues[1])?.let(out::add)
        }
        return out
    }

    private fun extractNamedAmount(q: String, labels: List<String>): Double? {
        for (label in labels.sortedByDescending { it.length }) {
            val escaped = Regex.escape(label)
            val patterns = listOf(
                Regex("(?i)$escaped\\s*(?:is|=|:|of)?\\s*([-+]?\\$?\\s*\\d[\\d,]*(?:\\.\\d+)?\\s*[kKmMbB]?)"),
                Regex("(?i)([-+]?\\$?\\s*\\d[\\d,]*(?:\\.\\d+)?\\s*[kKmMbB]?)\\s*(?:of\\s+)?$escaped")
            )
            for (p in patterns) {
                val token = p.find(q)?.groupValues?.getOrNull(1) ?: continue
                parseAmountToken(token)?.let { return it }
            }
        }
        return null
    }

    private fun extractNamedNumber(q: String, labels: List<String>): Double? {
        for (label in labels) {
            val p1 = Regex("(?i)(\\d+(?:\\.\\d+)?)\\s*${Regex.escape(label)}")
            val p2 = Regex("(?i)${Regex.escape(label)}\\s*(?:=|:|of)?\\s*(\\d+(?:\\.\\d+)?)")
            p1.find(q)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { return it }
            p2.find(q)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { return it }
        }
        return null
    }

    private fun extractPercentNear(q: String, labels: List<String>): Double? {
        for (label in labels.sortedByDescending { it.length }) {
            val escaped = Regex.escape(label.trim())
            val p1 = Regex("(?i)$escaped\\s*(?:is|=|:|of|at)?\\s*([-+]?\\d+(?:\\.\\d+)?)\\s*%")
            val p2 = Regex("(?i)([-+]?\\d+(?:\\.\\d+)?)\\s*%\\s*(?:$escaped)")
            p1.find(q)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { return it / 100.0 }
            p2.find(q)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { return it / 100.0 }
        }
        return null
    }

    private fun extractSplit(q: String): Pair<Double, Double>? {
        val m = Regex("(?i)(?:residual\\s+)?split\\s*(?:=|:|is)?\\s*(\\d{1,3}(?:\\.\\d+)?)\\s*[/\\-:]\\s*(\\d{1,3}(?:\\.\\d+)?)").find(q)
            ?: return null
        val a = m.groupValues[1].toDoubleOrNull() ?: return null
        val b = m.groupValues[2].toDoubleOrNull() ?: return null
        if (a <= 0 || b <= 0) return null
        val total = a + b
        return (a / total) to (b / total)
    }

    private fun extractDatedCashFlows(q: String): List<DatedCashFlow> {
        val datePattern = "(?:\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}|\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4})"
        val amountPattern = "[-+]?\\$?\\s*\\d[\\d,]*(?:\\.\\d+)?\\s*[kKmMbB]?"
        val out = mutableListOf<DatedCashFlow>()
        val seen = mutableSetOf<String>()
        val dateFirst = Regex("(?i)($datePattern)\\s*(?:[,=:]|->)?\\s*($amountPattern)")
        val amountFirst = Regex("(?i)($amountPattern)\\s*(?:[,=:]|->)?\\s*($datePattern)")

        fun add(dateText: String, amountText: String) {
            val day = parseDateMillis(dateText) ?: return
            val amount = parseAmountToken(amountText) ?: return
            val key = "$day|$amount"
            if (seen.add(key)) out += DatedCashFlow(day, amount)
        }
        dateFirst.findAll(q).forEach { add(it.groupValues[1], it.groupValues[2]) }
        amountFirst.findAll(q).forEach { add(it.groupValues[2], it.groupValues[1]) }
        return out.sortedBy { it.epochMillis }
    }

    private fun parseDateMillis(text: String): Long? {
        val formats = listOf("yyyy-MM-dd", "yyyy/MM/dd", "M/d/yyyy", "M/d/yy")
        for (pattern in formats) {
            try {
                val f = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                return f.parse(text.trim())?.time
            } catch (_: Exception) {
                // try next format
            }
        }
        return null
    }

    private fun parseAmountToken(token: String): Double? {
        val clean = token.replace("$", "").replace(",", "").replace(" ", "").trim()
        if (clean.isEmpty()) return null
        val suffix = clean.last().lowercaseChar()
        val multiplier = when (suffix) {
            'k' -> 1_000.0
            'm' -> 1_000_000.0
            'b' -> 1_000_000_000.0
            else -> 1.0
        }
        val numeric = if (suffix in listOf('k', 'm', 'b')) clean.dropLast(1) else clean
        return numeric.toDoubleOrNull()?.times(multiplier)
    }

    private fun formatFlows(flows: List<Double>): String = flows.joinToString(prefix = "[", postfix = "]") { formatMoneyLike(it) }
    private fun formatPercent(x: Double): String = "%.2f%%".format(x * 100.0)
    private fun formatNumber(x: Double): String = if (abs(x - x.toLong()) < 1e-9) x.toLong().toString() else "%.3f".format(x).trimEnd('0').trimEnd('.')
    private fun formatMoneyLike(x: Double): String {
        val ax = abs(x)
        return when {
            ax >= 1_000_000_000 -> "%.3fB".format(x / 1_000_000_000.0).trimZerosBeforeSuffix('B')
            ax >= 1_000_000 -> "%.3fM".format(x / 1_000_000.0).trimZerosBeforeSuffix('M')
            ax >= 1_000 -> "%.3fK".format(x / 1_000.0).trimZerosBeforeSuffix('K')
            else -> "%.2f".format(x).trimEnd('0').trimEnd('.')
        }
    }

    private fun String.trimZerosBeforeSuffix(suffix: Char): String {
        val core = dropLast(1).trimEnd('0').trimEnd('.')
        return core + suffix
    }

    private fun containsAny(text: String, vararg terms: String) = terms.any { text.contains(it) }
}

data class DatedCashFlow(val epochMillis: Long, val amount: Double)

data class CalculationEvidence(
    val kind: String,
    val summary: String,
    val details: String,
    val exact: Boolean
) {
    fun promptBlock(): String = buildString {
        appendLine("CALCULATION TYPE: $kind")
        appendLine("STATUS: ${if (exact) "DETERMINISTIC" else "INCOMPLETE / DO NOT GUESS"}")
        appendLine(summary)
        append(details)
    }
}

object FinanceMath {
    fun npv(rate: Double, cashFlows: List<Double>): Double =
        cashFlows.mapIndexed { index, cf -> cf / (1.0 + rate).pow(index) }.sum()

    fun xnpv(rate: Double, cashFlows: List<DatedCashFlow>): Double {
        if (cashFlows.isEmpty()) return 0.0
        val first = cashFlows.minOf { it.epochMillis }
        val millisPerDay = 86_400_000.0
        return cashFlows.sumOf { point ->
            val years = ((point.epochMillis - first) / millisPerDay) / 365.0
            point.amount / (1.0 + rate).pow(years)
        }
    }

    fun xirrRoots(cashFlows: List<DatedCashFlow>): List<Double> = findRoots { rate -> xnpv(rate, cashFlows) }

    /** Finds distinct IRR roots in approximately -99.99% to +10,000%. */
    fun irrRoots(cashFlows: List<Double>): List<Double> {
        if (cashFlows.size < 2) return emptyList()
        return findRoots { rate -> npv(rate, cashFlows) }
    }

    private fun findRoots(fn: (Double) -> Double): List<Double> {
        val roots = mutableListOf<Double>()
        val xMin = ln(0.0001)
        val xMax = ln(101.0)
        val steps = 2600
        var prevR = exp(xMin) - 1.0
        var prevF = fn(prevR)

        for (i in 1..steps) {
            val x = xMin + (xMax - xMin) * i / steps
            val r = exp(x) - 1.0
            val f = fn(r)
            if (f.isFinite() && prevF.isFinite()) {
                if (abs(f) < 1e-10) addUnique(roots, r)
                if ((f > 0 && prevF < 0) || (f < 0 && prevF > 0)) {
                    addUnique(roots, bisect(fn, prevR, r))
                }
            }
            prevR = r
            prevF = f
        }
        return roots.sorted()
    }

    private fun bisect(fn: (Double) -> Double, loIn: Double, hiIn: Double): Double {
        var lo = loIn
        var hi = hiIn
        var flo = fn(lo)
        repeat(100) {
            val mid = (lo + hi) / 2.0
            val fm = fn(mid)
            if (abs(fm) < 1e-12) return mid
            if ((flo > 0 && fm < 0) || (flo < 0 && fm > 0)) {
                hi = mid
            } else {
                lo = mid
                flo = fm
            }
        }
        return (lo + hi) / 2.0
    }

    private fun addUnique(roots: MutableList<Double>, candidate: Double) {
        if (candidate.isFinite() && roots.none { abs(it - candidate) < 1e-6 }) roots += candidate
    }
}
