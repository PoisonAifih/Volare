# OnTheFly

Aplikasi Android untuk menjalankan dan memantau [Cursor Cloud Agents](https://cursor.com/docs/cloud-agent)
dari HP. Dibuat karena Cursor belum punya aplikasi Android native — jalur resmi di Android
hanyalah PWA `cursor.com/agents`, yang notifikasi background-nya tidak andal.

Ini client tipis di atas [Cloud Agents API v1](https://cursor.com/docs/cloud-agent/api/endpoints),
bukan editor kode.

## Yang bisa dilakukan

- Menyimpan API key Cursor, terenkripsi dengan kunci AES-GCM di Android Keystore.
- Memulai agent: pilih repo, branch awal, model, mode `agent` atau `plan`, dan apakah PR
  dibuat otomatis.
- Memantau run secara live lewat Server-Sent Events, termasuk aktivitas tool call.
- Mengirim follow-up ke agent yang sedang berjalan, dan membatalkan run.
- Membuka pull request hasilnya di browser.
- Notifikasi saat run berakhir, bahkan setelah kamu menutup layar detail.
- Memperbarui dirinya sendiri dari HP lewat menu **Cek update**, tanpa kabel dan tanpa
  Android Studio.

## Yang belum ada

- Tidak ada review diff di dalam app. Diff dan merge dilakukan di GitHub lewat tautan PR.
- Tidak ada lampiran gambar, meski API-nya mendukung `prompt.images`.
- Tidak ada push notification sungguhan. Cloud Agents API v1 belum punya webhook, jadi
  notifikasi ditopang foreground service yang menjaga koneksi SSE. Konsekuensinya, Android
  bisa menghentikan pemantauan kalau sistem sedang agresif menghemat baterai.

## Menjalankan

Butuh JDK 17 dan Android SDK dengan platform `android-36`.

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

APK debug muncul di `app/build/outputs/apk/debug/`. Pasang ke HP lewat kabel:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Di macOS dengan Android Studio, JDK-nya perlu ditunjuk manual kalau `java` tidak ada di
`PATH`:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

## Menyiapkan API key

1. Buka [Cursor Dashboard → API Keys](https://cursor.com/dashboard/api) dan buat user API key.
2. Tempel key itu di layar pertama aplikasi. Aplikasi memverifikasinya lewat `GET /v1/me`.

Key ini memberi akses penuh ke cloud agent akunmu. Key tidak pernah keluar dari perangkat,
tapi kalau HP hilang, cabut key-nya dari dashboard.

Cloud Agents juga mensyaratkan plan berbayar dan source control yang sudah terhubung di
[dashboard Integrations](https://cursor.com/dashboard/integrations).

## Update dari HP

Aplikasi bisa mengganti dirinya sendiri tanpa dialog konfirmasi. Android mengizinkannya
karena pemasangnya adalah aplikasi itu sendiri; syaratnya izin
`UPDATE_PACKAGES_WITHOUT_USER_ACTION`, sesi `PackageInstaller` dengan
`USER_ACTION_NOT_REQUIRED`, dan `targetSdk` yang cukup tinggi.

Alurnya: setiap push ke `main` memicu workflow `release.yml`, yang membangun APK
bertanda tangan dan menerbitkannya ke repo private `OnTheFly-Release` beserta
`latest.json`. Di HP, menu **Cek update** membaca `latest.json`, membandingkan
`versionCode`, lalu mengunduh dan memasang.

Karena repo release private, aplikasi memakai REST API GitHub dengan token read-only yang
kamu tempel sekali di layar Update. `raw.githubusercontent.com` tidak dipakai karena tidak
menerima autentikasi token. Unduhan asset dijawab dengan redirect ke penyimpanan
ber-signature, dan header `Authorization` **tidak boleh** ikut ke sana, jadi redirect-nya
diikuti manual di `AppUpdater`.

Empat hal yang perlu diingat:

- **Instalasi pertama tetap manual.** Silent install hanya berlaku untuk update.
- **Kuncinya tidak boleh berubah.** Update hanya bisa memasang di atas versi lama bila
  ditandatangani kunci yang sama. Keystore ada di `keystore/` dan tidak masuk git; kalau
  hilang, HP harus uninstall lalu install ulang dari nol.
- **Tanda tangan itu pengamannya, bukan privasi repo.** Karena update dipasang tanpa dialog,
  yang mencegah APK asing terpasang adalah pemeriksaan tanda tangan Android.
- **Silent tidak dijamin.** Sebagian ROM tetap memunculkan dialog, jadi aplikasi menangani
  `STATUS_PENDING_USER_ACTION` dan meneruskan dialognya.

Setup sekali di GitHub, pada repo ini:

| Secret | Isi |
| --- | --- |
| `KEYSTORE_BASE64` | isi `keystore/release.jks.base64` |
| `KEYSTORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS` | dari `keystore/keystore.properties` |
| `RELEASES_TOKEN` | PAT dengan izin `Contents: Read and write` di repo `OnTheFly-Release` |

Repo `OnTheFly-Release` perlu sudah punya commit awal. Untuk HP, buat token terpisah yang
hanya punya `Contents: Read` di repo itu, supaya token yang tersimpan di perangkat tidak
bisa menulis apa pun.

## Struktur

- `data/` — DTO, klien API OkHttp, streaming SSE, penyimpanan rahasia terenkripsi, dan caching.
- `ui/` — layar Compose beserta ViewModel-nya.
- `service/RunWatchService.kt` — foreground service yang menjaga stream saat app di background.
- `update/` — pembacaan `latest.json` dan pemasangan APK lewat `PackageInstaller`.

Batasan versi dependensi dan aturan kontribusi ada di [AGENTS.md](AGENTS.md).
