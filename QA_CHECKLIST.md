# Blink v3 — status QA (2026-11-10)

## Selesai diverifikasi lokal

- [x] Proyek memiliki `MainActivity` Java dengan Android `WebView` dan listener unduhan.
- [x] Proyek menyediakan `PrivateActivity` terpisah dengan data directory suffix.
- [x] Source terdapat implementasi untuk HTTP(S) via DownloadManager dan blob/data via MediaStore.
- [x] File Java diparse oleh compiler JDK 17 tanpa error sintaks.
- [x] `UrlResolverTest` lulus via `javac/java -ea`.
- [x] `SafeFilenameTest` lulus via `javac/java -ea`.
- [x] XML manifest/resources dapat diparse.
- [x] Arsip source ZIP valid dan lokasi root sesuai workflow.

## Perlu Android SDK + tes perangkat sungguhan

- [ ] `gradle assembleDebug` lulus dan APK dapat diinstal.
- [ ] Browser tampil benar dan dapat mencari/buka situs nyata dengan mode kompatibilitas on/off.
- [ ] WebView Chrome engine provider terdeteksi di **Tentang Blink**.
- [ ] Tab hingga 40, state restoration, background/foreground, low-memory.
- [ ] Private process terpisah; cookie dari jendela normal tidak muncul di Samaran.
- [ ] Setelah menutup/membuka Samaran, cookie/storage privat sesi sebelumnya bersih.
- [ ] Unduh HTTPS biasa, auth cookie, file berukuran besar dan progress sistem.
- [ ] Unduh blob/data lokal dari `qa/browser-test.html` yang di-host dengan HTTPS.
- [ ] `blob:` di iframe lintas-origin dan file melebihi 128 MiB ditangani dengan pesan yang jelas.
- [ ] Download Samaran tetap terlihat di storage publik; pemberitahuan privasi jelas.
- [ ] MediaStore Downloads pada Android 10–16, ruang penyimpanan habis, file duplikat.
- [ ] Upload file, membuka target blank, menu tiga titik, fullscreen video.
- [ ] Tes stabilitas pada Android 10–16 dan berbagai perangkat/vendor.

**Status**: source v3 diperbarui dan validasi statis lulus, **belum APK terkompilasi**. Jangan klaim full Chrome parity atau lolos seluruh tes perangkat.
