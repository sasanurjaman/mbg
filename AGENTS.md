# AGENTS.md — Proyek MBG

> Instruksi project-level untuk agent yang mengerjakan repo ini, mengikuti
> struktur AGENTS.global.md milik Syamsul. Pemilik repo: sasanurjaman;
> Syamsul = collaborator (akses Write). Bahasa komunikasi & commit: Indonesia.

## Prinsip Umum
- Aplikasi penjualan/tagihan supplier dapur MBG (asalnya "Putri Karya Tani"): tagihan ke dapur/pelanggan, nota, kirim WhatsApp, belanja bersama, rekap keuntungan. Satu pengguna, satu perangkat, **offline penuh**.
- Stack TIDAK diganti: inti aplikasi satu file `MBG-Android-Studio/app/src/main/assets/index.html` (HTML + CSS + vanilla JS, tanpa framework/backend) dibungkus WebView Kotlin (`MainActivity.kt`) — bridge native hanya `printReceipt()` dan `saveBackup()` (Storage Access Framework).
- Versi kunci: AGP 8.7.3, Kotlin 2.0.21, Gradle 8.9 (tanpa wrapper, disediakan CI), compileSdk/targetSdk 35, minSdk 26, **JDK 17 wajib**. Target perangkat: Samsung Galaxy Tab S10 Lite (Android 15); UI juga harus layak di HP.
- Satu-satunya penyimpanan = localStorage, 5 key: `pkt_invoices`, `pkt_purchase_batches`, `pkt_app_name`, `mbg_customers`, `mbg_products`. Backup JSON `{app:'MBG', formatVersion:1, createdAt, data:{...}}` — jangan ubah format tanpa menaikkan formatVersion dan menjaga impor file lama.
- Kerja hanya di repo ini. Perubahan kecil & terarah: JANGAN refactor besar index.html (memecah file / mengganti pola override) tanpa persetujuan eksplisit — risikonya tinggi, test otomatis terbatas.
- **Rantai override index.html (aturan terpenting):** blok `<script>` kedua berisi fungsi dasar (jalan saat parsing); blok pertama adalah handler `DOMContentLoaded` yang berjalan PALING AKHIR dan menimpa fungsi via `window.*` — versi handler itulah yang EFEKTIF (addLine, getLines, calcTotals, saveInvoice, renderDashboard, renderReceipt, shareWhatsApp, showPage). Sebelum mengubah fungsi, cek definisi terakhir yang menang; mengubah definisi yang kalah = tidak ada efek.
- **JVM target Java & Kotlin harus sama-sama 17** di `app/build.gradle` (`compileOptions` VERSION_17 + `kotlinOptions jvmTarget '17'`); tanpa ini build gagal di `:app:compileDebugKotlin` (javac default AGP 1.8 vs Kotlin 17).
- **Workflow build hanya di `.github/workflows/android-build.yml` (root repo)** — GitHub tidak membaca workflow di subfolder. Jangan pakai `android-actions/setup-android@v3` (gagal di image runner terbaru); pakai SDK bawaan runner + `sdkmanager` manual. Step Gradle memakai `working-directory: MBG-Android-Studio`; path artifact upload dihitung dari root.

## Alur Kerja Fitur/Perubahan Baru
1. Diskusi fitur/perubahan + aturan iteratif dengan user.
2. Tanya: ada referensi tampilan/perilaku yang mau ditiru/disesuaikan?
3. Cek definisi fungsi EFEKTIF dulu (rantai override) sebelum menyentuh kode.
4. Perubahan UI: kerjakan 1 halaman/pola dulu, preview ke user, baru lanjut ke halaman lain.
5. Jalankan smoke test (lihat Keamanan & Kualitas) sebelum patch dianggap selesai.
6. Setelah final: update file ini bila ada keputusan/aturan baru + catat di Riwayat Stack & Keputusan.

## Fase Standar
Analisa → Perbaikan → Testing (smoke jsdom + syntax) → Build (GitHub Actions) → Uji Perangkat (tablet/HP) → Maintenance

## Dokumentasi (tidak duplikat)
- File ini: stack, aturan, peta fungsi, isu diketahui, standar build/test.
- README.md (di `MBG-Android-Studio/`): cara membuka & membangun APK untuk manusia.
- Peta fungsi index.html (versi efektif): navigasi `showPage`/`toggleNav`; tagihan `addLine`/`getLines`/`calcTotals`/`saveInvoice`/`persist`; tampilan `renderDashboard`/`renderHistory`/`renderReceipt`/`renderCustomers`/`renderProducts`/`statusTag`/`fmtDate`/`esc`; belanja `saveBatches`/`renderBatches`/`refreshBatchOptions`/`recalcBatchProfit` (biaya tambahan dibagi proporsional menurut modal, fallback bagi rata bila modal 0); rekap `renderProfitRecap`/`resetProfitPeriod`; lain-lain `nextNumber`, `shareWhatsApp`, handler backup `mbgExport`/`mbgImportFile` (versi extended 5 key yang menang).
- Isu diketahui (jangan diam-diam diubah, perlu keputusan pemilik): (1) nomor tagihan melompat +613 di `nextNumber` (INV00613 → INV01226; unik, tidak tabrakan, tapi tidak +1 — mengubahnya memengaruhi data tersimpan di perangkat pengguna); (2) halaman Data Barang nonaktif — script menghapus menu + `#productsPage` dari DOM, barang diketik manual per baris; (3) tidak ada edit/hapus tagihan; (4) wrapper `shareWhatsApp` menyisakan string "RINCIAN LABA INTERNAL" sebagai kode mati (tidak terkirim — bersihkan saat refactor); (5) cetak rekap dari browser desktop rusak oleh `@media print`, di tablet aman via bridge; (6) Kotlin memakai API deprecated (`onActivityResult`, `onBackPressed`) — berfungsi, bukan prioritas.

