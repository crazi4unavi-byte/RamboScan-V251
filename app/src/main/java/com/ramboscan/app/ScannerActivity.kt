package com.ramboscan.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@androidx.camera.core.ExperimentalGetImage
class ScannerActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private lateinit var solveTimerText: TextView
    private lateinit var resultPanel: View
    private lateinit var resultLabel: TextView
    private lateinit var questionText: TextView
    private lateinit var answerText: TextView
    private lateinit var countdownText: TextView
    private lateinit var solvingProgress: ProgressBar

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val aiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val analyzingFrame = AtomicBoolean(false)
    private val stableGate = StableTextGate()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val brain by lazy { OnDeviceRamboEngine() }
    private val answerCache by lazy { AnswerCache(applicationContext) }
    private val knowledgeStore by lazy { KnowledgeStore(applicationContext) }
    private val onlineFallback by lazy { OnlineFallbackEngine() }

    @Volatile
    private var scanLocked = false

    private var resetRunnable: Runnable? = null
    private var countdownRunnable: Runnable? = null
    private var solveTimerRunnable: Runnable? = null

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_scanner)

        // Intentionally silent: no scan beep, answer sound, vibration, or haptic feedback.
        window.decorView.isSoundEffectsEnabled = false
        window.decorView.isHapticFeedbackEnabled = false

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        hintText = findViewById(R.id.hintText)
        solveTimerText = findViewById(R.id.solveTimerText)
        resultPanel = findViewById(R.id.resultPanel)
        resultLabel = findViewById(R.id.resultLabel)
        questionText = findViewById(R.id.questionText)
        answerText = findViewById(R.id.answerText)
        countdownText = findViewById(R.id.countdownText)
        solvingProgress = findViewById(R.id.solvingProgress)

        aiScope.launch {
            val state = brain.prepare()
            withContext(Dispatchers.Main) {
                if (state == BrainState.UNSUPPORTED) {
                    hintText.text = "Full-screen scan • local database ready • deep web fallback available"
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                if (scanLocked || !analyzingFrame.compareAndSet(false, true)) {
                    imageProxy.close()
                    return@setAnalyzer
                }

                val mediaImage = imageProxy.image
                if (mediaImage == null) {
                    analyzingFrame.set(false)
                    imageProxy.close()
                    return@setAnalyzer
                }

                val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                recognizer.process(input)
                    .addOnSuccessListener { result ->
                        if (!scanLocked) {
                            // OCR analyzes the full camera frame. The old 300x180 visual box is gone.
                            val candidate = result.textBlocks
                                .asSequence()
                                .map { it.text.trim() }
                                .filter { it.length in 8..1200 }
                                .maxByOrNull { text -> text.length + if ('?' in text) 80 else 0 }

                            if (!candidate.isNullOrBlank()) {
                                stableGate.offer(candidate)?.let(::onStableQuestion)
                            }
                        }
                    }
                    .addOnFailureListener {
                        // Transient OCR errors are ignored; scanning continues silently.
                    }
                    .addOnCompleteListener {
                        analyzingFrame.set(false)
                        imageProxy.close()
                    }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                setScanningUi()
            } catch (_: Exception) {
                Toast.makeText(this, "Camera could not start", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onStableQuestion(question: String) {
        if (scanLocked) return
        scanLocked = true
        val cycleStartedAt = System.currentTimeMillis()

        runOnUiThread {
            statusText.text = "SOLVE • LOCAL"
            hintText.text = "Phone brain + database • up to 15 seconds"
            solvingProgress.visibility = View.VISIBLE
            startSolveTimer("LOCAL", LOCAL_SOLVE_BUDGET_MS)
        }

        aiScope.launch {
            val timeSensitive = isTimeSensitive(question)

            if (!timeSensitive) {
                val exact = answerCache.get(question)
                if (exact != null) {
                    withContext(Dispatchers.Main) { showAnswer(question, exact, NORMAL_RESULT_DURATION_MS, "LOCAL CACHE") }
                    return@launch
                }

                val similar = answerCache.getSimilar(question)
                if (similar != null) {
                    answerCache.put(question, similar)
                    withContext(Dispatchers.Main) { showAnswer(question, similar, NORMAL_RESULT_DURATION_MS, "LOCAL CACHE") }
                    return@launch
                }
            }

            val calculation = FinanceQuestionSolver.trySolve(question)
            val knowledgeHits = knowledgeStore.search(question, limit = 6)
            val bestKnowledgeScore = knowledgeHits.firstOrNull()?.score ?: 0.0

            val localAttempt = withTimeoutOrNull(LOCAL_SOLVE_BUDGET_MS) {
                buildLocalAttempt(question, knowledgeHits, calculation, bestKnowledgeScore)
            }

            if (localAttempt?.strong == true && !timeSensitive) {
                answerCache.put(question, localAttempt.answer)
                withContext(Dispatchers.Main) {
                    showAnswer(question, localAttempt.answer, NORMAL_RESULT_DURATION_MS, localAttempt.label)
                }
                return@launch
            }

            // Weak/failed/time-sensitive local result: automatically switch to public web sources.
            withContext(Dispatchers.Main) {
                statusText.text = "DEEP SEARCH"
                hintText.text = "Public online sources • automatic fallback"
                startSolveTimer("DEEP WEB", DEEP_SEARCH_BUDGET_MS)
            }

            val onlineEvidence = withTimeoutOrNull(DEEP_SEARCH_BUDGET_MS) {
                onlineFallback.search(question)
            }.orEmpty()

            if (onlineEvidence.isNotEmpty()) {
                val deepAnswer = try {
                    withTimeoutOrNull(DEEP_SYNTHESIS_BUDGET_MS) {
                        brain.solve(
                            question = question,
                            knowledge = knowledgeHits,
                            calculation = calculation,
                            onlineEvidence = onlineEvidence,
                            deepMode = true
                        )
                    } ?: onlineFallback.fallbackAnswer(onlineEvidence)
                } catch (_: Exception) {
                    onlineFallback.fallbackAnswer(onlineEvidence)
                }

                if (!deepAnswer.isNullOrBlank()) {
                    // Do not permanently cache deep-web answers: current facts can become stale.
                    withContext(Dispatchers.Main) {
                        showAnswer(question, deepAnswer, deepRemainingDuration(cycleStartedAt), "DEEP RESULT")
                    }
                    return@launch
                }
            }

            // If internet is unavailable or the public APIs did not help, use the best phone-only attempt.
            val fallback = localAttempt?.answer
                ?: if (calculation?.exact == true) calculation.summary + "\n\n" + calculation.details
                else knowledgeStore.fallbackAnswer(knowledgeHits)
                ?: calculation?.summary
                ?: "No reliable answer was found locally or from the public online fallback."

            if (!timeSensitive && (knowledgeHits.isNotEmpty() || calculation?.exact == true)) {
                answerCache.put(question, fallback)
            }
            withContext(Dispatchers.Main) {
                showAnswer(question, fallback, deepRemainingDuration(cycleStartedAt), "BEST AVAILABLE")
            }
        }
    }

    private suspend fun buildLocalAttempt(
        question: String,
        knowledgeHits: List<KnowledgeHit>,
        calculation: CalculationEvidence?,
        bestKnowledgeScore: Double
    ): LocalAttempt {
        if (calculation?.exact == true) {
            val answer = try {
                brain.solve(question, knowledgeHits, calculation)
            } catch (_: Exception) {
                calculation.summary + "\n\n" + calculation.details
            }
            return LocalAttempt(answer, strong = true, label = "CALCULATED")
        }

        val answer = try {
            brain.solve(question, knowledgeHits, calculation)
        } catch (_: BrainUnavailableException) {
            knowledgeStore.fallbackAnswer(knowledgeHits) ?: calculation?.summary.orEmpty()
        } catch (_: Exception) {
            knowledgeStore.fallbackAnswer(knowledgeHits) ?: calculation?.summary.orEmpty()
        }

        val strong = answer.isNotBlank() && bestKnowledgeScore >= STRONG_KNOWLEDGE_SCORE
        return LocalAttempt(
            answer = answer.ifBlank { "The local phone brain did not produce a reliable answer." },
            strong = strong,
            label = if (strong) "LOCAL KNOWLEDGE" else "LOCAL PROVISIONAL"
        )
    }


    private fun deepRemainingDuration(cycleStartedAt: Long): Long {
        val deadline = cycleStartedAt + DEEP_TOTAL_CYCLE_MS
        return (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    private fun showAnswer(question: String, answer: String, durationMs: Long, label: String) {
        stopSolveTimer()
        solvingProgress.visibility = View.GONE
        resultLabel.text = label
        resultPanel.visibility = View.VISIBLE
        questionText.text = question
        answerText.text = answer
        startResultCountdown(question, durationMs.coerceAtLeast(1L))
    }

    private fun startResultCountdown(question: String, durationMs: Long) {
        resetRunnable?.let(mainHandler::removeCallbacks)
        countdownRunnable?.let(mainHandler::removeCallbacks)

        val endAt = System.currentTimeMillis() + durationMs
        val ticker = object : Runnable {
            override fun run() {
                val remainingMs = (endAt - System.currentTimeMillis()).coerceAtLeast(0)
                val seconds = ((remainingMs + 999) / 1000).toInt()
                countdownText.text = seconds.toString()
                if (remainingMs > 0) mainHandler.postDelayed(this, 250)
            }
        }
        countdownRunnable = ticker
        mainHandler.post(ticker)

        val reset = Runnable {
            resultPanel.visibility = View.GONE
            answerText.text = ""
            questionText.text = ""
            countdownText.text = ""
            resultLabel.text = "DISPLAY"

            stableGate.block(question)
            scanLocked = false
            setScanningUi()
        }
        resetRunnable = reset
        mainHandler.postDelayed(reset, durationMs)
    }

    private fun startSolveTimer(label: String, durationMs: Long) {
        stopSolveTimer()
        val endAt = System.currentTimeMillis() + durationMs
        solveTimerText.visibility = View.VISIBLE
        val ticker = object : Runnable {
            override fun run() {
                val remaining = (endAt - System.currentTimeMillis()).coerceAtLeast(0)
                val seconds = ((remaining + 999) / 1000).toInt()
                solveTimerText.text = "$label • ${seconds}s"
                if (remaining > 0 && scanLocked) mainHandler.postDelayed(this, 500)
            }
        }
        solveTimerRunnable = ticker
        mainHandler.post(ticker)
    }

    private fun stopSolveTimer() {
        solveTimerRunnable?.let(mainHandler::removeCallbacks)
        solveTimerRunnable = null
        if (::solveTimerText.isInitialized) {
            solveTimerText.text = ""
            solveTimerText.visibility = View.GONE
        }
    }

    private fun setScanningUi() {
        stopSolveTimer()
        statusText.text = "SEE • RECOGNIZE"
        hintText.text = "Scanning the full screen • hold text steady"
        solvingProgress.visibility = View.GONE
    }

    private fun isTimeSensitive(question: String): Boolean {
        val q = question.lowercase(Locale.ROOT)
        return listOf(
            "today", "current", "currently", "latest", "now", "this week", "this month",
            "price today", "rate today", "who is currently", "as of today", "latest version", "forecast", "prediction"
        ).any(q::contains)
    }

    override fun onDestroy() {
        resetRunnable?.let(mainHandler::removeCallbacks)
        countdownRunnable?.let(mainHandler::removeCallbacks)
        stopSolveTimer()
        recognizer.close()
        brain.close()
        answerCache.close()
        knowledgeStore.close()
        cameraExecutor.shutdown()
        aiScope.cancel()
        super.onDestroy()
    }

    private data class LocalAttempt(
        val answer: String,
        val strong: Boolean,
        val label: String
    )

    companion object {
        // Normal Yuka-style result behavior remains 20 seconds.
        private const val NORMAL_RESULT_DURATION_MS = 20_000L

        // Local brain/database gets up to 15 seconds before the automatic deep-web fallback.
        private const val LOCAL_SOLVE_BUDGET_MS = 15_000L

        // Deep web research gets a bounded search window; it can never hang forever.
        private const val DEEP_SEARCH_BUDGET_MS = 45_000L
        private const val DEEP_SYNTHESIS_BUDGET_MS = 10_000L

        // Hard cap for the entire exception path: local attempt + deep search + synthesis + display.
        // Budgets leave at least ~20 seconds for a deep result when every earlier stage uses its full allowance.
        private const val DEEP_TOTAL_CYCLE_MS = 90_000L

        private const val STRONG_KNOWLEDGE_SCORE = 8.0
    }
}
