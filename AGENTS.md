# AGENTS.md

## About this project

Volare is an Android app (Kotlin + Jetpack Compose) for running and monitoring Cursor Cloud
Agents from a phone. It is a client of the Cloud Agents API v1 at `https://api.cursor.com`.

It is not a code editor. Its scope is: starting agents, watching runs live, sending
follow-ups, cancelling runs, and opening the resulting PR in a browser.

## Commands

- `./gradlew :app:assembleDebug` builds the debug APK.
- `./gradlew :app:testDebugUnitTest` runs the JVM unit tests.
- `./gradlew :app:lintDebug` runs Android Lint.

Requires JDK 17 and the Android SDK. On macOS with Android Studio, the JDK lives at
`/Applications/Android Studio.app/Contents/jbr/Contents/Home`.

## Version constraints that must not be broken

This is the easiest way to break the build. Dependencies are pinned to the newest set that
still builds with `compileSdk 36` and AGP 8.13.1:

- Newer androidx releases (`core-ktx` 1.19+, `lifecycle` 2.11+, Compose `ui` 1.12+) require
  `compileSdk 37` and AGP 9.1+.
- Moving there means replacing AGP, Gradle, and the SDK platform together. Do not do it
  halfway.
- `androidx.compose.material3` no longer pulls in icons transitively, so
  `material-icons-core` is declared explicitly. The BOM freezes it at 1.7.8, and only icons
  from the *core* set exist — do not use an icon that only ships in
  `material-icons-extended`.

To bump a version, edit `gradle/libs.versions.toml` only; never write versions directly in
`app/build.gradle.kts`.

## Conventions

- Kotlin, Jetpack Compose, Material 3. No XML layouts for UI.
- Manual dependency injection through `ServiceLocator`. Do not add Hilt or Koin.
- Networking is OkHttp directly plus kotlinx.serialization. **Do not add Retrofit** — there
  are only about ten endpoints, and OkHttp is already required for SSE.
- Every DTO uses `ignoreUnknownKeys` and optional properties with defaults. The API is still
  public beta, so a new field must never break parsing.
- An unrecognised run status is **not** treated as terminal. See `RunStatus.isTerminal`.
- User-visible text is written in English, as are identifiers, internal error messages, and
  file names.
- Window insets are handled in Compose, not by the system. `MainActivity` calls
  `enableEdgeToEdge()`, so any bar pinned to the bottom needs `navigationBarsPadding()` then
  `imePadding()`, and a screen without a `Scaffold` needs `safeDrawingPadding()`.
- Code comments: default to none. If a comment is necessary, use a single `//` line only.
  Do not add multi-line `/* */` or `/** */` comments. Do not restate what the code already
  says. Do not mass-delete existing comments unless the task asks for a cleanup.
- Git authorship: every `git commit` must set the author to
  `PoisonAifih <97334169+PoisonAifih@users.noreply.github.com>` via `--author` (or equivalent).
  Do not leave the default `Cursor Agent <cursoragent@cursor.com>` as the commit author.
  Do not disable `commit.gpgsign` or change the platform signing key. Do not permanently
  rewrite global `~/.gitconfig`. Hosted Cloud Agent push and PR creation may still use the
  Cursor GitHub App; squash-merge on GitHub keeps the final history under the human account.

## Things you must not do

- Never log the API key, put it in an `Intent` extra, or write it to an ordinary file. It
  travels only through `SecretStore`, which encrypts it with an AES-GCM key in the Android
  Keystore. Each secret has its own alias, so revoking one does not make the others
  unreadable.
- Do not rename `applicationId`, the Kotlin packages, the `SecretStore` file name, or the
  Keystore aliases. The application id decides whether an update installs over the existing
  app, and the file name and aliases decide whether the stored API key can still be read.
  Renaming any of them costs every phone a manual uninstall and a re-entered API key.
- Do not store anything derived from one account outside `volare_cache`. Signing out calls
  `AgentRepository.clearCache()`, which empties that whole file so the next account on the
  same phone does not see the previous one's repository list.
- Do not use Jetpack Security (`EncryptedSharedPreferences`). It is deprecated and was
  dropped deliberately.
- Do not call `GET /v1/repositories` outside `AgentRepository`. That endpoint is limited to
  1 request per minute and 30 per hour, and can take tens of seconds. Always serve from cache
  first.
- Do not remove the `Last-Event-ID` handling in `RunStream`. It is what keeps the transcript
  intact when the phone moves between Wi-Fi and mobile data.
