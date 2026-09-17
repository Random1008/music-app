#!/usr/bin/env python3
"""Connecteurs de playlists externes : Spotify, Apple Music, YouTube Music.

Chaque connecteur expose deux fonctions :

    matches(url) -> bool
    fetch(url)   -> {
        service   : 'spotify' | 'apple' | 'youtube'
        kind      : 'playlist' | 'album'
        name      : nom de la liste chez le service
        url       : URL d'origine
        artwork_url : pochette de la liste
        tracks    : [{position, title, artist, artists, duration_s,
                      external_id, source_url, artwork_url}]
    }

Règle tenue par les trois : on ne lit QUE des listes publiques, sans compte et
sans contourner quoi que ce soit. Les bibliothèques personnelles demandent un
compte ; c'est possible pour YouTube Music (cookies), impossible pour Apple Music
(jeton développeur payant) et pour Spotify (jeton OAuth). Le détail et les
solutions de repli sont dans docs/connecteurs.md.

Usage :
    from connect import fetch
    playlist = fetch('https://open.spotify.com/playlist/...')
"""
from __future__ import annotations

from . import apple_music, spotify, youtube_music
from .common import clean_title, first_artist, fmt_duration, norm_key, split_artist_title

# L'ordre compte peu (les URL des trois services sont disjointes), mais YouTube
# en dernier : son test est le plus permissif.
SERVICES = (spotify, apple_music, youtube_music)

SERVICE_LABELS = {
    "spotify": "Spotify",
    "apple": "Apple Music",
    "youtube": "YouTube Music",
}


def detect(url: str):
    for service in SERVICES:
        if service.matches(url):
            return service
    return None


def fetch(url: str) -> dict:
    service = detect(url)
    if service is None:
        raise ValueError(
            "lien non reconnu : attendu une playlist ou un album "
            "Spotify, Apple Music ou YouTube Music")
    return service.fetch(url)


__all__ = ["SERVICES", "SERVICE_LABELS", "detect", "fetch", "clean_title",
           "first_artist", "fmt_duration", "norm_key", "split_artist_title"]
