package com.example.wheelson.ui.feed

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.wheelson.ai.AiOrchestrator
import com.example.wheelson.ai.SeniorStockQualityEvaluationAgent
import com.example.wheelson.ai.StockQualityEvaluationAgent
import com.example.wheelson.data.FeedRepository
import com.example.wheelson.model.FeedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class ScoreState {
    data object Loading : ScoreState()
    data class Loaded(val score: Int) : ScoreState()
}

private fun itemKey(item: FeedItem): String = item.event.symbol + item.event.date.orEmpty()

@Composable
fun FeedScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var scoreState by remember { mutableStateOf<Map<String, ScoreState>>(emptyMap()) }

    LaunchedEffect(Unit) {
        items = withContext(Dispatchers.IO) {
            FeedRepository.loadFeed(context)
        }
        Log.d("AiOrchestrator", "starting init...")
        val initResult = AiOrchestrator.initializeAsync(context)
        Log.d("AiOrchestrator", "init: ${initResult.getOrElse { e -> e.message }}")

        if (initResult.isSuccess) {
            for (item in items) {
                val criteria = item.stockCriteria ?: continue
                val key = itemKey(item)
                scoreState = scoreState + (key to ScoreState.Loading)

                // Try junior evaluation with one retry on parse failure
                var juniorRaw: String? = null
                var juniorEval: com.example.wheelson.ai.StockQualityEvaluation? = null
                for (attempt in 1..2) {
                    val rawResult = StockQualityEvaluationAgent.evaluateRawJson(criteria)
                    val raw = rawResult.getOrNull()
                    if (raw == null) {
                        Log.d("StockQualityAgent", "${item.event.symbol} junior attempt $attempt: raw null")
                        continue
                    }
                    val parsed = StockQualityEvaluationAgent.parseEvaluation(raw).getOrNull()
                    if (parsed != null) {
                        juniorRaw = raw
                        juniorEval = parsed
                        break
                    }
                    Log.d("StockQualityAgent", "${item.event.symbol} junior attempt $attempt: parse failed")
                }

                if (juniorEval == null || juniorRaw == null) {
                    Log.d("StockQualityAgent", "${item.event.symbol} junior failed after retries")
                    scoreState = scoreState - key
                    continue
                }

                // Only send validated junior output to senior
                val seniorEval = SeniorStockQualityEvaluationAgent.review(criteria, juniorRaw).getOrNull()
                val finalScore = seniorEval?.score ?: juniorEval.score
                Log.d("StockQualityAgent", "score: ${item.event.symbol} = $finalScore (senior=${seniorEval != null})")
                scoreState = scoreState + (key to ScoreState.Loaded(finalScore))
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(items, key = { itemKey(it) }) { item ->
            FeedItemCard(feedItem = item, scoreState = scoreState[itemKey(item)])
        }
    }
}

@Composable
fun FeedItemCard(
    feedItem: FeedItem,
    scoreState: ScoreState? = null,
    modifier: Modifier = Modifier
) {
    val event = feedItem.event
    val chain = feedItem.optionChain
    val symbol = event.symbol.ifEmpty { chain?.ticker.orEmpty() }
    val quote = feedItem.quote ?: chain?.quote

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = symbol,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Symbol",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatQuote(quote),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Price",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                QualityScoreChip(scoreState = scoreState)
                Text(
                    text = "Quality",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun QualityScoreChip(
    scoreState: ScoreState?,
    modifier: Modifier = Modifier
) {
    val backgroundColor = when (scoreState) {
        is ScoreState.Loaded -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (scoreState) {
        is ScoreState.Loaded -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .widthIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        when (scoreState) {
            is ScoreState.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = contentColor
            )
            is ScoreState.Loaded -> Text(
                text = "${scoreState.score}",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = contentColor
            )
            null -> Text(
                text = "—",
                style = MaterialTheme.typography.titleMedium,
                color = contentColor
            )
        }
    }
}

private fun formatQuote(value: Double?): String {
    if (value == null) return "—"
    return "$${"%.2f".format(value)}"
}
