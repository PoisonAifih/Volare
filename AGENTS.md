# AGENTS.md

## Tentang project ini

OnTheFly adalah aplikasi Android (Kotlin + Jetpack Compose) untuk menjalankan dan
memantau Cursor Cloud Agents dari HP. Aplikasi ini adalah client dari Cloud Agents API v1
di `https://api.cursor.com`.

Aplikasi ini bukan editor kode. Lingkupnya: memulai agent, memantau run secara live,
mengirim follow-up, membatalkan run, dan membuka PR hasilnya di browser.

## Perintah

- `./gradlew :app:assembleDebug` membangun APK debug.
- `./gradlew :app:testDebugUnitTest` menjalankan unit test JVM.
- `./gradlew :app:lintDebug` menjalankan Android Lint.

Butuh JDK 17 dan Android SDK. Di macOS dengan Android Studio, JDK-nya ada di
`/Applications/Android Studio.app/Contents/jbr/Contents/Home`.

## Batasan versi yang tidak boleh dilanggar

Ini bagian paling mudah merusak build. Dependensi dikunci ke himpunan terakhir yang masih
bisa dibangun dengan `compileSdk 36` dan AGP 8.13.1:

- Rilis androidx yang lebih baru (`core-ktx` 1.19+, `lifecycle` 2.11+, Compose `ui` 1.12+)
  menuntut `compileSdk 37` dan AGP 9.1+.
- Naik ke sana berarti mengganti AGP, Gradle, dan platform SDK sekaligus. Jangan lakukan
  sebagian saja.
- `androidx.compose.material3` tidak lagi membawa ikon secara transitif, jadi
  `material-icons-core` disertakan eksplisit. Versinya dibekukan di 1.7.8 oleh BOM, dan
  hanya ikon dari set *core* yang tersedia — jangan pakai ikon yang cuma ada di
  `material-icons-extended`.

Kalau perlu menaikkan versi, ubah `gradle/libs.versions.toml` saja, jangan menulis versi
langsung di `app/build.gradle.kts`.

## Konvensi

- Kotlin, Jetpack Compose, Material 3. Tidak ada layout XML untuk UI.
- Dependency injection manual lewat `ServiceLocator`. Jangan menambahkan Hilt atau Koin.
- Jaringan memakai OkHttp langsung plus kotlinx.serialization. **Jangan menambahkan
  Retrofit** — hanya ada sekitar sepuluh endpoint dan OkHttp sudah dibutuhkan untuk SSE.
- Semua DTO memakai `ignoreUnknownKeys` dan properti opsional bernilai default. API-nya
  masih public beta, jadi field baru tidak boleh membuat parsing gagal.
- Status run yang tidak dikenal **tidak** dianggap terminal. Lihat `RunStatus.isTerminal`.
- Teks yang dilihat pengguna ditulis dalam bahasa Indonesia. Identifier, pesan error
  internal, dan nama file dalam bahasa Inggris.

## Yang tidak boleh dilakukan

- Jangan pernah mencatat API key atau token GitHub ke log, menaruhnya di `Intent` extra, atau
 menuliskannya ke berkas biasa. Keduanya hanya lewat `SecretStore`, yang mengenkripsinya
 dengan kunci AES-GCM di Android Keystore. Tiap rahasia punya alias sendiri, supaya mencabut
 satu tidak membuat yang lain tidak terbaca.
- Jangan memakai Jetpack Security (`EncryptedSharedPreferences`). Library itu deprecated
  dan sudah sengaja dilepas.
- Jangan memanggil `GET /v1/repositories` di luar `AgentRepository`. Endpoint itu dibatasi
  1 permintaan per menit dan 30 per jam, dan bisa perlu puluhan detik. Selalu sajikan dari
  cache lebih dulu.
- Jangan menghapus penanganan `Last-Event-ID` di `RunStream`. Itu yang membuat transkrip
  tidak hilang saat HP berpindah antara Wi-Fi dan data seluler.
- Jangan mengubah `applicationId` atau cara release ditandatangani. Kedua hal itu memutus
  jalur update di HP yang sudah memasang aplikasi, dan satu-satunya pemulihannya adalah
  uninstall manual.
- Jangan menulis `versionCode` sebagai angka tetap. Nilainya datang dari
  `ONTHEFLY_VERSION_CODE`, yang diisi CI dengan nomor run.

## Catatan API yang mudah terlewat

- Satu agent hanya boleh punya satu run aktif. `POST /runs` saat run lain berjalan
  mengembalikan `409 agent_busy` — tangani sebagai kondisi normal, bukan crash.
