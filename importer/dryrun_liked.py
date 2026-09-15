"""Dry-run : que reste-t-il a importer depuis les titres likes YouTube ?

Compare la playlist "Liked Music" avec (a) tracks.json (292 pistes Spotify)
et (b) les fichiers reellement presents sur le NAS.
N'ecrit AUCUN fichier audio : produit seulement la liste des manquants.
"""
import glob
import json
import os
import re
import unicodedata

HERE = os.path.dirname(os.path.abspath(__file__))
YT = os.path.join(HERE, 'library', 'youtube_liked.json')
SP = os.path.join(HERE, 'library', 'tracks.json')
LIB = os.environ.get('MUSIC_LIBRARY_PATH', '~/Music')
OUT = os.path.join(HERE, 'library', 'youtube_new.json')
LST = os.path.join(HERE, 'library', 'youtube_new_list.txt')

DECOR = re.compile(
    r'\b(slowed|reverb|sped\s?up|spedup|hardstyle|hardtekk|hard\s?tek|jumpstyle|remix|remixes|'
    r'official|officiel|video|videoclip|lyrics|lyric|audio|mv|m/v|hd|4k|version|tiktok|tik\s?tok|'
    r'full|extended|edit|nightcore|instrumental|prod|feat|ft)\b')


def norm(s):
    s = unicodedata.normalize('NFKD', s or '').encode('ascii', 'ignore').decode().lower()
    s = re.sub(r'\[[^\]]*\]|\([^)]*\)|\{[^}]*\}', ' ', s)
    s = DECOR.sub(' ', s)
    s = re.sub(r'[^a-z0-9 ]', ' ', s)
    return re.sub(r'\s+', ' ', s).strip()


def key(a, t):
    return (norm(a) + '|' + norm(t)).strip('|')


# --- ce qui existe deja -------------------------------------------------
existing = set()
for f in glob.glob(os.path.join(LIB, 'Artists', '*', '*', '*.mp3')):
    parts = f.split(os.sep)
    album, artist, fname = parts[-2], parts[-3], parts[-1]
    t = re.sub(r'^\d+\s*-\s*', '', os.path.splitext(fname)[0])
    existing.add(norm(t))
    existing.add(key(artist, t))
for t in json.load(open(SP, encoding='utf-8')):
    existing.add(norm(t['title']))
    existing.add(key(t.get('artists', ''), t['title']))
print("references existantes (fichiers + tracks.json) : %d cles" % len(existing))

# --- playlist ----------------------------------------------------------
entries = json.load(open(YT, encoding='utf-8')).get('entries') or []
new, broken, dup = [], [], []
for e in entries:
    vid, title = e.get('id'), (e.get('title') or '').strip()
    if not vid or len(vid) != 11 or not title:
        broken.append(e)
        continue
    ch = (e.get('channel') or e.get('uploader') or '').replace(' - Topic', '').strip()
    m = re.match(r'^(.{2,60}?)\s+[-–—]\s+(.{2,})$', title)
    if m:
        artist, tname = m.group(1).strip(), m.group(2).strip()
    else:
        artist, tname = (ch or 'Inconnu'), title
    if norm(tname) in existing or (artist and key(artist, tname) in existing):
        dup.append(e)
    else:
        new.append({'video_id': vid, 'artist': artist, 'title': tname, 'yt_title': title,
                    'channel': ch})
print("playlist      : %d" % len(entries))
print("deja presents : %d" % len(dup))
print("indisponibles : %d" % len(broken))
print("A IMPORTER    : %d" % len(new))

json.dump(new, open(OUT, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
with open(LST, 'w', encoding='utf-8') as f:
    f.write("A importer depuis 'Liked Music' : %d morceaux\n\n" % len(new))
    for i, n in enumerate(new, 1):
        f.write("%3d. %s - %s\n     https://music.youtube.com/watch?v=%s\n"
                % (i, n['artist'][:45], n['title'][:60], n['video_id']))
print("ecrit:", OUT)
print("ecrit:", LST)
print("\n30 premiers a importer :")
for i, n in enumerate(new[:30], 1):
    print("  %2d. %-30s %s" % (i, n['artist'][:30], n['title'][:52]))
