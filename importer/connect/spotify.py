#!/usr/bin/env python3
"""Connecteur Spotify — listes PUBLIQUES, sans compte.

L'API Web de Spotify exige un jeton OAuth, impossible à obtenir ici (le tableau
de bord développeur n'est pas accessible au propriétaire du compte). En revanche
la page d'intégration `/embed/` renvoyée par open.spotify.com embarque la liste
COMPLÈTE des pistes dans son bloc `__NEXT_DATA__`. C'est elle que ce connecteur lit.

Portée : playlists et albums publics. Les listes privées (Morceaux likés,
bibliothèque personnelle) restent hors de portée sans jeton — voir
docs/connecteurs.md.
"""
from __future__ import annotations

import json
import re

from .common import clean_title, first_artist, http_get

_URL = re.compile(r"open\.spotify\.com/(?:intl-[a-z]{2}/)?(playlist|album)/([A-Za-z0-9]+)")
_NEXT = re.compile(r'<script id="__NEXT_DATA__"[^>]*>(.*?)</script>', re.S)


def matches(url: str) -> bool:
    return bool(_URL.search(url or ""))


def fetch(url: str) -> dict:
    m = _URL.search(url)
    if not m:
        raise ValueError("lien Spotify non reconnu : %s" % url)
    kind, spotify_id = m.group(1), m.group(2)
    html = http_get("https://open.spotify.com/embed/%s/%s" % (kind, spotify_id))
    block = _NEXT.search(html)
    if not block:
        raise RuntimeError("page Spotify sans liste de pistes (lien privé ou invalide ?)")
    entity = json.loads(block.group(1))["props"]["pageProps"]["state"]["data"]["entity"]
    raw = entity.get("trackList") or []
    if not raw:
        raise RuntimeError("aucune piste dans cette playlist Spotify")

    tracks = []
    for i, t in enumerate(raw, 1):
        artists = (t.get("subtitle") or "").strip()
        tid = (t.get("uri") or "").split(":")[-1]
        tracks.append({
            "position": i,
            "title": clean_title(t.get("title") or "?"),
            "artist": first_artist(artists),
            "artists": artists,
            "duration_s": int((t.get("duration") or 0) / 1000),
            "external_id": tid,
            "source_url": "https://open.spotify.com/track/%s" % tid,
            "artwork_url": "",
        })

    return {
        "service": "spotify",
        "kind": kind,
        "name": (entity.get("name") or entity.get("title") or spotify_id).strip(),
        "url": url,
        "artwork_url": _cover(entity),
        "tracks": tracks,
    }


def _cover(entity: dict) -> str:
    try:
        sources = entity["coverArt"]["sources"]
        return max(sources, key=lambda s: s.get("width") or 0)["url"]
    except Exception:
        return ""
