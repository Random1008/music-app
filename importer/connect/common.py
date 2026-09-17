#!/usr/bin/env python3
"""Outils partagés par les connecteurs de playlists.

Rien d'intelligent ici : normalisation des titres et des clés de comparaison,
et un GET HTTP avec un User-Agent de navigateur (les pages Apple Music et
Spotify refusent les clients qui s'annoncent comme des robots).
"""
from __future__ import annotations

import re
import unicodedata
import urllib.request

UA = ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

# Mentions de clip à retirer d'un titre : « (Official Video) », « [Audio] », « HD »…
# Volontairement SANS live / remaster / version : ce sont d'autres versions du
# morceau, les retirer ferait passer un enregistrement live pour le studio.
_CLIP = re.compile(
    r"[\(\[\{][^\)\]\}]*(?:official|officiel|clip|vid[eé]o|audio|lyric|paroles|"
    r"visuali[sz]er|m/v|full\s*song)[^\)\]\}]*[\)\]\}]", re.I)

_ARTIST_SPLIT = re.compile(r"\s*[,&]\s*|\s+feat\.?\s+|\s+ft\.?\s+|\s+x\s+", re.I)
_FEAT = re.compile(r"\b(feat|ft|featuring)\.?\b.*$", re.I)


def http_get(url: str, timeout: int = 45) -> str:
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept-Language": "fr-FR,fr;q=0.9,en;q=0.8",
        "Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
    })
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read().decode("utf-8", "replace")


def clean_title(title: str) -> str:
    """« APT. (Official Music Video) » -> « APT. »"""
    t = _CLIP.sub(" ", title or "")
    t = re.sub(r"[\(\[]\s*[\)\]]", " ", t)
    t = re.sub(r"\s+", " ", t).strip(" -–—|")
    return t or (title or "?").strip()


def split_artist_title(raw: str, default_artist: str = "") -> tuple[str, str]:
    """« ROSÉ & Bruno Mars - APT. » -> (« ROSÉ & Bruno Mars », « APT. »)."""
    t = clean_title(raw)
    for sep in (" - ", " – ", " — ", " | "):
        if sep in t:
            left, right = t.split(sep, 1)
            if left.strip() and right.strip():
                return left.strip(), right.strip()
    return (default_artist or "?"), t


def first_artist(name: str) -> str:
    """« Sam Fender & Olivia Dean » -> « Sam Fender » (le dossier de rangement)."""
    return _ARTIST_SPLIT.split(name or "", 1)[0].strip() or "?"


def norm_key(artist: str, title: str) -> str:
    """Clé de comparaison tolérante : accents, casse, ponctuation, « feat. »."""
    def n(s: str) -> str:
        s = _FEAT.sub("", (s or "").lower())
        s = unicodedata.normalize("NFKD", s)
        s = "".join(c for c in s if not unicodedata.combining(c))
        return re.sub(r"[^a-z0-9]+", "", s)
    return n(first_artist(artist)) + "|" + n(title)


def fmt_duration(seconds) -> str:
    s = int(seconds or 0)
    return "%d:%02d" % (s // 60, s % 60)
