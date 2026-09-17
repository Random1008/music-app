#!/usr/bin/env python3
"""Connecteur YouTube Music.

Contrairement à Spotify et Apple Music, la liste ne vient pas d'une page HTML
mais de yt-dlp : c'est exactement le même extracteur que celui utilisé pour
télécharger, donc ce qu'on liste est ce qu'on saura récupérer. Et comme yt-dlp
nous donne l'identifiant de chaque vidéo, le téléchargement se fait par URL
directe — pas de recherche floue, pas de mauvais morceau.

Listes PUBLIQUES : aucune connexion nécessaire.
Listes PRIVÉES (« Morceaux likés », list=LM) : il faut les cookies du compte,
exportés dans un fichier et désignés par YTDLP_COOKIES (voir docs/connecteurs.md).
Les cookies restent hors du dépôt et en chmod 600.
"""
from __future__ import annotations

import json
import os
import re
import subprocess

from .common import first_artist, split_artist_title

HERE = os.path.dirname(os.path.abspath(__file__))


def matches(url: str) -> bool:
    u = url or ""
    return "youtube.com" in u and "list=" in u


def _ytdlp() -> str:
    """Le yt-dlp du venv de l'importateur, sinon celui du PATH."""
    local = os.path.join(os.path.dirname(HERE), ".venv", "bin", "yt-dlp")
    return local if os.path.exists(local) else os.environ.get("YTDLP", "yt-dlp")


def fetch(url: str) -> dict:
    cmd = [_ytdlp(), "--js-runtimes", "node", "--flat-playlist",
           "--ignore-errors", "--dump-json"]
    cookies = os.environ.get("YTDLP_COOKIES", "")
    if cookies:
        cmd += ["--cookies", cookies]
    cmd.append(url)

    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
    entries = []
    for line in proc.stdout.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            entries.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    if not entries:
        detail = (proc.stderr or "").strip().splitlines()
        raise RuntimeError("playlist YouTube Music illisible : %s"
                           % (detail[-1] if detail else "aucune entrée"))

    playlist_name = ""
    tracks = []
    for i, e in enumerate(entries, 1):
        playlist_name = playlist_name or (e.get("playlist") or "")
        creator = (e.get("creator") or "").strip()
        fallback = creator or (e.get("channel") or e.get("uploader") or "").strip()
        artist, title = split_artist_title(e.get("title") or "", default_artist=fallback)
        vid = e.get("id") or ""
        tracks.append({
            "position": i,
            "title": title,
            "artist": first_artist(artist),
            "artists": artist,
            "duration_s": int(e.get("duration") or 0),
            "external_id": vid,
            "source_url": "https://music.youtube.com/watch?v=%s" % vid,
            "artwork_url": _thumb(vid),
            "album": "",
        })

    return {
        "service": "youtube",
        "kind": "playlist",
        "name": playlist_name.strip() or "Playlist YouTube Music",
        "url": url,
        "artwork_url": tracks[0]["artwork_url"] if tracks else "",
        "tracks": tracks,
    }


def _thumb(video_id: str) -> str:
    return "https://i.ytimg.com/vi/%s/maxresdefault.jpg" % video_id if video_id else ""
