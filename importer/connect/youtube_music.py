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
    if "youtube.com" not in u and "youtu.be" not in u:
        return False
    return "list=" in u or "watch?v=" in u or "youtu.be/" in u


def single_video(url: str) -> bool:
    """Vrai pour un MORCEAU seul : une vidéo, sans « list= »."""
    u = url or ""
    if "list=" in u:
        return False
    return "watch?v=" in u or "youtu.be/" in u


def _ytdlp() -> str:
    """Le yt-dlp du venv de l'importateur, sinon celui du PATH."""
    local = os.path.join(os.path.dirname(HERE), ".venv", "bin", "yt-dlp")
    return local if os.path.exists(local) else os.environ.get("YTDLP", "yt-dlp")


def _dump(url: str, flat: bool) -> list[dict]:
    """Interroge yt-dlp et renvoie les entrées JSON."""
    cmd = [_ytdlp(), "--js-runtimes", "node", "--ignore-errors", "--dump-json"]
    if flat:
        cmd.append("--flat-playlist")
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
        raise RuntimeError("lien YouTube Music illisible : %s"
                           % (detail[-1] if detail else "aucune entrée"))
    return entries


def _track(e: dict, position: int, single: bool) -> dict:
    """Un morceau normalisé, comme les autres connecteurs."""
    vid = e.get("id") or ""
    creator = (e.get("creator") or "").strip()
    fallback = creator or (e.get("channel") or e.get("uploader") or "").strip()

    if single:
        # Vidéo seule : yt-dlp donne les métadonnées complètes. Pour une vraie
        # sortie musicale, YouTube Music expose le titre/artiste/album OFFICIELS —
        # bien meilleurs que le découpage du titre de la vidéo.
        raw_title = (e.get("track") or e.get("title") or "").strip()
        officiel = (e.get("artist") or "").strip()
        album = (e.get("album") or "").strip()
        if e.get("track") and officiel:
            title, artist = raw_title, officiel
        else:
            # Pas de sortie musicale officielle (ré-upload, montage) : on découpe
            # le titre, comme le fait le reste de la bibliothèque. Le nom de la
            # chaîne ne sert que s'il n'y a rien à découper.
            deduit, title = split_artist_title(raw_title, default_artist=fallback)
            artist = officiel or deduit
    else:
        artist, title = split_artist_title(e.get("title") or "", default_artist=fallback)
        album = ""

    return {
        "position": position,
        "title": title,
        "artist": first_artist(artist),
        "artists": artist,
        "duration_s": int(round(e.get("duration") or 0)),
        "external_id": vid,
        "source_url": "https://music.youtube.com/watch?v=%s" % vid,
        "artwork_url": _thumb(vid),
        "album": album,
    }


def fetch(url: str) -> dict:
    single = single_video(url)
    # Une vidéo seule est interrogée à fond (métadonnées officielles + durée
    # exacte, utile au filtre) ; une playlist reste en lecture « plate », sinon
    # yt-dlp ouvrirait chaque vidéo une par une.
    entries = _dump(url, flat=not single)

    if single:
        track = _track(entries[0], 1, single=True)
        return {
            "service": "youtube",
            "kind": "music",
            "name": track["title"] or "Morceau YouTube Music",
            "url": url,
            "artwork_url": track["artwork_url"],
            "tracks": [track],
        }

    playlist_name = ""
    tracks = []
    for i, e in enumerate(entries, 1):
        playlist_name = playlist_name or (e.get("playlist") or "")
        tracks.append(_track(e, i, single=False))

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
