package com.readdepth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/*
 * Calls the real OpenRouter with the app's own client, prompt and parser.
 * Skipped unless OPENROUTER_API_KEY is set, so CI never needs a key.
 *
 *   OPENROUTER_API_KEY=… ./gradlew testReleaseUnitTest --tests '*OpenRouterLiveTest*'
 */
class OpenRouterLiveTest {

	private val apiKey = System.getenv("OPENROUTER_API_KEY")

	@Test
	fun lookupThenFollowUp() {
		assumeTrue("OPENROUTER_API_KEY not set", !apiKey.isNullOrBlank())

		val settings = Settings(apiKey = apiKey!!)
		val prompt   = File("src/main/res/raw/system_prompt.md").readText()
		val system   = Core.systemPromptFor(settings, prompt)
		val data     = Data()
		val now      = System.currentTimeMillis()
		val python   = Core.ensureChannel(data, "Python", now - 60_000)

		data.lookups += Lookup("l1", "decorator", "", "https://docs.python.org/3/glossary.html", "Glossary", python.id, "", "",
			"A decorator is a function that wraps another function to add behaviour.", "", now - 60_000)

		val query   = Query("functools.wraps", "Use functools.wraps when writing a decorator.", "https://docs.python.org/3/glossary.html", "Glossary")
		val message = Core.buildLookupMessage(query, data, null, 12, now)
		val parser  = ReplyParser()
		var chunks  = 0

		val finish = OpenRouter.streamChat(settings, Core.lookupMessages(system, message), OpenRouter.Call()) {
			chunks++
			parser.push(it)
		}

		parser.finish()

		println("CHANNEL: ${parser.channel} · $chunks chunks · finish $finish\n${parser.text}")

		assertEquals("Python", parser.channel)
		assertTrue(parser.text.contains("wraps"))
		assertTrue("streamed in pieces", chunks > 1)

		val lookup = Lookup("l2", query.text, query.context, query.sourceUrl, query.sourceTitle, python.id, message,
			parser.text, Core.gistOf(parser.text), settings.model, now)
		var answer = ""

		OpenRouter.streamChat(settings, Core.followUpMessages(system, lookup, "Python", "Does it work on methods too?"), OpenRouter.Call()) {
			answer += it
		}

		println("FOLLOW-UP:\n$answer")

		assertTrue(answer.isNotBlank())
		assertTrue("no CHANNEL line in follow-ups", !answer.trimStart().lowercase().startsWith("channel"))
	}

	@Test
	fun badKeyIsExplained() {
		assumeTrue("OPENROUTER_API_KEY not set", !apiKey.isNullOrBlank())

		try {
			OpenRouter.streamChat(Settings(apiKey = "sk-or-v1-not-a-real-key"), listOf(Message("user", "hi")), OpenRouter.Call()) {}
		} catch (e: ApiException) {
			assertEquals("OpenRouter rejected the API key. Check it in settings.", e.message)
			assertTrue(e.needsKey)
			return
		}

		throw AssertionError("expected an ApiException")
	}
}
