# LocalMind

An Android AI chatbot where the conversation lives on the phone and only a small,
user-visible slice reaches Firebase.

Kotlin · Jetpack Compose · Room + SQLCipher · Firebase AI Logic (Gemini) · Firestore

---

## About "training the AI with a real-time dataset"

This is worth being direct about, because it changes what you build.

You cannot train or fine-tune a language model inside an Android app. Training means
adjusting billions of weights across a GPU cluster over hours or days. A phone can't do
it, and no mobile SDK exposes it. Anyone who tells you their app "trains its AI on user
chats in real time" is describing one of the three mechanisms below.

What actually produces current, personal, realistic-feeling replies:

| Goal | Mechanism | Where it lives |
|---|---|---|
| Knows today's facts | Grounding with Google Search — the model queries the live web per turn | `AiEngine.kt` |
| Knows *you*, and improves as you talk | Facts extracted from your messages, stored locally, injected into every prompt | `MemoryExtractor.kt`, `PromptBuilder.kt` |
| Has a consistent personality | System instruction, tunable without shipping a build | `PromptBuilder.kt` |

The second one is what people usually mean by "learns from me," and it's better than
fine-tuning for this use case: it takes effect on the very next message rather than the
next training run, the user can read and delete each fact, and nothing about them is
ever baked into shared model weights.

If you genuinely need weight-level customisation later, that's a separate offline
pipeline: export a curated dataset, tune a model on Vertex AI, then point
`CHAT_MODEL` at the tuned endpoint. Don't start there — the prompt-and-memory approach
handles the overwhelming majority of what "make it realistic" means in practice.

---

## What is stored where

**On the phone** (Room, in a SQLCipher-encrypted file, key wrapped by the Android
Keystore):

- every message, both sides, in full
- conversation titles and timestamps
- every extracted fact, including ones too sensitive to sync
- grounding sources for each reply

**In Firestore** — only these three things:

- `users/{uid}` — the persona string and settings
- `users/{uid}/memories/{id}` — durable facts, but only high-confidence ones in an
  allowed category
- `users/{uid}/pinned/{id}` — individual messages the user explicitly pinned

Never uploaded: raw transcripts, unpinned messages, drafts, the database key, or
anything categorised health / finance / credentials / precise location / political /
religious / sexuality.

Two things enforce this rather than one:

1. `SyncPolicy.kt` is the only gate in the app. Every Firestore write passes through it,
   so "what does this app upload?" is answered by reading one short file.
2. `firestore.rules` re-checks the same constraints server-side, because a client-side
   rule is unenforceable once someone repackages your APK. `CloudStore` also has no
   method capable of writing a conversation — the absence is deliberate.

Local history is excluded from Android cloud backup in `data_extraction_rules.xml`. If
it were included, Google would hold a copy of the transcript, which defeats the point.
The consequence is real and worth surfacing in your onboarding: **lose the phone, lose
the history.** Only the pinned slice comes back.

---

## Automatic cleanup

Unimportant chat history is deleted after 30 days (configurable: 7 / 30 / 90 / 365 /
never). The window lives in `RetentionPolicy.kt` — the companion to `SyncPolicy`, so
each data-lifecycle question sits in one short readable file.

**Exempt from deletion, regardless of age:**

- pinned messages — the user marked these important, and they have a cloud copy
- everything in the memory store — the app's long-term knowledge
- a reply still streaming, or a failed one the user can still retry

The exemptions live in the SQL `WHERE` clause rather than in Kotlin, so no caller can
sweep without them.

**Deleting old chats costs less than it looks like it should.** Facts are extracted at
send time, so what the assistant *learned* from a conversation outlives the
conversation. Only the most recent `CONTEXT_TURNS` are ever sent to the model, so a
chat from three months ago contributes nothing to the current reply either way.

### Why deletion alone frees no space

SQLite does not shrink its file when rows are deleted — it marks the pages free for
later reuse. A `DELETE`-only implementation would clear the data while leaving phone
storage exactly as crowded as before, which is the opposite of the goal. So
`RetentionSweeper` runs two steps:

1. `DELETE` the expired rows, then drop conversation shells the sweep emptied
2. `VACUUM` to rewrite the database compactly and hand the pages back to the
   filesystem, then `PRAGMA wal_checkpoint(TRUNCATE)` so the `-wal` sidecar doesn't
   stay large

`VACUUM` is proportional to database size and needs temporary space for a copy, so it's
rate-limited: it runs when a sweep clears 150+ messages, when a week has passed since
the last one, or immediately when the user taps "Clear now". A failed `VACUUM` is not a
failed sweep — the rows are already gone and the free pages get reused.

### When it runs

`RetentionWorker` is a daily `PeriodicWorkRequest`. Someone who doesn't open the app
for two months shouldn't be storing two months of chat, and the job needs to survive
reboots and process death. It's enqueued with `KEEP`, so scheduling it on every cold
start is safe and doesn't reset the period. There is deliberately **no**
`requiresStorageNotLow` constraint — that would block the sweep exactly when freeing
storage matters most.

A sweep also runs on launch, so a returning user sees retention applied immediately
instead of waiting for WorkManager's next window.

### Change this before you ship

