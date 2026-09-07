package com.ramboscan.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceCalculatorTest {
    @Test fun irrSimple() {
        val roots = FinanceMath.irrRoots(listOf(-100.0, 110.0))
        assertEquals(1, roots.size)
        assertEquals(0.10, roots.first(), 1e-6)
    }

    @Test fun dcfQuestionRoutesToDeterministicCalculator() {
        val result = FinanceQuestionSolver.trySolve(
            "DCF: FCF 100, 110, 120; WACC 10%; terminal growth 3%"
        )
        assertNotNull(result)
        assertTrue(result!!.exact)
        assertEquals("DCF", result.kind)
    }

    @Test fun basicWaterfallCalculatesOnlyWhenTermsAreExplicit() {
        val complete = FinanceQuestionSolver.trySolve(
            "Waterfall: LP contribution $90m, GP contribution $10m, distributable cash $150m, preferred return 8%, 5 years, residual split 70/30, simple preferred return, no catch up"
        )
        assertTrue(complete!!.exact)

        val ambiguous = FinanceQuestionSolver.trySolve("Waterfall with 8% pref and 70/30 split")
        assertFalse(ambiguous!!.exact)
    }

    @Test fun pmpCpiUsesExactFormula() {
        val result = FinanceQuestionSolver.trySolve(
            "What is CPI if earned value EV is 80 and actual cost AC is 100?"
        )
        assertTrue(result!!.exact)
        assertTrue(result.summary.contains("0.8"))
    }
}
