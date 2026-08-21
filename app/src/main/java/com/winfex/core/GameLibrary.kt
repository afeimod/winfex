package com.winfex.core

import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.winfex.model.GameItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 游戏库：扫描 prefix 的 drive_c 下的 .exe，建立索引。
 */
object GameLibrary {

    private const val TAG = "GameLibrary"

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(List::class.java, GameItem::class.java)
    private val adapter = moshi.adapter<List<GameItem>>(listType)

    private val _games = MutableStateFlow<List<GameItem>>(emptyList())
    val games: StateFlow<List<GameItem>> = _games.asStateFlow()

    suspend fun loadAll() = withContext(Dispatchers.IO) {
        val f = WinfexPaths.gamesIndexFile
        if (!f.exists()) return@withContext
        try {
            _games.value = adapter.fromJson(f.readText()) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "loadIndex failed", e)
        }
    }

    /**
     * 扫描某个 prefix 下的所有 .exe（C:\Program Files 等）。
     */
    suspend fun scanPrefix(prefixId: String) = withContext(Dispatchers.IO) {
        val prefixDir = WinfexPaths.prefixDir(prefixId)
        val driveC = File(prefixDir, "drive_c")
        if (!driveC.exists()) return@withContext

        val found = mutableListOf<GameItem>()
        driveC.walkTopDown().filter { it.isFile && it.extension.equals("exe", true) }
            .forEach { exeFile ->
                val name = exeFile.nameWithoutExtension
                if (name.equals("uninstall", true) || name.equals("winecfg", true)
                    || name.equals("winemenubuilder", true) || name.startsWith("wine")) return@forEach

                val item = GameItem(
                    id = "${prefixId}_${exeFile.name}_${exeFile.length()}",
                    name = name,
                    exePath = exeFile.absolutePath,
                    prefixId = prefixId
                )
                found.add(item)
            }

        val others = _games.value.filterNot { it.prefixId == prefixId }
        _games.value = (others + found).sortedByDescending { it.lastPlayedAt }
        saveIndex()
    }

    suspend fun markPlayed(itemId: String) = withContext(Dispatchers.IO) {
        _games.value = _games.value.map {
            if (it.id == itemId) it.copy(
                lastPlayedAt = System.currentTimeMillis(),
                playCount = it.playCount + 1
            ) else it
        }
        saveIndex()
    }

    suspend fun add(item: GameItem) = withContext(Dispatchers.IO) {
        _games.value = _games.value + item
        saveIndex()
    }

    suspend fun remove(itemId: String) = withContext(Dispatchers.IO) {
        _games.value = _games.value.filterNot { it.id == itemId }
        saveIndex()
    }

    private fun saveIndex() {
        WinfexPaths.gamesIndexFile.writeText(adapter.toJson(_games.value))
    }
}
