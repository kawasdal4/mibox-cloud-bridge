#!/usr/bin/env python3
"""MiBox Cloud Bridge server for Termux.

Streams uploads to rclone rcat; it does not stage whole files on Mi Box storage.
Only accepts requests from loopback or Tailscale IPv4/IPv6 addresses by default.
"""
from __future__ import annotations

import configparser
import hashlib
import hmac
import ipaddress
import json
import mimetypes
import os
import re
import shutil
import socket
import subprocess
import threading
import time
import traceback
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

HOME = Path.home()
APP_HOME = Path(os.environ.get("MIBOX_CLOUD_HOME", str(HOME / "cloud-bridge"))).expanduser()
CONFIG_FILE = APP_HOME / "config" / "settings.json"
LOG_DIR = APP_HOME / "logs"
LOG_FILE = LOG_DIR / "server.log"
UPLOAD_LOCK = threading.Lock()
TAILSCALE_NETS = [
    ipaddress.ip_network("100.64.0.0/10"),
    ipaddress.ip_network("fd7a:115c:a1e0::/48"),
]
MAX_NAME_LENGTH = 240
READ_CHUNK = 256 * 1024


def log(message: str) -> None:
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    line = f"{time.strftime('%Y-%m-%d %H:%M:%S')} {message}\n"
    try:
        with LOG_FILE.open("a", encoding="utf-8") as f:
            f.write(line)
    except OSError:
        pass


def load_settings() -> dict[str, Any]:
    if not CONFIG_FILE.exists():
        raise RuntimeError(f"Config belum ada: {CONFIG_FILE}. Jalankan install_cloud_bridge.sh.")
    data = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
    required = ["token", "remote", "port", "rclone_config", "min_free_bytes"]
    missing = [x for x in required if not data.get(x)]
    if missing:
        raise RuntimeError("Settings kurang: " + ", ".join(missing))
    if not re.fullmatch(r"[A-Za-z0-9_-]+", data["remote"]):
        raise RuntimeError("Nama remote rclone tidak valid")
    if len(data["token"]) < 32:
        raise RuntimeError("Token API terlalu pendek; jalankan ulang installer")
    return data


SETTINGS = load_settings()
RCLONE = shutil.which("rclone")
if not RCLONE:
    raise RuntimeError("rclone tidak ditemukan di PATH Termux")
REMOTE = SETTINGS["remote"]
RCLONE_CONFIG = SETTINGS["rclone_config"]
TOKEN = SETTINGS["token"]
PORT = int(SETTINGS.get("port", 8765))
MIN_FREE_BYTES = int(SETTINGS.get("min_free_bytes", 300 * 1024 * 1024))
MAX_UPLOAD_BYTES = int(SETTINGS.get("max_upload_bytes", 0))  # 0 = unlimited
ALLOW_LAN = bool(SETTINGS.get("allow_private_lan_for_testing", False))


def run_rclone(args: list[str], *, input_bytes: bytes | None = None,
               stdin: Any = None, stdout=subprocess.PIPE) -> subprocess.CompletedProcess:
    cmd = [RCLONE, "--config", RCLONE_CONFIG, *args]
    return subprocess.run(
        cmd, input=input_bytes, stdin=stdin, stdout=stdout,
        stderr=subprocess.PIPE, check=False,
    )


def rclone_prefix(parent_id: str) -> list[str]:
    if parent_id == "root":
        return []
    # Root-folder-ID changes the view of the remote to the selected Drive folder.
    return ["--drive-root-folder-id", parent_id]


def safe_name(value: str) -> str:
    name = (value or "").strip()
    if not name:
        raise ValueError("Nama file/folder kosong")
    if len(name) > MAX_NAME_LENGTH:
        raise ValueError(f"Nama terlalu panjang (maksimum {MAX_NAME_LENGTH} karakter)")
    if name in {".", ".."} or "/" in name or "\\" in name:
        raise ValueError("Nama tidak boleh berupa path atau mengandung / atau \\")
    if any(ord(ch) < 32 or ord(ch) == 127 for ch in name):
        raise ValueError("Nama mengandung karakter kontrol")
    return name


def command_json(args: list[str]) -> Any:
    proc = run_rclone(args)
    if proc.returncode != 0:
        err = proc.stderr.decode("utf-8", "replace")[-800:]
        raise RuntimeError(f"rclone gagal ({proc.returncode}): {err.strip()}")
    try:
        return json.loads(proc.stdout.decode("utf-8", "replace") or "[]")
    except json.JSONDecodeError as exc:
        raise RuntimeError("Jawaban rclone bukan JSON yang valid") from exc


