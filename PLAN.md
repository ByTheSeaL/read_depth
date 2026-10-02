# Read Depth — Plan

Demystify technical language without losing your place. Highlight a word,
phrase or sentence in anything you're reading (a medical journal, unfamiliar
code, a work of philosophy), send it to Read Depth, and get a short,
plain-language explanation back from the LLM you've chosen.

Two clients, one idea:

| Platform | How you trigger a lookup | Where the answer appears |
|----------|--------------------------|--------------------------|
| Android  | Highlight → **Share** → Read Depth, or highlight → **Read Depth** in the text-selection menu | A dialog on top of the app you're reading |
| Android  | Open the app, type into the search box | The app's main screen |
| Chrome   | Highlight → right-click → **Explain with Read Depth**, or a keyboard shortcut | An overlay panel on the page |
| Chrome   | Click the toolbar icon, type into the search box | The popup |

The structure follows `ByTheSeaL/excerpt-anywhere`, which already pairs an
Android share-target app with an MV3 Chrome extension. The difference is that
Read Depth has **no server**.

---

## 1. Architecture: no endpoint

```
Chrome extension ──┐
                   ├──► https://openrouter.ai/api/v1/chat/completions  (streamed)
Android app ───────┘
```

Excerpt Anywhere needs its VPS endpoint because its job is to write files into
the Obsidian vault that lives there. Read Depth's job is to ask a question and
show an answer. Nothing has to be stored centrally, so a server would only
relay requests to OpenRouter.

- **The extension calls OpenRouter directly** from its background service
  worker, with `https://openrouter.ai/*` in `host_permissions`. This uses the
  same pattern as Excerpt Anywhere, where every network call goes through the
  service worker, never the page. That keeps the page's own security rules
  (CSP) out of the way.
- **The Android app calls OpenRouter directly** with `HttpURLConnection`.
  As in Excerpt Anywhere, it uses no networking or JSON libraries.
- **What this costs:** the OpenRouter key, model and prompt are configured
  once per client. The extension stores them in `chrome.storage.sync`, so one
  setup covers every Chrome where you're signed in. Android is once per phone.
- **History and channels are per platform**, which you said is fine. Your
  phone's channels and Chrome's channels are separate.
- **When to add an endpoint later:** if you ever want one shared history and
  set of channels across phone and desktop. The clients can gain an optional
  "sync to endpoint" without changing how lookups work.

**Why only OpenRouter:** one key and one API shape for both clients. Claude
models are available through it, so nothing is lost by not wiring up an
Anthropic subscription. A Claude Pro/Max subscription can't be used by
third-party apps anyway; that route needs a separate paid API key.

---

## 2. Channels: continuity between lookups

A **channel** is an area of knowledge that lookups belong to, for example
"Python", "Cardiology", "Kant's Critique of Pure Reason" or "Kubernetes".
Every lookup is filed under one channel. When you look something up, the
model sees what you've already looked up in that channel, so later
explanations build on earlier ones. It can say "like the `@property`
decorator you looked up earlier…" and doesn't re-explain basics you've
already covered.

Channel continuity has to work in both of these cases:

- **Same document, minutes apart.** Three lookups from the same article go
  into the same channel, and each one is informed by the previous ones.
- **Same topic, days apart.** You do four Python lookups today, read about
  something else tomorrow, then come back to Python the day after. The new
  Python lookup lands back in the Python channel with that history attached.

### 2.1 The channel box

Both the main screens (Android app, extension popup) and the lookup UIs
(Android dialog, Chrome overlay) show a **Channel** text box above the
explanation.

- **Auto (default).** The box shows the channel the model chose for this
  lookup, marked *auto*. The next lookup is classified afresh.
- **Pinned.** Type a channel name into the box, or tap the pin icon, and the
  channel is pinned. Every lookup goes into that channel until you unpin it
  or type a different name. A name that doesn't exist yet creates a new
  channel. The box suggests existing channel names as you type.
- **Correcting a misfile.** Editing the box after an answer has appeared
  moves *that* lookup into the channel you typed, and pins it. If the model
  put a lookup in "Snakes" and you meant "Python", you fix it in one edit.
- **Unpinning** returns to auto.

### 2.2 How the model picks a channel, in a single call

Classification and explanation happen in **one** streamed request, so a
lookup costs one round trip.

Each request's prompt includes:

1. **The channel roster.** Up to 12 most recently used channels. For each
   one, the channel's name and its last ~6 lookups as one-liners
   (`term — gist`). This is small, roughly 1–2k tokens, and it holds
   everything the model needs to pick a channel *and* build on that channel's
   history in the same pass.
