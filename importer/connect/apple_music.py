#!/usr/bin/env python3
"""Connecteur Apple Music — listes PUBLIQUES, sans compte ni jeton développeur.

L'API officielle d'Apple exige un jeton de développeur (compte payant) ; la page
publique, elle, embarque la liste complète des pistes dans son bloc
`serialized-server-data`. C'est ce bloc que ce connecteur lit.

Bonus non négligeable : Apple publie les pochettes en 3000×3000 sur un gabarit
`{w}x{h}bb.{f}` — on demande du 1000×1000, très au-dessus des miniatures YouTube
utilisées jusqu'ici pour l'import.
"""
from __future__ import annotations

import json
import re

from .common import clean_title, first_artist, http_get

_URL = re.compile(r"music\.apple\.com/(?:[a-z]{2}/)?(playlist|album)/", re.I)
_SERVER_DATA = re.compile(
    r'<script[^>]*id="serialized-server-data"[^>]*>(.*?)</script>', re.S)


def matches(url: str) -> bool:
    return bool(_URL.search(url or ""))


def fetch(url: str) -> dict:
    m = _URL.search(url)
    html = http_get(url)
    block = _SERVER_DATA.search(html)
    if not block:
        raise RuntimeError("page Apple Music sans liste de pistes (lien privé ou invalide ?)")
    payload = json.loads(block.group(1))

    sections = []
    for entry in payload.get("data") or []:
        sections += (entry.get("data") or {}).get("sections") or []

    def items(kind):
        return [it for s in sections if s.get("itemKind") == kind
                for it in (s.get("items") or [])]

    header = (items("containerDetailHeaderLockup") or [{}])[0]
    cover = _art(header.get("artwork"))
    raw = items("trackLockup")
    if not raw:
        raise RuntimeError("aucune piste dans cette page Apple Music")

    tracks = []
    for i, it in enumerate(raw, 1):
        artists = (it.get("artistName") or "").strip()
        tracks.append({
            "position": i,
            "title": clean_title(it.get("title") or "?"),
            "artist": first_artist(artists),
            "artists": artists,
            "duration_s": int((it.get("duration") or 0) / 1000),
            "external_id": _song_id(it),
            "source_url": _track_url(it),
            "artwork_url": _art(it.get("artwork")) or cover,
            "album": _album(it),
        })

    return {
        "service": "apple",
        "kind": m.group(1).lower(),
        "name": (header.get("title") or "Sans titre").strip(),
        "url": url,
        "artwork_url": cover,
        "tracks": tracks,
    }


def _art(art) -> str:
    """Gabarit {w}x{h}bb.{f} -> 1000x1000 JPEG."""
    try:
        url = art["dictionary"]["url"]
    except (TypeError, KeyError):
        return ""
    return url.replace("{w}", "1000").replace("{h}", "1000").replace("{f}", "jpg")


def _track_url(item: dict) -> str:
    try:
        return item["playAction"]["actionMetrics"]["data"][0]["fields"]["actionUrl"]
    except Exception:
        return ""


def _song_id(item: dict) -> str:
    m = re.search(r"[?&]i=(\d+)", _track_url(item))
    return m.group(1) if m else ""


def _album(item: dict) -> str:
    try:
        title = item["tertiaryLinks"][0]["title"]
    except Exception:
        return ""
    return re.sub(r"\s*-\s*(Single|EP|Album)\s*$", "", title, flags=re.I).strip()
