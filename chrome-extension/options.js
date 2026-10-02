import { DEFAULT_SETTINGS } from "./lib/core.js";
import { DEFAULT_SYSTEM_PROMPT } from "./lib/prompt.js";
import { getSettings, saveSettings } from "./lib/storage.js";

const $ = (id) => document.getElementById(id);

const manifest = chrome.runtime.getManifest();

$("version").textContent = `Version ${manifest.version_name || manifest.version}`;

chrome.commands.getAll((commands) => {
	const command = commands.find((c) => c.name === "explain-selection");

	$("shortcut").textContent = (command && command.shortcut) || "not set";
});

function request(message) {
	return chrome.runtime.sendMessage(message).then((response) => {
		if (!response || !response.ok) throw new Error(response ? response.error : "No response");
		return response.result;
	});
}

function setStatus(id, text, kind = "") {
	$(id).textContent = text;
	$(id).className   = `status ${kind}`;
}

// ─── Settings ───────────────────────────────────────────────────────────────

let knownModels = [];

async function loadSettings() {
	const settings = await getSettings();

	$("apiKey").value          = settings.apiKey;
	$("model").value           = settings.model;
	$("targetWords").value     = settings.targetWords;
	$("reasoningEffort").value = settings.reasoningEffort;
	$("rosterSize").value      = settings.rosterSize;
	$("historyLimit").value    = settings.historyLimit;
	$("sendContext").checked   = settings.sendContext;
	$("systemPrompt").value    = settings.systemPrompt.trim() ? settings.systemPrompt : DEFAULT_SYSTEM_PROMPT;
}

let savedTimer = null;

async function save(partial) {
	await saveSettings(partial);
	setStatus("saveStatus", "Saved", "ok");
	clearTimeout(savedTimer);
	savedTimer = setTimeout(() => setStatus("saveStatus", ""), 1500);
}

$("apiKey").addEventListener("change", () => save({ apiKey: $("apiKey").value.trim() }));
$("targetWords").addEventListener("change", () => save({ targetWords: Number($("targetWords").value) || DEFAULT_SETTINGS.targetWords }));
$("reasoningEffort").addEventListener("change", () => save({ reasoningEffort: $("reasoningEffort").value }));
$("rosterSize").addEventListener("change", () => save({ rosterSize: Number($("rosterSize").value) || DEFAULT_SETTINGS.rosterSize }));
$("historyLimit").addEventListener("change", () => save({ historyLimit: Number($("historyLimit").value) || DEFAULT_SETTINGS.historyLimit }));
$("sendContext").addEventListener("change", () => save({ sendContext: $("sendContext").checked }));

// Saved as "" while it matches the default, so a future improvement to the
// default reaches you unless you've written your own.
$("systemPrompt").addEventListener("change", () => {
	const value = $("systemPrompt").value;

	save({ systemPrompt: value.trim() === DEFAULT_SYSTEM_PROMPT.trim() ? "" : value });
});

$("resetPrompt").addEventListener("click", () => {
	$("systemPrompt").value = DEFAULT_SYSTEM_PROMPT;
	save({ systemPrompt: "" });
});

$("model").addEventListener("change", () => {
	const model = $("model").value.trim();

	save({ model: model || DEFAULT_SETTINGS.model });
	checkModel();
});

$("toggleKey").addEventListener("click", () => {
	const hidden = $("apiKey").type === "password";

	$("apiKey").type          = hidden ? "text" : "password";
	$("toggleKey").textContent = hidden ? "Hide" : "Show";
});

$("testKey").addEventListener("click", async () => {
	const apiKey = $("apiKey").value.trim();

	if (!apiKey) {
		setStatus("keyStatus", "Enter a key first.", "error");
		return;
	}

	await save({ apiKey });
	setStatus("keyStatus", "Checking…");

	try {
		const info  = await request({ type: "checkKey", apiKey });
		const usage = typeof info.usage === "number" ? `, $${info.usage.toFixed(2)} used` : "";
		const left  = info.limit_remaining != null ? `, $${Number(info.limit_remaining).toFixed(2)} left` : ", no spending limit";

		setStatus("keyStatus", `Key OK${usage}${left}.`, "ok");
	} catch (error) {
		setStatus("keyStatus", error.message, "error");
	}
});

