# Prior work vs event work

Event start (kickoff end) is **2026-10-26T16:24:00Z**. Everything below was committed after that moment, in two private repositories, and is reconstructed here one commit per original commit with the original author date and subject. Original hashes refer to the private repositories and are listed for traceability only.

Pinned pre-event bases (the last commit before kickoff in each private repo):

| Codebase | Private repo | Base commit | Base date | Event head |
|---|---|---|---|---|
| backend | ekatraone/ekatra-platform-pvt | `63964970` | 2026-09-30 | `c342241d` (branch `hackathon/demo-stable`) |
| android | ekatra-asmaa, branch `hackathon/maya-lessons` | `87a7fa3` | 2026-09-30 | `7013108` |

## backend: 49 original commits, 49 reconstructed

- (i) Event-authored files (did not exist before the event), stored verbatim under `backend/`: **43**
- (ii) Pre-existing files modified during the event, stored as diffs under `patches/backend/`: **23**
- Pre-existing files deleted during the event (listed in `patches/backend/*.deletes`): **225**
- Paths withheld from this repository on purpose (internal notes, store/signing material, Firebase configs, business docs): **8**

| # | Original | Date (UTC) | Subject | This repo |
|---|---|---|---|---|
| 1 | `3c906499` | 2026-10-30 16:51Z | Add Meta Model API (Muse Spark) provider to llmChat | `72ff03b` |
| 2 | `957e606a` | 2026-10-31 02:20Z | Add Muse Spark smoke script | `47f0b1f` |
| 3 | `611b1472` | 2026-10-31 02:20Z | Add migration 012: client_event_id and sync_lesson_requests | `55b6bf9` |
| 4 | `84e059cc` | 2026-10-31 02:21Z | Add micro course size, provider and force options to course generator | `6a4969a` |
| 5 | `fb9a853f` | 2026-10-31 02:23Z | Add sync service and /api/sync routes for the Maya offline app | `964366c` |
| 6 | `70baf933` | 2026-10-31 02:23Z | Add generateCourse and issueDemoLearner scripts with a production-database guard | `55d043e` |
| 7 | `d69a16fa` | 2026-10-31 02:24Z | Initialize the database pool in the demo scripts | `994f3da` |
| 8 | `4146f7bd` | 2026-10-31 02:27Z | Add sync service and micro course generator tests | `fd5ff97` |
| 9 | `669ba3b8` | 2026-10-31 02:32Z | Fence lesson-request claims with attempt numbers, heartbeat and a retry cap | `4817c7f` |
| 10 | `fd1d964e` | 2026-10-31 02:32Z | Harden sync learner auth: require exp, UUID ids and a live learner row | `7489bee` |
| 11 | `8d18863f` | 2026-10-31 03:11Z | Send WhatsApp images through Twilio as MediaUrl instead of a link in the body | `1a2918a` |
| 12 | `3c4c57f4` | 2026-10-31 03:11Z | Use a configurable Muse model and generate a cover plus one image per module | `c7bfeeb` |
| 13 | `e84695ba` | 2026-10-31 03:11Z | Serve lesson images publicly and add image URLs to the bundle | `6ec0036` |
| 14 | `3cca8552` | 2026-10-31 03:11Z | Send lesson and cover pictures on WhatsApp and update the generating message | `66cafe2` |
| 15 | `afcd5e51` | 2026-10-31 03:13Z | Exempt public lesson images from the global API rate limiter | `c524595` |
| 16 | `134b69df` | 2026-10-31 06:35Z | Add shared helpers to resolve stored media paths into public URLs | `f8fb186` |
| 17 | `b7b7addb` | 2026-10-31 06:35Z | Bound image waiting by a deadline, store relative media paths, accept only small JPEGs | `e4ac517` |
| 18 | `3ede225f` | 2026-10-31 06:35Z | Add bundleVersion to /me, image ids to the bundle version, media ETag and limiter, 330 s socket timeout | `b827d82` |
| 19 | `147b04ab` | 2026-10-31 06:35Z | Make WhatsApp image links absolute when sending | `e1bd328` |
| 20 | `72b253cd` | 2026-10-31 06:39Z | Reduce the public base URL to its origin and require https in production | `2fbeda7` |
| 21 | `a3163fb3` | 2026-10-31 06:39Z | Compute /me bundle versions in two queries with the same hash as the bundle | `6e7c3c4` |
| 22 | `9bc4fb01` | 2026-10-31 06:39Z | Trust one proxy hop so per-IP rate limiters see real clients | `1f197a0` |
| 23 | `f1223b12` | 2026-10-31 07:22Z | Share find-or-create learner logic matching the WhatsApp flow's phone format | `fd99b39` |
| 24 | `b2c9db1b` | 2026-10-31 07:22Z | Add public tester sign-in (POST /api/sync/join) behind DEMO_INVITE_CODE | `1b15d57` |
| 25 | `226ffc95` | 2026-10-31 07:29Z | Never send to app-only fb: learners: messaging guard and reminder skip | `495975c` |
| 26 | `d8506fc1` | 2026-10-31 07:29Z | Add WhatsApp sign-in code providers (Twilio Verify, own template, mock) and lazy Firebase Admin | `635c0c7` |
| 27 | `a33519cf` | 2026-10-31 07:29Z | Add WhatsApp sign-in routes and the Firebase ID token exchange | `55cf0e1` |
| 28 | `89b50f4f` | 2026-10-31 07:39Z | Treat a no-channel send as a permanent skip: no retry, no dead-letter queue | `2ffbad2` |
| 29 | `27cbdc2b` | 2026-10-31 07:39Z | Add durable OTP abuse caps, a separate HKDF-derived code key and stale-row purging | `eda0a78` |
| 30 | `8507fab9` | 2026-10-31 07:39Z | Harden tester join, trust phone identity only from its sign-in provider, add account deletion | `3e81645` |
| 31 | `a83a4b1f` | 2026-10-31 07:39Z | Log Firebase init failures without the error text; log the proxy chain once | `0283897` |
| 32 | `81747d24` | 2026-10-31 07:43Z | Shorten the sign-in lock to 1 hour (24 h on a repeat), refund failed sends, warn on the global cap | `8109425` |
| 33 | `12213d8c` | 2026-10-31 07:43Z | Delete orphaned AI-generated courses with the account | `03c6b27` |
| 34 | `5531ed8c` | 2026-10-31 07:43Z | Log the proxy chain for the first request that carries X-Forwarded-For | `6daadc6` |
| 35 | `d19b3443` | 2026-10-31 07:45Z | Add DELETE /api/sync/auth/account: delete an account with a Firebase ID token | `b02366f` |
| 36 | `5a3b682c` | 2026-10-31 07:51Z | Serve account deletion by ID token also as POST /auth/account/delete | `9997bcb` |
| 37 | `1d7a4c4b` | 2026-10-31 07:55Z | Delete only courses generated for the learner when their account is deleted | `a5139f1` |
| 38 | `450ea42c` | 2026-10-31 07:56Z | Make trusted proxy hops configurable (TRUST_PROXY_HOPS, 2 in production) and log the XFF chain | `2584e77` |
| 39 | `ce679e13` | 2026-10-31 07:59Z | Answer auth_not_configured on every 503 caused by a missing Firebase service account | `6907107` |
| 40 | `a72581ec` | 2026-10-31 08:01Z | Answer auth_unavailable when the Firebase key is set but unusable | `344532e` |
| 41 | `2f338c8f` | 2026-10-31 10:39Z | Fix stale WhatsApp plan cards and ask the course style for every new course | `3784590` |
| 42 | `848a6716` | 2026-10-31 10:54Z | Fix course completion on WhatsApp: real name and course, certificate as a PDF, optional feedback flow | `24fdacb` |
| 43 | `937ff8e2` | 2026-10-31 15:07Z | Send the day-end review questions one at a time on WhatsApp | `dd6ed0d` |
| 44 | `63cb02e1` | 2026-11-01 01:11Z | Stop tracking build output, node_modules and .DS_Store | `5886620` |
| 45 | `18fa12e6` | 2026-11-01 01:11Z | Persist Cloud API webhook receipts and retry failed handling | `220218b` |
| 46 | `643188af` | 2026-11-01 01:11Z | Tighten route access and certificate access checks | `7b7bd7c` |
| 47 | `b9a61199` | 2026-11-01 01:11Z | Update production setup, hardening progress and integration docs | `dcc0bf2` |
| 48 | `2f5d9429` | 2026-11-01 01:14Z | Renumber webhook receipts migration to 018 | `3d6c65e` |
| 49 | `c342241d` | 2026-11-03 18:33Z | Rename WhatsApp chatbot persona Alfred to Maya | `9a3cf51` |

