package com.spectroflac.queue

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Saves the files still waiting in the queue so they can be resumed after a restart. Only the
 * unfinished files are stored: finished results already live in the history.
 */
class QueueStore(private val file: File) {

    fun save(unfinished: List<NewFile>) {
        runCatching {
            if (unfinished.isEmpty()) {
                file.delete()
            } else {
                val array = JSONArray()
                unfinished.forEach {
                    array.put(
                        JSONObject().put("uri", it.uri).put("name", it.name)
                            .put("size", it.sizeBytes).put("modified", it.lastModified),
                    )
                }
                file.writeText(array.toString())
            }
        }
    }

    fun load(): List<NewFile> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).map {
            val o = array.getJSONObject(it)
            NewFile(o.getString("uri"), o.optString("name", "audio.flac"), o.optLong("size"), o.optLong("modified"))
        }
    }.getOrDefault(emptyList())

    fun clear() {
        runCatching { file.delete() }
    }
}
