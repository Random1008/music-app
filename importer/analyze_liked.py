import json
import re
import unicodedata

YT = '~/hermes-music/importer/library/youtube_liked.json'
SP = '~/hermes-music/importer/library/tracks.json'
LST = '~/hermes-music/importer/library/youtube_liked_list.txt'

d = json.load(open(YT))
entries = d.get('entries') or []
spot = json.load(open(SP))
print("Spotify (bibliotheque actuelle):", len(spot), "| YouTube liked:", len(entries))


def norm(s):
    s = unicodedata.normalize('NFKD', (s or '')).encode('ascii', 'ignore').decode().lower()
    s = re.sub(r'\[[^\]]*\]|\([^)]*\)', ' ', s)
    s = re.sub(r'\b(slowed|reverb|sped ?up|hardstyle|hardtekk|remix|official|video|lyrics|audio|mv|hd|version|tiktok|full)\b', ' ', s)
    s = re.sub(r'[^a-z0-9 ]', ' ', s)
    return re.sub(r'\s+', ' ', s).strip()


broken, ok = [], []
for e in entries:
    vid = e.get('id')
    title = (e.get('title') or '').strip()
    if not vid or len(vid) != 11 or not title:
        broken.append(e)
    else:
        ok.append(e)
print("\nvideos exploitables :", len(ok), "| cassees/vides :", len(broken))
for b in broken[:8]:
    print("   cassee:", b.get('id'), '|', repr((b.get('title') or '')[:50]))

spot_titles = {norm(t['title']) for t in spot}
print("\nrecoupements probables avec les 292 pistes Spotify :")
dupes = 0
for e in ok:
    n = norm(e.get('title'))
    hit = n in spot_titles or any(n and (n in st or st in n) for st in spot_titles if len(st) > 6)
    if hit:
        dupes += 1
print("   ~", dupes, "titres deja presents (correspondance approximative)")

with open(LST, 'w', encoding='utf-8') as f:
    f.write("Titres likes YouTube Music -- %d entrees (%d exploitables)\n\n" % (len(entries), len(ok)))
    for i, e in enumerate(entries, 1):
        f.write("%3d. %s\n     https://music.youtube.com/watch?v=%s\n" % (
            i, (e.get('title') or '[VIDEO INDISPONIBLE]')[:95], e.get('id')))
print("\nliste lisible ecrite dans:", LST)