- `GET /v1/agents` hanya mengembalikan field identitas. `repos` dan `autoCreatePR` baru ada
  di `GET /v1/agents/{id}`.
- `Run.git` bersifat per-agent, bukan per-run. Semua run di agent yang sama mengembalikan
  snapshot git yang sama.
- Stream bisa mengembalikan `410 stream_expired` setelah retention window lewat. Itu bukan
  error yang bisa diulang; baca status akhir lewat `GET run`.
- Webhook untuk API v1 belum ada. Karena itu notifikasi dikerjakan oleh
  `RunWatchService`, sebuah foreground service yang menjaga koneksi SSE saat layar detail
  ditutup. Kalau webhook sudah rilis, service ini bisa diganti FCM.

## Alur release dan update mandiri

Setiap push ke `main` menjalankan `.github/workflows/release.yml`: APK ditandatangani
dengan keystore dari secret, lalu diterbitkan ke repo private `PoisonAifih/OnTheFly-ApkRelease`
bersama `latest.json`. Di HP, menu **Cek update** membaca `latest.json`, membandingkan
`versionCode`, mengunduh APK, dan memasangnya lewat `PackageInstaller`.

Karena repo release private, semua permintaan lewat REST API GitHub dengan token read-only
yang disimpan di `ServiceLocator.updateTokenStore`:

- `latest.json` dibaca lewat `GET /repos/{repo}/contents/latest.json` dengan
 `Accept: application/vnd.github.raw`. Jangan kembali ke `raw.githubusercontent.com`, karena
 host itu tidak menerima autentikasi token.
- APK diunduh lewat `GET /repos/{repo}/releases/assets/{id}` dengan
 `Accept: application/octet-stream`. Endpoint itu menjawab 302 ke penyimpanan
 ber-signature, dan **header `Authorization` tidak boleh ikut** ke tujuan redirect, kalau
 tidak akan ditolak dengan "only one auth mechanism allowed". Karena itu `AppUpdater`
 memakai client `followRedirects(false)` dan menyusun ulang permintaan tanpa header auth.
- `assetId` diisi CI ke `latest.json`, jadi aplikasi cukup dua permintaan. Untuk repo
 private, `404` bisa berarti file tidak ada **atau** token tidak punya akses.

- Instalasi berjalan tanpa dialog karena aplikasi memasang dirinya sendiri, memegang
  `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, dan memakai `USER_ACTION_NOT_REQUIRED`. Sistem
  masih boleh meminta konfirmasi, jadi `InstallResultReceiver` wajib tetap menangani
  `STATUS_PENDING_USER_ACTION` dan meneruskan `Intent.EXTRA_INTENT`.
- `PendingIntent` untuk status sesi **harus** `FLAG_MUTABLE`. Tanpa itu sistem tidak bisa
  menyisipkan extra status dan hasil instalasi tidak pernah sampai.
- Di dalam blok `signingConfigs`, jangan menamai variabel lokal `keyAlias` atau
  `keyPassword`. Nama itu diselesaikan ke properti `SigningConfig` sehingga nilainya jadi
  null dan build gagal dengan pesan "missing required property".
- URL `latest.json` ada sebagai konstanta di `AppUpdater`. Kalau repo release diganti nama,
  ubah di situ dan di `RELEASES_REPO` pada workflow.

## Definisi selesai

`./gradlew :app:testDebugUnitTest :app:assembleDebug` harus lulus sebelum kamu melapor
selesai.

## Instruksi khusus Cursor Cloud

Bagian ini berlaku saat kamu berjalan sebagai Cloud Agent, biasanya dipicu dari HP dengan
prompt singkat dan tanpa banyak konteks.

- `.cursor/install.sh` sudah menyiapkan Android SDK dan menjalankan build. Kalau
  `sdkmanager` tidak ada, jalankan script itu lebih dulu.
- Jalankan build dan test sendiri sebelum melapor. Reviewer ada di layar kecil dan tidak
  bisa dengan cepat menjalankannya manual.
- Tetap di branch `cursor/...` milikmu. Jangan pernah push langsung ke `main`.
- Buat perubahan yang kecil dan fokus. Diff panjang tidak bisa direview dengan layak dari
  HP.
- Tulis deskripsi PR yang bisa dinilai tanpa membuka editor: apa yang berubah, mengapa, dan
  bukti build serta test lulus.
- Kalau promptnya ambigu, pilih interpretasi paling sederhana, kerjakan, lalu sebutkan
  asumsimu di deskripsi PR.
