# Disclosure and runtime models (full)

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