2. **Recent activity.** The most recent channel, and whether the new lookup
   came from the same source (URL or page title) as the previous one. If it
   did, the prompt says so, which strongly suggests the same channel.
3. **The lookup itself.** The selected text, plus surrounding context and the
   page title when available (see §4).
4. **Pinned mode** replaces 1 and 2 with: "The channel is *X*." Then the
   prompt carries X's history only, with more of it, up to the last ~15
   lookups.

The model replies in a fixed format:

```
CHANNEL: Python
`@functools.wraps` is a decorator you put on the inner function of your own
decorator so the wrapped function keeps its original name and docstring. …
```

- **The first line** is the channel. It is stripped from the display and
  shown in the channel box instead. The model is told to reuse an existing
  channel name exactly when one fits, and to coin a short new name otherwise.
- **The first sentence of the explanation** is written as a standalone,
  one-sentence definition. It is stored as the lookup's **gist**, which is
  what appears in future rosters. That gives the app a gist without asking
  for a second, hidden section.
- **If the model ignores the format**, so that there is no `CHANNEL:` line,
  the lookup goes into the most recent channel and the whole reply is shown.
  Nothing is lost.

Channels older than the top 12 are still kept and listed in the channel
manager. They just don't appear in the auto roster. Typing an old channel's
name into the box brings its history back.

### 2.3 What is stored

Per platform, locally:

```
Lookup  { id, text, context, sourceUrl, sourceTitle, channelId,
          explanation, gist, model, createdAt }
Channel { id, name, createdAt, lastUsedAt }
State   { pinnedChannelId | null }
```

- **Extension:** `chrome.storage.local`. The sync storage area's 100 KB quota
  is too small for history.
- **Android:** a JSON file in app storage. That is fine at this scale; switch
  to SQLite if it ever isn't.
- **Cap:** the last 1,000 lookups. The oldest are dropped first. Channels
  with no remaining lookups are kept by name.

### 2.4 Channel manager

A screen on each platform (in Android's main menu, and a tab in the extension
options page) where you can:

- rename, merge and delete channels;
- browse a channel's lookups;
- clear all history.

Merging is the remedy when the model has split one topic into two names
("Python" vs "Python programming").

---

## 3. The system prompt

There is a default prompt, editable in settings on both platforms with a
"reset to default" button. The canonical copy lives in
`shared/system-prompt.md`, and `scripts/sync_prompt.sh` copies it into
`chrome-extension/` and `android/app/src/main/res/raw/`. That way the two
clients never drift apart.

What the default prompt asks for:

- **Audience:** someone in the middle of reading who wants to keep going.
  Plain language, no padding, no "Great question".
- **Length:** about 40–120 words. Longer only for a full sentence that needs
  unpacking.
- **Shape:**
  1. One-sentence definition, written to stand alone as the gist.
  2. What it means *here*, using the surrounding context when given.
  3. Optionally, one tiny example, or for code, a one- or two-line snippet.
- **Sentences:** when the selection is a whole sentence, paraphrase it
  plainly and flag the one or two terms that carry its meaning.
- **Continuity:** use the channel's history. Refer back to earlier lookups
  when it helps, and don't re-define terms already explained.
- **Format:** `CHANNEL:` first line, then the explanation in light Markdown
  (bold, inline code, short lists only).

Variables filled in per request: the channel roster or pinned channel
history, the source title and URL, the surrounding context and the
selection.

---

## 4. Context sent with a lookup

| Source | Selection | Surrounding context | Source title / URL |
|--------|-----------|---------------------|--------------------|
| Chrome right-click / shortcut | ✓ | ✓ the sentence(s) around the selection, read from the page's DOM, ~400 chars | ✓ |
| Chrome popup search box | ✓ typed | — | — |
| Android **Share** | ✓ | — | sometimes: Chrome for Android adds `EXTRA_SUBJECT` (page title) and may add a link-to-text URL |
| Android **select-text menu** (`PROCESS_TEXT`) | ✓ | — | — |
| Android search box | ✓ typed | — | — |

Android only hands over the highlighted text, so on the phone the channel
roster carries most of the disambiguation. If you want more help from
context there, highlight the whole sentence.

---

## 5. Chrome extension (MV3)

