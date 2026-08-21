package com.winfex.model

import com.squareup.moshi.JsonClass
import java.io.Serializable

@JsonClass(generateAdapter = true)
data class GameItem(
    val id: String,
    val name: String,
    val exePath: String,
    val prefixId: String,
    val arguments: String = "",
    val coverUri: String? = null,
    val lastPlayedAt: Long = 0L,
    val playCount: Int = 0,
    val tags: List<String> = emptyList()
) : Serializable
