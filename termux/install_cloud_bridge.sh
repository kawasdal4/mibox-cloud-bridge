#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

SRC_DIR="$(cd "$(dirname "$0")" && pwd)"
HOME_DIR="$HOME"
APP="$HOME_DIR/cloud-bridge"
REMOTE="${1:-gdrive}"

fail() { echo "ERROR: $*" >&2; exit 1; }
command -v python >/dev/null 2>&1 || fail "Python tidak ditemukan. Jalankan: pkg install python"
command -v rclone >/dev/null 2>&1 || fail "rclone tidak ditemukan. Pastikan rclone yang dipakai backup CCTV tersedia di PATH Termux."
[[ -f "$SRC_DIR/cloud_bridge_server.py" ]] || fail "cloud_bridge_server.py tidak ditemukan di folder paket yang sama."

REMOTES="$(rclone listremotes 2>/dev/null || true)"
printf '%s\n' "$REMOTES" | grep -Fxq "${REMOTE}:" || fail "Remote rclone '${REMOTE}:' tidak ditemukan. Remote terdaftar: ${REMOTES//$'\n'/, }. Instalasi dihentikan tanpa mengubah rclone."

CONFIG_PATH="$(rclone config file 2>/dev/null | tail -n 1 | sed 's/^Configuration file is stored at: //')"
[[ -n "$CONFIG_PATH" && -f "$CONFIG_PATH" ]] || fail "File konfigurasi rclone tidak dapat ditemukan. Tidak ada file yang diubah."

mkdir -p "$APP/config" "$APP/server" "$APP/logs" "$APP/status"
chmod 700 "$APP" "$APP/config" "$APP/server" "$APP/logs" "$APP/status"
cp "$SRC_DIR/cloud_bridge_server.py" "$APP/server/cloud_bridge_server.py.new"
python -m py_compile "$APP/server/cloud_bridge_server.py.new"
mv "$APP/server/cloud_bridge_server.py.new" "$APP/server/cloud_bridge_server.py"
chmod 700 "$APP/server/cloud_bridge_server.py"

SETTINGS="$APP/config/settings.json"
if [[ ! -f "$SETTINGS" ]]; then
  export MIBOX_REMOTE="$REMOTE" MIBOX_RCLONE_CONFIG="$CONFIG_PATH" MIBOX_SETTINGS="$SETTINGS"
  python - <<'PY'
import json, os, secrets
p = os.environ['MIBOX_SETTINGS']
settings = {
  'version': 1,
  'token': secrets.token_urlsafe(36),
  'remote': os.environ['MIBOX_REMOTE'],
  'rclone_config': os.environ['MIBOX_RCLONE_CONFIG'],
  'port': 8765,
  'min_free_bytes': 314572800,
  'max_upload_bytes': 0,
  'allow_private_lan_for_testing': False,
}
with open(p, 'w', encoding='utf-8') as f:
    json.dump(settings, f, indent=2)
    f.write('\n')
os.chmod(p, 0o600)
PY
  NEW_CONFIG=1
else
  NEW_CONFIG=0
  echo "Settings lama ditemukan. Token dan konfigurasi yang sudah ada dipertahankan."
fi

cat > "$APP/start.sh" <<'SH2'
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
APP="$HOME/cloud-bridge"
PIDFILE="$APP/server.pid"
SERVER="$APP/server/cloud_bridge_server.py"
mkdir -p "$APP/logs"
if [[ -f "$PIDFILE" ]]; then
  PID="$(cat "$PIDFILE" 2>/dev/null || true)"
  if [[ "$PID" =~ ^[0-9]+$ ]] && kill -0 "$PID" 2>/dev/null; then
    ARGS="$(tr '\0' ' ' < "/proc/$PID/cmdline" 2>/dev/null || true)"
    if [[ "$ARGS" == *"cloud_bridge_server.py"* ]]; then
      echo "MiBox Cloud Bridge sudah berjalan (PID $PID)."
      exit 0
    fi
  fi
fi
export MIBOX_CLOUD_HOME="$APP"
nohup python "$SERVER" >> "$APP/logs/console.log" 2>&1 </dev/null &
PID=$!
echo "$PID" > "$PIDFILE"
sleep 2
if kill -0 "$PID" 2>/dev/null; then
  echo "MiBox Cloud Bridge dimulai (PID $PID, port 8765)."
  echo "Boot otomatis belum diaktifkan."
else
  rm -f "$PIDFILE"
  echo "Server gagal dimulai. Periksa $APP/logs/console.log"
  tail -n 40 "$APP/logs/console.log" 2>/dev/null || true
  exit 1
fi
SH2