def read_remote_root_folder_id() -> str | None:
    """Read only root_folder_id for the active remote; never logs config secrets."""
    try:
        cp = subprocess.run([RCLONE, "--config", RCLONE_CONFIG, "config", "file"],
                           stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=False)
        cfg_path = Path(cp.stdout.decode("utf-8", "replace").strip())
        if cp.returncode != 0 or not cfg_path.exists():
            return None
        parser = configparser.ConfigParser(interpolation=None)
        parser.read(cfg_path, encoding="utf-8")
        if parser.has_option(REMOTE, "root_folder_id"):
            value = parser.get(REMOTE, "root_folder_id", fallback="").strip()
            return value or None
    except Exception:
        return None
    return None


REMOTE_ROOT_ID = read_remote_root_folder_id()


def parent_query_id(parent_id: str) -> str:
    if parent_id == "root":
        return REMOTE_ROOT_ID or "root"
    return parent_id


def drive_escape(value: str) -> str:
    return value.replace("\\", "\\\\").replace("'", "\\'")


def query_children(parent_id: str, name: str | None = None,
                   mime_type: str | None = None) -> list[dict[str, Any]]:
    pid = parent_query_id(parent_id)
    p = "root" if pid == "root" else pid
    clauses = [f"'{p}' in parents", "trashed = false"]
    if name is not None:
        clauses.append(f"name = '{drive_escape(name)}'")
    if mime_type is not None:
        clauses.append(f"mimeType = '{drive_escape(mime_type)}'")
    query = " and ".join(clauses)
    data = command_json(["backend", "query", f"{REMOTE}:", query])
    # rclone may emit JSON null when a query has no matches.
    if data is None:
        return []
    if not isinstance(data, list):
        raise RuntimeError("Format hasil Google Drive query tidak dikenal")
    return [item for item in data if isinstance(item, dict)]


def list_folders(parent_id: str) -> list[dict[str, Any]]:
    if parent_id != "root" and not re.fullmatch(r"[A-Za-z0-9_-]{5,200}", parent_id):
        raise ValueError("ID folder tidak valid")
    args = [*rclone_prefix(parent_id), "lsjson", f"{REMOTE}:", "--dirs-only", "--max-depth", "1"]
    data = command_json(args)
    folders: list[dict[str, Any]] = []
    for item in data:
        if not item.get("IsDir"):
            continue
        folder_id = item.get("ID") or item.get("OrigID")
        name = item.get("Name")
        if folder_id and name:
            folders.append({"id": str(folder_id), "name": str(name), "is_folder": True})
    folders.sort(key=lambda f: f["name"].casefold())
    return folders


def create_folder(parent_id: str, raw_name: str) -> dict[str, Any]:
    name = safe_name(raw_name)
    if parent_id != "root" and not re.fullmatch(r"[A-Za-z0-9_-]{5,200}", parent_id):
        raise ValueError("ID folder tidak valid")
    existing = query_children(parent_id, name=name, mime_type="application/vnd.google-apps.folder")
    if existing:
        raise FileExistsError("Folder bernama sama sudah ada. Pilih folder yang sudah ada.")
    args = [*rclone_prefix(parent_id), "mkdir", f"{REMOTE}:{name}"]
    proc = run_rclone(args)
    if proc.returncode != 0:
        err = proc.stderr.decode("utf-8", "replace")[-800:]
        raise RuntimeError(f"Gagal membuat folder: {err.strip()}")
    # Verify it exists and retrieve its exact Drive ID for navigation.
    for _ in range(3):
        found = query_children(parent_id, name=name, mime_type="application/vnd.google-apps.folder")
        if found:
            item = found[0]
            if item.get("id"):
                return {"id": str(item["id"]), "name": str(item.get("name", name)), "is_folder": True}
        time.sleep(1)
    raise RuntimeError("Folder dibuat, tetapi ID-nya belum dapat diverifikasi. Refresh daftar folder.")


def query_file_by_name(parent_id: str, name: str) -> list[dict[str, Any]]:
    # The Drive query is scoped to the selected parent and excludes trashed files.
    return query_children(parent_id, name=name)


def get_available_bytes() -> int:
    return shutil.disk_usage(str(APP_HOME)).free


def client_is_allowed(handler: BaseHTTPRequestHandler) -> bool:
    raw = handler.client_address[0].split("%", 1)[0]
    try:
        ip = ipaddress.ip_address(raw)
        if getattr(ip, "ipv4_mapped", None) is not None:
            ip = ip.ipv4_mapped
    except ValueError:
        return False
    if ip.is_loopback:
        return True
    if any(ip in network for network in TAILSCALE_NETS):
        return True
    if ALLOW_LAN and (ip.is_private or ip.is_link_local):
        return True
    return False