```
chrome-extension/
  manifest.json      contextMenus, storage, scripting, activeTab;
                     host_permissions: https://openrouter.ai/*
  background.js      context menu + command; builds the prompt; streams from
                     OpenRouter; owns history and channels
  overlay.js         the on-page panel, inside a shadow root (as in
                     Excerpt Anywhere) so no site CSS reaches it
  popup.html/js      search box, channel box, recent lookups
  options.html/js    API key, model picker, system prompt, channel manager
  prompt.js          the canonical prompt, generated by sync_prompt.sh
  lib/               shared JS: prompt building, response parsing, storage
```

- **Context menu:** "Explain “%s” with Read Depth" on any selection.
- **Shortcut:** suggested `Alt+Shift+D`. You can change it at
  `chrome://extensions/shortcuts`.
- **Streaming:** the service worker reads the response from OpenRouter as it
  arrives (`stream: true`) and relays chunks to the overlay over a
  `chrome.runtime` port. The answer appears as it's written.
- **Overlay:**
  - the selected text;
  - the channel box (with auto/pinned state);
  - the streamed explanation;
  - **Copy** and **Ask a follow-up** buttons. A follow-up continues the same
    conversation and is stored under the same lookup.
  - It closes with **Esc** or a click outside.
- **Pages where content scripts can't run** (`chrome://` pages, the Web
  Store, Chrome's built-in PDF viewer): the context menu still provides the
  selected text. The answer opens in a small extension popup window instead
  of an overlay.
- **Model picker:** filled from OpenRouter's public `GET /api/v1/models`
  list, searchable, with a free-text fallback.

## 6. Android app

Kotlin, `minSdk 26`, AppCompat. As in Excerpt Anywhere: no OkHttp, no JSON
library, no Compose. Distributed as an APK on GitHub Releases.

```
android/app/src/main/java/com/readdepth/
  ExplainActivity.kt   dialog-themed; handles SEND (text/plain) and
                       PROCESS_TEXT; shows channel box + streamed answer
  MainActivity.kt      launcher: search box, channel box, answer, recent
                       lookups
  SettingsActivity.kt  API key, model, system prompt
  ChannelsActivity.kt  channel manager
  OpenRouter.kt        streamed chat completion over HttpURLConnection
  Prompt.kt            builds the request (roster, history, context)
  Store.kt             lookups + channels (JSON file)
  Settings.kt          SharedPreferences wrapper
```

- **Two ways into a lookup from another app:**
  - `ACTION_SEND` with `text/plain`: the share sheet. It appears as
    **Read Depth**.
  - `ACTION_PROCESS_TEXT`: appears directly in the text-selection menu as
    **Read Depth**. It's one tap faster than Share. Excerpt Anywhere already
    registers both.
- **Dialog:** `ExplainActivity` uses a dialog theme, like Excerpt Anywhere's
  share form. The app you were reading stays visible behind it, and Back
  dismisses it.
- **Streaming:** read the SSE response line by line on a background thread
  and append it to the `TextView` on the UI thread.
- **Main screen:** search box with an **Explain** button, the channel box,
  the answer area, and then a list of recent lookups. Tap a lookup to reopen
  it.

---

## 7. Settings (both platforms)

| Setting | Default |
|---------|---------|
| OpenRouter API key | — (required) |
| Model | a fast, inexpensive model; chosen during phase 0 testing |
| System prompt | the shared default, with a reset button |
| Send surrounding context | on (Chrome only) |
| Channel roster size | 12 |
| History kept | 1,000 lookups |

The API key is stored in `chrome.storage.sync` (convenient, and tied to your
Google account) and in Android `SharedPreferences` (private to the app).

---

## 8. Build phases

0. **Prompt and format.**
   - Write `shared/system-prompt.md` and the response-format spec.
   - Add a small script that runs a set of sample lookups through two or
     three OpenRouter models. The samples cover medical, code, philosophy
     and a whole sentence, including a same-channel sequence and a
     come-back-tomorrow case.
   - Pick the default model on quality, speed and price.
1. **Chrome extension.**
   - Context menu, overlay, streaming, popup search, options.
   - Channels: auto, pinned, and correcting a misfile.
2. **Android app.**
   - Share and select-text entry points, dialog, main screen search,
     settings, channels.
   - First APK on GitHub Releases.
3. **Polish.**
   - Channel manager (rename, merge, delete).
   - Follow-up questions.
   - History browsing.
   - Error states: no key, no credit, rate-limited, offline.

## 9. Open questions

- **Default model:** to be decided from the phase 0 comparison.
- **Explanation length:** is 40–120 words right, or would you like a
  "Shorter / Longer" toggle?
- **Follow-ups:** should a follow-up count as part of the channel history,
  or only the original lookup?
