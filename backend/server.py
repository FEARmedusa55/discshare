#!/usr/bin/env python3
"""
DiscShare backend: converts songs to small mono .ogg files for the mod and
keeps track of which jukeboxes are playing what.

Needs: Python 3.10+, ffmpeg (and ffprobe) on PATH, and yt-dlp (`pip install yt-dlp`).
Run:   python server.py            (listens on 0.0.0.0:8080)
Env:   DISCSHARE_PORT=8080
       DISCSHARE_DATA=./data
       DISCSHARE_UPLOAD_TOKEN=...   (optional password for making short codes)
       DISCSHARE_MAX_MINUTES=12     (longest song allowed)
       DISCSHARE_SERVER_CONVERT=0   (1 = also convert songs here; normally players' PCs do it)

Endpoints
  GET  /                       small page for making [#code] discs (link or file upload)
  GET  /audio/<key>.ogg        key = yt_<id> | sc_<artist>~<track> | c_<code>
  POST /api/playing            {"server","dim","x","y","z","disc"}
  GET  /api/playing?server=&dim=&x=&y=&z=   -> {"disc","elapsedMs"} or {}
  POST /api/codes              {"url": "..."}         -> {"code": "abc123"}
  PUT  /api/upload?ext=mp3     raw audio file as body -> {"code": "abc123"}
  PUT  /api/texture?code=abc123  raw PNG (max 128x128) as body -> {"ok": true}
  PUT  /api/disc-texture?key=<key>  raw PNG -> {"code"}: new [#code] with the same song + this texture (used by the anvil button)
  GET  /texture/<key>.png      the disc's texture, or 404 if it has none
  GET  /api/info/<key>         {"title": "..."} for the "Now Playing" pop-up
  POST /api/prepare/<key>      start converting a song in the background (returns right away)
  GET  /api/source/<key>       {"url": "..."} or {"file": "/raw/<key>"}: what a disc points to (players convert it)
  GET  /raw/<key>              an uploaded file, unconverted
  POST /api/portable           {"server","uuid","disc"|null} start/refresh (disc) or stop (null) a portable player
  GET  /api/portable?server=   {"players": [{"uuid","disc","elapsedMs"}]}
  POST /api/presence           {"server","uuid"} -> {"players": [uuids with the mod on that server]}
"""
import struct
import json
import os
import re
import secrets
import shutil
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

PORT = int(os.environ.get("DISCSHARE_PORT", "8080"))
DATA = Path(os.environ.get("DISCSHARE_DATA", "./data")).resolve()
UPLOAD_TOKEN = os.environ.get("DISCSHARE_UPLOAD_TOKEN", "")
MAX_SECONDS = int(float(os.environ.get("DISCSHARE_MAX_MINUTES", "12")) * 60)
MAX_UPLOAD_BYTES = 60 * 1024 * 1024
SERVER_CONVERT = os.environ.get("DISCSHARE_SERVER_CONVERT", "0") == "1"

AUDIO_DIR = DATA / "audio"
UPLOAD_DIR = DATA / "uploads"
TEXTURE_DIR = DATA / "textures"
MAX_TEXTURE = 128
CODES_FILE = DATA / "codes.json"
TITLES_FILE = DATA / "titles.json"
titles_lock = threading.Lock()
for d in (AUDIO_DIR, UPLOAD_DIR, TEXTURE_DIR):
    d.mkdir(parents=True, exist_ok=True)

KEY_RE = re.compile(r"^(yt_[A-Za-z0-9_-]{11}|sc_[A-Za-z0-9_-]+~[A-Za-z0-9_-]+|c_[A-Za-z0-9]{3,16})$")
EXT_RE = re.compile(r"^[a-z0-9]{2,5}$")

codes_lock = threading.Lock()
key_locks: dict[str, threading.Lock] = {}
key_locks_guard = threading.Lock()

# (server, dim, x, y, z) -> (disc, started_at, expires_at)
playing: dict[tuple, tuple] = {}
playing_lock = threading.Lock()

# server -> {uuid: last_check_in}; players count as online for 90s after checking in
presence: dict[str, dict[str, float]] = {}
presence_lock = threading.Lock()
PRESENCE_TTL = 90
# server -> {uuid: (disc, started_at, last_refresh)}; dropped 30s after the last refresh
portable: dict[str, dict[str, tuple]] = {}
portable_lock = threading.Lock()
PORTABLE_TTL = 30
UUID_RE = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")


# ---------- short codes ----------

def load_codes() -> dict:
    if CODES_FILE.exists():
        return json.loads(CODES_FILE.read_text())
    return {}