class Handler(BaseHTTPRequestHandler):
    server_version = "MiBoxCloud/0.1"
    sys_version = ""

    def log_message(self, fmt: str, *args: Any) -> None:
        # Avoid logging URLs with sensitive query values or Authorization headers.
        log(f"HTTP {self.address_string()} {fmt % args}")

    def send_json(self, status: int, payload: dict[str, Any]) -> None:
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(raw)

    def authorize(self) -> bool:
        if not client_is_allowed(self):
            self.send_json(403, {"ok": False, "error": "Akses ditolak: gunakan koneksi Tailscale privat."})
            return False
        auth = self.headers.get("Authorization", "")
        candidate = auth[7:].strip() if auth.startswith("Bearer ") else ""
        if not hmac.compare_digest(candidate, TOKEN):
            self.send_json(401, {"ok": False, "error": "Token tidak valid."})
            return False
        return True

    def do_GET(self) -> None:
        parsed = urllib.parse.urlsplit(self.path)
        if parsed.path == "/api/ping":
            # No auth token needed, but keep even this minimal endpoint inside loopback/Tailscale.
            if not client_is_allowed(self):
                self.send_json(403, {"ok": False, "error": "Akses ditolak: gunakan koneksi Tailscale privat."})
                return
            self.send_json(200, {"ok": True, "service": "MiBox Cloud Bridge", "version": "0.1"})
            return
        if not self.authorize():
            return
        try:
            if parsed.path == "/api/health":
                available = get_available_bytes()
                # Probe the configured remote so the phone's "test connection" also validates rclone/Drive auth.
                command_json(["lsjson", f"{REMOTE}:", "--dirs-only", "--max-depth", "1"])
                self.send_json(200, {
                    "ok": True, "service": "MiBox Cloud Bridge", "version": "0.1",
                    "remote": REMOTE, "drive_reachable": True, "free_bytes": available,
                    "minimum_free_bytes": MIN_FREE_BYTES,
                    "accepting_uploads": available >= MIN_FREE_BYTES,
                    "streaming_upload": True,
                })
                return
            if parsed.path == "/api/folders":
                query = urllib.parse.parse_qs(parsed.query)
                parent = query.get("parent", ["root"])[0]
                folders = list_folders(parent)
                self.send_json(200, {"ok": True, "parent_id": parent, "folders": folders})
                return
            self.send_json(404, {"ok": False, "error": "Endpoint tidak ditemukan."})
        except ValueError as exc:
            self.send_json(400, {"ok": False, "error": str(exc)})
        except Exception as exc:
            log(f"GET error {parsed.path}: {type(exc).__name__}: {exc}")
            self.send_json(502, {"ok": False, "error": "Gagal membaca Google Drive. Periksa koneksi/rclone; detail ada di log Mi Box."})

    def do_POST(self) -> None:
        parsed = urllib.parse.urlsplit(self.path)
        if not self.authorize():
            return
        if parsed.path == "/api/folders":
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length < 1 or length > 16_384:
                    self.send_json(400, {"ok": False, "error": "Body permintaan tidak valid."})
                    return
                body = json.loads(self.rfile.read(length).decode("utf-8"))
                parent = str(body.get("parent_id", "root"))
                name = safe_name(str(body.get("name", "")))
                folder = create_folder(parent, name)
                log(f"FOLDER_CREATED name={name!r} parent_id={parent!r} id={folder['id']!r}")
                self.send_json(201, {"ok": True, "folder": folder})
            except FileExistsError as exc:
                self.send_json(409, {"ok": False, "error": str(exc)})
            except ValueError as exc:
                self.send_json(400, {"ok": False, "error": str(exc)})
            except Exception as exc:
                log(f"Folder create error: {type(exc).__name__}: {exc}")
                self.send_json(502, {"ok": False, "error": "Folder tidak berhasil diverifikasi di Google Drive."})
            return

        if parsed.path != "/api/upload":
            self.send_json(404, {"ok": False, "error": "Endpoint tidak ditemukan."})
            return

        try:
            query = urllib.parse.parse_qs(parsed.query)
            parent_id = query.get("parent_id", ["root"])[0]
            name = safe_name(query.get("name", [""])[0])
            expected_size = int(query.get("size", [self.headers.get("Content-Length", "-1")])[0])
            content_length = int(self.headers.get("Content-Length", "-1"))
            if expected_size < 0 or content_length < 0 or expected_size != content_length:
                self.send_json(411, {"ok": False, "error": "Ukuran file tidak diketahui atau tidak cocok."})
                return
            if MAX_UPLOAD_BYTES and expected_size > MAX_UPLOAD_BYTES:
                self.send_json(413, {"ok": False, "error": f"Ukuran file melewati batas konfigurasi ({MAX_UPLOAD_BYTES} byte)."})
                return
            if parent_id != "root" and not re.fullmatch(r"[A-Za-z0-9_-]{5,200}", parent_id):
                self.send_json(400, {"ok": False, "error": "ID folder tidak valid."})
                return
            if get_available_bytes() < MIN_FREE_BYTES:
                self.send_json(507, {"ok": False, "error": "Ruang kosong Mi Box di bawah ambang aman. Upload ditunda."})
                return
        except (ValueError, TypeError) as exc:
            self.send_json(400, {"ok": False, "error": f"Parameter upload tidak valid: {exc}"})
            return

        if not UPLOAD_LOCK.acquire(blocking=False):
            self.send_json(409, {"ok": False, "error": "Upload lain sedang berjalan. Coba lagi setelah selesai."})
            return
        try:
            # Never overwrite an existing file silently.
            before = query_file_by_name(parent_id, name)
            if before:
                self.send_json(409, {"ok": False, "error": "Nama file sudah ada di folder ini. Ubah nama file sebelum upload."})
                return

            md5 = hashlib.md5()
            sha256 = hashlib.sha256()
            args = [*rclone_prefix(parent_id), "rcat", f"{REMOTE}:{name}"]
            cmd = [RCLONE, "--config", RCLONE_CONFIG, *args]
            log(f"UPLOAD_START name={name!r} size={expected_size} parent_id={parent_id!r}")
            proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
                                    stderr=subprocess.PIPE)
            remaining = expected_size
            try:
                assert proc.stdin is not None
                while remaining > 0:
                    chunk = self.rfile.read(min(READ_CHUNK, remaining))
                    if not chunk:
                        raise ConnectionError("Koneksi terputus sebelum seluruh file diterima")
                    proc.stdin.write(chunk)
                    md5.update(chunk)
                    sha256.update(chunk)
                    remaining -= len(chunk)
                proc.stdin.close()
            except Exception:
                try:
                    proc.stdin.close()  # type: ignore[union-attr]
                except Exception:
                    pass
                proc.terminate()
                try:
                    proc.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    proc.kill()
                raise

            stderr = proc.stderr.read().decode("utf-8", "replace")[-1200:] if proc.stderr else ""
            rc = proc.wait()
            if rc != 0:
                log(f"UPLOAD_RCLONE_FAILED name={name!r} rc={rc} detail={stderr!r}")
                self.send_json(502, {"ok": False, "error": "rclone gagal mengunggah file. File lokal di HP tetap aman; coba lagi."})
                return

            # Re-query by exact name + selected parent; verification is against Drive metadata.
            matches = query_file_by_name(parent_id, name)
            candidates = [x for x in matches if x.get("id") and str(x.get("size", "-1")) == str(expected_size)]
            md5_text = md5.hexdigest().lower()
            verified = None
            for item in candidates:
                remote_md5 = str(item.get("md5Checksum", "")).lower()
                if remote_md5 and remote_md5 == md5_text:
                    verified = (item, "size+md5")
                    break
            if verified is None:
                # If Drive doesn't expose MD5, require a unique matching item and exact size.
                without_md5 = [x for x in candidates if not x.get("md5Checksum")]
                if len(without_md5) == 1:
                    verified = (without_md5[0], "size_only")

            if verified is None:
                log(f"UPLOAD_VERIFY_UNCERTAIN name={name!r} expected={expected_size} sha256={sha256.hexdigest()}")
                self.send_json(202, {"ok": False, "verification_pending": True,
                                     "error": "Upload sudah dikirim, tetapi verifikasi Google Drive belum dapat dipastikan. Jangan langsung mengunggah ulang; refresh folder terlebih dahulu.",
                                     "name": name, "size": expected_size})
                return

            item, verification = verified
            log(f"UPLOAD_VERIFIED name={name!r} id={item.get('id')!r} size={expected_size} method={verification} sha256={sha256.hexdigest()}")
            self.send_json(200, {"ok": True, "verified": True, "verification": verification,
                                 "file_id": str(item["id"]), "name": name, "size": expected_size,
                                 "md5": md5_text, "sha256": sha256.hexdigest(),
                                 "mime_type": item.get("mimeType") or mimetypes.guess_type(name)[0] or "application/octet-stream"})
        except ValueError as exc:
            self.send_json(400, {"ok": False, "error": str(exc)})
        except Exception as exc:
            log(f"UPLOAD_ERROR name={name!r}: {type(exc).__name__}: {exc}")
            self.send_json(502, {"ok": False, "error": "Upload tidak terverifikasi. Jangan hapus file sumber HP; periksa status folder di Google Drive sebelum mencoba kembali."})
        finally:
            UPLOAD_LOCK.release()


def main() -> None:
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    server.daemon_threads = True
    log(f"SERVER_START port={PORT} remote={REMOTE} min_free_bytes={MIN_FREE_BYTES}")
    print(f"MiBox Cloud Bridge aktif pada port {PORT}.")
    print("Akses dibatasi ke localhost/Tailscale IP + token.")
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        log("SERVER_STOP")


if __name__ == "__main__":
    main()
