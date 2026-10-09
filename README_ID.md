# MiBox Cloud — Universal File Uploader (paket sumber v0.1.1)

Paket ini menyiapkan **cloud bridge terpisah** untuk Mi Box 4 / Android 9 + Termux dan aplikasi Android yang menerima file dari menu **Bagikan**. Sasaran aplikasinya: semua jenis file yang dapat dibagikan oleh aplikasi Android, pemilihan folder Drive manual, buat folder baru, progres, dan notifikasi sukses setelah verifikasi metadata Google Drive.

> **Status paket:** server Termux dan source project Android disertakan. APK belum terkompilasi pada lingkungan pembuatan ini karena Android SDK/Gradle tidak tersedia dan unduhan internet dari lingkungan build tidak aktif. Source project bisa dibuka dan dibangun menggunakan Android Studio. Jangan menganggap aplikasi sudah terpasang di HP/Mi Box sampai langkah pemasangan dilakukan.

## Perlindungan untuk sistem CCTV

- Installer hanya membuat/memperbarui `~/cloud-bridge/`.
- Installer tidak membaca, menulis, menghapus, atau mereset `~/tapo-agent/work/state.json`.
- Installer tidak mengubah `~/tapo-agent/tapo-backup-runner.sh`, PID runner CCTV, maupun launcher `~/.termux/boot/tapo-backup`.
- Autostart cloud bridge **tidak** diaktifkan otomatis. File opsional `enable_boot_optional.sh` hanya membuat launcher baru bernama `50-mibox-cloud-bridge` jika dijalankan secara manual.
- Upload di-stream langsung ke `rclone rcat`; server tidak membuat salinan penuh file pada disk Mi Box. File dengan nama yang sudah ada tidak ditimpa secara diam-diam.
- Sukses hanya dikembalikan setelah file ditemukan pada folder Drive yang benar dan ukuran serta checksum MD5 (jika disediakan Drive) cocok. Jika verifikasi tidak pasti, aplikasi tidak akan memberi notifikasi sukses.

## Arsitektur

1. Di HP, pilih file dan tekan **Bagikan → MiBox Cloud**.
2. Aplikasi menampilkan file yang dipilih, navigasi folder/subfolder Google Drive, pencarian folder, tombol **Folder baru**, dan konfirmasi tujuan.
3. Aplikasi mengirim file melalui HTTP di dalam jaringan privat Tailscale ke server Termux.
4. Server melakukan stream ke `rclone rcat`, kemudian memeriksa metadata file pada folder/ID parent Google Drive yang dipilih.
5. Aplikasi mengirim notifikasi sukses hanya jika respons server menyertakan `verified: true`.

Pemilihan folder menggunakan ID Drive, bukan hanya nama folder. Jadi dua folder dengan nama sama di lokasi berbeda tetap dapat dibedakan.

## Persyaratan

- Mi Box 4: Android 9, Termux dan Python.
- rclone sudah terpasang di Termux dan remote bernama `gdrive:` sudah berfungsi. Paket tidak mengubah remote rclone.
- Tailscale terpasang serta login pada Mi Box dan HP. Perangkat boleh berada di jaringan berbeda.
- HP Android 9 atau lebih baru.
- Untuk membangun APK: Android Studio dengan Android SDK Platform 35 dan koneksi yang memungkinkan Gradle mengunduh Android Gradle Plugin.

## A. Pasang server di Termux (manual, aman)

1. Ekstrak folder `MiBoxCloud` ke PC.
2. Salin ke Mi Box melalui ADB atau metode file transfer yang sudah biasa digunakan. Contoh PowerShell (jalankan dari folder yang berisi `adb.exe`):

   ```powershell
   $ADB = Join-Path $PWD 'adb.exe'
   $D = '192.168.1.19:5555'
   & $ADB -s $D push '.\MiBoxCloud' '/sdcard/Download/'
   ```

3. Buka aplikasi Termux di Mi Box. Bila Termux belum punya izin storage, jalankan `termux-setup-storage` lalu setujui dialog izin Android.
4. Jalankan installer dari Termux:

   ```bash
   bash /sdcard/Download/MiBoxCloud/termux/install_cloud_bridge.sh gdrive
   ```

   Installer berhenti tanpa melakukan setup jika remote `gdrive:` tidak ditemukan. Ia menyimpan token di `~/cloud-bridge/config/settings.json` (izin file dibatasi) dan menampilkan token sekali pada output instalasi. **Salin token itu ke tempat aman; jangan kirim ke orang lain.** Jangan menaruh token di folder bersama Android.

5. Mulai server secara manual terlebih dahulu:

   ```bash
   bash ~/cloud-bridge/start.sh
   bash ~/cloud-bridge/status.sh
   ```

   Server memakai port `8765`. Tidak ada perubahan pada launcher boot CCTV. Untuk menghentikan hanya server ini:

   ```bash
   bash ~/cloud-bridge/stop.sh
   ```

## B. Konfigurasikan Tailscale

1. Pasang/aktifkan Tailscale pada Mi Box dan login ke tailnet yang sama dengan HP.
2. Catat IP Tailscale Mi Box (umumnya alamat IPv4 `100.x.y.z`), bukan alamat Wi-Fi `192.168.x.x`.
3. Pada HP pastikan Tailscale aktif. Uji aplikasi setelah memasukkan `http://IP-TAILSCALE-MIBOX:8765` dan token yang dibuat installer.
4. Server memblokir permintaan dari alamat publik/LAN biasa; secara default hanya loopback dan alamat Tailscale yang diizinkan. **Jangan membuat port forwarding pada router.**

