package com.readdepth

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/* The launcher screen: a search box over the lookup panel, then recent lookups. */
class MainActivity : AppCompatActivity() {

	companion object {
		const val EXTRA_LOOKUP_ID = "lookupId"
	}

	private lateinit var panel: LookupPanel
	private lateinit var recent: LinearLayout
	private lateinit var recentHeader: View

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)

		recent       = findViewById(R.id.recent)
		recentHeader = findViewById(R.id.recent_header)
		panel        = LookupPanel(this, findViewById(R.id.panel)) { loadRecent() }

		openFromIntent(intent)
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		openFromIntent(intent)
	}

	override fun onResume() {
		super.onResume()
		panel.refresh()
		loadRecent()
	}

	override fun onDestroy() {
		panel.detach()
		super.onDestroy()
	}

	private fun openFromIntent(intent: Intent) {
		val id = intent.getStringExtra(EXTRA_LOOKUP_ID) ?: return

		thread {
			val data   = Store.get(this).load()
			val lookup = Core.lookupById(data, id) ?: return@thread
			val name   = Core.channelById(data, lookup.channelId)?.name ?: FALLBACK_CHANNEL

			runOnUiThread { panel.show(lookup, name) }
		}
	}

	private fun loadRecent() {
		thread {
			val data    = Store.get(this).load()
			val lookups = data.lookups.takeLast(20).reversed()
			val names   = data.channels.associate { it.id to it.name }

			runOnUiThread {
				recent.removeAllViews()
				recentHeader.visibility = if (lookups.isEmpty()) View.GONE else View.VISIBLE

				val pad = (8 * resources.displayMetrics.density).toInt()

				for (lookup in lookups) {
					val channel = names[lookup.channelId] ?: FALLBACK_CHANNEL
					val item    = layoutInflater.inflate(android.R.layout.simple_list_item_2, recent, false)

					item.findViewById<TextView>(android.R.id.text1).text = "${lookup.text.take(60)}  ·  $channel"
					item.findViewById<TextView>(android.R.id.text2).apply {
						text     = lookup.gist
						maxLines = 2
						alpha    = 0.7f
					}
					item.setPadding(0, pad, 0, pad)
					item.setOnClickListener {
						panel.show(lookup, channel)
						findViewById<ScrollView>(R.id.scroll).smoothScrollTo(0, 0)
					}

					recent.addView(item)
				}
			}
		}
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menuInflater.inflate(R.menu.main, menu)
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
		R.id.menu_settings -> {
			startActivity(Intent(this, SettingsActivity::class.java))
			true
		}
		R.id.menu_channels -> {
			startActivity(Intent(this, ChannelsActivity::class.java))
			true
		}
		else -> super.onOptionsItemSelected(item)
	}
}
