package com.example.wheelson.ai

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

object StockQualityEvaluationAgent {
    private const val TAG = "StockQualityEvaluationAgent"
    private val gson = Gson()

    /**
     * Calls the LLM and returns the model's raw JSON-only reply.
     *
     * Expected model output (and nothing else):
     * {"score": <integer 1..10>}
     */
    suspend fun evaluateRawJson(stockCriteria: JsonObject): Result<String> {
        val prompt = buildPrompt(stockCriteria)
        val responseResult = AiOrchestrator.generateResponseAsync(prompt)
        Log.d(TAG, "responseResult: ${responseResult.getOrNull()}")
        val response = responseResult.getOrElse { return Result.failure(it) }

        val trimmed = response.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalStateException("Empty response from model"))
        }
        return Result.success(trimmed)
    }

    /**
     * Calls the LLM and returns a parsed [StockQualityEvaluation].
     */
    suspend fun evaluate(stockCriteria: JsonObject): Result<StockQualityEvaluation> {
        val raw = evaluateRawJson(stockCriteria).getOrElse { return Result.failure(it) }
        return parse(raw)
    }

    /**
     * Parses the junior agent's raw JSON response (e.g. with step_by_step_reasoning, reflection, score)
     * into [StockQualityEvaluation]. Use for fallback when senior review is not available.
     */
    fun parseEvaluation(rawJson: String): Result<StockQualityEvaluation> = parse(rawJson)

    private fun buildPrompt(stockCriteria: JsonObject): String {
        val criteriaJson = gson.toJson(stockCriteria)
        return """
            <start_of_turn>user
            # ROLE
            Act as an Expert Options Trader. Score this stock's suitability for the Wheel Strategy (selling cash-secured puts on high-quality stocks you want to own long-term) from 1 (Worst) to 10 (Best). Focus strictly on fundamental quality, volatility, and the risk-reward profile.

            # TASK INSTRUCTIONS
            Evaluate the JSON data for Wheel Strategy suitability based on these strict rules:
            - Use ONLY the provided data.
            - Do not calculate missing metrics.

            # KEY CRITERIA:
            Fundamentals: Requires D/E < 0.5, Current Ratio > 2, positive FCF/Net Income, Margins/ROE > 0.1, consistent growth (EPS YoY > 0, Rev YoY > -0.1), strong institutional backing, and majority "Buy" ratings.
            Wheel Fit: Must be holdable long-term (Forward EPS > 0). Seek moderate valuation (P/B < 10) and assumed liquidity (Market Cap > $10B).
            Red Flags: Heavily penalize negative revenue growth, Trailing P/E > 50, low institutional ownership, or weak ratings.

            # EVALUATION PROCESS
            1. Extract metrics and evaluate against the criteria.
            2. Identify red flags.
            3. Reflect: Aggressively lower the score if major risks exist.
            4. Assign a final score from 1 (High risk/Poor) to 10 (Perfect fit/Safe).

            # EXAMPLES
            Example 1:
            Data implies: Low debt, high growth, buy ratings.
            Output: {"step_by_step_reasoning": "Fundamentals strong, liquid inferred, great fit.", "reflection": "Data confidently points to a strong Wheel candidate.", "score": 9}

            # OUTPUT FORMAT
            Respond STRICTLY in valid JSON format matching the structure below. Do not include markdown formatting like ```json.
            JSON rules: Output must be valid JSON only. Do not put unescaped double quotes inside string values—either avoid using quotes in your text (e.g. write Buy instead of "Buy") or escape them as backslash-quote (\").
            {
            "step_by_step_reasoning": "Your step-by-step analysis of the data...",
            "reflection": "Your self-reflection and confidence check...",
            "score": 0
            }

            # DATA
            $criteriaJson
            <end_of_turn>
            <start_of_turn>model
            """.trimIndent()
    }

    private fun stripMarkdownCodeFence(s: String): String {
        var t = s.trim()
        if (t.startsWith("```")) {
            t = t.substringAfter("```")
            // Case-insensitive removal of the optional language tag (e.g. json, JSON, Json)
            t = t.replaceFirst(Regex("""^json\s*""", RegexOption.IGNORE_CASE), "")
            if (t.endsWith("```")) t = t.dropLast(3).trimEnd()
        }
        return t
    }

    /** Try to extract the outermost JSON object from a string that may have surrounding text. */
    private fun extractJsonObject(s: String): String {
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        return if (start != -1 && end > start) s.substring(start, end + 1) else s
    }

    private val scoreRegex = Pattern.compile("""["']score["']\s*:\s*(\d+\.?\d*)""")

    private fun parse(rawJson: String): Result<StockQualityEvaluation> {
        val json = extractJsonObject(stripMarkdownCodeFence(rawJson))
        val strict = runCatching {
            val obj = JsonParser.parseString(json).asJsonObject
            if (!obj.has("score")) throw IllegalArgumentException("Missing 'score' key")
            val scoreElement = obj.get("score")
            val score = when {
                scoreElement.isJsonPrimitive && scoreElement.asJsonPrimitive.isNumber -> scoreElement.asInt
                scoreElement.isJsonPrimitive && scoreElement.asJsonPrimitive.isString -> scoreElement.asString.toIntOrNull() ?: throw IllegalArgumentException("'score' not a number")
                else -> throw IllegalArgumentException("'score' must be a number")
            }
            if (score !in 1..10) throw IllegalArgumentException("Score out of range: $score")
            StockQualityEvaluation(score = score)
        }
        if (strict.isSuccess) return strict
        Log.w(TAG, "Strict JSON parse failed, trying score regex fallback", strict.exceptionOrNull())
        val matcher = scoreRegex.matcher(json)
        if (matcher.find()) {
            val score = matcher.group(1)!!.toDoubleOrNull()?.toInt()?.coerceIn(1, 10)
            if (score != null) {
                Log.d(TAG, "Fallback extracted score: $score")
                return Result.success(StockQualityEvaluation(score = score))
            }
        }
        Log.e(TAG, "Failed to parse agent JSON: $rawJson", strict.exceptionOrNull())
        return strict
    }
}

