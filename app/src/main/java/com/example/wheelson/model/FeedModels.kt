package com.example.wheelson.model

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

data class FeedResponse(
    val items: List<FeedItem> = emptyList()
)

data class FeedItem(
    val event: Event,
    val quote: Double? = null,
    @SerializedName("option_chain") val optionChain: OptionChain? = null,
    @SerializedName("stock_criteria") val stockCriteria: JsonObject? = null
)

data class Event(
    val symbol: String = "",
    val date: String? = null,
    val hour: String? = null,
    val quarter: Int? = null,
    val year: Int? = null
)

data class OptionChain(
    val ticker: String = "",
    val quote: Double? = null,
    val puts: List<Put>? = null
)

data class Put(
    val strike: Double = 0.0,
    @SerializedName("seller_roi") val sellerRoi: Double = 0.0,
    val expiration: String = "",
    val delta: Double = 0.0,
    val theta: Double = 0.0,
    val vega: Double = 0.0,
    val pop: Double = 0.0,
    @SerializedName("contract_symbol") val contractSymbol: String? = null
)