## Recovery Sesi Terhenti
1. Cek `git status`/`git diff` + `git fetch` untuk menyamakan dengan remote + kondisi nyata kode.
2. Bandingkan dengan file ini (aturan + isu diketahui).
3. Jangan tulis kode baru sebelum langkah 1–2 selesai.
4. Beri tahu user eksplisit jika ada kode setengah jadi berisiko.
5. Bila build Actions gagal: minta teks log step yang gagal dari user (log Actions butuh login; dari luar hanya terlihat step gagal + exit code) — diagnosis dari teks error persisnya, jangan menebak.

## Git Commit
- Commit langsung di `main` (proyek kecil, disepakati), prefix konvensional (`feat:`, `fix:`, `docs:`, `refactor:`) + pesan bahasa Indonesia. Fix kecil boleh gabung ke commit fitur terkait; fix signifikan berdiri sendiri dipisah.
- Commit dan push ke remote (GitHub) tidak boleh otomatis — wajib izin user dulu. Eksekusi push dari laptop Syamsul (SSH key di sana); agent menyiapkan patch + blok perintah.
- Patch disimpan di `~/Downloads/Patch/` di laptop (bukan di dalam repo), dihapus setelah `git apply` dan sebelum `git add` agar tidak ikut ter-commit. Basis patch WAJIB commit remote terakhir + verifikasi `git apply --check` di pohon bersih.

## Seed Data
- Tidak ada akun/login di aplikasi ini (aturan password `dev123` dari AGENTS.global TIDAK berlaku di sini).
- Seed bawaan di index.html saling terhubung untuk debugging: 2 barang (APEL PUJI, JERUK — ada harga beli & jual) + 6 dapur/pelanggan (MN1, NH1, NM BAYONGBONG, CISURUPAN 1, CISURUPAN 2 — tertulis "CISUURUPAN 2", CISURUPAN 3).
- Data uji jangan pernah dicampur ke data produksi perangkat pengguna; uji logika di jsdom/browser, bukan di tablet pengguna.

## Standar UI/UX
- Identitas visual dipertahankan: primer `#176b4d`, sekunder `#e7f3ec`, font Inter/system-ui, panel radius 16px, gaya terang + dark-mode via `light-dark()`. Jangan ganti tema tanpa persetujuan.
- Wajib: spacing/typography konsisten, target sentuh ≥44px pada pointer coarse, keadaan kosong (empty state) jelas di tiap tabel.
- Design token & breakpoint dicatat di file ini, dipakai konsisten: kartu `repeat(auto-fit, minmax(min(230px,100%),1fr))`; breakpoint 1100 (sidebar menyempit 205px), 1024 (grid 2 kolom → 1), 900 (sidebar overlay fixed + tombol menu; auto-tutup setelah menu dipilih / tap di luar sidebar; style inline sisa dari mode mobile dibersihkan otomatis ≥901px), 700 (font input 16px anti-zoom, tabel min-width 540px + scroll horizontal, form 1 kolom), 640 (baris barang 2 kolom dengan nama sebaris penuh, toast full-width, nota scroll horizontal, teks rekening panjang membungkus), 560 (heading menumpuk, tombol full-width).
- **Dilarang memberi inline style layout dari JS** (mis. `gridTemplateColumns`) — inline style mengalahkan media query; atur layout di CSS. (Ini akar masalah baris barang sempit di HP, diperbaiki 2026-11-11.)
- **Media query max-width yang lebih lebar di blok CSS yang lebih akhir mengalahkan breakpoint sempit**: aturan `.shell` 205px di `@media(max-width:1100px)` (blok responsif tambahan) sempat mengalahkan `1fr` bawaan di `max-width:900px` sampai konten HP portrait menyempit ke kolom 205px — aturan sempit wajib ditegaskan ulang di dalam blok max-width:900px itu sendiri (diperbaiki 2026-11-11).
- Self-check sebelum dianggap selesai: cek tampilan pada lebar 360 (HP), 768 (tablet portrait), 1280 (tablet landscape); cek mode gelap; cek kontras teks di atas warna primer.

