package com.readdepth

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/*
 * Markdown history: one note per lookup in the folder chosen in settings,
 * rewritten whenever the lookup changes (re-explained, a follow-up added,
 * moved to another channel, channel renamed). Off unless a folder is set.
 *
 * The folder is picked with the system folder picker (Storage Access
 * Framework), so it can be anywhere the phone can write, including a synced
 * Obsidian vault, and the app needs no storage permission.
 */
object Exporter {

	/* Writes the notes for these lookups. Returns how many were written (0 when export is off). */
	fun export(context: Context, ids: Collection<String>): Int {
		val folder = Prefs.exportFolder(context) ?: return 0

		if (ids.isEmpty()) return 0

		val tree = Uri.parse(folder)

		// The file name is fixed at first export and kept on the lookup.
		val notes = Store.get(context).update { data ->
			ids.mapNotNull { Core.lookupById(data, it) }.map { lookup ->
				if (lookup.exportName.isEmpty()) lookup.exportName = Core.exportFileName(lookup)

				val channel = Core.channelById(data, lookup.channelId)?.name ?: FALLBACK_CHANNEL

				lookup.exportName to Core.lookupMarkdown(lookup, channel)
			}
		}

		val existing = listFiles(context, tree)

		for ((name, markdown) in notes) {
			val uri = existing[name] ?: createFile(context, tree, name)

			// "wt" truncates, so a shorter rewrite doesn't leave old text behind.
			context.contentResolver.openOutputStream(uri, "wt").use { out ->
				(out ?: throw IllegalStateException("Couldn't open $name for writing.")).write(markdown.toByteArray())
			}
		}

		return notes.size
	}

	fun exportChannel(context: Context, channelId: String): Int {
		val ids = Store.get(context).load().lookups.filter { it.channelId == channelId }.map { it.id }

		return export(context, ids)
	}

	fun exportAll(context: Context): Int = export(context, Store.get(context).load().lookups.map { it.id })

	/* The chosen folder's display name, for the settings screen. */
	fun folderName(context: Context): String? {
		val folder = Prefs.exportFolder(context) ?: return null
		val tree   = Uri.parse(folder)
		val doc    = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

		return try {
			context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
				if (it.moveToFirst()) it.getString(0) else null
			} ?: folder
		} catch (e: Exception) {
			null
		}
	}

	private fun listFiles(context: Context, tree: Uri): Map<String, Uri> {
		val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
		val files    = mutableMapOf<String, Uri>()

		context.contentResolver.query(
			children,
			arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
			null, null, null
		)?.use { cursor ->
			while (cursor.moveToNext()) {
				files[cursor.getString(1)] = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
			}
		} ?: throw IllegalStateException("The Markdown folder isn't available. Choose it again in settings.")

		return files
	}

	private fun createFile(context: Context, tree: Uri, name: String): Uri {
		val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

		// A generic MIME type, so providers keep the ".md" name as given rather
		// than adding an extension of their own.
		return DocumentsContract.createDocument(context.contentResolver, parent, "application/octet-stream", name)
			?: throw IllegalStateException("Couldn't create $name in the Markdown folder.")
	}
}