Dokumentasi Tailscale menyatakan klien Android mendukung Android 8+ termasuk Android TV: https://tailscale.com/docs/install/android

## C. Bangun aplikasi Android

### Opsi 1 — Android Studio

1. Buka Android Studio pada PC.
2. Pilih **Open** dan buka folder `MiBoxCloud/android-app`.
3. Biarkan Gradle Sync selesai. Pastikan Android SDK Platform 35 tersedia.
4. Pilih **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. APK debug umumnya berada di:

   `android-app/app/build/outputs/apk/debug/app-debug.apk`

6. Instal ke HP, buka MiBox Cloud dan isi:
   - Server: `http://IP-TAILSCALE-MIBOX:8765`
   - Token: token dari installer Termux
7. Tekan **Simpan dan tes koneksi**. Setelah koneksi berhasil, coba bagikan file kecil terlebih dahulu.

### Opsi 2 — GitHub Actions

Paket juga menyertakan `.github/workflows/build-android.yml`. Untuk memakai opsi ini, buat repositori GitHub dan letakkan **isi folder `MiBoxCloud` pada root repositori** (bukan sebagai subfolder tambahan). Buka tab **Actions**, pilih workflow **Build MiBox Cloud debug APK**, lalu jalankan **Run workflow**. Setelah selesai, unduh artifact `MiBoxCloud-debug-apk` dan ambil `app-debug.apk` dari ZIP artifact tersebut. Workflow ini membangun APK debug; bukan rilis Play Store.

Aplikasi Android menggunakan `ACTION_SEND` dan `ACTION_SEND_MULTIPLE` dengan MIME `*/*`; Android hanya dapat memberikan file yang memang diizinkan oleh aplikasi sumber. Dokumentasi: https://developer.android.com/develop/ui/compose/sharing/receive

## Cara memilih folder

- Ketuk folder untuk masuk ke subfolder.
- Gunakan tombol **Kembali** untuk naik ke folder induk.
- Cari nama folder melalui kolom pencarian.
- Tekan **Folder baru** untuk membuat subfolder di lokasi saat ini. Jika berhasil dibuat, folder baru langsung dibuka dan dipilih sebagai tujuan.
- Tekan **Upload ke folder ini** dan konfirmasi.

## Verifikasi dan notifikasi

- File tidak dikonversi; nama dan isi asli dipertahankan.
- Upload bersifat satu per satu untuk menghindari beban berlebih pada Mi Box.
- Jika nama file sudah ada, upload dihentikan agar file lama tidak tertimpa.
- Notifikasi sukses diterbitkan setelah server menemukan file pada parent folder yang dipilih, memeriksa ukuran, dan mencocokkan MD5 jika Google Drive menyediakannya.
- Jika terjadi HTTP 202/hasil verifikasi tidak pasti, **jangan langsung upload ulang**. Periksa folder Drive dahulu untuk menghindari duplikat.
- Jika HP tidak tersambung, notifikasi tidak dapat dikirim dari jarak jauh karena status disampaikan melalui respons upload pada aplikasi yang sedang berjalan. Versi ini belum memiliki antrean notifikasi cloud/offline yang disinkronkan setelah HP kembali online.

## Ruang penyimpanan Mi Box

Server mengalirkan isi request langsung ke `rclone rcat`; tidak mengharuskan ruang disk sebesar ukuran file. Ada ambang keamanan 300 MiB: upload ditolak bila ruang kosong pada Mi Box turun di bawah ambang tersebut. Pada HP, file yang ukuran dari penyedia Android tidak diketahui akan disalin sementara ke cache HP untuk mengetahui ukuran; salinan cache tersebut dihapus setelah upload.

## Log dan troubleshooting

- Log server: `~/cloud-bridge/logs/server.log`
- Log stdout/stderr: `~/cloud-bridge/logs/console.log`
- Status: `bash ~/cloud-bridge/status.sh`
- Token/config: `~/cloud-bridge/config/settings.json` (jangan tampilkan atau bagikan seluruh file karena berisi token dan path konfigurasi).

Jika folder tidak dapat dibaca, tes `rclone lsd gdrive:` secara manual di Termux. Jangan mengganti atau memindahkan konfigurasi rclone yang digunakan runner CCTV.

## Autostart (jangan jalankan sebelum tes manual lulus)

Setelah server dan upload benar-benar lolos pengujian beberapa kali, autostart opsional dapat diaktifkan lewat:

```bash
bash ~/cloud-bridge/enable_boot_optional.sh
```

Ini membuat **launcher tambahan yang terpisah**, tidak menimpa `~/.termux/boot/tapo-backup`. Untuk tahap awal, biarkan autostart belum aktif.

## Batasan v0.1

- Server upload satu file pada satu waktu; beberapa file yang dibagikan dikirim berurutan.
- Upload file dengan nama sama pada folder tujuan ditolak; pengguna perlu mengganti nama file di HP.
- Transfer yang benar-benar putus di tengah file mungkin harus dimulai ulang. File sumber HP tidak dihapus.
- Server HTTP digunakan hanya di tailnet privat Tailscale; enkripsi transport diberikan oleh tunnel Tailscale. Server menolak sumber koneksi yang bukan loopback/Tailscale secara default.
- APK belum dibangun pada lingkungan ini; source project perlu dibangun dan diinstal seperti bagian C.
