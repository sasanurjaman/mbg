# MBG Android — proyek Android Studio

Proyek ini membungkus aplikasi MBG HTML yang sudah ada ke dalam Android WebView.

## Target awal
- Tablet: Samsung Galaxy Tab S10 Lite
- Android target: Android 15 / API 35
- Aplikasi berjalan lokal dari `app/src/main/assets/index.html`
- Tidak membutuhkan hosting atau backend untuk transaksi
- Data tetap di WebView localStorage
- Backup memakai Android Storage Access Framework (`ACTION_CREATE_DOCUMENT`), sehingga pengguna dapat memilih Google Drive bila Drive tersedia pada pemilih dokumen.
- Restore memakai pemilih dokumen Android melalui input file HTML.
- Tautan WhatsApp dibuka lewat aplikasi/browser eksternal.

## Fitur yang dipertahankan
Dashboard, tagihan, riwayat tagihan, pelanggan, nota, WhatsApp, belanja bersama, alokasi biaya, backup/restore JSON, dan penyimpanan lokal.

## Fitur tambahan
Menu Rekap Keuntungan dengan filter tanggal, total pendapatan, modal barang, biaya tambahan, diskon, laba bersih, rincian per tagihan, dan tombol cetak.

## Membuka dan membangun APK
1. Ekstrak ZIP.
2. Buka folder `MBG-Android-Studio` melalui Android Studio.
3. Pasang Android SDK Platform 35 dan Android SDK Build-Tools yang diminta Android Studio.
4. Tunggu Gradle Sync selesai.
5. Pilih Build > Build Bundle(s) / APK(s) > Build APK(s).
6. APK debug biasanya berada di `app/build/outputs/apk/debug/app-debug.apk`.

## Catatan pengujian penting
- APK belum dikompilasi atau diuji pada tablet fisik dalam paket ini.
- Versi Android 15 disebut sebagai target berdasarkan informasi pengguna; pastikan versi aktual perangkat sebelum instalasi.
- Backup Google Drive bergantung pada ketersediaan aplikasi/akun Google Drive di Android file picker. File disimpan ke lokasi yang dipilih, bukan disinkronkan otomatis.
- Data localStorage akan hilang jika data aplikasi dihapus atau aplikasi di-uninstall. Lakukan backup berkala.
- Pastikan proses print diuji dengan printer/layanan cetak yang dipakai. Fungsi print native tersedia melalui bridge; UI cetak HTML standar dapat bervariasi antar perangkat.
- Pastikan data usaha, nomor rekening, dan detail nota benar sebelum penggunaan produksi.

## Build APK tanpa Android Studio (GitHub Actions)

1. Buat repository baru di GitHub, misalnya `mbg-android`.
2. Upload seluruh isi folder proyek ini ke repository (pastikan folder `.github/workflows` ikut ter-upload).
3. Buka tab **Actions** pada repository. Jika diminta, pilih **I understand my workflows, go ahead and enable them**.
4. Pilih workflow **Build MBG APK**, klik **Run workflow**, lalu klik tombol hijau **Run workflow**.
5. Tunggu sampai proses selesai dengan tanda centang hijau.
6. Buka hasil run tersebut. Di bagian **Artifacts**, unduh `MBG-debug-APK`.
7. Ekstrak ZIP artifact; di dalamnya ada `app-debug.apk`. Kirim ke tablet dan instal.

Workflow ini membangun APK debug dan menyimpan artifact selama 14 hari. Build pertama membutuhkan koneksi internet di sisi GitHub untuk mengunduh Gradle dan Android SDK. APK ditujukan untuk pemasangan langsung/pribadi, bukan publikasi Play Store.
