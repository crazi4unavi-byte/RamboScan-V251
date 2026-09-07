import com.ramboscan.app.FinanceMath
import com.ramboscan.app.FinanceQuestionSolver
import kotlin.math.abs

fun requireNear(actual: Double, expected: Double, tol: Double, name: String) {
    require(abs(actual - expected) <= tol) { "$name expected $expected got $actual" }
}

fun main() {
    requireNear(FinanceMath.npv(0.10, listOf(-100.0, 110.0)), 0.0, 1e-8, "NPV")
    val roots = FinanceMath.irrRoots(listOf(-100.0, 110.0))
    require(roots.size == 1) { "IRR root count: $roots" }
    requireNear(roots.first(), 0.10, 1e-6, "IRR")

    val irr = FinanceQuestionSolver.trySolve("Calculate IRR. Cash flows: -100, 30, 40, 50")
    require(irr != null && irr.exact && irr.kind == "IRR") { "IRR question parse failed: $irr" }

    val npv = FinanceQuestionSolver.trySolve("NPV at discount rate 10%. Cash flows: -100, 60, 60")
    require(npv != null && npv.exact) { "NPV question parse failed: $npv" }

    val dcf = FinanceQuestionSolver.trySolve("DCF: FCF 100, 110, 120; WACC 10%; terminal growth 3%")
    require(dcf != null && dcf.exact && dcf.kind == "DCF") { "DCF parse failed: $dcf" }

    val cap = FinanceQuestionSolver.trySolve("What is the cap rate if NOI is $500,000 and property value is $10,000,000?")
    require(cap != null && cap.exact && cap.summary.contains("5.00%")) { "Cap rate parse failed: $cap" }

    val waterfall = FinanceQuestionSolver.trySolve("Waterfall: LP contribution $90m, GP contribution $10m, distributable cash $150m, preferred return 8%, 5 years, residual split 70/30, simple preferred return, no catch up")
    require(waterfall != null && waterfall.exact && waterfall.kind == "Waterfall") { "Waterfall parse failed: $waterfall" }

    val xirr = FinanceQuestionSolver.trySolve("XIRR cash flows: 2026-01-01 -100; 2027-01-01 110")
    require(xirr != null && xirr.exact && xirr.kind == "XIRR") { "XIRR parse failed: $xirr" }

    val cpi = FinanceQuestionSolver.trySolve("What is CPI if earned value EV is 80 and actual cost AC is 100?")
    require(cpi != null && cpi.exact && cpi.summary.contains("0.8")) { "CPI parse failed: $cpi" }

    val ambiguous = FinanceQuestionSolver.trySolve("Waterfall with 8% pref and 70/30 split")
    require(ambiguous != null && !ambiguous.exact) { "Ambiguous waterfall should not guess" }

    println("FinanceCalculatorSelfTest PASS")
    println(irr!!.summary)
    println(dcf!!.summary)
    println(waterfall!!.summary)
}