### (i) Event-authored files

- `backend/apps/backend/src/flows/__tests__/quickReplyCacheKey.test.ts`
- `backend/apps/backend/src/lib/__tests__/hardening.test.ts`
- `backend/apps/backend/src/lib/firebaseAdmin.ts`
- `backend/apps/backend/src/lib/metaModelClient.ts`
- `backend/apps/backend/src/lib/proxyDiagnostics.ts`
- `backend/apps/backend/src/routes/__tests__/authNotConfigured.test.ts`
- `backend/apps/backend/src/routes/__tests__/authWhatsapp.test.ts`
- `backend/apps/backend/src/routes/__tests__/fakeAuthDb.ts`
- `backend/apps/backend/src/routes/__tests__/syncAuth.test.ts`
- `backend/apps/backend/src/routes/__tests__/syncDelete.test.ts`
- `backend/apps/backend/src/routes/__tests__/syncExchange.test.ts`
- `backend/apps/backend/src/routes/__tests__/syncJoin.test.ts`
- `backend/apps/backend/src/routes/__tests__/syncMedia.test.ts`
- `backend/apps/backend/src/routes/authWhatsapp.ts`
- `backend/apps/backend/src/routes/sync.ts`
- `backend/apps/backend/src/scripts/generateCourse.ts`
- `backend/apps/backend/src/scripts/issueDemoLearner.ts`
- `backend/apps/backend/src/scripts/museSmoke.ts`
- `backend/apps/backend/src/scripts/safeDb.ts`
- `backend/apps/backend/src/services/__tests__/cloudApiReceipts.test.ts`
- `backend/apps/backend/src/services/__tests__/courseImages.test.ts`
- `backend/apps/backend/src/services/__tests__/courseOwnership.test.ts`
- `backend/apps/backend/src/services/__tests__/otpLimits.test.ts`
- `backend/apps/backend/src/services/__tests__/syncService.test.ts`
- `backend/apps/backend/src/services/__tests__/webhookReceiptRetry.test.ts`
- `backend/apps/backend/src/services/__tests__/whatsappOtp.test.ts`
- `backend/apps/backend/src/services/accountDeletion.ts`
- `backend/apps/backend/src/services/cloudApiReceipts.ts`
- `backend/apps/backend/src/services/courseImages.ts`
- `backend/apps/backend/src/services/firebaseIdentity.ts`
- `backend/apps/backend/src/services/learnerIdentity.ts`
- `backend/apps/backend/src/services/otpLimits.ts`
- `backend/apps/backend/src/services/syncService.ts`
- `backend/apps/backend/src/services/webhookRecovery.ts`
- `backend/apps/backend/src/services/whatsappOtp.ts`
- `backend/packages/database/src/migrations/012_learner_events_sync.sql`
- `backend/packages/database/src/migrations/013_sync_lesson_request_attempts.sql`
- `backend/packages/database/src/migrations/014_lesson_media.sql`
- `backend/packages/database/src/migrations/015_otp_challenges.sql`
- `backend/packages/database/src/migrations/016_learner_channel_addresses.sql`
- `backend/packages/database/src/migrations/017_otp_events.sql`
- `backend/packages/database/src/migrations/018_webhook_receipts.sql`
- `backend/packages/delivery/src/__tests__/certificateAccess.test.ts`

