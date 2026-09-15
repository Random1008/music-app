#!/usr/bin/env python3
"""Importateur Hermes Music.

Lit une liste de pistes (tracks.json, produite par consolidate.py),
cherche chaque morceau sur YouTube, télécharge l'audio, le convertit en MP3
tagué (titre/artiste/album/pochette), le range dans la bibliothèque
(/music/Artists/Artiste/Album/NN - Titre.mp3), puis déclenche un scan Jellyfin.

Usage:
  python3 importer.py [--limit N] [--only "artiste - titre"]

Variables d'environnement:
  MUSIC_LIBRARY_PATH  (défaut ~/Music)
  JELLYFIN_URL        (défaut http://localhost:8096)
  JELLYFIN_API_KEY    clé API Jellyfin pour le scan
  YTDLP               chemin vers yt-dlp (défaut "yt-dlp")
"""
import argparse
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request

BASE = os.environ.get("MUSIC_LIBRARY_PATH", "/srv/music")
ARTISTS_DIR = os.path.join(BASE, "Artists")
HERE = os.path.dirname(os.path.abspath(__file__))
TRACKS_JSON = os.path.join(HERE, "library", "tracks.json")
STATUS_JSON = os.path.join(HERE, "library", "status.json")
YTDLP = os.environ.get("YTDLP", "yt-dlp")
YTDLP_COOKIES = os.environ.get("YTDLP_COOKIES", "")
FFMPEG = "ffmpeg"
JELLYFIN_URL = os.environ.get("JELLYFIN_URL", "http://localhost:8096")
JELLYFIN_API_KEY = os.environ.get("JELLYFIN_API_KEY", "")
SLEEP = float(os.environ.get("IMPORTER_SLEEP", "2"))


def log(msg):
    print(msg, flush=True)


def sanitize(s):
    s = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "", s or "")
    s = re.sub(r"\s+", " ", s).strip().rstrip(".")
    return s or "Unknown"


def load_status():
    if os.path.exists(STATUS_JSON):
        with open(STATUS_JSON, encoding="utf-8") as f:
            return json.load(f)
    return {}


def save_status(status):
    with open(STATUS_JSON, "w", encoding="utf-8") as f:
        json.dump(status, f, ensure_ascii=False, indent=1)


def first_artist(track):
    artists = track.get("artists") or track.get("album_artist") or "Unknown"
    return artists.split(",")[0].strip()


def target_path(track):
    artist = first_artist(track)
    album = track.get("album") or "Singles"
    num = int(track.get("track_num") or "1")
    fname = f"{num:02d} - {sanitize(track['title'])}.mp3"
    return os.path.join(ARTISTS_DIR, sanitize(artist), sanitize(album), fname)


def _ytdlp_base(cookies):
    base = [YTDLP, "--js-runtimes", "node", "--no-playlist"]
    if cookies:
        base += ["--cookies", cookies]
    return base


def _info(target, cookies):
    """target = URL directe ou 'ytsearch1:...' ; renvoie (titre, url) ou None."""
    p1 = subprocess.run(
        _ytdlp_base(cookies) + ["--skip-download",
                                "--print", "%(title)s",
                                "--print", "%(webpage_url)s", target],
        capture_output=True, text=True, timeout=90)
    lines = [l for l in p1.stdout.splitlines() if l.strip()]
    url = lines[-1].strip() if lines else ""
    if p1.returncode == 0 and url.startswith("http"):
        return (lines[-2].strip() if len(lines) >= 2 else "?"), url
    return None


def download_audio(artist, title, tmpdir, url=None):
    cookies = YTDLP_COOKIES
    yt_title = yt_url = None
    if url:  # URL fournie (override) : pas de recherche
        res = _info(url, cookies)
        yt_title, yt_url = res if res else (url, url)
    else:  # recherche : variantes, précise puis plus large
        for q in (f"{artist} - {title}", f"{title} {artist} audio"):
            for attempt in (1, 2):
                res = _info(f"ytsearch1:{q}", cookies)
                if res:
                    yt_title, yt_url = res
                    break
                time.sleep(3 * attempt)
            if yt_url:
                break
    if not yt_url:
        raise RuntimeError("recherche sans résultat (toutes variantes épuisées)")
    # Télécharger l'audio
    out_tmpl = os.path.join(tmpdir, "audio.%(ext)s")
    p2 = subprocess.run(
        _ytdlp_base(cookies) + ["-f", "bestaudio", "--retries", "3",
                                "--socket-timeout", "20",
                                "-o", out_tmpl, yt_url],
        capture_output=True, text=True, timeout=240)
    if p2.returncode != 0:
        raise RuntimeError(f"yt-dlp download: {p2.stderr.strip()[-250:]}")
    files = [f for f in glob.glob(os.path.join(tmpdir, "audio.*"))
             if not f.endswith(".part")]
    if not files:
        raise RuntimeError("aucun fichier audio produit")
    return files[0], yt_title, yt_url