function price(n) {
	return n == null ? "?" : n === 0 ? "free" : `$${n}`;
}

async function loadModels(force = false) {
	setStatus("modelStatus", "Loading models…");

	try {
		knownModels = await request({ type: "models", force });

		$("models").replaceChildren(...knownModels.map((m) => {
			const option = document.createElement("option");

			option.value = m.id;
			option.label = `${m.name} · ${price(m.prompt)} in / ${price(m.completion)} out per M tokens`;

			return option;
		}));

		checkModel();
	} catch (error) {
		setStatus("modelStatus", `Couldn't load the model list (${error.message}). You can still type a model ID.`, "error");
	}
}

function checkModel() {
	const id = $("model").value.trim();

	if (!knownModels.length) return;

	const model = knownModels.find((m) => m.id === id);

	if (model) {
		setStatus("modelStatus", `${model.name}: ${price(model.prompt)} in / ${price(model.completion)} out per million tokens.`);
	} else {
		setStatus("modelStatus", `"${id}" isn't in OpenRouter's model list. Check the ID.`, "error");
	}
}

$("refreshModels").addEventListener("click", () => loadModels(true));

// ─── Channels ───────────────────────────────────────────────────────────────

async function loadChannels() {
	const { channels, pinnedId } = await request({ type: "channels" });
	const container = $("channels");

	if (!channels.length) {
		container.innerHTML = '<p class="hint">No channels yet. They appear as you look things up.</p>';
		return;
	}

	container.replaceChildren(...channels.map((channel) => {
		const row  = document.createElement("div");
		const head = document.createElement("div");
		const name = document.createElement("span");
		const list = document.createElement("div");

		row.className  = "channel";
		head.className = "channel-head";
		name.className = "channel-name";
		list.className = "channel-lookups";
		list.hidden    = true;

		name.textContent = `${channel.name} (${channel.count})`;
		head.append(name);

		if (channel.id === pinnedId) {
			const tag = document.createElement("span");

			tag.className   = "pinned-tag";
			tag.textContent = "pinned";
			head.append(tag);
		}

		head.append(
			button("View", "secondary small", async () => {
				list.hidden = !list.hidden;
				if (!list.hidden) await showLookups(channel, list);
			}),
			button("Rename", "secondary small", async () => {
				const newName = prompt(`Rename "${channel.name}" to (an existing name merges them):`, channel.name);

				if (newName && newName.trim() && newName.trim() !== channel.name) {
					await request({ type: "renameChannel", id: channel.id, name: newName.trim() });
					loadChannels();
				}
			}),
			button("Delete", "danger small", async () => {
				if (confirm(`Delete "${channel.name}" and its ${channel.count} lookup(s)?`)) {
					await request({ type: "deleteChannel", id: channel.id });
					loadChannels();
				}
			})
		);

		row.append(head, list);

		return row;
	}));
}

async function showLookups(channel, list) {
	const lookups = await request({ type: "channelLookups", id: channel.id });

	list.replaceChildren(...lookups.map((lookup) => {
		const row  = document.createElement("div");
		const term = document.createElement("strong");
		const gist = document.createElement("span");

		row.className    = "lookup-row";
		gist.className   = "gist";
		term.textContent = lookup.text.length > 50 ? `${lookup.text.slice(0, 49)}…` : lookup.text;
		gist.textContent = lookup.gist;

		row.append(term, gist, button("Remove", "secondary small", async () => {
			await request({ type: "deleteLookup", id: lookup.id });
			row.remove();
			loadChannels();
		}));

		return row;
	}));

	if (!lookups.length) list.textContent = "No lookups.";
}

function button(text, className, onClick) {
	const node = document.createElement("button");

	node.type        = "button";
	node.className   = className;
	node.textContent = text;
	node.addEventListener("click", onClick);

	return node;
}

$("clearHistory").addEventListener("click", async () => {
	if (confirm("Delete every lookup and channel? Settings are kept.")) {
		await request({ type: "clearHistory" });
		loadChannels();
	}
});

await loadSettings();
loadChannels();
loadModels();
