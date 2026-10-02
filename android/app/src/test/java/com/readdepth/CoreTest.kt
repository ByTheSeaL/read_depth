package com.readdepth

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/* Runs shared/test-cases.json, the same cases as chrome-extension/test/core.test.js. */
class CoreTest {

	private val cases = JSONObject(javaClass.classLoader!!.getResource("test-cases.json")!!.readText())
	private val utc   = ZoneId.of("UTC")

	private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
	private fun JSONObject.str(key: String) = if (isNull(key)) null else getString(key)

	@Test
	fun parse() {
		for (case in cases.getJSONArray("parse").objects()) {
			val parser = ReplyParser()
			val chunks = case.getJSONArray("chunks")

			for (i in 0 until chunks.length()) parser.push(chunks.getString(i))
			parser.finish()

			assertEquals(case.getString("name"), case.str("channel"), parser.channel)
			assertEquals(case.getString("name"), case.getString("text"), parser.text)
		}
	}

	@Test
	fun deltasAddUpToFinalText() {
		val parser  = ReplyParser()
		val visible = StringBuilder()

		for (chunk in listOf("CHANNEL: Py", "thon\nOne ", "two ", "three.")) {
			parser.push(chunk).delta?.let { visible.append(it) }
		}

		assertEquals("One two three.", visible.toString())
	}

	@Test
	fun gist() {
		for (case in cases.getJSONArray("gist").objects()) {
			assertEquals(case.getString("gist"), Core.gistOf(case.getString("input")))
		}
	}

	@Test
	fun refinement() {
		for (case in cases.getJSONArray("refinement").objects()) {
			assertEquals(case.toString(), case.getBoolean("expected"), Core.isRefinement(case.getString("original"), case.getString("updated")))
		}
	}

	@Test
	fun lookupMessage() {
		for (case in cases.getJSONArray("lookupMessage").objects()) {
			val raw  = case.getJSONObject("data")
			val data = Data(
				channels = raw.getJSONArray("channels").objects().map {
					Channel(it.getString("id"), it.getString("name"), it.getLong("createdAt"), it.getLong("lastUsedAt"))
				}.toMutableList(),
				lookups = raw.getJSONArray("lookups").objects().map {
					Lookup(it.getString("id"), it.getString("text"), "", it.getString("sourceUrl"), it.getString("sourceTitle"),
						it.getString("channelId"), "", "", it.getString("gist"), "", it.getLong("createdAt"))
				}.toMutableList()
			)
			val q = case.getJSONObject("query")

			val message = Core.buildLookupMessage(
				query         = Query(q.getString("text"), q.getString("context"), q.getString("sourceUrl"), q.getString("sourceTitle")),
				data          = data,
				forcedChannel = case.str("forcedChannel"),
				rosterSize    = case.getInt("rosterSize"),
				now           = case.getLong("now"),
				zone          = utc
			)

			assertEquals(case.getString("name"), case.getString("expected"), message)
		}
	}

	@Test
	fun reasoningAllowance() {
		assertEquals(1400, Core.maxTokensFor(Settings(targetWords = 80, reasoningEffort = "low")))
		assertEquals(400, Core.maxTokensFor(Settings(targetWords = 80, reasoningEffort = "off")))
	}

	@Test
	fun sseReader() {
		val sse   = SseReader()
		val first = sse.push(": OPENROUTER PROCESSING\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\ndata: {\"choices\":[{\"del")
		val rest  = sse.push("ta\":{\"content\":\"lo\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")

		assertEquals(listOf("Hel"), first.deltas)
		assertEquals(listOf("lo"), rest.deltas)
		assertEquals("stop", sse.finishReason)
		assertTrue(sse.done)
		assertEquals("Provider overloaded", SseReader().push("data: {\"error\":{\"message\":\"Provider overloaded\"}}\n").error)
	}

	@Test
	fun moveMisfiledLookup() {
		val data   = Data()
		val snakes = Core.ensureChannel(data, "Snakes", 1, "l1")

		data.lookups += Lookup("l1", "x", "", "", "", snakes.id, "", "", "", "", 1)

		val python = Core.moveLookup(data, "l1", "Python", 2)!!

		assertEquals(python.id, data.lookups[0].channelId)
		assertEquals(listOf("Python"), data.channels.map { it.name })
		assertEquals(python.id, data.pinnedChannelId)
	}

	@Test
	fun renameOntoExistingMerges() {
		val data = Data()
		val a    = Core.ensureChannel(data, "Python", 1)
		val b    = Core.ensureChannel(data, "Python programming", 2)

		data.lookups += Lookup("x", "x", "", "", "", b.id, "", "", "", "", 2)
		Core.renameChannel(data, b.id, "python")

		assertEquals(listOf("Python"), data.channels.map { it.name })
		assertEquals(a.id, data.lookups[0].channelId)
	}

	@Test
	fun markdown() {
		assertEquals(
			"<p><strong>Bold</strong> and <code>a&lt;b</code></p><ul><li>one</li><li>two</li></ul><pre><code>x = 1 &lt; 2</code></pre>",
			Core.markdownToHtml("**Bold** and `a<b`\n\n- one\n- two\n\n```py\nx = 1 < 2\n```")
		)
		assertEquals("<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>", Core.markdownToHtml("<script>alert(1)</script>"))
		assertEquals("<p>snake_case_name and <em>em</em></p>", Core.markdownToHtml("snake_case_name and *em*"))
		assertEquals("<p><strong><code>return_exceptions=True</code></strong> works</p>", Core.markdownToHtml("**`return_exceptions=True`** works"))
		assertEquals("<p><tt>a</tt></p><p><tt>x<br>&nbsp;&nbsp;y</tt></p>", Core.toAndroidHtml("<p><code>a</code></p><pre><code>x\n  y</code></pre>"))
	}

	@Test
	fun parseShared() {
		val chrome = Core.parseShared("“functools.wraps”\nhttps://docs.python.org/3/library/functools.html#:~:text=functools.wraps", "functools")

		assertEquals("functools.wraps", chrome.text)
		assertEquals("https://docs.python.org/3/library/functools.html", chrome.sourceUrl)
		assertEquals("functools", chrome.sourceTitle)

		val plain = Core.parseShared("  ischemia ", null)

		assertEquals("ischemia", plain.text)
		assertEquals("", plain.sourceUrl)

		val link = Core.parseShared("https://example.com/a", null)

		assertEquals("https://example.com/a", link.text)
		assertFalse(link.sourceUrl.isNotEmpty())
	}
}