def save_code(entry: dict) -> str:
    with codes_lock:
        codes = load_codes()
        code = secrets.token_hex(3)  # 6 chars, e.g. "a1b2c3"
        while code in codes:
            code = secrets.token_hex(3)
        codes[code] = entry
        CODES_FILE.write_text(json.dumps(codes, indent=2))
        return code


def png_size(data: bytes):
    """(width, height) of a PNG, or None if it isn't one."""
    if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        return None
    return struct.unpack(">II", data[16:24])


# ---------- song titles ----------

def load_titles() -> dict:
    if TITLES_FILE.exists():
        return json.loads(TITLES_FILE.read_text())
    return {}


def title_for(key: str) -> str | None:
    """Song title for the in-game pop-up. Looked up once, then remembered."""
    with titles_lock:
        cached = load_titles().get(key)
    if cached is not None:
        return cached or None
    title = ""
    try:
        if key.startswith("c_"):
            entry = load_codes().get(key[2:], {})
            title = entry.get("title", "")
        if not title:
            kind, src = source_for(key)
            if kind == "url":
                r = subprocess.run(["yt-dlp", "--no-playlist", "--skip-download", "--print", "title", src],
                                   capture_output=True, text=True, timeout=30)
                title = r.stdout.strip().splitlines()[0] if r.returncode == 0 and r.stdout.strip() else ""
    except Exception as e:
        print(f"title lookup {key} failed: {e}")
        return None  # don't remember failures; try again next time
    with titles_lock:
        titles = load_titles()
        titles[key] = title[:100]
        TITLES_FILE.write_text(json.dumps(titles, indent=2))
    return title or None


# ---------- conversion ----------

def lock_for(key: str) -> threading.Lock:
    with key_locks_guard:
        return key_locks.setdefault(key, threading.Lock())


def source_for(key: str):
    """Returns ("url", url) or ("file", path) for a disc key."""
    if key.startswith("yt_"):
        return "url", f"https://www.youtube.com/watch?v={key[3:]}"
    if key.startswith("sc_"):
        return "url", "https://soundcloud.com/" + key[3:].replace("~", "/")
    entry = load_codes().get(key[2:])
    if not entry:
        raise FileNotFoundError(key)
    if "file" in entry:
        return "file", str(UPLOAD_DIR / entry["file"])
    return "url", entry["url"]


def convert(key: str) -> Path:
    out = AUDIO_DIR / f"{key}.ogg"
    if out.exists():
        return out
    with lock_for(key):
        if out.exists():
            return out
        kind, src = source_for(key)
        work = DATA / f"work_{key}"
        work.mkdir(exist_ok=True)
        try:
            if kind == "url":
                subprocess.run(
                    ["yt-dlp", "--no-playlist", "-f", "bestaudio/best",
                     "--match-filter", f"duration <= {MAX_SECONDS}",
                     "-o", str(work / "src.%(ext)s"), src],
                    check=True, timeout=300, capture_output=True)
                files = list(work.glob("src.*"))
                if not files:
                    raise RuntimeError("download failed or song too long")
                src = str(files[0])
            tmp = work / "out.ogg"
            # Mono so it sounds positional in-game like a real jukebox.
            subprocess.run(
                ["ffmpeg", "-y", "-i", src, "-vn", "-ac", "1", "-ar", "44100",
                 "-c:a", "libvorbis", "-q:a", "3", "-t", str(MAX_SECONDS), str(tmp)],
                check=True, timeout=300, capture_output=True)
            tmp.replace(out)
            return out
        finally:
            shutil.rmtree(work, ignore_errors=True)


def duration_of(path: Path) -> float | None:
    try:
        r = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "default=nw=1:nk=1", str(path)],
            capture_output=True, text=True, timeout=20)
        return float(r.stdout.strip())
    except Exception:
        return None


# ---------- HTTP ----------