## Keamanan & Kualitas Pre-Deployment
- **Repo ini PUBLIK**: jangan memasukkan data pribadi (nomor rekening, HP, email, alamat asli) ke kode. Nomor rekening yang dulu hardcoded di template nota sudah DIHAPUS dari nota & kode 2026-11-11 (jejaknya tetap ada di riwayat git lama — itu di luar kendali patch). Info usaha di nota kini datang dari Pengaturan (nama, alamat, HP, email — localStorage `pkt_app_name` + `pkt_business_*`, ikut backup 8 key), bukan hardcoded.
- **Pesan WhatsApp ke pelanggan TIDAK BOLEH memuat data internal** (modal, laba, alokasi biaya). Pernah bocor karena rantai override dan diperbaiki 2026-11-11 — smoke test wajib menangkap URL `window.open` dari `shareWhatsApp` dan memastikan pesan bersih sebelum rilis.
- Test dasar wajib sebelum push perubahan index.html — smoke test jsdom atas alur inti: muat aplikasi → buat tagihan (cek subtotal/modal/laba/sisa) → simpan (cek nomor & localStorage) → belanja + tagihan terkait (cek alokasi & laba bersih) → render rekap (cek jumlah baris) → cek pesan WhatsApp. Terakhir dijalankan 2026-11-11: **47/47 lulus**. Ditambah: `node --check` semua blok script + kurung CSS seimbang.
- Build lulus ≠ fungsi jalan — build hanya membuktikan kompilasi Kotlin + kemasan APK. Fungsi native (cetak, backup Drive, WhatsApp) hanya terbukti lewat uji manual di perangkat.
- Build & rilis: push ke `main` memicu Actions otomatis; bisa juga Run workflow manual. Artifact `MBG-debug-APK` (ZIP ±876 KB, isi `app-debug.apk`, retensi 14 hari, unduh wajib login) = **debug build** untuk sideload pribadi, BUKAN Play Store (belum ada signing config release). Dependensi Gradle di-cache setup-gradle; paket SDK diunduh ulang tiap run di server GitHub (bukan kuota user).

## Interaksi dengan User
- Saat perlu klarifikasi: gunakan opsi interaktif/pilihan singkat (checklist/list bernomor), bukan pertanyaan terbuka.
- Keputusan kecil-menengah (detail implementasi, bukan arsitektur/stack) diputuskan sendiri oleh agent dan dicatat di file ini — tidak perlu minta konfirmasi di tengah sesi. Keputusan besar (stack, arsitektur, hal yang sulit dibalik — termasuk mengubah nomor tagihan & mengaktifkan Data Barang) tetap wajib tanya user dulu.

## Riwayat Stack & Keputusan
- Proyek bawaan (dari pemilik repo, dipertahankan): aplikasi satu file HTML vanilla + WebView Kotlin; data localStorage; target tablet Android 15.
- 10 Nov 2026: workflow build diperbaiki — SDK bawaan runner menggantikan `android-actions/setup-android@v3`; `working-directory` ke subfolder; path artifact dikoreksi. Build APK pertama sukses.
- 10 Nov 2026: JVM target disamakan ke 17 di `app/build.gradle` (fix gagal `:app:compileDebugKotlin`).
- 11 Nov 2026: UI responsif HP/tablet (blok breakpoint di atas) + baris barang lepas dari inline style + auto-tutup sidebar; bocor modal/laba di pesan WhatsApp diperbaiki; nota dipasangi gambar: kop memakai logo warna PD. LS Putra hasil restorasi AI dari foto buku nota (`logo-warna.png`), bagian Hormat kami memakai tanda tangan (`ttd.png`) + stempel ungu (`logo-stempel.png`) di bawah nama — stempel & ttd diekstrak dari foto pemilik, latar transparan; standar smoke test jsdom ditetapkan (29/29).
- 11 Nov 2026 (lanjutan, versi 1.2.0): bug portrait HP diperbaiki (akar masalah di pelajaran media query di atas); Pengaturan usaha jadi 4 field (nama/alamat/HP/email) dan kop nota membacanya; nota dibersihkan — blok Cara Pembayaran + rekening dihapus, field metode bayar di form tagihan dihapus, kolom kiri kaki nota jadi "Tanda terima" + nama pelanggan, Hormat kami memakai nama usaha dengan stempel menimpa ttd (absolute, miring -8°); kop nota abu `#374151`, header tabel abu `#4b5563`; nama bawaan diganti "PD. LS Putra"; kirim WhatsApp kini GAMBAR nota (render SVG foreignObject → PNG → bridge `AndroidBridge.shareImage` + FileProvider + dependensi `androidx.core:1.13.1`; fallback otomatis ke teks bila gagal — WAJIB uji perangkat, belum terbukti dari VM); kunci sidik jari opsional (saklar di Pengaturan, `pkt_biometric_lock` + SharedPreferences native `mbg_prefs.biometric_enabled`; BiometricPrompt `androidx.biometric:1.1.0`, `MainActivity` kini `FragmentActivity`, fallback PIN/pola perangkat, batal = aplikasi tutup, kunci ulang setelah 60 detik di latar; sidik jari TIDAK pernah disimpan aplikasi — pendaftaran & data biometrik murni urusan sistem Android; WAJIB uji perangkat); zoom halaman dikunci (setSupportZoom(false) + viewport user-scalable=no + touch-action manipulation); smoke test 47/47.
