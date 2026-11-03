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

Backend (`backend/`, 43 new files plus changes to 23 existing ones):
- Meta Model API client for Muse Wonder and Muse Image, plugged into the existing `llmChat` router.
- Micro-lesson generation (size, provider and force options), cover and per-module images, public image serving with absolute URLs and a bundle version.
- Sync API for the Android app: sign-in, lesson requests with claim fencing, lesson bundle, events, `/me`, account deletion by learner token or Firebase ID token.
- WhatsApp sign-in codes (Twilio Verify, own template, or mock), durable abuse caps, Firebase Admin token exchange.
- WhatsApp fixes: images through Twilio MediaUrl, stale plan cards, course completion with real name and a PDF certificate, day-end review questions one at a time, durable webhook receipts with retry.
- Migrations 012 to 018, demo scripts, and unit tests for all of the above.

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

The event work does not run on its own: it is an overlay on those two codebases. A **pre-event base repository** for each is named in the setup steps below. The public `ekatra-platform-oss` is *not* a usable base: its first commit (July) is too old for the patches (21 of 27 backend patches fail on it), and its later commits already contain event code.

## Setup

You need: Node 22.18.x, pnpm 10.28.1, PostgreSQL 14+, git. For the Android part: JDK 17, Android SDK and NDK (Android Studio), a phone or emulator.

### 1. Get the pre-event bases

Obtain the two pre-event base checkouts and put them next to this repository. The base repositories are published separately as sanitized single-commit snapshots of the pinned commits (names to be filled in by the owner before submission):

```
git clone <backend pre-event base repo>   ekatra-platform-base   # commit 63964970 equivalent
git clone <android pre-event base repo>   ekatra-maya-base       # commit 87a7fa3 equivalent (with lib/llama.cpp submodule)
```

### 2. Apply the event work

```
git clone <this repo> ekatra-offline && cd ekatra-offline
scripts/apply_overlay.sh --backend ../ekatra-platform-base --android ../ekatra-maya-base
```

The script copies the event-authored files, applies `patches/<codebase>/*.patch` in order with `git apply`, and removes the files listed in `*.deletes`. It stops at the first patch that does not apply. It warns if the target is a git checkout not at the pinned base (`--force` to continue).

### 3. Run the backend

```
cd ../ekatra-platform-base
cp ../ekatra-offline/.env.example .env      # then fill in the values, see below
npx pnpm@10.28.1 install --frozen-lockfile
npx pnpm@10.28.1 -r build                   # database, messaging, delivery, backend
createdb ekatra && export DATABASE_URL=postgres://localhost/ekatra
npx pnpm@10.28.1 db:migrate                 # applies migrations including 012 to 018
npx pnpm@10.28.1 dev                        # API on $PORT
```

### 4. Add your Meta key

Put these in the backend `.env` (or export them in the shell):

```
MODEL_API_KEY=<your Meta Model API key>
META_MODEL_API_BASE_URL=<Meta Model API base URL, default https://api.meta.ai/v1>
META_MODEL_ID=muse-wonder-1.0
META_IMAGE_MODEL_ID=muse-image-1.0
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

```
cd ../ekatra-maya-base
git submodule update --init lib/llama.cpp
cat >> local.properties <<'EOP'
SYNC_BASE_URL=http://<your backend host>:<port>
DEMO_LEARNER_TOKEN=<token printed by issueDemoLearner.ts, debug builds only>
EOP
# add your own Firebase config as app/src/maya/google-services.json
./gradlew assembleMayaDebug
adb install -r <the APK Gradle prints under app/build/outputs/apk/maya/debug/>
```

On first run the app downloads the Llama 3.2 1B Instruct GGUF. No model weights are stored in this repository.

### 6. Tests

Backend (tested on the pinned base with the overlay: 290 of 290 pass, `tsc` clean):

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

Git history has one commit per original event commit, with the original author date and message.

## Disclosure

**Pre-existing code (not in this repo).**
- Ekatra platform backend, private repo `ekatraone/ekatra-platform-pvt` (public snapshot `ekatraone/ekatra-platform-oss`, July 2026), at `63964970`.
- Maya / Asmaa Android app, private repo `ekatra-asmaa`, at `87a7fa3`.
- Their commit history stays out of this repository because it predates the event.

**Third-party code, models and assets.**
- **llama.cpp** (MIT), included by the Android base as a git submodule (`lib/llama.cpp`); not modified during the event.
- **Llama 3.2 1B Instruct** weights under the **Llama 3.2 Community License**; downloaded by the app at runtime, not redistributed here. **Built with Llama.**
- **bartowski GGUF** quantization of Llama 3.2 1B Instruct, the file the app downloads.
- **Meta Muse Wonder and Muse Image** through the Meta Model API (outputs are generated at runtime).
- **Maya portrait image** shipped in the app: generated with Google Gemini; it predates the event and is part of the pre-existing Android code, not this repository.
- **Firebase** (Authentication, Firestore, Analytics) for sign-in and the existing app backend.
- **Twilio** (Verify and WhatsApp messaging) for sign-in codes and delivery.
- Libraries pulled in by the lockfiles and Gradle files of the base repositories (Express, Room, Kotlin and others) under their own licenses.

**Withheld on purpose.** Internal planning notes, store and signing material, Firebase project configuration files and business documents that were committed during the event are not included; the patches skip them (the overlay output equals the deployed trees except for those files).

**AI assistance.** Much of the code in the event commits was written with Claude Code, as shown in the commit trailers.

## License

MIT, see [LICENSE](LICENSE). Third-party components keep their own licenses.