PAGE = """<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>DiscShare</title>
<style>body{font:16px system-ui;max-width:560px;margin:40px auto;padding:0 16px}
input,button{font:inherit;padding:8px;margin:4px 0;width:100%;box-sizing:border-box}
code{background:#eee;padding:2px 6px;border-radius:4px;font-size:1.2em}</style>
<h1>DiscShare</h1>
<p>YouTube songs don't need a code. Just rename a disc to <code>[yt:VIDEO_ID]</code>.</p>
<p>For anything else, make a short code:</p>
<input id=tok placeholder="Upload password (if the server has one)" type=password>
<input id=url placeholder="Link (SoundCloud, Bandcamp, direct .mp3 ...)">
<button onclick=byUrl()>Make code from link</button>
<input id=file type=file accept="audio/*">
<button onclick=byFile()>Upload file</button>
<p>Disc texture (optional): square PNG, 16x16 up to 128x128. Pick it before making the code.
You can also add one later by entering the code.</p>
<input id=tex type=file accept="image/png">
<input id=code placeholder="Existing code (to add a texture later)">
<button onclick=texOnly()>Add texture to code</button>
<p id=out></p>
<script>
async function sendTex(c){const f=tex.files[0];if(!f)return '';
const r=await fetch('/api/texture?code='+encodeURIComponent(c),{method:'PUT',headers:h(),body:f});const j=await r.json();
return j.ok?' (texture added)':' (texture error: '+j.error+')'}
async function show(j){if(!j.code){out.textContent='Error: '+(j.error||'unknown');return}
const t=await sendTex(j.code);out.innerHTML='Rename your disc to <code>[#'+j.code+']</code>'+t}
async function texOnly(){out.textContent=(await sendTex(code.value.trim().replace(/^\[?#|\]$/g,'')))||'Pick a PNG first'}
const h=()=>({'X-Token':tok.value});
async function byUrl(){const r=await fetch('/api/codes',{method:'POST',headers:{...h(),'Content-Type':'application/json'},body:JSON.stringify({url:url.value})});await show(await r.json())}
async function byFile(){const f=file.files[0];if(!f)return;out.textContent='Uploading...';
const ext=(f.name.split('.').pop()||'mp3').toLowerCase();const nm=f.name.replace(/\.[^.]+$/,'');
const r=await fetch('/api/upload?ext='+encodeURIComponent(ext)+'&name='+encodeURIComponent(nm),{method:'PUT',headers:h(),body:f});await show(await r.json())}
</script>"""


