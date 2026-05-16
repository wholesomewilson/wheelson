package com.example.wheelson.ai

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.regex.Pattern

object SeniorStockQualityEvaluationAgent {
    private const val TAG = "SeniorStockQualityAgent"
    private val gson = Gson()

    /**
     * Reviews the junior analyst's JSON output against stock data and returns a single score.
     * Output from the model is strictly {"score": N} where N is 1-10.
     */
    suspend fun review(stockCriteria: JsonObject, juniorResponseJson: String): Result<StockQualityEvaluation> {
        val prompt = buildPrompt(stockCriteria, juniorResponseJson)
        val responseResult = AiOrchestrator.generateResponseAsync(prompt)
        Log.d(TAG, "responseResult: ${responseResult}")
        val response = responseResult.getOrElse { return Result.failure(it) }
        return parse(response.trim())
    }

    private fun buildPrompt(stockCriteria: JsonObject, juniorResponseJson: String): String {
        val stockData = gson.toJson(stockCriteria)
        return """
            <start_of_turn>user
            # ROLE
            Act as a Senior Expert Options Trader. Review the Junior Analyst's evaluation of a stock for the options trading Wheel Strategy.

            # TASK INSTRUCTIONS
            Cross-check the Junior Analyst's evaluation against the raw # STOCK DATA.
            Your job is to catch mathematical errors, missed red flags, and hallucinated data.
            - If the analyst hallucinated metrics or missed a violation (e.g., ignored high debt or negative growth), drastically lower the score.
            - If the analyst correctly cited the numbers and applied the rules, keep the score.

            # THE STRICT RULES (Audit against these)
            Fundamentals: Requires D/E < 0.5, Current Ratio > 2, positive FCF/Net Income, Margins/ROE > 0.1, consistent growth (EPS YoY > 0, Rev YoY > -0.1), strong institutional backing, and majority Buy ratings.
            Wheel Fit: Must be holdable long-term (Forward EPS > 0). Seek moderate valuation (P/B < 10) and assumed liquidity (Market Cap > $10B).
            Red Flags: Heavily penalize negative revenue growth, Trailing P/E > 50, low institutional ownership, or weak ratings.

            # OUTPUT FORMAT
            Respond STRICTLY in JSON format matching the structure below. Do not include markdown formatting like ```json.
            JSON rules: Output must be valid JSON only. Do not put unescaped double quotes inside string values—either avoid using quotes in your text (e.g. write Buy instead of "Buy") or escape them as backslash-quote (\").
            {
            "score": 0
            }

            # STOCK DATA
            $stockData

            # JUNIOR ANALYST EVALUATION
            $juniorResponseJson
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
        Log.e(TAG, "Failed to parse senior agent JSON: $rawJson", strict.exceptionOrNull())
        return strict
    }
}
