#!/usr/bin/env python3
"""Corrige les tags des morceaux importés depuis YouTube.

LE PROBLÈME
L'import initial ne lisait que le TITRE de la vidéo YouTube (extraction
« plate »). D'où des morceaux étiquetés « JAWNY - Honeypie Animation MV (Full
Version by SeanWay Studio) #animation @JAWNY », ou un artiste déduit d'un
découpage sur « - » alors que la vidéo n'a pas cette forme.

LA SOURCE DE VÉRITÉ
YouTube Music expose des métadonnées OFFICIELLES quand la vidéo correspond à une
vraie sortie musicale : `track`, `artist`, `album`, `release_date`. Vérifié :

    truth yandere^^ (hardtekk slowed)  -> artist=akkiemi, album=truth yandere^^ (hardtekk remix), 2026
    GIVE ME EVERYTHING HARDTEKK        -> artist=ANDONIS, coolbillyy, … , 2026

Quand la vidéo n'est qu'un envoi YouTube (un ré-upload « slowed + reverb », une
animation de fan), ces champs sont ABSENTS : on garde alors le découpage déduit du
titre, en retirant seulement les mentions qui ne font jamais partie d'un titre
(hashtags, @mentions, « (Official Video) », « (Lyrics) »). On ne touche JAMAIS à
« slowed », « reverb », « remix », « hardtekk » : ce sont des versions
différentes, les fusionner serait une perte.

Usage :
    tools/fix-youtube-tags.py --fetch     # interroge YouTube (long, mis en cache)
    tools/fix-youtube-tags.py             # montre avant/après, ne change rien
    tools/fix-youtube-tags.py --apply     # réécrit les tags et déplace si besoin

Aucun retéléchargement : seule l'étiquette du fichier est réécrite (ffmpeg -c copy).
Les doublons sont refusés, pas créés.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import unicodedata

HERE = os.path.dirname(os.path.abspath(__file__))
IMPORTER = os.path.join(os.path.dirname(HERE), "importer")
sys.path.insert(0, IMPORTER)

import jellyfin as jf  # noqa: E402  (charge .env)
from connect.common import norm_key  # noqa: E402

import importer as imp  # noqa: E402

STATUS = os.path.join(IMPORTER, "library", "youtube_status.json")
CACHE = os.path.join(IMPORTER, "library", "youtube_metadata.json")
YTDLP = os.path.join(IMPORTER, ".venv", "bin", "yt-dlp")

# Mentions qui ne font jamais partie d'un titre. Volontairement ABSENTES de cette
# liste : slowed, reverb, remix, hardtekk, sped up, version — ce sont des
# variantes du morceau, pas du bruit.
BRUIT = re.compile(
    r"[\(\[\{][^\)\]\}]*(official|officiel|clip|vidéo|audio|lyrics?|paroles|"
    r"visuali[sz]er|animation|full version|m/v)[^\)\]\}]*[\)\]\}]"
    r"|#\w+|@[\w.-]+", re.I)

# Variantes du morceau, à NE PAS perdre même quand elles se trouvent dans un
# crochet jugé bruyant : « [Slowed + Lyrics] » doit devenir « (Slowed) », pas
# disparaître. C'est une information sur le morceau, pas du bruit.
VERSIONS = re.compile(r"slowed|reverb|remix|sped\s*up|hardtekk|nightcore|"
                      r"instrumental|extended|super", re.I)


def clean_title(title: str) -> str:
    """Retire le bruit du titre, en conservant les variantes (« slowed », …)."""
    marques = []
    for trouve in BRUIT.finditer(title or ""):
        for mot in VERSIONS.findall(trouve.group(0)):
            marques.append(" ".join(mot.split()).title())
    t = BRUIT.sub(" ", title or "")
    t = re.sub(r"\s*[\(\[]\s*[\)\]]", " ", t)
    t = re.sub(r"\s+", " ", t).strip(" -–—|_")
    t = t or title or "?"
    if marques:
        suffixe = " + ".join(dict.fromkeys(marques))
        if suffixe.lower() not in t.lower():
            t = "%s (%s)" % (t, suffixe)
    return t


def log(msg):
    print(msg, flush=True)


def fetch_meta(video_id: str) -> dict:
    """Métadonnées complètes d'une vidéo (une seule requête réseau)."""
    url = "https://music.youtube.com/watch?v=%s" % video_id
    res = subprocess.run(
        [YTDLP, "--js-runtimes", "node", "--skip-download", "--dump-json", url],
        capture_output=True, text=True, timeout=180)
    if res.returncode != 0 or not res.stdout.strip():
        return {}
    try:
        meta = json.loads(res.stdout.splitlines()[0])
    except json.JSONDecodeError:
        return {}
    return {k: meta.get(k) for k in
            ("track", "artist", "album", "release_date", "release_year",
             "title", "uploader", "channel", "duration") if meta.get(k)}


def load_cache() -> dict:
    if os.path.exists(CACHE):
        with open(CACHE, encoding="utf-8") as fh:
            return json.load(fh)
    return {}


def save_cache(cache: dict) -> None:
    with open(CACHE, "w", encoding="utf-8") as fh:
        json.dump(cache, fh, ensure_ascii=False, indent=1)


