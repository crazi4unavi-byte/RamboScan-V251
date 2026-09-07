package com.ramboscan.app

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest

/**
 * On-device reasoning engine backed by Gemini Nano through Android AICore.
 * No API key or PC backend is used.
 */
class OnDeviceRamboEngine : AutoCloseable {
    private val model: GenerativeModel = Generation.getClient()

    suspend fun prepare(): BrainState {
        return try {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> BrainState.READY

                FeatureStatus.DOWNLOADABLE -> {
                    var finalState = BrainState.PREPARING
                    model.download().collect { status ->
                        finalState = when (status) {
                            is DownloadStatus.DownloadFailed -> BrainState.ERROR
                            DownloadStatus.DownloadCompleted -> BrainState.READY
                            else -> BrainState.PREPARING
                        }
                    }
                    finalState
                }

                FeatureStatus.DOWNLOADING -> BrainState.PREPARING
                FeatureStatus.UNAVAILABLE -> BrainState.UNSUPPORTED
                else -> BrainState.ERROR
            }
        } catch (_: Exception) {
            BrainState.ERROR
        }
    }

    suspend fun solve(
        question: String,
        knowledge: List<KnowledgeHit>,
        calculation: CalculationEvidence? = null,
        onlineEvidence: List<OnlineEvidence> = emptyList(),
        deepMode: Boolean = false
    ): String {
        val readiness = prepare()
        if (readiness != BrainState.READY) throw BrainUnavailableException(readiness)

        val cleanQuestion = StableTextGate.cleanForModel(question).take(2200)
        val localContext = knowledge
            .take(6)
            .joinToString("\n") { it.promptSnippet() }
            .take(2600)

        val calculationContext = calculation?.promptBlock()?.take(1800).orEmpty()
        val onlineContext = onlineEvidence
            .take(7)
            .joinToString("\n") { it.promptSnippet() }
            .take(3600)

        val prompt = buildString {
            appendLine("You are RAMBO Scan, a concise on-device educational assistant.")
            appendLine("Answer the scanned question directly. Put the answer first.")
            appendLine("For technical, certification-study, finance, real-estate, and sustainability questions, use the LOCAL KNOWLEDGE below as the primary reference when relevant.")
            appendLine("If local knowledge is incomplete, use general knowledge carefully. Never invent exam rules, certification dates, measurements, codes, manufacturer limits, or financial assumptions. Distinguish formulas from assumptions.")
            appendLine("For finance/math questions, CALCULATION EVIDENCE is authoritative when marked DETERMINISTIC. Never redo or contradict deterministic arithmetic. When marked INCOMPLETE, identify the missing inputs and do not manufacture them.")
            appendLine("For forecasts or future-looking questions, present assumption-based scenarios, sensitivities, ranges, and drivers. Never claim an offline model knows future prices, rates, rents, or market outcomes.")
            if (deepMode) {
                appendLine("This is DEEP FALLBACK mode. Synthesize the online evidence carefully. State uncertainty when sources conflict or do not fully answer the question. Keep the answer structured and readable, but it may be longer than the normal 20-second answer.")
            } else {
                appendLine("Keep the final response short enough to read in about 20 seconds. Put the numeric result first when one exists.")
            }
            appendLine("For hazardous technical work such as energized electrical, combustion, or refrigerant service, stay conceptual and emphasize qualified procedures rather than hands-on instructions.")
            if (calculationContext.isNotBlank()) {
                appendLine()
                appendLine("CALCULATION EVIDENCE:")
                appendLine(calculationContext)
            }
            if (localContext.isNotBlank()) {
                appendLine()
                appendLine("LOCAL KNOWLEDGE:")
                appendLine(localContext)
            }
            if (onlineContext.isNotBlank()) {
                appendLine()
                appendLine("ONLINE EVIDENCE:")
                appendLine(onlineContext)
                appendLine("Use the source labels in the answer when they materially support a claim.")
            }
            appendLine()
            appendLine("QUESTION:")
            append(cleanQuestion)
        }

        val response = model.generateContent(
            generateContentRequest(TextPart(prompt)) {
                temperature = 0.15f
                topK = 20
                candidateCount = 1
                maxOutputTokens = if (deepMode) 650 else 300
            }
        )

        val answer = response.candidates.firstOrNull()?.text?.trim().orEmpty()
        if (answer.isBlank()) throw IllegalStateException("On-device model returned an empty answer")
        return answer
    }

    override fun close() {
        model.close()
    }
}

enum class BrainState {
    READY,
    PREPARING,
    UNSUPPORTED,
    ERROR
}

class BrainUnavailableException(val state: BrainState) : Exception(state.name)
