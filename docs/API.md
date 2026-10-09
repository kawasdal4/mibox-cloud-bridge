# API MiBox Cloud Bridge v0.1

Semua endpoint kecuali `/api/ping` memerlukan `Authorization: Bearer <token>` dan sumber koneksi harus loopback/Tailscale.

- `GET /api/ping` — cek proses hidup; tidak melakukan query Google Drive.
- `GET /api/health` — info remote dan ruang kosong.
- `GET /api/folders?parent=root` — subfolder langsung. Untuk navigasi berikutnya, gunakan ID folder yang dikembalikan.
- `POST /api/folders` JSON `{"parent_id":"root","name":"Folder Baru"}` — membuat folder dan mengembalikan ID yang ditemukan kembali di Drive.
- `POST /api/upload?parent_id=<id>&name=<urlencoded-filename>&size=<bytes>` — body berupa byte asli file. Header `Content-Length` wajib sama dengan `size`.

Upload di-stream ke `rclone rcat`; tidak ada file lengkap yang ditulis ke storage lokal Mi Box. Response sukses:

```json
{"ok":true,"verified":true,"verification":"size+md5","file_id":"...","name":"sample.bin","size":1234}
```

Metode verifikasi `size_only` hanya digunakan jika Drive tidak menyediakan MD5. Jika metadata tidak dapat dipastikan, server merespons non-sukses agar aplikasi tidak menampilkan notifikasi berhasil.