def entries() -> list:
    """Les morceaux réellement importés : (identifiant vidéo, chemin du fichier)."""
    with open(STATUS, encoding="utf-8") as fh:
        status = json.load(fh)
    out = []
    for vid, info in status.items():
        if info.get("state") != "done":
            continue
        path = info.get("output") or ""
        if path and os.path.exists(path):
            out.append((vid, path))
    return out


def de_stylise(texte: str) -> str:
    """« 𝙏𝙖𝙩𝙡𝙞 » -> « Tatli », « ＬＵＭＩ » -> « LUMI ».

    Ces caractères ne sont pas un nom : c'est une police détournée
    (alphabets mathématiques Unicode, formes pleine largeur). Les normaliser rend
    les morceaux trouvables par la recherche, sans toucher aux accents réels :
    on ne convertit QUE ces plages-là.
    """
    sortie = []
    for c in texte or "":
        code = ord(c)
        if 0x1D400 <= code <= 0x1D7FF or 0xFF01 <= code <= 0xFF5E:
            sortie.append(unicodedata.normalize("NFKC", c))
        else:
            sortie.append(c)
    return "".join(sortie)


def propose(meta: dict, current: dict) -> dict | None:
    """Tags cibles d'après les métadonnées officielles, sinon nettoyage du titre.

    On ne repart JAMAIS du titre brut de la vidéo pour les morceaux sans
    métadonnées officielles : ce titre contient encore « Artiste - », que l'import
    initial avait justement retiré. On nettoie donc le titre DÉJÀ stocké, sans
    réintroduire d'artiste — sinon on dégrade au lieu de corriger.
    """
    if meta.get("track") and meta.get("artist"):
        artists = [a.strip() for a in str(meta["artist"]).split(",") if a.strip()]
        return {
            "title": de_stylise(meta["track"].strip()),
            "artist": de_stylise(artists[0]),
            "artists": ", ".join(de_stylise(a) for a in artists),
            "album": de_stylise((meta.get("album") or "Singles").strip()),
            "year": str(meta.get("release_date") or meta.get("release_year") or "")[:4],
            "source": "catalogue YouTube Music",
        }

    titre = clean_title(de_stylise(current.get("title") or ""))
    artiste = de_stylise(current.get("artist") or "")
    if titre == current.get("title") and artiste == current.get("artist"):
        return None
    return {
        "title": titre,
        "artist": artiste,
        "artists": artiste,
        "album": current.get("album") or "Singles",
        "year": current.get("year") or "",
        "source": "nettoyage du titre",
    }


def lire_tags(path: str) -> dict:
    res = subprocess.run(
        ["ffprobe", "-v", "quiet", "-print_format", "json", "-show_format", path],
        capture_output=True, text=True, timeout=60)
    try:
        tags = json.loads(res.stdout)["format"].get("tags", {})
    except Exception:  # noqa: BLE001
        tags = {}
    bas = {k.lower(): v for k, v in tags.items()}
    return {
        "title": bas.get("title") or os.path.splitext(os.path.basename(path))[0],
        "artist": bas.get("artist") or bas.get("album_artist") or "",
        "artists": bas.get("artist") or "",
        "album": bas.get("album") or "Singles",
        "year": (bas.get("date") or "")[:4],
    }


def chemin_cible(tags: dict) -> str:
    return imp.target_path({
        "title": tags["title"],
        "artists": tags.get("artists") or tags["artist"],
        "album": tags.get("album") or "Singles",
        "track_num": "1",
    })