### (ii) Pre-existing files modified (diffs only)

- `.env.example`
- `.gitignore`
- `apps/backend/src/flows/__tests__/conversationFSM.test.ts`
- `apps/backend/src/flows/conversationFSM.ts`
- `apps/backend/src/index.ts`
- `apps/backend/src/lib/llm.ts`
- `apps/backend/src/middleware/__tests__/auth.test.ts`
- `apps/backend/src/routes/admin.ts`
- `apps/backend/src/routes/analytics.ts`
- `apps/backend/src/routes/certificates.ts`
- `apps/backend/src/routes/courses.ts`
- `apps/backend/src/routes/schedules.ts`
- `apps/backend/src/routes/webhooks.ts`
- `apps/backend/src/services/__tests__/courseGenerator.test.ts`
- `apps/backend/src/services/courseGenerator.ts`
- `apps/backend/src/services/messageHandler.ts`
- `apps/backend/src/services/qaService.ts`
- `apps/backend/src/services/scheduler.ts`
- `packages/delivery/src/__tests__/sendResult.test.ts`
- `packages/delivery/src/sendResult.ts`
- `packages/delivery/src/services/certificateService.ts`
- `packages/delivery/src/services/courseDelivery.ts`
- `packages/messaging/src/index.ts`

## android: 61 original commits, 53 reconstructed

