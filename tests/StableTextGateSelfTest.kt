import com.ramboscan.app.StableTextGate

private fun assertTrue(value: Boolean, message: String) {
    if (!value) error(message)
}

fun main() {
    val gate = StableTextGate(requiredStableSamples = 3)

    // Partial text must not accidentally combine with a more complete frame.
    assertTrue(gate.offer("What is the best fabric") == null, "sample 1 should not trigger")
    assertTrue(gate.offer("What is the best fabric to wear for summer?") == null, "changed partial sample should reset stability")
    assertTrue(gate.offer("What is the best fabric to wear for summer?") == null, "stable sample 2 should not trigger")
    val accepted = gate.offer("What is the best fabric to wear for summer?")
    assertTrue(accepted != null, "stable sample 3 should trigger")

    // Same question still visible after display/reset must not loop.
    gate.block(accepted!!)
    repeat(5) {
        assertTrue(gate.offer("What is the best fabric to wear for summer?") == null, "blocked question must not retrigger")
    }

    // A genuinely different question should clear the block, then trigger after stability.
    assertTrue(gate.offer("What does a variable frequency drive do in HVAC systems?") == null, "new question first sample")
    assertTrue(gate.offer("What does a variable frequency drive do in HVAC systems?") == null, "new question second sample")
    val different = gate.offer("What does a variable frequency drive do in HVAC systems?")
    assertTrue(different != null, "different stable question should be accepted after rearm")

    // A minor OCR substitution should still count as the same scene.
    assertTrue(
        StableTextGate.similarity("what is the best fabric for summer", "what is the best fabrlc for summer") > 0.86,
        "minor OCR substitution should remain similar"
    )

    // Tiny/noisy text should be ignored.
    val noiseGate = StableTextGate(requiredStableSamples = 2)
    repeat(4) { assertTrue(noiseGate.offer("SALE") == null, "short background text should not trigger") }

    // Clearing all allows the same question again after moving away / explicit reset.
    gate.clearAll()
    assertTrue(gate.offer("What is the best fabric to wear for summer?") == null, "revisit sample 1")
    assertTrue(gate.offer("What is the best fabric to wear for summer?") == null, "revisit sample 2")
    assertTrue(gate.offer("What is the best fabric to wear for summer?") != null, "revisit sample 3")

    println("StableTextGate self-test: PASS")
}
