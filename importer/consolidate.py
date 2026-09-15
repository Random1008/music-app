#!/usr/bin/env python3
"""Consolide les CSV Exportify en une liste unique de pistes (tracks.json)."""
import csv, json, glob, os

SRC = os.path.expanduser(os.environ.get("SPOTIFY_CSV_DIR", "~/Downloads"))
HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = os.path.join(HERE, "library")
OUT = os.path.join(OUT_DIR, "tracks.json")

def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    seen = {}
    files = sorted(glob.glob(os.path.join(SRC, "*.csv")))
    total = 0
    for f in files:
        with open(f, encoding="utf-8-sig") as fh:
            r = csv.DictReader(fh)
            for row in r:
                uri = (row.get("Track URI") or "").strip()
                if not uri:
                    continue
                total += 1
                track = {
                    "spotify_uri": uri,
                    "title": (row.get("Track Name") or "").strip(),
                    "artists": (row.get("Artist Name(s)") or "").strip(),
                    "album": (row.get("Album Name") or "").strip(),
                    "album_artist": (row.get("Album Artist Name(s)") or "").strip(),
                    "release_date": (row.get("Album Release Date") or "").strip(),
                    "image_url": (row.get("Album Image URL") or "").strip(),
                    "disc": (row.get("Disc Number") or "").strip(),
                    "track_num": (row.get("Track Number") or "").strip(),
                    "duration_ms": (row.get("Track Duration (ms)") or "").strip(),
                    "isrc": (row.get("ISRC") or "").strip(),
                    "source_playlist": os.path.basename(f).replace(".csv", ""),
                }
                if uri not in seen:
                    seen[uri] = track
    tracks = list(seen.values())
    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(tracks, fh, ensure_ascii=False, indent=1)
    print(f"fichiers lus: {len(files)} | lignes totales: {total} | pistes uniques: {len(tracks)}")
    print(f"sortie: {OUT}")

if __name__ == "__main__":
    main()