- (i) Event-authored files (did not exist before the event), stored verbatim under `android/`: **64**
- (ii) Pre-existing files modified during the event, stored as diffs under `patches/android/`: **42**
- Pre-existing files deleted during the event (listed in `patches/android/*.deletes`): **2**
- Paths withheld from this repository on purpose (internal notes, store/signing material, Firebase configs, business docs): **15**

| # | Original | Date (UTC) | Subject | This repo |
|---|---|---|---|---|
| 1 | `e6283b54` | 2026-10-30 02:21Z | Add Oct 2026 audit and roadmap | withheld (only withheld paths) |
| 2 | `467a4976` | 2026-10-30 02:36Z | Fix chat context overflow, reload crash, and UTF-8 token streaming | `c9f73f0` |
| 3 | `6c7afe86` | 2026-10-30 02:36Z | Add Gemma 4 empty thought channel and explicit LFM2.5 template branch | `defb651` |
| 4 | `191b002b` | 2026-10-30 02:36Z | Target API 36 and version each persona flavor separately | `c2894eb` |
| 5 | `7e2bf31f` | 2026-10-30 02:36Z | Serve a per-flavor host mode page | `bada124` |
| 6 | `a40f1ba9` | 2026-10-30 02:36Z | Collect analytics only after consent | `fc0d9b8` |
| 7 | `099f923d` | 2026-10-30 02:36Z | Tighten Firestore rules and add Firebase CLI config | `10ce572` |
| 8 | `2e20b5b0` | 2026-10-30 02:36Z | Escape bidi isolate marks in ChatMarkdown | `e93504c` |
| 9 | `b11e0382` | 2026-10-30 02:41Z | Require a per-session key on the host mode server and cap requests | `a80e08f` |
| 10 | `d70beaa7` | 2026-10-30 02:41Z | Handle full storage in model downloads; stabilize model selection | `7666248` |
| 11 | `e4fef469` | 2026-10-30 02:44Z | Add repeat penalty and random seed; drop Qwen3-only think switches | `24c052e` |
| 12 | `be626823` | 2026-10-30 02:44Z | Open the database off the main thread; make migration asserts real | `c73fc66` |
| 13 | `589a7206` | 2026-10-30 02:44Z | Strip verbose, debug and info logs from release builds | `8dfd011` |
| 14 | `36db5038` | 2026-10-30 02:44Z | MathInterceptor: skip dates, whole-word operators, Arabic-Indic digits | `8fd7121` |
| 15 | `24f9c0d3` | 2026-10-30 02:45Z | Make prompt prefill cancellable | `c56b05b` |
| 16 | `5efaf37c` | 2026-10-30 02:46Z | Stop Asmaa remote prompts from reaching Maya; add persona tests | `be52163` |
| 17 | `84c71c1c` | 2026-10-30 02:46Z | Record audit progress | withheld (only withheld paths) |
| 18 | `1acf8ded` | 2026-10-30 12:25Z | Firebase: register Asmaa app and use per-app configs with upload and Play-signing hashes | withheld (only withheld paths) |
| 19 | `5dadff47` | 2026-10-30 12:29Z | Document Oct 30 device test, signing keys, Firebase and Play prep | withheld (only withheld paths) |
| 20 | `0d081e9b` | 2026-10-30 12:32Z | Record verified Play app-signing certificates for both apps | withheld (only withheld paths) |
| 21 | `a011ad89` | 2026-10-30 13:18Z | Plan milestones 2-4: account deletion, release builds, store documents | withheld (only withheld paths) |
| 22 | `d8d69eab` | 2026-10-30 13:20Z | Add account deletion sequence with re-authentication and tests | `8d9edb9` |
| 23 | `28f91af0` | 2026-10-30 14:13Z | Amend milestone 2 plan to match the code: no FCM step, email provider, consent cases | withheld (only withheld paths) |
| 24 | `6ff7750b` | 2026-10-30 14:14Z | Wire account deletion to Firebase and Room; add delete-account view model | `ec47578` |
| 25 | `2f300434` | 2026-10-30 14:19Z | Add Delete account entry and dialogs to Settings | `c54e337` |
| 26 | `26740c2d` | 2026-10-30 14:20Z | Re-authenticate Google users before account deletion | `822c88f` |
| 27 | `c802a7cd` | 2026-10-30 14:21Z | Clear cached settings on account deletion, keeping consent_shown | `0e72db8` |
| 28 | `a6fbb39d` | 2026-10-30 14:22Z | Test consent gate and list consent data sources | `a3849e1` |
| 29 | `86a1e27c` | 2026-10-30 14:22Z | Gate cloud sync on collection consent | `f249108` |
| 30 | `b541be55` | 2026-10-30 14:23Z | Align consent screen profile line with the synced UserProfile | `0895e21` |
| 31 | `da08e41f` | 2026-10-30 14:25Z | Record Vikram plan reviews for milestone 2 | withheld (only withheld paths) |
| 32 | `9f83fe55` | 2026-10-30 14:26Z | Use ConsentManager.needsPrompt() for the launch consent gate | `3d313bc` |
| 33 | `178ca20d` | 2026-10-30 14:40Z | Make privacy_right_change true: no consent toggle exists | `884bb4c` |
| 34 | `719619f2` | 2026-10-30 14:41Z | Stop auto-selecting gemma-4-e2b-it | `ac8ac22` |
| 35 | `c0c8e1f3` | 2026-10-30 14:41Z | Replace the DPDP/GDPR compliance claim on the consent screen | `3bcd168` |
| 36 | `018591b4` | 2026-10-30 16:26Z | Fix Settings > Profile closing immediately | `3250892` |
| 37 | `036d33ad` | 2026-10-30 16:29Z | Show the consent screen before any onward navigation | `5fb1079` |
| 38 | `3fbbb41d` | 2026-10-31 02:00Z | Stop SyncManager.syncPending deleting non-analytics queue rows | `676bdac` |
| 39 | `4ffeb565` | 2026-10-31 02:00Z | Add Llama 3.2 1B Instruct as Maya's first-preference model | `e4c40fb` |
| 40 | `9ae89f0c` | 2026-10-31 02:02Z | WIP: learner sync models, API interface and HttpURLConnection client | `10d016c` |
| 41 | `89d210b0` | 2026-10-31 02:07Z | Add lesson storage and learner sync (A2, A3) | `1506959` |
| 42 | `46d96cfc` | 2026-10-31 02:09Z | Never delete queued events on a whole-request 4xx | `463f1f1` |
| 43 | `7a512f91` | 2026-10-31 02:12Z | Add Lessons screen for Maya (A4) | `3934574` |
| 44 | `8308d683` | 2026-10-31 02:16Z | Apply review fixes to lesson sync | `83d555b` |
| 45 | `9b5f2ffc` | 2026-10-31 02:19Z | Label Lessons honestly: model name, explainer timeouts, profile source | `8d617bd` |
| 46 | `1d4b0611` | 2026-10-31 02:19Z | Fix explainer timeout test for the Asmaa flavor | `1036c1b` |
| 47 | `7339ab97` | 2026-10-31 02:20Z | Clear lessons on account deletion | `6905b54` |
| 48 | `bf61c1f2` | 2026-10-31 02:21Z | Clear queued chat analytics on account deletion | `d77af82` |
| 49 | `ee7f56c1` | 2026-10-31 02:43Z | Show the right message for pending and refused lesson requests | `98c6193` |
| 50 | `82dc1418` | 2026-10-31 03:11Z | Lesson images offline, events-first sync, and bundle-pull fixes | `8edaa97` |
| 51 | `ee93421b` | 2026-10-31 06:36Z | Image delivery fixes: late images, decode size, placeholder, wording | `7455c24` |
| 52 | `06d97b93` | 2026-10-31 06:42Z | Fix offline detection, and polish lesson images | `65d6d4c` |
| 53 | `3a091f88` | 2026-10-31 07:26Z | Store the learner token privately and let config change at runtime | `4f1be8e` |
| 54 | `eda62261` | 2026-10-31 07:34Z | Production sign-in: WhatsApp number option and learner token exchange | `85497cd` |
| 55 | `3bfcf721` | 2026-10-31 07:39Z | Erase the learner on account deletion, and ask before Lessons stores anything | `ebe4f0d` |
| 56 | `bd87e810` | 2026-10-31 07:41Z | Main consent covers Lessons on Maya, a missing learner counts as erased, deletion dialog says what goes | `f9feda8` |
| 57 | `aa410528` | 2026-10-31 07:50Z | Auth review fixes: erase without a token, consent for both buttons, confirm the number, hardening | `42a804f` |
| 58 | `52a76d01` | 2026-10-31 07:52Z | Erase by ID token with a POST, not a DELETE with a body | `40dce81` |
| 59 | `17bd3b7b` | 2026-10-31 07:58Z | Make account deletion all-or-nothing, and stop counting a decline as consent | `4adf996` |
| 60 | `2b700d58` | 2026-10-31 07:59Z | A backend with no Firebase never blocks account deletion | `ac5103a` |
| 61 | `7013108b` | 2026-10-31 08:02Z | Test that auth_unavailable blocks account deletion | `b57b507` |

