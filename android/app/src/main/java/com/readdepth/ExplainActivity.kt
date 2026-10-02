package com.readdepth

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/*
 * Opened from another app, either through the share sheet (ACTION_SEND) or
 * straight from the text-selection menu (ACTION_PROCESS_TEXT). Shown as a
 * dialog over what you were reading; Back or a tap outside returns you there.
 */
class ExplainActivity : AppCompatActivity() {

	private lateinit var panel: LookupPanel

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_explain)
		setFinishOnTouchOutside(true)

		panel = LookupPanel(this, findViewById(R.id.panel))

		if (savedInstanceState == null) handle(intent)
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		handle(intent)
	}

	private fun handle(intent: Intent) {
		val query = when (intent.action) {
			Intent.ACTION_PROCESS_TEXT -> Query(intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim() ?: "")
			Intent.ACTION_SEND -> Core.parseShared(
				intent.getStringExtra(Intent.EXTRA_TEXT) ?: "",
				intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: intent.getStringExtra(Intent.EXTRA_TITLE)
			)
			else -> Query("")
		}

		panel.lookUp(query)
	}

	override fun onDestroy() {
		panel.detach()
		super.onDestroy()
	}
}
