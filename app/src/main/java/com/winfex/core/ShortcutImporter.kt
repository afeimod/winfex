package com.winfex.core

import android.content.Context
import android.net.Uri
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.winfex.model.ShortcutEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * 快捷方式导入：解析 .lnk / .url / .exe 文件。
 */
object ShortcutImporter {

    private const val TAG = "ShortcutImporter"

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(List::class.java, ShortcutEntry::class.java)
    private val adapter = moshi.adapter<List<ShortcutEntry>>(listType)

    private val _shortcuts = MutableStateFlow<List<ShortcutEntry>>(emptyList())
    val shortcuts: StateFlow<List<ShortcutEntry>> = _shortcuts.asStateFlow()

    suspend fun loadAll() = withContext(Dispatchers.IO) {
        val f = WinfexPaths.shortcutsIndexFile
        if (!f.exists()) return@withContext
        try {
            _shortcuts.value = adapter.fromJson(f.readText()) ?: emptyList()
        } catch (_: Exception) {}
    }

    suspend fun importFromUri(context: Context, uri: Uri, prefixId: String): ShortcutEntry? =
        withContext(Dispatchers.IO) {
            val name = queryFileName(context, uri) ?: "shortcut-${System.currentTimeMillis()}"
            val ext = name.substringAfterLast('.', "").lowercase()
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
            val entry = when (ext) {
                "lnk" -> parseLnk(bytes, name, prefixId)
                "url" -> parseUrl(bytes, name, prefixId)
                "exe" -> ShortcutEntry(
                    id = UUID.randomUUID().toString().take(8),
                    name = name.substringBeforeLast('.'),
                    target = name,
                    prefixId = prefixId
                )
                else -> null
            } ?: return@withContext null

            _shortcuts.value = _shortcuts.value + entry
            saveIndex()
            entry
        }

    suspend fun scanPrefixLinks(prefixId: String) = withContext(Dispatchers.IO) {
        val dir = File(WinfexPaths.prefixDir(prefixId), "drive_c/users/winfex/Desktop")
        if (!dir.exists()) return@withContext
        val found = dir.listFiles { f -> f.extension.equals("lnk", true) }?.mapNotNull { f ->
            parseLnk(f.readBytes(), f.nameWithoutExtension, prefixId)
        } ?: emptyList()

        val others = _shortcuts.value.filterNot { it.prefixId == prefixId }
        _shortcuts.value = others + found
        saveIndex()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        _shortcuts.value = _shortcuts.value.filterNot { it.id == id }
        saveIndex()
    }

    // ===== MS-SHLLINK 简化解析 =====

    private fun parseLnk(bytes: ByteArray, displayName: String, prefixId: String): ShortcutEntry? {
        if (bytes.size < 76) return null
        val header = ByteBuffer.wrap(bytes, 0, 76).order(ByteOrder.LITTLE_ENDIAN)
        val magic = header.getLong(4)
        if (magic != 0x00000001L) return null

        val flags = header.getInt(0x14)
        var offset = 0x4c

        if ((flags and 0x01) != 0) {
            if (offset + 2 > bytes.size) return null
            val idSize = ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            offset += 2 + idSize
        }

        var target: String? = null
        var workingDir: String? = null
        var arguments: String = ""

        if ((flags and 0x02) != 0) {
            if (offset + 4 > bytes.size) return null
            val linkInfoSize = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (offset + 16 <= bytes.size) {
                val lbpOff = ByteBuffer.wrap(bytes, offset + 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (lbpOff > 0 && offset + lbpOff < bytes.size) {
                    target = readCString(bytes, offset + lbpOff)
                }
            }
            offset += linkInfoSize
        }

        if ((flags and 0x04) != 0) { offset = skipStringData(bytes, offset) }
        if ((flags and 0x08) != 0) { val pair = readStringData(bytes, offset); offset = pair.first }
        if ((flags and 0x10) != 0) { val pair = readStringData(bytes, offset); workingDir = pair.second; offset = pair.first }
        if ((flags and 0x20) != 0) { val pair = readStringData(bytes, offset); arguments = pair.second; offset = pair.first }

        return ShortcutEntry(
            id = UUID.randomUUID().toString().take(8),
            name = displayName,
            target = target ?: "",
            arguments = arguments,
            workingDir = workingDir,
            prefixId = prefixId
        )
    }

    private fun readStringData(bytes: ByteArray, offset: Int): Pair<Int, String> {
        if (offset + 2 > bytes.size) return offset to ""
        val count = ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        val strOffset = offset + 2
        if (count <= 0 || strOffset + count > bytes.size) return strOffset to ""
        val s = String(bytes, strOffset, count, Charsets.UTF_16LE).trimEnd('\u0000')
        return (strOffset + count) to s
    }

    private fun skipStringData(bytes: ByteArray, offset: Int): Int {
        if (offset + 2 > bytes.size) return offset
        val count = ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        return offset + 2 + count
    }

    private fun readCString(bytes: ByteArray, offset: Int): String {
        val sb = StringBuilder()
        var i = offset
        while (i < bytes.size && bytes[i].toInt() != 0) {
            sb.append(bytes[i].toInt().toChar()); i++
        }
        return sb.toString()
    }

    private fun parseUrl(bytes: ByteArray, displayName: String, prefixId: String): ShortcutEntry {
        val text = String(bytes, Charsets.UTF_8)
        var target = ""
        var icon = ""
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("URL=", ignoreCase = true)) target = t.substring(4)
            else if (t.startsWith("IconFile=", ignoreCase = true)) icon = t.substring(9)
        }
        return ShortcutEntry(
            id = UUID.randomUUID().toString().take(8),
            name = displayName,
            target = target,
            icon = icon,
            prefixId = prefixId
        )
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
        cursor.use {
            val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && it.moveToFirst()) return it.getString(idx)
        }
        return uri.lastPathSegment
    }

    private fun saveIndex() {
        WinfexPaths.shortcutsIndexFile.writeText(adapter.toJson(_shortcuts.value))
    }
}
