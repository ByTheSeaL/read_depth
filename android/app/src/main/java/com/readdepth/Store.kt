package com.readdepth

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/*
 * History and channels, kept as one JSON file in app-private storage. A few
 * hundred kilobytes at most at the default 1,000-lookup cap, so it's read
 * and written whole. Every change goes through update(), one at a time.
 */
class Store private constructor(private val file: File) {

	companion object {
		@Volatile private var instance: Store? = null

		fun get(context: Context): Store = instance ?: synchronized(this) {
			instance ?: Store(File(context.applicationContext.filesDir, "history.json")).also { instance = it }
		}
	}

	@Synchronized
	fun load(): Data = if (file.exists()) {
		try {
			fromJson(JSONObject(file.readText()))
		} catch (e: Exception) {
			// A corrupt file shouldn't brick the app; keep it aside for inspection.
			file.renameTo(File(file.parentFile, "history.corrupt-${System.currentTimeMillis()}.json"))
			Data()
		}
	} else {
		Data()
	}

	@Synchronized
	fun <T> update(change: (Data) -> T): T {
		val data   = load()
		val result = change(data)
		val tmp    = File(file.parentFile, "${file.name}.tmp")

		tmp.writeText(toJson(data).toString())
		tmp.renameTo(file)

		return result
	}

	private fun toJson(data: Data) = JSONObject().apply {
		put("channels", JSONArray(data.channels.map { c ->
			JSONObject()
				.put("id", c.id)
				.put("name", c.name)
				.put("createdAt", c.createdAt)
				.put("lastUsedAt", c.lastUsedAt)
				.put("createdBy", c.createdBy ?: JSONObject.NULL)
		}))
		put("lookups", JSONArray(data.lookups.map { l ->
			JSONObject()
				.put("id", l.id)
				.put("text", l.text)
				.put("context", l.context)
				.put("sourceUrl", l.sourceUrl)
				.put("sourceTitle", l.sourceTitle)
				.put("channelId", l.channelId)
				.put("prompt", l.prompt)
				.put("explanation", l.explanation)
				.put("gist", l.gist)
				.put("model", l.model)
				.put("createdAt", l.createdAt)
				.put("thread", JSONArray(l.thread.map { t ->
					JSONObject().put("q", t.q).put("a", t.a).put("createdAt", t.createdAt)
				}))
		}))
		put("pinnedChannelId", data.pinnedChannelId ?: JSONObject.NULL)
	}

	private fun fromJson(json: JSONObject): Data {
		val channels = json.optJSONArray("channels") ?: JSONArray()
		val lookups  = json.optJSONArray("lookups") ?: JSONArray()

		return Data(
			channels = (0 until channels.length()).map { i ->
				val c = channels.getJSONObject(i)

				Channel(
					id         = c.getString("id"),
					name       = c.getString("name"),
					createdAt  = c.optLong("createdAt"),
					lastUsedAt = c.optLong("lastUsedAt"),
					createdBy  = if (c.isNull("createdBy")) null else c.optString("createdBy")
				)
			}.toMutableList(),
			lookups = (0 until lookups.length()).map { i ->
				val l      = lookups.getJSONObject(i)
				val thread = l.optJSONArray("thread") ?: JSONArray()

				Lookup(
					id          = l.getString("id"),
					text        = l.optString("text"),
					context     = l.optString("context"),
					sourceUrl   = l.optString("sourceUrl"),
					sourceTitle = l.optString("sourceTitle"),
					channelId   = l.optString("channelId"),
					prompt      = l.optString("prompt"),
					explanation = l.optString("explanation"),
					gist        = l.optString("gist"),
					model       = l.optString("model"),
					createdAt   = l.optLong("createdAt"),
					thread      = (0 until thread.length()).map { j ->
						val t = thread.getJSONObject(j)
						Turn(t.optString("q"), t.optString("a"), t.optLong("createdAt"))
					}.toMutableList()
				)
			}.toMutableList(),
			pinnedChannelId = if (json.isNull("pinnedChannelId")) null else json.optString("pinnedChannelId")
		)
	}
}
