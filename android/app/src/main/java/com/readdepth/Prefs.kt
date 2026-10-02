package com.readdepth

import android.content.Context

/* Settings, in SharedPreferences (private to the app). */
object Prefs {

	private const val NAME = "settings"

	fun load(context: Context): Settings {
		val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
		val d = Settings()

		return Settings(
			apiKey          = p.getString("apiKey", d.apiKey)!!.trim(),
			model           = p.getString("model", d.model)!!.trim().ifEmpty { d.model },
			targetWords     = Core.clamp(p.getInt("targetWords", d.targetWords), 20, 300),
			reasoningEffort = p.getString("reasoningEffort", d.reasoningEffort)!!,
			systemPrompt    = p.getString("systemPrompt", d.systemPrompt)!!,
			rosterSize      = Core.clamp(p.getInt("rosterSize", d.rosterSize), 1, 50),
			historyLimit    = Core.clamp(p.getInt("historyLimit", d.historyLimit), 50, 5000)
		)
	}

	fun save(context: Context, settings: Settings) {
		context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
			.putString("apiKey", settings.apiKey.trim())
			.putString("model", settings.model.trim())
			.putInt("targetWords", settings.targetWords)
			.putString("reasoningEffort", settings.reasoningEffort)
			.putString("systemPrompt", settings.systemPrompt)
			.putInt("rosterSize", settings.rosterSize)
			.putInt("historyLimit", settings.historyLimit)
			.apply()
	}

	fun defaultSystemPrompt(context: Context): String =
		context.resources.openRawResource(R.raw.system_prompt).bufferedReader().use { it.readText() }

	fun cachedModels(context: Context): Pair<Long, String>? {
		val p    = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
		val json = p.getString("modelCache", null) ?: return null

		return p.getLong("modelCacheAt", 0) to json
	}

	fun cacheModels(context: Context, json: String) {
		context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
			.putString("modelCache", json)
			.putLong("modelCacheAt", System.currentTimeMillis())
			.apply()
	}
}
