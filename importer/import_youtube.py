#!/usr/bin/env python3
"""Importe les morceaux manquants de la playlist « Liked Music » de YouTube Music.

Entree  : library/youtube_new.json (produit par dryrun_liked.py)
Sortie  : bibliotheque (Artists/<artiste>/Singles/NN - <titre>.mp3) + scan Jellyfin

Le telechargement se fait par URL DIRECTE (le morceau exact de la playlist,
pas une recherche floue) et SANS cookies : ce sont des videos publiques, le
compte n'est donc pas sollicite pendant le telechargement.

Usage:
  python3 import_youtube.py [--limit N] [--only "texte"]
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)


def load_env():
    """Charge ../.env (sinon JELLYFIN_URL=http://jellyfin:8096, nom Docker injoignable)."""
    path = os.path.join(os.path.dirname(HERE), '.env')
    if os.path.exists(path):
        for line in open(path, encoding='utf-8'):
            line = line.strip()
            if line and not line.startswith('#') and '=' in line:
                k, v = line.split('=', 1)
                os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))


load_env()
os.environ.setdefault('JELLYFIN_URL', 'http://127.0.0.1:8096')
if 'jellyfin:' in os.environ.get('JELLYFIN_URL', ''):
    os.environ['JELLYFIN_URL'] = 'http://127.0.0.1:8096'
# Par defaut on telecharge SANS cookies (videos publiques) : le compte n'est pas
# sollicite pendant le telechargement. Surchargeable via YTDLP_COOKIES si besoin.
os.environ.setdefault('YTDLP_COOKIES', '')

import importer as imp                     # noqa: E402  (reutilise le code valide de l'importateur)

NEW = os.path.join(HERE, 'library', 'youtube_new.json')
STATUS = os.path.join(HERE, 'library', 'youtube_status.json')
SLEEP = float(os.environ.get('IMPORTER_SLEEP', '2'))


def load_status():
    if os.path.exists(STATUS):
        with open(STATUS, encoding='utf-8') as f:
            return json.load(f)
    return {}


def save_status(s):
    with open(STATUS, 'w', encoding='utf-8') as f:
        json.dump(s, f, ensure_ascii=False, indent=1)


def out_path(entry):
    return os.path.join(imp.ARTISTS_DIR, imp.sanitize(entry['artist']),
                        'Singles', '01 - %s.mp3' % imp.sanitize(entry['title']))


def cover_for(vid, tmpdir):
    """Miniature YouTube : maxresdefault sinon hqdefault."""
    for name in ('maxresdefault', 'hqdefault'):
        url = 'https://i.ytimg.com/vi/%s/%s.jpg' % (vid, name)
        try:
            p = os.path.join(tmpdir, 'cover.jpg')
            urllib.request.urlretrieve(url, p)
            if os.path.getsize(p) > 1000:
                return p
        except Exception:
            continue
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--limit', type=int, default=0)
    ap.add_argument('--only', help='filtre partiel sur "artiste - titre"')
    ap.add_argument('--retry-failed', action='store_true',
                    help='ne retraiter que les entrees deja en echec (utile avec YTDLP_COOKIES)')
    args = ap.parse_args()

    entries = json.load(open(NEW, encoding='utf-8'))
    status = load_status()
    todo = entries
    if args.retry_failed:
        todo = [e for e in entries if status.get(e['video_id'], {}).get('state') == 'failed']
        print('mode rattrapage: %d entrees en echec' % len(todo))
    if args.only:
        q = args.only.lower()
        todo = [e for e in entries if q in ('%s - %s' % (e['artist'], e['title'])).lower()]
    if args.limit:
        todo = todo[:args.limit]

    print('a traiter: %d / %d (bibliotheque: %s)' % (len(todo), len(entries), imp.BASE))
    ok = fail = skip = 0
    for i, e in enumerate(todo, 1):
        vid, label = e['video_id'], '%s - %s' % (e['artist'], e['title'])
        st = status.get(vid, {})
        out = out_path(e)
        if st.get('state') == 'done' or os.path.exists(out):
            skip += 1
            print('[%d/%d] SKIP %s' % (i, len(todo), label[:70]))
            continue
        tmp = tempfile.mkdtemp(prefix='ytimp_')
        try:
            audio, yt_title, yt_url = imp.download_audio(
                e['artist'], e['title'], tmp,
                url='https://music.youtube.com/watch?v=%s' % vid)
            os.makedirs(os.path.dirname(out), exist_ok=True)
            imp.convert_and_tag(audio, cover_for(vid, tmp), {
                'title': e['title'], 'artists': e['artist'], 'album_artist': e['artist'],
                'album': 'Singles', 'track_num': '1',
            }, out)
            status[vid] = {'state': 'done', 'output': out, 'yt_title': yt_title, 'yt_url': yt_url}
            ok += 1
            print('[%d/%d] OK   %s' % (i, len(todo), label[:70]))
            print('        -> %s' % out)
        except Exception as ex:
            status[vid] = {'state': 'failed', 'error': str(ex)[:400]}
            fail += 1
            print('[%d/%d] FAIL %s -> %s' % (i, len(todo), label[:60], str(ex)[:120]))
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
        if i % 5 == 0:
            save_status(status)
        time.sleep(SLEEP)
    save_status(status)
    print('\n=== RESUME: %d ok | %d echecs | %d ignores ===' % (ok, fail, skip))
    imp.scan_jellyfin()


if __name__ == '__main__':
    main()
