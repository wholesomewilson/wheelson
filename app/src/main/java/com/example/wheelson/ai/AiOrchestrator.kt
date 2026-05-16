package com.example.wheelson.ai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Singleton orchestration layer for on-device LLM inference.
 * Uses Option B: copies the model from assets to internal storage, then loads from file path.
 * Ensures a single [LlmInference] instance to avoid memory exhaustion.
 */
object AiOrchestrator {

    private const val TAG = "AiOrchestrator"
    private const val MODEL_ASSET_PATH = "models/gemma3-1b-it-int4.task"
    private const val MODEL_FILENAME = "gemma3-1b-it-int4.task"
    private const val MODELS_DIR_NAME = "models"
    private const val DEFAULT_MAX_TOKENS = 4096

    @Volatile
    private var llmInference: LlmInference? = null

    private val initLock = Any()

    /**
     * Whether the orchestrator has been successfully initialized and is ready for inference.
     */
    fun isInitialized(): Boolean = llmInference != null

    /**
     * Initializes the orchestration layer: copies the model from assets to internal storage
     * (if not already present), then creates the single [LlmInference] instance.
     * Idempotent: safe to call multiple times; subsequent calls no-op if already initialized.
     *
     * @param context Application or activity context; application context is used internally.
     * @return [Result.success] when ready, or [Result.failure] with the exception (e.g. missing asset, copy failure).
     */
    fun initialize(context: Context): Result<Unit> {
        if (llmInference != null) return Result.success(Unit)
        synchronized(initLock) {
            if (llmInference != null) return Result.success(Unit)
            val appContext = context.applicationContext
            val modelPathResult = copyModelToInternalStorageIfNeeded(appContext)
            val path = modelPathResult.getOrElse { return Result.failure(it) }
            Log.d(TAG, "model path: $path, size: ${File(path).length()}")
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(path)
                .setMaxTokens(DEFAULT_MAX_TOKENS)
                .setPreferredBackend(LlmInference.Backend.CPU)
                .build()
            return try {
                llmInference = LlmInference.createFromOptions(appContext, options)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Suspending version of [initialize]. Runs the copy and model load on [Dispatchers.IO].
     */
    suspend fun initializeAsync(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        initialize(context)
    }

    /**
     * Generates a response for the given prompt. Runs inference off the main thread.
     * Call [initialize] first; if not initialized, returns [Result.failure].
     *
     * @param prompt The input text for the model.
     * @return [Result.success] with the generated string, or [Result.failure] with the exception.
     */
    fun generateResponse(prompt: String): Result<String> {
        val inference = llmInference ?: return Result.failure(NotInitializedException())
        return try {
            val response = inference.generateResponse(prompt)
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Suspending version of [generateResponse]. Runs inference on [Dispatchers.Default].
     */
    suspend fun generateResponseAsync(prompt: String): Result<String> = withContext(Dispatchers.Default) {
        generateResponse(prompt)
    }

    /**
     * Releases the LLM instance. Call when the app is shutting down or when switching models.
     * After this, [initialize] must be called again before [generateResponse].
     */
    fun shutdown() {
        synchronized(initLock) {
            try {
                llmInference?.close()
            } catch (_: Exception) { }
            llmInference = null
        }
    }

    /**
     * Copies the model from assets to context.filesDir/models/MODEL_FILENAME if the destination
     * does not exist. Returns the absolute path to the copied file.
     */
    private fun copyModelToInternalStorageIfNeeded(context: Context): Result<String> {
        val modelsDir = File(context.filesDir, MODELS_DIR_NAME)
        val destFile = File(modelsDir, MODEL_FILENAME)
        if (destFile.exists()) {
            return when {
                destFile.length() == 0L -> Result.failure(IllegalStateException("Model file is empty: ${destFile.absolutePath}"))
                else -> Result.success(destFile.absolutePath)
            }
        }
        return runCatching {
            modelsDir.mkdirs()
            context.assets.open(MODEL_ASSET_PATH).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (destFile.length() == 0L) throw IllegalStateException("Model file is empty after copy")
            Log.d(TAG, "copied model to ${destFile.absolutePath}, size: ${destFile.length()}")
            destFile.absolutePath
        }
    }

    class NotInitializedException : IllegalStateException("AiOrchestrator not initialized. Call initialize(context) first.")
}