### (i) Event-authored files

- `android/app/src/asmaa/assets/index.html`
- `android/app/src/main/java/org/ekatra/alfred/HostAccess.kt`
- `android/app/src/main/java/org/ekatra/alfred/LessonsActivity.kt`
- `android/app/src/main/java/org/ekatra/alfred/ProfileFlow.kt`
- `android/app/src/main/java/org/ekatra/alfred/data/ConsentManager.kt`
- `android/app/src/main/java/org/ekatra/alfred/data/account/AccountDeleter.kt`
- `android/app/src/main/java/org/ekatra/alfred/data/account/AccountModule.kt`
- `android/app/src/main/java/org/ekatra/alfred/data/account/FirebaseAccountRemote.kt`
- `android/app/src/main/java/org/ekatra/alfred/lessons/AuthModels.kt`
- `android/app/src/main/java/org/ekatra/alfred/lessons/Connectivity.kt`
- `android/app/src/main/java/org/ekatra/alfred/lessons/FirebaseSession.kt`
- `android/app/src/main/java/org/ekatra/alfred/lessons/Jwt.kt`
- `android/app/src/main/java/org/ekatra/alfred/lessons/LearnerSyncApi.kt`
- `android/app/src/main/java/org/ekatra/alfred/lessons/LearnerSyncClient.kt`
- `android/app/src/main/java/org/ekatra/alfred/lessons/LearnerSyncManager.kt`
- `android/app/src/main/java/org/ekatra/alfred/lessons/LearnerTokenProvider.kt`
- `android/app/src/main/java/org/ekatra/alfred/lessons/LessonGrading.kt`
- `android/app/src/main/java/org/ekatra/alfred/lessons/LessonImage.kt`
- `android/app/src/main/java/org/ekatra/alfred/lessons/LessonModels.kt`
- `android/app/src/main/java/org/ekatra/alfred/lessons/LessonProgressStore.kt`
- `android/app/src/main/java/org/ekatra/alfred/lessons/LessonStore.kt`
- `android/app/src/main/java/org/ekatra/alfred/lessons/LessonsConsent.kt`
- `android/app/src/main/java/org/ekatra/alfred/lessons/LessonsEntry.kt`
- `android/app/src/main/java/org/ekatra/alfred/lessons/LessonsModule.kt`
- `android/app/src/main/java/org/ekatra/alfred/lessons/LessonsScreens.kt`
- `android/app/src/main/java/org/ekatra/alfred/lessons/LessonsSupport.kt`
- `android/app/src/main/java/org/ekatra/alfred/lessons/LessonsViewModel.kt`
- `android/app/src/main/java/org/ekatra/alfred/lessons/LocalLearnerData.kt`
- `android/app/src/main/java/org/ekatra/alfred/lessons/PhoneNumbers.kt`
- `android/app/src/main/java/org/ekatra/alfred/lessons/ServerAccountDeletion.kt`
- `android/app/src/main/java/org/ekatra/alfred/lessons/SizedLru.kt`
- `android/app/src/main/java/org/ekatra/alfred/lessons/SyncResult.kt`
- `android/app/src/main/java/org/ekatra/alfred/lessons/TokenStore.kt`
- `android/app/src/main/java/org/ekatra/alfred/lessons/WhatsAppSignIn.kt`
- `android/app/src/main/java/org/ekatra/alfred/viewmodel/DeleteAccountViewModel.kt`
- `android/app/src/maya/assets/index.html`
- `android/app/src/test/java/org/ekatra/alfred/HostAccessTest.kt`
- `android/app/src/test/java/org/ekatra/alfred/ProfileFlowTest.kt`
- `android/app/src/test/java/org/ekatra/alfred/data/AppSettingsTest.kt`
- `android/app/src/test/java/org/ekatra/alfred/data/ConsentManagerTest.kt`
- `android/app/src/test/java/org/ekatra/alfred/data/SyncWorkerRequestTest.kt`
- `android/app/src/test/java/org/ekatra/alfred/data/account/AccountDeleterLearnerTest.kt`
- `android/app/src/test/java/org/ekatra/alfred/data/account/AccountDeleterTest.kt`
- `android/app/src/test/java/org/ekatra/alfred/data/account/RoomAccountDataTest.kt`
- `android/app/src/test/java/org/ekatra/alfred/lessons/ConnectivityTest.kt`
- `android/app/src/test/java/org/ekatra/alfred/lessons/Fakes.kt`
- `android/app/src/test/java/org/ekatra/alfred/lessons/JwtTest.kt`
- `android/app/src/test/java/org/ekatra/alfred/lessons/LearnerSyncClientTest.kt`
- `android/app/src/test/java/org/ekatra/alfred/lessons/LearnerSyncManagerTest.kt`
- `android/app/src/test/java/org/ekatra/alfred/lessons/LearnerTokenProviderTest.kt`
- `android/app/src/test/java/org/ekatra/alfred/lessons/LessonStoresTest.kt`
- `android/app/src/test/java/org/ekatra/alfred/lessons/LessonsConsentTest.kt`
- `android/app/src/test/java/org/ekatra/alfred/lessons/LessonsViewModelTest.kt`
- `android/app/src/test/java/org/ekatra/alfred/lessons/ReleaseHardeningTest.kt`
- `android/app/src/test/java/org/ekatra/alfred/lessons/ServerAccountDeletionTest.kt`
- `android/app/src/test/java/org/ekatra/alfred/lessons/TokenConfigTest.kt`
- `android/app/src/test/java/org/ekatra/alfred/lessons/WhatsAppSignInTest.kt`
- `android/app/src/test/java/org/ekatra/alfred/tools/MathInterceptorTest.kt`
- `android/app/src/test/java/org/ekatra/alfred/viewmodel/DeleteAccountViewModelTest.kt`
- `android/app/src/test/java/org/ekatra/alfred/viewmodel/ProfileViewModelTest.kt`
- `android/app/src/testAsmaa/java/org/ekatra/alfred/persona/AsmaaPersonaTest.kt`
- `android/app/src/testMaya/java/org/ekatra/alfred/persona/MayaPersonaTest.kt`
- `android/app/src/testRelease/java/org/ekatra/alfred/ReleaseBuildConfigTest.kt`
- `android/firebase/firebase.json`