def download_cover(url, tmpdir):
    if not url:
        return None
    p = os.path.join(tmpdir, "cover.jpg")
    try:
        urllib.request.urlretrieve(url, p)
        return p if os.path.getsize(p) > 0 else None
    except Exception:
        return None


def convert_and_tag(audio, cover, track, out):
    title = track["title"]
    artist = track.get("artists") or track.get("album_artist") or "Unknown"
    album_artist = track.get("album_artist") or artist
    album = track.get("album") or "Singles"
    year = (track.get("release_date") or "")[:4]
    num = track.get("track_num") or "1"

    cmd = [FFMPEG, "-y", "-hide_banner", "-loglevel", "error", "-i", audio]
    if cover:
        cmd += ["-i", cover]
    cmd += ["-map", "0:a"]
    if cover:
        cmd += ["-map", "1:v", "-c:v", "copy", "-disposition:v", "attached_pic"]
    cmd += ["-c:a", "libmp3lame", "-b:a", "320k", "-id3v2_version", "3",
            "-metadata", f"title={title}",
            "-metadata", f"artist={artist}",
            "-metadata", f"album={album}",
            "-metadata", f"album_artist={album_artist}",
            "-metadata", f"track={num}"]
    if year:
        cmd += ["-metadata", f"date={year}"]
    if cover:
        cmd += ["-metadata:s:v", "title=Album cover",
                "-metadata:s:v", "comment=Cover (front)"]
    cmd += [out]
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=240)
    if p.returncode != 0:
        raise RuntimeError(f"ffmpeg: {p.stderr.strip()[-300:]}")


def scan_jellyfin():
    if not JELLYFIN_API_KEY:
        log("[scan] pas de clé API Jellyfin -> scan sauté")
        return
    req = urllib.request.Request(
        f"{JELLYFIN_URL}/Library/Refresh", method="POST",
        headers={"Authorization": f'MediaBrowser Token="{JELLYFIN_API_KEY}"'})
    try:
        urllib.request.urlopen(req, timeout=30)
        log("[scan] scan Jellyfin déclenché")
    except Exception as e:
        log(f"[scan] échec scan Jellyfin: {e}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0, help="traiter N pistes max")
    ap.add_argument("--only", help="filtre partiel sur 'artiste - titre'")
    args = ap.parse_args()

    with open(TRACKS_JSON, encoding="utf-8") as f:
        tracks = json.load(f)
    status = load_status()
    overrides = {}
    ov_path = os.path.join(HERE, "library", "overrides.json")
    if os.path.exists(ov_path):
        with open(ov_path, encoding="utf-8") as f:
            overrides = json.load(f)
        log(f"overrides chargés: {len(overrides)}")

    todo = tracks
    if args.only:
        q = args.only.lower()
        todo = [t for t in tracks
                if q in f"{first_artist(t)} - {t['title']}".lower()]
    if args.limit:
        todo = todo[:args.limit]

    log(f"pistes sélectionnées: {len(todo)} (total bibliothèque: {len(tracks)})")
    ok = fail = skip = 0
    for i, t in enumerate(todo, 1):
        uri = t["spotify_uri"]
        st = status.get(uri, {})
        out = target_path(t)
        if st.get("state") == "done" or os.path.exists(out):
            if st.get("state") != "done":
                status[uri] = {"state": "done", "output": out, "note": "déjà présent"}
            skip += 1
            continue
        artist = first_artist(t)
        query = f"{artist} - {t['title']}"
        tmpdir = tempfile.mkdtemp(prefix="imp_")
        try:
            audio, yt_title, yt_url = download_audio(artist, t["title"], tmpdir,
                                                     url=overrides.get(uri))
            cover = download_cover(t.get("image_url"), tmpdir)
            os.makedirs(os.path.dirname(out), exist_ok=True)
            convert_and_tag(audio, cover, t, out)
            status[uri] = {"state": "done", "output": out,
                           "youtube": yt_url, "yt_title": yt_title}
            ok += 1
            log(f"[{i}/{len(todo)}] OK   {query}  <-  {yt_title[:60]}")
        except Exception as e:
            status[uri] = {"state": "failed", "error": str(e)[:500]}
            fail += 1
            log(f"[{i}/{len(todo)}] FAIL {query}  ->  {str(e)[:140]}")
        finally:
            shutil.rmtree(tmpdir, ignore_errors=True)
        if i % 10 == 0:
            save_status(status)
        time.sleep(SLEEP)
    save_status(status)
    log(f"\n=== RÉSUMÉ: {ok} ok | {fail} échecs | {skip} ignorés ===")
    scan_jellyfin()


if __name__ == "__main__":
    main()