Silently deleting someone's data is a trust problem rather than a technical one, so the
sweep is gated on `RetentionSettings.disclosureShown`. That flag is currently set when
the user sees the empty state or opens the storage sheet, both of which state the
window in plain language. **Move it into your onboarding flow** — otherwise a user who
never sees an empty chat and never opens the sheet would never have retention applied
at all.

The storage sheet names the number before the user commits ("47 messages are past 30
days and will be cleared"). That preview is the difference between a setting people
trust and one they're afraid to touch.

If losing the history bothers you, the natural addition is a JSON export before the
sweep. The hook is already there: `MessageDao.observeExpiring` returns exactly the set
that is about to go.

---

## Setup

**1. Create the Firebase project**

At [console.firebase.google.com](https://console.firebase.google.com): new project →
add an Android app with package name `com.localmind.chat` → download
`google-services.json` into `app/`. It's gitignored; keep it that way.

**2. Turn on the services**

- **Firebase AI Logic** — click through the setup wizard, pick the *Gemini Developer
  API* backend. This provisions the API and proxies calls so no Gemini key ships in
  your APK.
- **Firestore** — create a database, then `firebase deploy --only firestore:rules` to
  push `firestore.rules`. Do this before your first real user; the default rules are
  wide open.
- **Authentication** — enable the Anonymous provider. Each install gets a stable `uid`,
  which is what makes the security rules meaningful. Link to a real credential later
  with `linkWithCredential` if you want pinned data to survive reinstalls.
- **App Check** — register the app with Play Integrity. Skipping this leaves your
  Gemini quota billable by anyone who extracts your config. In debug builds, grab the
  token from logcat and register it under App Check → Manage debug tokens.

**3. Build**

Open in Android Studio and run. Android Studio will likely offer newer AGP/Kotlin
versions than the ones pinned in `gradle/libs.versions.toml` — accept, but upgrade AGP,
Kotlin and KSP together, since they're version-locked to each other.

Two version constraints that matter: Firebase BoM must be **34.0.0 or newer** for
Google Search grounding, and `minSdk` is 26 for hardware-backed AES/GCM in the
Keystore.

---

## Layout

```
ai/
  AiEngine.kt        Streaming replies, Google Search grounding, source extraction
  PromptBuilder.kt   Rebuilds the system instruction per request from memory + clock
  MemoryExtractor.kt Distils durable facts from a turn; runs after the reply, off the
                     critical path, on a cheaper model
data/local/
  Entities.kt        conversations, messages, memories
  Daos.kt            Flow-based queries
  LocalDatabase.kt   Room wired to SQLCipher
  DatabaseKeys.kt    Keystore-wrapped random passphrase
data/cloud/
  CloudStore.kt      The entire cloud surface. Deliberately narrow.
data/repo/
  SyncPolicy.kt        The one gate for uploads. Read this to audit the app.
  RetentionPolicy.kt   The 30-day window and what is exempt from it
  RetentionSweeper.kt  DELETE + VACUUM, so space is actually returned to the OS
  RetentionWorker.kt   Daily background sweep
  RetentionSettings.kt Window, last-vacuum bookkeeping, disclosure flag
  ChatRepository.kt    Local-first writes, streaming, extraction, retry
ui/
  ChatScreen.kt      Chat, pin affordance, source chips, disclosure sheet
  ChatViewModel.kt
  theme/Theme.kt     Mint = on this phone. Blue = backed up.
```

The UI colour system does one job: mint means the data has never left the device, blue
means a server has a copy. Once those two carry meaning, nothing else in the palette is
allowed to be saturated, or the signal stops reading. The disclosure sheet lists every
fact the app holds, why it did or didn't sync, and a per-item Forget that deletes the
Firestore document too.

---

## Design decisions you may want to revisit

**Local-first writes.** Both the user turn and an empty assistant row are committed to
Room before the network call, and the stream writes into that row. The UI renders purely
from Room, so a kill mid-reply leaves a partial answer rather than a hole. Cost: one DB
write per token batch. If it shows up in profiling, buffer and flush every ~100ms.

**Extraction runs after the reply.** It never adds latency, and a failure is swallowed —
enrichment must never break a conversation. It's also narrow by design: extract
liberally and the memory store fills with conversational trivia, which degrades every
later reply.

**Pinning is the only route to the cloud for message text.** An automatic importance
classifier was the alternative, and it's the wrong call for a privacy app — the user
can't predict it, so they can't trust it. An explicit tap they can see the result of is
worth more than a smarter heuristic.

**Anonymous auth.** Zero signup friction, real `uid` for the rules. The tradeoff is that
uninstalling orphans the cloud data.

## Before you ship

- Google Search grounding carries display requirements from the API provider (you must
  show the grounded sources — `Sources()` in `ChatScreen.kt` does this). Check the
  current terms for your chosen backend.
- Write real Room migrations for schema changes. There's intentionally no
  `fallbackToDestructiveMigration()`, because destroying local-only history is
  unrecoverable.
- Set a Firebase billing budget alert. Grounding calls cost more than plain generation,
  and a runaway loop is expensive.
- If you want history behind biometrics, add `setUserAuthenticationRequired(true)` in
  `DatabaseKeys.kt` — but note that background sync then can't read the DB while locked.
