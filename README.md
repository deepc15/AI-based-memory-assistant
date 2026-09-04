# LocalMind

An on-device, privacy-first Android AI companion where conversations live strictly on your phone, powered by local open-source LLMs (Ollama / Llama) with an open web search fallback and transparent long-term memory extraction.

**Kotlin** · **Jetpack Compose (Material 3)** · **Room + SQLCipher** · **Local Llama (Ollama)** · **DuckDuckGo & Wikipedia Grounding** · **WorkManager**

---

## Key Highlights

- 🛡️ **100% Local & Zero Cloud Dependency**: Outbound cloud sync is disabled. No Firebase, no third-party account requirements, and no remote telemetry. Your conversations and memories never leave your device.
- 🧠 **Continuous On-Device Learning**: Simulates continuous learning without fine-tuning weights. As you chat, the app extracts durable personal facts asynchronously and injects them into the dynamic system prompt on every turn.
- 🦙 **Dual-Tier AI Engine**: Connects to a local open-source Llama model over HTTP/streaming (e.g. Ollama running on your local network/PC).
- 🌐 **Open Web Search Fallback**: If the local Llama server is offline or unreachable, the engine seamlessly falls back to open web search (DuckDuckGo Instant Answer API & Wikipedia Search API) to compile grounded answers with source citations.
- 🔐 **Hardware-Backed Encryption**: All chat history and extracted facts are encrypted at rest using SQLCipher with a 256-bit passphrase generated via `SecureRandom` and hardware-wrapped in the Android Keystore.
- 🧹 **Intelligent Data Lifecycle & Storage Reclamation**: Configurable retention window (7 / 30 / 90 / 365 days, or keep forever). Combines SQLite `DELETE` with `VACUUM` and `PRAGMA wal_checkpoint(TRUNCATE)` to physically reclaim disk space back to the Android OS.
- 📱 **Clean Jetpack Compose Interface**: Semantic color system featuring Mint accents for 100% on-device data, streaming token rendering, thinking animation, message pinning, and a full Memory & Storage disclosure sheet.

---

## How "Continuous Learning" Works Without Model Training

You cannot fine-tune or train an LLM directly on an Android smartphone—training requires adjusting billions of parameters across high-end GPU clusters. Apps that claim to "train on your chats in real time" are actually orchestrating prompt-level context injection.

LocalMind achieves personalized, realistic, and context-aware responses through three complementary mechanisms:

| Goal | Mechanism | Implementation |
|---|---|---|
| **Knows your personal context** | Asynchronously extracts facts from user messages into an encrypted local store, injected into every future prompt | `MemoryExtractor.kt`, `PromptBuilder.kt` |
| **Knows current facts** | Open web search (DuckDuckGo / Wikipedia) when Llama is offline or search grounding is needed | `AiEngine.kt` |
| **Maintains consistent personality** | Dynamic system prompt customized with active persona, wall clock, and locale | `PromptBuilder.kt` |

### Why Memory Injection Outperforms Fine-Tuning Here
1. **Immediate effect**: A newly learned fact is reflected in the very next turn rather than waiting for an offline training loop.
2. **100% User transparency**: Users can inspect, audit, and revoke/forget each extracted fact at any time in the app's Memory sheet.
3. **Privacy**: Personal facts remain local and are never baked into shared model weights.

---

## Architecture & Data Storage

### What is Stored Where

- **On the Phone Only** (Room database encrypted with SQLCipher):
  - Every user and assistant message, conversation titles, and timestamps.
  - Extracted long-term memory facts (`identity`, `preference`, `goal`, `relationship`, `constraint`).
  - Source citations from search lookups.
  - User retention and privacy disclosure settings.
- **In the Cloud**:
  - **Nothing.** Outbound sync is gated by `SyncPolicy.kt` (`shouldSync = false`), and `CloudStore.kt` acts as a local stub.
- **Android Cloud Backup Excluded**:
  - `data_extraction_rules.xml` and `AndroidManifest.xml` explicitly exclude `localmind.db`, `-wal`, `-shm`, and secure preferences from Google Drive backups and device-to-device transfers. If the phone is lost or wiped, local history cannot be recovered from Google servers.

### Encryption Details (`DatabaseKeys.kt`)

1. A cryptographically secure 256-bit random passphrase is generated on first launch.
2. The passphrase is encrypted (wrapped) with AES-256-GCM via the hardware-backed **Android Keystore** (`AndroidKeyStore`).
3. Only the wrapped ciphertext is stored in private SharedPreferences (`localmind_secure.xml`).
4. The database cannot be decrypted outside the physical device or if the app is uninstalled.

---

## Automatic Cleanup & Physical Disk Reclamation

Chat history past the configured retention window (7 / 30 / 90 / 365 days, or Keep Everything) is cleared automatically:

- **Exempt from deletion:**
  - Pinned messages (`pinned = 1`).
  - Extracted facts in the memory store (long-term knowledge outlives the chat history).
  - In-flight streaming replies or failed messages awaiting retry.
- **Physical Disk Reclamation (`RetentionSweeper.kt`):**
  - Standard SQLite `DELETE` merely marks pages as free for reuse without reducing the database file size.
  - LocalMind executes `DELETE` on expired rows, drops orphaned empty conversations, runs `VACUUM` to compact pages back to the filesystem, and executes `PRAGMA wal_checkpoint(TRUNCATE)` so the `-wal` sidecar file drops to 0 bytes.
- **Scheduled Automation (`RetentionWorker.kt`):**
  - Runs daily via Android `WorkManager` (survives reboots and process death).
  - Also sweeps on app launch so returning users immediately see current storage usage.
  - Safe by design: No deletion occurs until the user has seen the retention disclosure in the UI.

