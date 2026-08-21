package com.winfex.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ShortcutEntry(
    val id: String,
    val name: String,
    val target: String,
    val arguments: String = "",
    val icon: String? = null,
    val workingDir: String? = null,
    val prefixId: String
)