- Do not change how releases are signed. A different key breaks the update path on every
  phone that already has the app, and the only recovery is a manual uninstall.
- Do not hardcode `versionCode`. It comes from `VOLARE_VERSION_CODE`, which CI fills with the
  run number.
- Gradle reads `VOLARE_*` environment variables, but the GitHub secrets holding the signing
  credentials are still named `ONTHEFLY_*`. The "Build signed release APK" step maps one onto
  the other. That mismatch is deliberate: the secrets live in GitHub settings, so renaming the
  `secrets.*` references here would make them resolve to empty strings and fail the build.

## API details that are easy to miss

- An agent may only have one active run. `POST /runs` while another run is going returns
  `409 agent_busy` — treat it as a normal condition, not a crash.
- `GET /v1/agents` returns identity fields only. `repos` and `autoCreatePR` appear only in
  `GET /v1/agents/{id}`.
- `Run.git` is per-agent, not per-run. Every run on the same agent returns the same git
  snapshot.
- The stream can return `410 stream_expired` once the retention window passes. That is not a
  retryable error; read the final status with `GET run`.
- The stream is best effort in general: it can also end on an error event or simply close.
  `AgentDetailViewModel.settle` reconciles against `GET run` whenever that happens, so a
  finished run never shows up as an error. Keep that fallback.
- There are no webhooks for API v1 yet. Notifications are therefore handled by
  `RunWatchService`, a foreground service that holds the SSE connection open when the detail
  screen closes. Once webhooks ship, it can be replaced with FCM.

## Release and self-update flow

Every push to `main` runs `.github/workflows/release.yml`: the APK is signed with a keystore
from secrets, then published to the public repository `PoisonAifih/Volare-ApkRelease`
alongside `latest.json`. On the phone, **Check for updates** reads `latest.json`, compares
`versionCode`, downloads the APK, and installs it through `PackageInstaller`.

The release repository is public, so both requests are anonymous and no token appears
anywhere in the update path:

- `latest.json` is read from `https://raw.githubusercontent.com/{repo}/main/latest.json`.
  That host sits behind a CDN that can serve a stale copy for a few minutes, so a new release
  can take that long to appear. That is expected; do not switch back to the GitHub REST API,
  where anonymous requests are limited to 60 per hour per IP.
- The APK is downloaded straight from `apkUrl` in `latest.json`. OkHttp follows the redirect
  to signed storage normally, which is safe because no auth header exists to leak.
- What keeps this path safe is the APK signature, not the repository being private. A package
  signed with a different key is rejected by the system when installed over the existing one.
- Installation runs without a dialog because the app installs itself, holds
  `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, and uses `USER_ACTION_NOT_REQUIRED`. The system may
  still ask for confirmation, so `InstallResultReceiver` must keep handling
  `STATUS_PENDING_USER_ACTION` and forwarding `Intent.EXTRA_INTENT`.
- The `PendingIntent` for session status **must** be `FLAG_MUTABLE`. Without it the system
  cannot attach the status extras and the install result never arrives.
- Inside the `signingConfigs` block, do not name a local variable `keyAlias` or
  `keyPassword`. Those names resolve to `SigningConfig` properties, so the values come out
  null and the build fails with "missing required property".
- The `latest.json` URL is a constant in `AppUpdater`. If the release repository is renamed,
  change it there and in `RELEASES_REPO` in the workflow.

## Definition of done

`./gradlew :app:testDebugUnitTest :app:assembleDebug` must pass before you report that you
are finished.

## Cursor Cloud specific instructions

This section applies when you run as a Cloud Agent, usually triggered from a phone with a
short prompt and little context.

- `.cursor/install.sh` already sets up the Android SDK and runs the build. If `sdkmanager` is
  missing, run that script first.
- Run the build and tests yourself before reporting. The reviewer is on a small screen and
  cannot quickly run them by hand.
- Stay on your own `cursor/...` branch. Never push directly to `main`.
- Make small, focused changes. A long diff cannot be reviewed properly from a phone.
- Write a PR description that can be judged without opening an editor: what changed, why, and
  evidence that the build and tests pass.
- If the prompt is ambiguous, take the simplest interpretation, do the work, then state your
  assumptions in the PR description.
- When committing, always pass
  `--author="PoisonAifih <97334169+PoisonAifih@users.noreply.github.com>"`.
  Prefer no new comments; if one is required, keep it to a single line.
