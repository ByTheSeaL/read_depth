package com.readdepth

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/*
 * The channel manager: browse, rename, delete. Renaming a channel to an
 * existing name merges the two, which is the fix when the model has split
 * one topic ("Python" vs "Python Programming").
 */
class ChannelsActivity : AppCompatActivity() {

	private lateinit var list: ListView
	private var channels: List<Core.ChannelSummary> = emptyList()
	private var pinnedId: String? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		title = getString(R.string.channels)
		supportActionBar?.setDisplayHomeAsUpEnabled(true)

		list = ListView(this)
		setContentView(list)

		list.setOnItemClickListener { _, _, position, _ -> channelActions(channels[position]) }
	}

	override fun onResume() {
		super.onResume()
		load()
	}

	override fun onSupportNavigateUp(): Boolean {
		finish()
		return true
	}

	private fun load() {
		thread {
			val data = Store.get(this).load()
			val now  = System.currentTimeMillis()

			channels = Core.channelSummaries(data)
			pinnedId = data.pinnedChannelId

			val rows = channels.map { c ->
				val pinned = if (c.id == pinnedId) "  📌" else ""
				"${c.name} (${c.count})$pinned\nlast used ${Core.relativeDay(c.lastUsedAt, now)}"
			}

			runOnUiThread {
				list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows.ifEmpty { listOf("No channels yet. They appear as you look things up.") })
				list.isEnabled = channels.isNotEmpty()
			}
		}
	}

	private fun update(change: (Data) -> Unit) {
		thread {
			Store.get(this).update(change)
			runOnUiThread { load() }
		}
	}

	private fun channelActions(channel: Core.ChannelSummary) {
		AlertDialog.Builder(this)
			.setTitle(channel.name)
			.setItems(arrayOf("View lookups", "Rename or merge", if (channel.id == pinnedId) "Unpin" else "Pin", "Delete")) { _, which ->
				when (which) {
					0 -> showLookups(channel)
					1 -> rename(channel)
					2 -> update { it.pinnedChannelId = if (channel.id == pinnedId) null else channel.id }
					3 -> confirmDelete(channel)
				}
			}
			.show()
	}

	private fun showLookups(channel: Core.ChannelSummary) {
		thread {
			val lookups = Store.get(this).load().lookups.filter { it.channelId == channel.id }.reversed()

			runOnUiThread {
				if (lookups.isEmpty()) return@runOnUiThread

				AlertDialog.Builder(this)
					.setTitle(channel.name)
					.setItems(lookups.map { "${it.text.take(50)}: ${it.gist}" }.toTypedArray()) { _, which ->
						startActivity(Intent(this, MainActivity::class.java)
							.putExtra(MainActivity.EXTRA_LOOKUP_ID, lookups[which].id)
							.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
					}
					.show()
			}
		}
	}

	private fun rename(channel: Core.ChannelSummary) {
		val input = EditText(this).apply { setText(channel.name) }

		AlertDialog.Builder(this)
			.setTitle("Rename \"${channel.name}\"")
			.setMessage("Use an existing channel's name to merge the two.")
			.setView(input)
			.setPositiveButton("Rename") { _, _ ->
				val name = input.text.toString().trim()

				if (name.isNotEmpty() && name != channel.name) update { Core.renameChannel(it, channel.id, name) }
			}
			.setNegativeButton("Cancel", null)
			.show()
	}

	private fun confirmDelete(channel: Core.ChannelSummary) {
		AlertDialog.Builder(this)
			.setMessage("Delete \"${channel.name}\" and its ${channel.count} lookup(s)?")
			.setPositiveButton("Delete") { _, _ -> update { Core.deleteChannel(it, channel.id) } }
			.setNegativeButton("Cancel", null)
			.show()
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menuInflater.inflate(R.menu.channels, menu)
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		if (item.itemId != R.id.menu_clear) return super.onOptionsItemSelected(item)

		AlertDialog.Builder(this)
			.setMessage("Delete every lookup and channel? Settings are kept.")
			.setPositiveButton("Delete all") { _, _ ->
				update {
					it.channels.clear()
					it.lookups.clear()
					it.pinnedChannelId = null
				}
			}
			.setNegativeButton("Cancel", null)
			.show()

		return true
	}
}
