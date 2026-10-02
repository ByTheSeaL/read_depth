/*
 * Settings live in chrome.storage.sync, so one setup covers every Chrome you
 * sign in to. History and channels live in chrome.storage.local: they're too
 * big for sync's 100 KB quota, and stay per machine by design.
 */

import { DEFAULT_SETTINGS, emptyData } from "./core.js";

const SETTING_KEYS = Object.keys(DEFAULT_SETTINGS);
const DATA_KEY     = "data";

export async function getSettings() {
	const stored   = await chrome.storage.sync.get(SETTING_KEYS);
	const settings = { ...DEFAULT_SETTINGS };

	for (const key of SETTING_KEYS) {
		if (stored[key] !== undefined) settings[key] = stored[key];
	}

	settings.apiKey      = (settings.apiKey || "").trim();
	settings.model       = (settings.model || "").trim() || DEFAULT_SETTINGS.model;
	settings.targetWords = clamp(Number(settings.targetWords) || DEFAULT_SETTINGS.targetWords, 20, 300);
	settings.rosterSize  = clamp(Number(settings.rosterSize) || DEFAULT_SETTINGS.rosterSize, 1, 50);
	settings.historyLimit = clamp(Number(settings.historyLimit) || DEFAULT_SETTINGS.historyLimit, 50, 5000);

	return settings;
}

export async function saveSettings(partial) {
	await chrome.storage.sync.set(partial);
}

function clamp(n, lo, hi) {
	return Math.min(hi, Math.max(lo, Math.round(n)));
}

export async function loadData() {
	const stored = await chrome.storage.local.get(DATA_KEY);

	return { ...emptyData(), ...(stored[DATA_KEY] || {}) };
}

async function saveData(data) {
	await chrome.storage.local.set({ [DATA_KEY]: data });
}

// Every change to history goes through here, one at a time, so two lookups
// finishing together can't each save over the other's result.
let queue = Promise.resolve();

export function updateData(change) {
	const run = queue.then(async () => {
		const data   = await loadData();
		const result = await change(data);

		await saveData(data);

		return result;
	});

	queue = run.catch(() => {});

	return run;
}