def retag(path: str, tags: dict) -> None:
    """Réécrit les étiquettes sans réencoder l'audio (copie du flux)."""
    tmp = path + ".retag.mp3"
    cmd = ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", path,
           "-map", "0", "-c", "copy",
           "-metadata", "title=%s" % tags["title"],
           "-metadata", "artist=%s" % (tags.get("artists") or tags["artist"]),
           "-metadata", "album_artist=%s" % tags["artist"],
           "-metadata", "album=%s" % (tags.get("album") or "Singles")]
    if tags.get("year"):
        cmd += ["-metadata", "date=%s" % tags["year"]]
    cmd += ["-id3v2_version", "3", tmp]
    res = subprocess.run(cmd, capture_output=True, text=True, timeout=240)
    if res.returncode != 0:
        if os.path.exists(tmp):
            os.remove(tmp)
        raise RuntimeError("ffmpeg : %s" % (res.stderr or "")[-200:])
    shutil.move(tmp, path)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--fetch", action="store_true", help="interroger YouTube (long)")
    ap.add_argument("--apply", action="store_true", help="appliquer les corrections")
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()

    items = entries()
    if args.limit:
        items = items[:args.limit]
    log("%d morceaux importés depuis YouTube" % len(items))

    cache = load_cache()
    if args.fetch:
        manquants = [v for v, _ in items if v not in cache]
        log("métadonnées à récupérer : %d (déjà en cache : %d)"
            % (len(manquants), len(cache)))
        for i, vid in enumerate(manquants, 1):
            cache[vid] = fetch_meta(vid)
            if i % 5 == 0 or i == len(manquants):
                save_cache(cache)
                log("  %d/%d" % (i, len(manquants)))
            time.sleep(0.8)
        save_cache(cache)
        avec = sum(1 for v in cache.values() if v.get("track") and v.get("artist"))
        log("terminé : %d vidéos en cache, dont %d avec métadonnées officielles"
            % (len(cache), avec))

    if not args.fetch:
        if not cache:
            log("aucune métadonnée en cache : lance d'abord --fetch")
            return 1

        index = jf.library_index()
        corrections, inchangees, sansmeta, collisions = [], 0, 0, []

        for vid, path in items:
            meta = cache.get(vid) or {}
            current = lire_tags(path)
            if not meta.get("track"):
                sansmeta += 1
            cible = propose(meta, current)
            if not cible:
                inchangees += 1
                continue
            if (cible["title"] == current["title"]
                    and cible["artist"] == current["artist"]
                    and (cible.get("album") or "Singles") == (current.get("album") or "Singles")):
                inchangees += 1
                continue
            nouveau_chemin = chemin_cible(cible)
            cle = norm_key(cible.get("artists") or cible["artist"], cible["title"])
            autre = index.get(cle)
            # Jellyfin voit les chemins depuis l'intérieur du conteneur
            # (/music/...) alors que nous sommes sur l'hôte (/home/.../Music/...) :
            # on compare donc les NOMS de fichier, pas les chemins complets.
            autre_nom = os.path.basename((autre or {}).get("Path") or "")
            if autre and autre_nom != os.path.basename(path):
                collisions.append((path, cible, autre.get("Path")))
            corrections.append((vid, path, current, cible, nouveau_chemin))

        log("")
        log("=== corrections proposées : %d ===" % len(corrections))
        for vid, path, current, cible, nouveau in corrections:
            log("")
            log("  %s  [%s]" % (os.path.basename(path), cible["source"]))
            log("    artiste : %-32s -> %s" % (current["artist"][:32], cible["artist"]))
            log("    titre   : %-32s -> %s" % (current["title"][:32], cible["title"]))
            if (current.get("album") or "") != (cible.get("album") or ""):
                log("    album   : %-32s -> %s" % (current.get("album") or "-", cible["album"]))
            if current.get("year") != cible.get("year") and cible.get("year"):
                log("    année   : %-32s -> %s" % (current.get("year") or "-", cible["year"]))
            if nouveau != path:
                log("    fichier : déplacé vers %s" % os.path.relpath(nouveau, os.path.dirname(imp.BASE)))

        log("")
        log("  inchangés : %d | sans métadonnées officielles : %d | conflits : %d"
            % (inchangees, sansmeta, len(collisions)))
        for path, cible, autre in collisions:
            log("  CONFLIT (ignoré) : %s -> déjà présent sous %s"
                % (os.path.basename(path), autre))

        if not args.apply:
            log("")
            log("Aucun fichier modifié (relancer avec --apply pour appliquer).")
            return 0

        log("")
        log("=== application ===")
        # Trace écrite avant/après : si une correction déplaisait, on sait
        # exactement quoi remettre, sans dépendre du souvenir de cette sortie.
        journal = os.path.join(IMPORTER, "library", "tag_corrections.json")
        historique = []
        if os.path.exists(journal):
            with open(journal, encoding="utf-8") as fh:
                historique = json.load(fh)
        lot = {"date": time.strftime("%Y-%m-%d %H:%M:%S"), "corrections": []}

        ok = echecs = 0
        for vid, path, current, cible, nouveau in corrections:
            if any(path == c[0] for c in collisions):
                log("  ignoré (conflit) : %s" % os.path.basename(path))
                continue
            try:
                retag(path, cible)
                if nouveau != path:
                    os.makedirs(os.path.dirname(nouveau), exist_ok=True)
                    shutil.move(path, nouveau)
                    # On retire les dossiers devenus vides (Singles, puis l'artiste
                    # s'il n'a plus rien) : sinon la bibliothèque se remplit de
                    # dossiers fantômes après chaque correction.
                    for dossier in (os.path.dirname(path),
                                    os.path.dirname(os.path.dirname(path))):
                        if os.path.isdir(dossier) and not os.listdir(dossier):
                            os.rmdir(dossier)
                lot["corrections"].append({"video": vid, "avant": current,
                                           "apres": cible, "fichier_avant": path,
                                           "fichier_apres": nouveau})
                ok += 1
            except Exception as exc:  # noqa: BLE001
                echecs += 1
                log("  ÉCHEC %s : %s" % (os.path.basename(path), str(exc)[:120]))
        historique.append(lot)
        with open(journal, "w", encoding="utf-8") as fh:
            json.dump(historique, fh, ensure_ascii=False, indent=1)
        log("  %d corrigé(s), %d échec(s)" % (ok, echecs))
        log("  trace écrite dans importer/library/tag_corrections.json")

        jf.scan()
        log("")
        log("Scan Jellyfin déclenché. Vérifie ensuite avec :")
        log("  python3 tools/check-jellyfin-api.py   (et l'application)")
        return 0 if not echecs else 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
