# Ekatra Offline

An offline-first AI tutor. A learner asks for a lesson on any topic (from the Maya Android app, or on WhatsApp). A backend uses **Meta's Muse Wonder** to write a short, graded micro-lesson and **Muse Image** to illustrate it. The phone downloads the lesson as a bundle, then everything after that works with no network: the lessons screens, quizzes graded on the device, and a **Llama 3.2 1B Instruct** tutor that runs fully on the phone through llama.cpp. Progress and quiz results sync back when the phone is next online.

**Built with Llama.**

This repository contains **only work done during the event** (after 2026-10-26T16:24:00Z). It does not contain the pre-existing code those features were built on; see [What existed before the event](#what-existed-before-the-event) and [docs/PRIOR_VS_NEW.md](docs/PRIOR_VS_NEW.md).

## Meta models: which ones, where they run

| Model | Used for | Where it runs |
|---|---|---|
| **Muse Wonder** (`muse-wonder-1.0` by default, set with `META_MODEL_ID`) | Writes the micro-lesson: modules, review questions, answers and explanations | Meta Model API, called from the **backend** (`backend/apps/backend/src/lib/metaModelClient.ts`) |
| **Muse Image** (`muse-image-1.0`, set with `META_IMAGE_MODEL_ID`) | One cover picture plus one picture per module (JPEG, max 2 MB, best effort) | Meta Model API, called from the **backend** |
| **Llama 3.2 1B Instruct** (Q4 GGUF by bartowski, downloaded by the app on first run) | The on-device tutor chat, answers with no network | **On the phone**, via llama.cpp (JNI), Llama 3 chat format in `PromptFormatter.kt` |

The Meta key never reaches the phone. It lives only in the backend environment.

## Runtime models: only Meta models are called

| Model | Called by | Where | How it is reached |
|---|---|---|---|
| Muse Wonder (`META_MODEL_ID`) | the backend (lesson text, WhatsApp course generation) | Meta Model API | `llmChat` in `apps/backend/src/lib/llm.ts` calls `metaModelClient` |
| Muse Image (`META_IMAGE_MODEL_ID`) | the backend (lesson pictures) | Meta Model API | `metaModelClient` |
| Llama 3.2 1B Instruct (Q4 GGUF) | the Maya app (on-device tutor) | the phone, llama.cpp | the app downloads it on first run; it is first in `modelPreference` for the Maya flavor |

Nothing else is called at runtime for AI. Firebase sign-in and Twilio are not AI models. The pre-existing backend also contains clients for Anthropic and Azure OpenAI (chat, embeddings, Whisper, image, speech); the `META_ONLY` switch disables them:

- `META_ONLY=1` forces it on, `META_ONLY=0` forces it off. If unset it is **on whenever `MODEL_API_KEY` (the Meta key) is set and no other AI provider key is set** (`AZURE_ANTHROPIC_API_KEY`, `ANTHROPIC_API_KEY`, `AZURE_OPENAI_API_KEY`, `OPENAI_API_KEY`, `AZURE_LLAMA_API_KEY`, `GOOGLE_API_KEY`, `AZURE_SPEECH_KEY`, `ELEVENLABS_API_KEY`).
- When on: `llmChat` skips the Anthropic and Azure branches, `llmAvailable()` checks only the Meta key, `azureOpenAI.isAvailable()` returns false and `getClient()` throws (so RAG embeddings and Whisper are off), and the Azure model router in `packages/delivery` reports every capability off (gpt-image, Azure speech, transcription, video).
- Code: `packages/delivery/src/lib/metaOnly.ts` (the rule), the patch `patches/backend/0050-meta-only.patch` (llm.ts, azureOpenAI.ts, modelRouter.ts, delivery `package.json`), tests in `apps/backend/src/lib/__tests__/metaOnly.test.ts`.
- **The submitted demo runs with only the Meta key configured** (`MODEL_API_KEY`, no Azure, Anthropic or OpenAI keys), so `META_ONLY` is on. `.env.example` sets `META_ONLY=1`.

## Architecture

```
 Learner (WhatsApp)                         Learner (Maya Android app)
        |                                    |  Lessons screens, sync queue,
        | Twilio / Cloud API                 |  lesson store (Room), Llama 3.2 1B
        v                                    |  on-device through llama.cpp
 +--------------------------- Backend (Node, Express, PostgreSQL) ----------------+
 |  /api/auth/whatsapp  WhatsApp one-time code -> Firebase custom token            |
 |  /api/sync/join|exchange   sign-in, learner token                               |
 |  /api/sync/lessons/request   ask for a lesson (idempotent, fenced claims)       |
 |  /api/sync/courses/:id/bundle   lesson bundle with image ids                    |
 |  /api/sync/media/:id   public lesson images (ETag, rate-limited)                |
 |  /api/sync/events   idempotent batches of quiz/progress events                  |
 |  /api/sync/me, DELETE|POST account deletion (ID token or learner token)         |
 |  courseGenerator -> llmChat(provider: 'meta') -> metaModelClient                |
 +------------------------------------+--------------------------------------------+
                                      |
                                      v
                    Meta Model API: Muse Wonder (text), Muse Image (pictures)
```

Flow: request lesson (online) -> backend generates with Muse -> bundle + images downloaded and stored on the phone -> lessons, quizzes and the Llama tutor work offline -> events queue locally and are sent in idempotent batches when connectivity returns.

## What was built during the event

Backend (`backend/`, 46 new files plus changes to 26 existing ones):
- Meta Model API client for Muse Wonder and Muse Image, plugged into the existing `llmChat` router.
- Micro-lesson generation (size, provider and force options), cover and per-module images, public image serving with absolute URLs and a bundle version.
- Sync API for the Android app: sign-in, lesson requests with claim fencing, lesson bundle, events, `/me`, account deletion by learner token or Firebase ID token.
- WhatsApp sign-in codes (Twilio Verify, own template, or mock), durable abuse caps, Firebase Admin token exchange.
- WhatsApp fixes: images through Twilio MediaUrl, stale plan cards, course completion with real name and a PDF certificate, day-end review questions one at a time, durable webhook receipts with retry.
- Migrations 012 to 018, demo scripts, and unit tests for all of the above.
- `META_ONLY`: a switch that turns off every non-Meta AI path in the backend (see [Runtime models](#runtime-models-only-meta-models-are-called)).

Android, Maya app (`android/`, 64 new files plus changes to 42 existing ones):
- Lessons screens (list, lesson, quiz, results), lesson store and progress store, offline lesson images.
- Sync queue changes and the learner sync client and manager, with token storage, connectivity detection and consent gating.
- Llama 3.2 1B Instruct as the first-preference model and the Llama 3 prompt format.
- WhatsApp-number sign-in, account deletion that erases the learner on the server and on the device, consent flow, release hardening, and about 25 test files.

## What existed before the event

Two private codebases, pinned at their last commit before kickoff (2026-09-30):

| Codebase | What it was | Base commit |
|---|---|---|
| Ekatra platform backend (WhatsApp and Telegram micro-learning platform, course generator, FSM, scheduler, admin UI, delivery and messaging packages) | `ekatraone/ekatra-platform-pvt`, a public snapshot from July 2026 is `ekatraone/ekatra-platform-oss` | `63964970` |
| Maya / Asmaa Android app (offline chat with an on-device model, Room, Firebase sign-in and analytics, host mode) | private repo `ekatra-asmaa` | `87a7fa3` |

The event work is an overlay on those two codebases. The pre-existing code lives in two separate repositories, each a sanitised single-commit snapshot of the pinned commit (authored 2026-09-30, before the event):

| Base repository | Snapshot of |
|---|---|
| https://github.com/ekatraone/ekatra-platform-base | backend, `63964970` |
| https://github.com/ekatraone/ekatra-maya-android-base | Android app, `87a7fa3`, with the `lib/llama.cpp` submodule |

To run the event code, follow the setup steps below: clone a base, run `scripts/apply_overlay.sh`, build. The public `ekatra-platform-oss` is *not* a usable base: its first commit (July) is too old for the patches (21 of 27 backend patches fail on it), and its later commits already contain event code.

## Setup

You need: Node 22.18.x, pnpm 10.28.1, PostgreSQL 14+, git. For the Android part: JDK 17, Android SDK and NDK (Android Studio), a phone or emulator.

### 1. Clone this repository and the bases

```
git clone https://github.com/ekatraone/ekatra-offline-hackathon ekatra-offline-hackathon                           # event work
git clone https://github.com/ekatraone/ekatra-platform-base          # backend base, single commit
git clone --recurse-submodules https://github.com/ekatraone/ekatra-maya-android-base   # Android base + llama.cpp
```

Put the three directories side by side. The directory layout is what matters, not the names.

### 2. Apply the event work

```
cd ekatra-offline-hackathon
scripts/apply_overlay.sh --backend ../ekatra-platform-base --android ../ekatra-maya-android-base
```

The script accepts the base snapshots (it recognises their commit subject), copies the event-authored files, applies `patches/<codebase>/*.patch` in order with `git apply`, and removes the files listed in `*.deletes`. It stops at the first patch that does not apply. It warns if the target is a git checkout at neither the pinned private commit nor a sanitised snapshot of it (`--force` to continue). It leaves the bases' git history alone: the result is uncommitted changes in the base checkouts.

### 3. Run the backend

Needs Node 22.18.x, pnpm 10.28.1 (`npx pnpm@10.28.1` works), PostgreSQL 14+, and Redis only for the delivery worker.

```
cd ../ekatra-platform-base
cp ../ekatra-offline-hackathon/.env.example .env      # then fill in the values, see step 4
npx pnpm@10.28.1 install --frozen-lockfile
npx pnpm@10.28.1 build:packages             # database, messaging, delivery
createdb ekatra
export DATABASE_URL=postgres://localhost/ekatra DATABASE_DISABLE_SSL=true
npx pnpm@10.28.1 db:migrate                 # applies the base migrations plus 012 to 018
(cd apps/backend && NODE_ENV=test npx pnpm@10.28.1 test)     # 295 tests
npx pnpm@10.28.1 dev                        # API on $PORT
```

### 4. Add your Meta key

Put these in the backend `.env` (or export them in the shell). Configure **only** the Meta key; leave every Azure, Anthropic and OpenAI variable unset (`.env.example` sets `META_ONLY=1` as well):

```
MODEL_API_KEY=<your Meta Model API key>
META_MODEL_API_BASE_URL=<Meta Model API base URL, default https://api.meta.ai/v1>
META_MODEL_ID=muse-wonder-1.0
META_IMAGE_MODEL_ID=muse-image-1.0
META_ONLY=1
```

Check it works, then generate a lesson from the command line (the scripts refuse to run against anything but localhost or an explicitly allowed demo database):

```
cd apps/backend
npx tsx src/scripts/museSmoke.ts
npx tsx src/scripts/issueDemoLearner.ts --phone +10000000000 --name Demo   # prints a 30-day learner token
npx tsx src/scripts/generateCourse.ts "Photosynthesis" en
```

`.env.example` lists every variable name the event code reads. Never commit `.env`.

### 5. Build the Android app

Needs JDK 17, Android Studio (SDK and NDK) and an arm64 phone or emulator image.

```
cd ../ekatra-maya-android-base
git submodule update --init lib/llama.cpp       # skip if you cloned with --recurse-submodules
cp app/src/maya/google-services.json.example app/src/maya/google-services.json
cp app/src/asmaa/google-services.json.example app/src/asmaa/google-services.json
# replace both with the files from your own Firebase project (packages org.ekatra.alfred and org.ekatra.asmaa)
cat >> local.properties <<'EOP'
sdk.dir=/path/to/Android/sdk
SYNC_BASE_URL=http://<your backend host>:<port>
DEMO_LEARNER_TOKEN=<token printed by issueDemoLearner.ts>
EOP
./gradlew :app:assembleMayaDebug
adb install -r app/build/outputs/apk/maya/debug/*.apk
```

**How the app finds the server.** Debug builds read `SYNC_BASE_URL` and `DEMO_LEARNER_TOKEN` from `local.properties` into `BuildConfig` (`app/build.gradle.kts`); the Lessons screen and the sync client use them (`LessonsModule.kt`). Release builds always compile both as empty strings, so no server address or token is inside a release build, and the Lessons entry is hidden in them. The Meta key is never in the app.

On first run the app downloads the Llama 3.2 1B Instruct GGUF. No model weights are stored in this repository.

**Try it without building.** The public demo APK that judges install is the release https://github.com/ekatraone/ekatra-android-oss/releases/tag/hackathon-submission-apk-1 (source snapshot of the Android half: `ekatraone/ekatra-android-oss`). The demo WhatsApp number is **+1 254 655 3377**.

### 6. Tests

Backend (tested on the sanitised base snapshot with the overlay: `apps/backend` 295 of 295 pass, `packages/delivery` 20 of 20, `tsc` clean; the 295 include 5 `META_ONLY` tests):

```
cd apps/backend && NODE_ENV=test npx pnpm@10.28.1 test
cd packages/delivery && NODE_ENV=test npx pnpm@10.28.1 test
```

Android (needs the full Android toolchain, not run in the overlay check): `./gradlew testMayaDebugUnitTest testAsmaaDebugUnitTest`.

## Repository layout

```
backend/   event-authored backend files, original paths
android/   event-authored Android files, original paths
patches/   one unified diff per original commit that touched a pre-existing file (event changes only),
           plus *.deletes lists of pre-existing files the event removed
scripts/   apply_overlay.sh
docs/PRIOR_VS_NEW.md   full commit table and file lists
```

Git history has one commit per original event commit, with the original author date and message, followed by the later commits listed in `docs/PRIOR_VS_NEW.md`.

## Disclosure

**Pre-existing code (not in this repo).**
- Ekatra platform backend, private repo `ekatraone/ekatra-platform-pvt` (public snapshot `ekatraone/ekatra-platform-oss`, July 2026), at `63964970`.
- Maya / Asmaa Android app, private repo `ekatra-asmaa`, at `87a7fa3`.
- Their commit history stays out of this repository because it predates the event. Sanitised single-commit snapshots of both are published as `ekatraone/ekatra-platform-base` and `ekatraone/ekatra-maya-android-base` (what was removed is listed in their READMEs).

**Third-party code, models and assets.**
- **llama.cpp** (MIT), included by the Android base as a git submodule (`lib/llama.cpp`); not modified during the event.
- **Llama 3.2 1B Instruct** weights under the **Llama 3.2 Community License**; downloaded by the app at runtime, not redistributed here. **Built with Llama.**
- **bartowski GGUF** quantization of Llama 3.2 1B Instruct, the file the app downloads.
- **Meta Muse Wonder and Muse Image** through the Meta Model API (outputs are generated at runtime).
- **Maya portrait image** (`app/src/main/res/drawable/maya_ai.png`) shipped in the app: AI-generated with Google Gemini. It predates the event and is part of the pre-existing Android code, not this repository.
- **Non-Meta models in the Android model catalog (pre-existing, not used by default).** The app's catalog (`ModelConfig.kt` and the Maya `ActivePersona.kt`) also lists non-Meta models: Liquid AI `lfm2.5-1.2b-instruct` and Google `gemma-3-1b-it` (the second and third entries of the Maya `modelPreference` list, after `llama-3.2-1b-instruct`), and, in the shared catalog, Google Gemma 4 E2B, Qwen 2.5 0.5B and 1.5B, Microsoft Phi-3 Mini and TinyLlama 1.1B. Llama 3.2 1B Instruct (minimum 2 GB RAM in the catalog) is first preference and the model the app downloads by default; on a phone with less memory than a catalog entry needs, the default moves down the `modelPreference` list, and the other catalog entries are only used if a saved model setting names them (the Maya flavor defines no load fallbacks). The pre-existing Remote Config default for `model_download_url` also names a Qwen file (the property is not read by the event code). The event did not add any of these.
- **Demo video.** The voiceover is ElevenLabs text-to-speech. The scenery shots are AI-generated (Meta Muse Image and ElevenLabs) and are labelled as such in the video. The video is not part of this repository.
- **Firebase** (Authentication, Firestore, Analytics) for sign-in and the existing app backend. Not an AI model.
- **Twilio** (Verify and WhatsApp messaging) for sign-in codes and delivery. Not an AI model.
- Libraries pulled in by the lockfiles and Gradle files of the base repositories (Express, Room, Kotlin and others) under their own licenses.

**Withheld on purpose.** Internal planning notes, store and signing material, Firebase project configuration files and business documents that were committed during the event are not included; the patches skip them (the overlay output equals the deployed trees except for those files).

**AI assistance.** Much of the code in the event commits was written with Claude Code, as shown in the commit trailers.

## License

MIT, see [LICENSE](LICENSE). Third-party components keep their own licenses.