### (ii) Pre-existing files modified (diffs only)

- `app/build.gradle.kts`
- `app/proguard-rules.pro`
- `app/src/androidTest/java/org/ekatra/alfred/data/DatabaseMigrationTest.kt`
- `app/src/asmaa/java/org/ekatra/alfred/persona/ActivePersona.kt`
- `app/src/asmaa/res/values/strings.xml`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/cpp/llama_jni.cpp`
- `app/src/main/java/org/ekatra/alfred/AlfredServer.kt`
- `app/src/main/java/org/ekatra/alfred/AlfredServerService.kt`
- `app/src/main/java/org/ekatra/alfred/EkatraApp.kt`
- `app/src/main/java/org/ekatra/alfred/InferenceEngine.kt`
- `app/src/main/java/org/ekatra/alfred/LlamaEngine.kt`
- `app/src/main/java/org/ekatra/alfred/ModelDownloadActivity.kt`
- `app/src/main/java/org/ekatra/alfred/NativeChatActivity.kt`
- `app/src/main/java/org/ekatra/alfred/PrivacyConsentActivity.kt`
- `app/src/main/java/org/ekatra/alfred/ProfileActivity.kt`
- `app/src/main/java/org/ekatra/alfred/SettingsActivity.kt`
- `app/src/main/java/org/ekatra/alfred/data/AppSettings.kt`
- `app/src/main/java/org/ekatra/alfred/data/local/EkatraDatabase.kt`
- `app/src/main/java/org/ekatra/alfred/data/local/SyncQueueDao.kt`
- `app/src/main/java/org/ekatra/alfred/data/sync/SyncManager.kt`
- `app/src/main/java/org/ekatra/alfred/data/sync/SyncWorker.kt`
- `app/src/main/java/org/ekatra/alfred/tools/MathInterceptor.kt`
- `app/src/main/java/org/ekatra/alfred/tools/PromptFormatter.kt`
- `app/src/main/java/org/ekatra/alfred/ui/chat/ChatMarkdown.kt`
- `app/src/main/java/org/ekatra/alfred/ui/chat/ChatScreen.kt`
- `app/src/main/java/org/ekatra/alfred/viewmodel/AnalyticsHelper.kt`
- `app/src/main/java/org/ekatra/alfred/viewmodel/ChatViewModel.kt`
- `app/src/main/java/org/ekatra/alfred/viewmodel/ProfileViewModel.kt`
- `app/src/main/java/org/ekatra/alfred/viewmodel/SettingsViewModel.kt`
- `app/src/main/java/org/ekatra/alfred/worker/ModelDownloadWorker.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/maya/java/org/ekatra/alfred/persona/ActivePersona.kt`
- `app/src/test/java/org/ekatra/alfred/data/ModelTierTest.kt`
- `app/src/test/java/org/ekatra/alfred/data/SyncManagerTest.kt`
- `app/src/test/java/org/ekatra/alfred/tools/PromptFormatterTest.kt`
- `app/src/test/java/org/ekatra/alfred/viewmodel/AnalyticsHelperTest.kt`
- `app/src/test/java/org/ekatra/alfred/viewmodel/ChatViewModelTest.kt`
- `app/src/test/java/org/ekatra/alfred/viewmodel/SettingsViewModelTest.kt`
- `build.gradle.kts`
- `firebase/firestore.rules`
- `gradle/wrapper/gradle-wrapper.properties`

## Notes

- All commits are by the same author. No files were authored by anyone else. Third-party code (llama.cpp, Gradle wrapper, libraries) is pre-existing and untouched by event commits; the llama.cpp gitlink was not changed during the event.
- `android/app/src/{maya,asmaa}/assets/index.html` replace one shared pre-existing `app/src/main/assets/index.html` (deleted by the overlay): the new pages are about 97% derived from it (per-flavor host-mode page).
- Some original commit messages say "Muse Spark"; that was the model's working name. The code default is `muse-wonder-1.0` for text and `muse-image-1.0` for images.
- Commits after the reconstruction (dated when made, not by the original table): the overlay script learned to accept the sanitised base snapshots; `META_ONLY` was added as `patches/backend/0050-meta-only.patch` plus 3 new files (`packages/delivery/src/lib/metaOnly.ts`, `apps/backend/src/lib/metaOnly.ts`, `apps/backend/src/lib/__tests__/metaOnly.test.ts`); README and `.env.example` updates. These are post-event work like the rest of the repository.