---

## Project Structure

```
memo-rise/LocalMind/
├── app/
│   ├── build.gradle.kts                   # Dependencies, Room KSP, and BuildConfig fields
│   └── src/main/
│       ├── AndroidManifest.xml            # Internet permission, cleartext LAN traffic, backup rules
│       ├── res/xml/data_extraction_rules.xml # Cloud backup exclusions for SQLCipher DB
│       └── java/com/localmind/chat/
│           ├── LocalMindApp.kt            # Initializes periodic WorkManager retention sweep
│           ├── MainActivity.kt            # Edge-to-edge Compose entry point
│           │
│           ├── ai/
│           │   ├── AiEngine.kt            # OkHttp streaming to local Llama + DuckDuckGo/Wikipedia fallback
│           │   ├── MemoryExtractor.kt     # Llama JSON fact extractor + offline NLP rule extractor fallback
│           │   └── PromptBuilder.kt       # Dynamic system instruction assembler (clock, persona, memories)
│           │
│           ├── data/
│           │   ├── local/
│           │   │   ├── Entities.kt        # Room entities (ConversationEntity, MessageEntity, MemoryEntity)
│           │   │   ├── Daos.kt            # Reactive Flow-based DAOs with retention queries
│           │   │   ├── LocalDatabase.kt   # Room database wired to SQLCipher encryption
│           │   │   └── DatabaseKeys.kt    # Android Keystore AES-GCM passphrase wrapping
│           │   ├── cloud/
│           │   │   └── CloudStore.kt      # Stubbed cloud surface for zero-cloud architecture
│           │   └── repo/
│           │       ├── ChatRepository.kt  # Local-first coordinator: writes, streaming, extraction
│           │       ├── SyncPolicy.kt      # Policy enforcer: guarantees zero outbound cloud uploads
│           │       ├── RetentionPolicy.kt # Retention windows (7/30/90/365/forever) and thresholds
│           │       ├── RetentionSweeper.kt# DELETE + VACUUM + wal_checkpoint(TRUNCATE) compaction
│           │       ├── RetentionWorker.kt # Daily WorkManager background sweep
│           │       └── RetentionSettings.kt# SharedPreferences for retention preferences & disclosure
│           │
│           └── ui/
│               ├── ChatScreen.kt          # Chat UI, Composer, Sources, and MemorySheet
│               ├── ChatViewModel.kt       # State flows for messages, memories, storage, and sweep actions
│               └── theme/
│                   └── Theme.kt           # Semantic color tokens (Mint = 100% on this phone)
```

---

## Getting Started & Setup Guide

### 1. Prerequisites
- **Android Studio** Ladybug (or newer) with JDK 17.
- An Android device or emulator running **Android 8.0 (API 26)** or higher.
- [Ollama](https://ollama.com/) installed on your computer (if using the local Llama engine).

---

### 2. Setting Up the Local Llama Server (Ollama)

1. Open PowerShell / terminal on your host computer.
2. Allow Ollama to accept connections from devices on your local Wi-Fi network:
   ```powershell
   $env:OLLAMA_HOST="0.0.0.0"
   ollama serve
   ```
3. In another terminal, pull and verify your preferred model (e.g., `llama3.2`):
   ```bash
   ollama run llama3.2
   ```
4. Find your PC's local network IP address (e.g. run `ipconfig` on Windows or `ifconfig` on macOS/Linux, looking for `192.168.x.x`).

---

### 3. Configure the Android App

Open `memo-rise/LocalMind/app/build.gradle.kts` and point `LOCAL_LLAMA_URL` to your host computer's IP address:

```kotlin
defaultConfig {
    applicationId = "com.localmind.chat"
    minSdk = 26
    targetSdk = 35
    versionCode = 1
    versionName = "1.0"

    // Set this to your host PC's local IP address running Ollama:
    buildConfigField("String", "LOCAL_LLAMA_MODEL", "\"llama3.2\"")
    buildConfigField("String", "LOCAL_LLAMA_URL", "\"http://192.168.x.x:11434\"")
}
```

> **Note**: If testing on an Android emulator connecting to Ollama running on the same host machine, you can use `http://10.0.2.2:11434`.

---

### 4. Build and Run

1. Open the project folder `memo-rise/LocalMind` in Android Studio.
2. Sync Gradle and build the app.
3. Run on your physical device or emulator.

---

### 5. Offline / No-Server Fallback Mode

If your computer is turned off or Ollama is unreachable:
- LocalMind will **not crash**.
- It will automatically transition to **Open Search Fallback Mode**, querying DuckDuckGo and Wikipedia to summarize responses and cite sources.
- Fact extraction will automatically switch to the built-in offline NLP pattern matcher (`extractOfflineFacts`), continuing to learn your name, location, and preferences without any server running.

---

## UI Color System & Meaning

LocalMind uses a purposeful color palette:
- **Mint (`#7FD4B0` Dark / `#1F8F68` Light)**: Indicates that the information has **never left the device**. Used for status dots, composer buttons, and local storage receipts.
- **Slate Cloud (`#7FA9E8` Dark / `#2E6CB8` Light)**: Indicates external sources or web citations.
- **Warning / Red (`#E08A7A`)**: Used for destructive actions (Forget memory, Clear now, Delete everything).
- Tap the **"On this phone"** header bar anytime to view all extracted memories, revoke facts, inspect disk footprint, and configure retention.

---

## License

This project is licensed under the MIT License - see the [LICENSE.md](LICENSE.md) file for details.
