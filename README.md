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

## Struktur

- `data/` — DTO, klien API OkHttp, streaming SSE, penyimpanan key, dan caching.
- `ui/` — layar Compose beserta ViewModel-nya.
- `service/RunWatchService.kt` — foreground service yang menjaga stream saat app di background.

Batasan versi dependensi dan aturan kontribusi ada di [AGENTS.md](AGENTS.md).