class Handler(BaseHTTPRequestHandler):
    server_version = "DiscShare/0.1"

    def send_json(self, obj, status=200):
        body = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def authorized(self) -> bool:
        return not UPLOAD_TOKEN or secrets.compare_digest(self.headers.get("X-Token", ""), UPLOAD_TOKEN)

    def read_body(self, limit: int) -> bytes:
        n = int(self.headers.get("Content-Length", "0"))
        if n <= 0 or n > limit:
            raise ValueError("bad body size")
        return self.rfile.read(n)

    def do_GET(self):
        u = urlparse(self.path)
        if u.path == "/":
            body = PAGE.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        m = re.fullmatch(r"/audio/([^/]+)\.ogg", u.path)
        if m:
            key = m.group(1)
            if not KEY_RE.match(key):
                return self.send_json({"error": "bad key"}, 400)
            if not SERVER_CONVERT and not (AUDIO_DIR / f"{key}.ogg").exists():
                return self.send_json({"error": "server conversion is off; players convert songs themselves"}, 404)
            try:
                path = convert(key)
            except FileNotFoundError:
                return self.send_json({"error": "unknown code"}, 404)
            except Exception as e:
                self.log_message("convert %s failed: %s", key, e)
                return self.send_json({"error": "conversion failed"}, 502)
            size = path.stat().st_size
            self.send_response(200)
            self.send_header("Content-Type", "audio/ogg")
            self.send_header("Content-Length", str(size))
            self.send_header("Cache-Control", "public, max-age=31536000")
            self.end_headers()
            with open(path, "rb") as f:
                shutil.copyfileobj(f, self.wfile)
            return

        m = re.fullmatch(r"/texture/([^/]+)\.png", u.path)
        if m:
            key = m.group(1)
            path = TEXTURE_DIR / f"{key}.png"
            if not KEY_RE.match(key) or not path.exists():
                return self.send_json({"error": "no texture"}, 404)
            body = path.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()
            self.wfile.write(body)
            return

        m = re.fullmatch(r"/api/source/([^/]+)", u.path)
        if m:
            key = m.group(1)
            if not KEY_RE.match(key):
                return self.send_json({"error": "bad key"}, 400)
            try:
                kind, src = source_for(key)
            except FileNotFoundError:
                return self.send_json({"error": "unknown code"}, 404)
            return self.send_json({"url": src} if kind == "url" else {"file": f"/raw/{key}"})

        m = re.fullmatch(r"/raw/(c_[A-Za-z0-9]{3,16})", u.path)
        if m:
            entry = load_codes().get(m.group(1)[2:], {})
            path = UPLOAD_DIR / entry["file"] if "file" in entry else None
            if not path or not path.exists():
                return self.send_json({"error": "no such upload"}, 404)
            size = path.stat().st_size
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(size))
            self.end_headers()
            with open(path, "rb") as f:
                shutil.copyfileobj(f, self.wfile)
            return

        m = re.fullmatch(r"/api/info/([^/]+)", u.path)
        if m:
            key = m.group(1)
            if not KEY_RE.match(key):
                return self.send_json({"error": "bad key"}, 400)
            try:
                t = title_for(key)
            except FileNotFoundError:
                return self.send_json({"error": "unknown code"}, 404)
            return self.send_json({"title": t} if t else {})

        if u.path == "/api/portable":
            server = parse_qs(u.query).get("server", [""])[0].lower()[:100]
            now = time.time()
            with portable_lock:
                players = portable.get(server, {})
                for k in [k for k, e in players.items() if now - e[2] > PORTABLE_TTL]:
                    del players[k]
                out = [{"uuid": k, "disc": e[0], "elapsedMs": int((now - e[1]) * 1000)} for k, e in players.items()]
            return self.send_json({"players": out})

        if u.path == "/api/playing":
            q = {k: v[0] for k, v in parse_qs(u.query).items()}
            try:
                k = (q["server"], q["dim"], int(q["x"]), int(q["y"]), int(q["z"]))
            except (KeyError, ValueError):
                return self.send_json({"error": "bad query"}, 400)
            now = time.time()
            with playing_lock:
                entry = playing.get(k)
                if entry and entry[2] < now:
                    del playing[k]
                    entry = None
            if not entry:
                return self.send_json({})
            return self.send_json({"disc": entry[0], "elapsedMs": int((now - entry[1]) * 1000)})

        self.send_json({"error": "not found"}, 404)

    def do_POST(self):
        u = urlparse(self.path)
        m = re.fullmatch(r"/api/prepare/([^/]+)", u.path)
        if m:
            key = m.group(1)
            if not KEY_RE.match(key):
                return self.send_json({"error": "bad key"}, 400)
            ready = (AUDIO_DIR / f"{key}.ogg").exists()
            if not ready:
                threading.Thread(target=lambda: (_safe_convert(key), title_for(key)), daemon=True).start()
            return self.send_json({"ready": ready})

        try:
            data = json.loads(self.read_body(10_000))
        except Exception:
            return self.send_json({"error": "bad json"}, 400)

        if u.path == "/api/playing":
            try:
                k = (str(data["server"])[:100], str(data["dim"])[:100],
                     int(data["x"]), int(data["y"]), int(data["z"]))
                disc = str(data["disc"])
            except (KeyError, ValueError, TypeError):
                return self.send_json({"error": "bad body"}, 400)
            if not KEY_RE.match(disc):
                return self.send_json({"error": "bad disc"}, 400)
            now = time.time()
            ogg = AUDIO_DIR / f"{disc}.ogg"
            length = duration_of(ogg) if ogg.exists() else None
            with playing_lock:
                playing[k] = (disc, now, now + (length or MAX_SECONDS) + 5)
                # Drop expired entries so memory doesn't grow forever.
                for key in [key for key, e in playing.items() if e[2] < now]:
                    del playing[key]
            # Start converting now so it's ready when others ask.
            threading.Thread(target=lambda: _safe_convert(disc), daemon=True).start()
            return self.send_json({"ok": True})

        if u.path == "/api/portable":
            server = str(data.get("server", "")).lower()[:100]
            uid = str(data.get("uuid", "")).lower()
            disc = data.get("disc")
            if not server or not UUID_RE.match(uid) or (disc is not None and not KEY_RE.match(str(disc))):
                return self.send_json({"error": "bad body"}, 400)
            now = time.time()
            with portable_lock:
                players = portable.setdefault(server, {})
                if disc is None:
                    players.pop(uid, None)
                else:
                    old = players.get(uid)
                    started = old[1] if old and old[0] == disc else now  # same song = just a refresh
                    players[uid] = (disc, started, now)
            return self.send_json({"ok": True})

        if u.path == "/api/presence":
            server = str(data.get("server", ""))[:100]
            uid = str(data.get("uuid", ""))
            if not server or not UUID_RE.match(uid):
                return self.send_json({"error": "bad body"}, 400)
            now = time.time()
            with presence_lock:
                players = presence.setdefault(server.lower(), {})
                players[uid.lower()] = now
                for k in [k for k, t in players.items() if now - t > PRESENCE_TTL]:
                    del players[k]
                online = list(players.keys())
            return self.send_json({"players": online})

        if u.path == "/api/codes":
            if not self.authorized():
                return self.send_json({"error": "wrong password"}, 403)
            url = str(data.get("url", "")).strip()
            if not re.match(r"^https?://", url) or len(url) > 500:
                return self.send_json({"error": "enter a http(s) link"}, 400)
            code = save_code({"url": url})
            # Start downloading/converting now so the first play is instant.
            threading.Thread(target=lambda: (_safe_convert("c_" + code), title_for("c_" + code)), daemon=True).start()
            return self.send_json({"code": code})

        self.send_json({"error": "not found"}, 404)

    def do_PUT(self):
        u = urlparse(self.path)
        if u.path == "/api/texture":
            return self.put_texture(u)
        if u.path == "/api/disc-texture":
            return self.put_disc_texture(u)
        if u.path != "/api/upload":
            return self.send_json({"error": "not found"}, 404)
        if not self.authorized():
            return self.send_json({"error": "wrong password"}, 403)
        qs = parse_qs(u.query)
        ext = qs.get("ext", ["mp3"])[0].lower()
        title = qs.get("name", [""])[0][:100]
        if not EXT_RE.match(ext):
            return self.send_json({"error": "bad file type"}, 400)
        try:
            body = self.read_body(MAX_UPLOAD_BYTES)
        except ValueError:
            return self.send_json({"error": f"file must be under {MAX_UPLOAD_BYTES // 1024 // 1024} MB"}, 400)
        name = f"{secrets.token_hex(8)}.{ext}"
        (UPLOAD_DIR / name).write_bytes(body)
        code = save_code({"file": name, "title": title} if title else {"file": name})
        threading.Thread(target=lambda: _safe_convert("c_" + code), daemon=True).start()
        return self.send_json({"code": code})


