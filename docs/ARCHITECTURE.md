# Architecture and what was built

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
- `META_ONLY`: a switch that turns off every non-Meta AI path in the backend (see [Runtime models](DISCLOSURE.md#runtime-models-only-meta-models-are-called)).

Android, Maya app (`android/`, 64 new files plus changes to 42 existing ones):
- Lessons screens (list, lesson, quiz, results), lesson store and progress store, offline lesson images.
- Sync queue changes and the learner sync client and manager, with token storage, connectivity detection and consent gating.
- Llama 3.2 1B Instruct as the first-preference model and the Llama 3 prompt format.
- WhatsApp-number sign-in, account deletion that erases the learner on the server and on the device, consent flow, release hardening, and about 25 test files.