cat > "$APP/stop.sh" <<'SH2'
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
APP="$HOME/cloud-bridge"
PIDFILE="$APP/server.pid"
[[ -f "$PIDFILE" ]] || { echo "PID file tidak ada; tidak ada proses yang dihentikan."; exit 0; }
PID="$(cat "$PIDFILE" 2>/dev/null || true)"
[[ "$PID" =~ ^[0-9]+$ ]] || { echo "PID tidak valid; menghapus hanya file PID cloud bridge."; rm -f "$PIDFILE"; exit 0; }
if ! kill -0 "$PID" 2>/dev/null; then rm -f "$PIDFILE"; echo "Cloud bridge sudah berhenti."; exit 0; fi
ARGS="$(tr '\0' ' ' < "/proc/$PID/cmdline" 2>/dev/null || true)"
if [[ "$ARGS" != *"cloud_bridge_server.py"* ]]; then
  echo "PID tidak cocok dengan server MiBox Cloud; tidak menghentikan proses apa pun."
  exit 1
fi
kill "$PID"
rm -f "$PIDFILE"
echo "MiBox Cloud Bridge dihentikan. Runner CCTV tidak disentuh."
SH2

cat > "$APP/status.sh" <<'SH2'
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
APP="$HOME/cloud-bridge"
echo "=== MIBOX CLOUD BRIDGE STATUS ==="
if [[ -f "$APP/server.pid" ]]; then
  PID="$(cat "$APP/server.pid" 2>/dev/null || true)"
  if [[ "$PID" =~ ^[0-9]+$ ]] && kill -0 "$PID" 2>/dev/null && [[ "$(tr '\0' ' ' < "/proc/$PID/cmdline" 2>/dev/null || true)" == *"cloud_bridge_server.py"* ]]; then
    echo "Process: RUNNING (PID $PID)"
  else
    echo "Process: NOT RUNNING (PID file stale or process changed)"
  fi
else
  echo "Process: NOT RUNNING"
fi
python - <<'PY'
import json, os, shutil, urllib.request
app=os.path.expanduser('~/cloud-bridge')
try:
    s=json.load(open(app+'/config/settings.json', encoding='utf-8'))
    free=shutil.disk_usage(app).free
    print('Remote:', s.get('remote'))
    print('Free Mi Box:', round(free/(1024**3), 2), 'GiB')
    print('Minimum free threshold:', round(s.get('min_free_bytes',0)/(1024**2)), 'MiB')
    try:
        with urllib.request.urlopen('http://127.0.0.1:'+str(s.get('port',8765))+'/api/ping', timeout=3) as r:
            print('Local ping:', r.status)
    except Exception:
        print('Local ping: NOT REACHABLE')
except Exception as e:
    print('Settings check failed:', type(e).__name__)
PY
echo "Recent log:"
tail -n 15 "$APP/logs/server.log" 2>/dev/null || true
SH2

cat > "$APP/enable_boot_optional.sh" <<'SH2'
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
BOOT="$HOME/.termux/boot"
HOOK="$BOOT/50-mibox-cloud-bridge"
mkdir -p "$BOOT"
chmod 700 "$BOOT"
if [[ -e "$HOOK" ]]; then
  echo "Launcher $HOOK sudah ada; tidak ditimpa."
  exit 1
fi
cat > "$HOOK" <<'HOOK2'
#!/data/data/com.termux/files/usr/bin/bash
# Separate launcher; leaves the existing tapo-backup launcher untouched.
sleep 20
termux-wake-lock >/dev/null 2>&1 || true
bash "$HOME/cloud-bridge/start.sh" >> "$HOME/cloud-bridge/logs/boot.log" 2>&1
HOOK2
chmod 700 "$HOOK"
echo "Launcher terpisah dibuat: $HOOK"
echo "Jalankan hanya setelah cloud bridge diuji manual. Launcher tapo-backup tidak diubah."
SH2

chmod 700 "$APP"/*.sh

if [[ "$NEW_CONFIG" == "1" ]]; then
  echo
  echo "=== SETUP BERHASIL ==="
  echo "Server folder: $APP"
  echo "Remote: ${REMOTE}:"
  echo "Port: 8765"
  echo "Minimum free-space guard: 300 MiB"
  echo "API token (rahasia; masukkan ke aplikasi HP, jangan kirim ke orang lain):"
  python - "$SETTINGS" <<'PY'
import json, sys
print(json.load(open(sys.argv[1], encoding='utf-8'))['token'])
PY
else
  echo "Server diperbarui. Config/token yang lama dipertahankan."
fi
cat <<'TXT'

Langkah selanjutnya:
  1) Jalankan: bash ~/cloud-bridge/start.sh
  2) Jalankan: bash ~/cloud-bridge/status.sh
  3) Siapkan Tailscale di Mi Box dan HP; catat IP Tailscale Mi Box (biasanya 100.x.y.z).
  4) Di aplikasi HP isi alamat http://IP-TAILSCALE-MIBOX:8765 dan token di atas.

Catatan keamanan:
  - Server hanya menerima alamat loopback/Tailscale secara default.
  - Jangan aktifkan allow_private_lan_for_testing untuk penggunaan normal.
  - Boot otomatis tidak diaktifkan oleh installer ini.
  - Tidak ada file CCTV atau state.json yang diakses/diubah oleh installer.
TXT