def _put_texture(handler, u):
    if not handler.authorized():
        return handler.send_json({"error": "wrong password"}, 403)
    code = parse_qs(u.query).get("code", [""])[0]
    if not re.fullmatch(r"[A-Za-z0-9]{3,16}", code) or code not in load_codes():
        return handler.send_json({"error": "unknown code"}, 404)
    try:
        body = handler.read_body(512 * 1024)
    except ValueError:
        return handler.send_json({"error": "texture too big"}, 400)
    size = png_size(body)
    if not size:
        return handler.send_json({"error": "texture must be a PNG"}, 400)
    w, h = size
    if w > MAX_TEXTURE or h > MAX_TEXTURE or w != h:
        return handler.send_json({"error": f"texture must be square and at most {MAX_TEXTURE}x{MAX_TEXTURE}"}, 400)
    (TEXTURE_DIR / f"c_{code}.png").write_bytes(body)
    return handler.send_json({"ok": True})


Handler.put_texture = _put_texture


def _put_disc_texture(handler, u):
    """From the in-game anvil button: copy the disc's song into a new code and attach the texture.
    No password needed: it can only point at songs that already exist as disc tags."""
    key = parse_qs(u.query).get("key", [""])[0]
    if not KEY_RE.match(key):
        return handler.send_json({"error": "bad disc"}, 400)
    try:
        body = handler.read_body(512 * 1024)
    except ValueError:
        return handler.send_json({"error": "texture too big"}, 400)
    size = png_size(body)
    if not size:
        return handler.send_json({"error": "texture must be a PNG"}, 400)
    w, h = size
    if w > MAX_TEXTURE or h > MAX_TEXTURE or w != h:
        return handler.send_json({"error": f"must be square, max {MAX_TEXTURE}x{MAX_TEXTURE}"}, 400)
    if key.startswith("c_"):
        entry = dict(load_codes().get(key[2:], {}))
        if not entry:
            return handler.send_json({"error": "unknown code"}, 404)
    else:
        entry = {"url": source_for(key)[1]}
    with titles_lock:
        known_title = load_titles().get(key)
    if known_title and "title" not in entry:
        entry["title"] = known_title
    code = save_code(entry)
    (TEXTURE_DIR / f"c_{code}.png").write_bytes(body)
    return handler.send_json({"code": code})


Handler.put_disc_texture = _put_disc_texture


def _safe_convert(key: str):
    if not SERVER_CONVERT:
        return
    try:
        convert(key)
    except Exception as e:
        print(f"background convert {key} failed: {e}")


if __name__ == "__main__":
    for tool in ("ffmpeg", "ffprobe", "yt-dlp"):
        if not shutil.which(tool):
            print(f"WARNING: {tool} not found on PATH")
    print(f"DiscShare backend on :{PORT}, data in {DATA}")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
