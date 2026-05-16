package com.example.wheelson.data

import android.content.Context
import com.example.wheelson.model.FeedItem
import com.example.wheelson.model.FeedResponse
import com.google.gson.Gson

object FeedRepository {
    private const val ASSET_FILE = "feed.json"
    private val gson = Gson()

    fun loadFeed(context: Context): List<FeedItem> {
        return context.assets.open(ASSET_FILE).bufferedReader().use { reader ->
            val response = gson.fromJson(reader, FeedResponse::class.java)
            response.items
        }
    }
}
