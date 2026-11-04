# Setup (full)

This is the full clean-machine setup for the event code. The short version is in the [README](../README.md#run-it-yourself).


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

