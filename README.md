# Volare

An Android app for running and monitoring [Cursor Cloud Agents](https://cursor.com/docs/cloud-agent)
from a phone. It exists because Cursor has no native Android app — the official route on
Android is the `cursor.com/agents` PWA, whose background notifications are unreliable.

This is a thin client over the [Cloud Agents API v1](https://cursor.com/docs/cloud-agent/api/endpoints),
not a code editor.

## What it does

- Stores your Cursor API key, encrypted with an AES-GCM key in the Android Keystore.
- Starts agents: pick the repository, starting branch, model, `agent` or `plan` mode, and
  whether a PR is created automatically.
- Follows runs live over Server-Sent Events, including tool call activity.
- Sends follow-ups to a running agent, and cancels runs.
- Opens the resulting pull request in a browser.
- Notifies you when a run ends, even after you close the detail screen.
- Updates itself from the phone through **Check for updates**, with no cable and no Android
  Studio.

## What it does not do

- No diff review in the app. Diffs and merges happen on GitHub through the PR link.
- No image attachments, even though the API supports `prompt.images`.
- No real push notifications. Cloud Agents API v1 has no webhooks, so notifications rely on a
  foreground service holding the SSE connection open. Android may stop that monitoring when
  the system is aggressive about saving battery.

## Building

Requires JDK 17 and the Android SDK with platform `android-36`.

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

The debug APK lands in `app/build/outputs/apk/debug/`. Install it over a cable:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On macOS with Android Studio, point at its JDK if `java` is not on your `PATH`:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

## Setting up the API key

1. Open [Cursor Dashboard → API Keys](https://cursor.com/dashboard/api) and create a user API key.
2. Paste it into the app's first screen. The app verifies it with `GET /v1/me`.

The key grants full access to your account's cloud agents. It never leaves the device, but if
you lose the phone, revoke the key from the dashboard.

Cloud Agents also require a paid plan and source control connected in the
[Integrations dashboard](https://cursor.com/dashboard/integrations).

## Updating from the phone

The app can replace itself with no confirmation dialog. Android allows this because the
installer is the app itself; it requires the `UPDATE_PACKAGES_WITHOUT_USER_ACTION` permission,
a `PackageInstaller` session with `USER_ACTION_NOT_REQUIRED`, and a high enough `targetSdk`.

The flow: every push to `main` triggers the `release.yml` workflow, which builds a signed APK
and publishes it to the public `Volare-ApkRelease` repository alongside `latest.json`. On
the phone, **Check for updates** reads `latest.json`, compares `versionCode`, then downloads
and installs.

Because that repository is public, both requests are anonymous. `latest.json` comes from
`raw.githubusercontent.com` and the APK from the release download URL in the manifest, so no
token is involved anywhere in the update path.

Four things to keep in mind:

- **The first install is still manual.** Silent install only applies to updates.
- **The signing key must not change.** An update can only install over an older version if it
  is signed with the same key. The keystore lives in `keystore/`, which is not in git; if it
  is lost, phones have to uninstall and start over.
- **The signature is the safeguard, not repository privacy.** Since updates install without a
  dialog, what stops a foreign APK is Android's signature check.
- **Silent is not guaranteed.** Some ROMs still show a dialog, so the app handles
  `STATUS_PENDING_USER_ACTION` and forwards it.

One-time setup on this repository:

| Secret | Contents |
| --- | --- |
| `KEYSTORE_BASE64` | contents of `keystore/release.jks.base64` |
| `ONTHEFLY_KEYSTORE_PASSWORD`, `ONTHEFLY_KEY_PASSWORD`, `ONTHEFLY_KEY_ALIAS` | from `keystore/keystore.properties` |
| `RELEASES_TOKEN` | PAT with `Contents: Read and write` on `Volare-ApkRelease` |

`Volare-ApkRelease` needs an initial commit before the first release. The phone needs no
token of its own.

## Structure

- `data/` — DTOs, the OkHttp API client, SSE streaming, encrypted secret storage, and caching.
- `ui/` — Compose screens and their ViewModels.
- `service/RunWatchService.kt` — foreground service that keeps the stream alive in the background.
- `update/` — reading `latest.json` and installing the APK through `PackageInstaller`.

Dependency version constraints and contribution rules live in [AGENTS.md](AGENTS.md).
