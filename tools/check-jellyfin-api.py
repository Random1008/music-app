#!/usr/bin/env python3
"""Vérifie que le serveur Jellyfin accepte réellement les appels de l'application.

Couvre les routes qu'on ne peut pas valider sans téléphone : playlists
(création, ajout, réordonnancement, retrait, renommage, suppression), reprise de
lecture, historique, favoris, et la déclaration des lectures
(/Sessions/Playing*), dont un champ mal nommé ferait un no-op silencieux.

PIÈGE IMPORTANT, découvert en écrivant ce script : les routes d'écriture de
playlists ont besoin d'un JETON UTILISATEUR. Appelées avec la clé d'API, elles
répondent 400 « Error processing request », ce qui ressemble à un bug de code
alors que le même appel passe en 204 avec un jeton utilisateur. Le script crée
donc un compte jetable, teste avec SON jeton, puis le supprime.

Usage :  python3 tools/check-jellyfin-api.py
Lit JELLYFIN_API_KEY dans .env (jamais affichée). Nettoie derrière lui.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("JELLYFIN_CHECK_URL", "http://127.0.0.1:8096")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TMP_USER = "zz-hermes-check"

failures: list[str] = []


def read_key() -> str:
    env = os.path.join(ROOT, ".env")
    try:
        with open(env, encoding="utf-8") as fh:
            for line in fh:
                if line.strip().startswith("JELLYFIN_API_KEY="):
                    return line.split("=", 1)[1].strip().strip('"').strip("'")
    except FileNotFoundError:
        pass
    sys.exit(f"JELLYFIN_API_KEY introuvable (attendu dans {env})")


KEY = read_key()


def headers(token: str) -> dict[str, str]:
    return {
        "Authorization": (
            f'MediaBrowser Token="{token}", Client="Hermes Music Check", '
            f'Device="CLI", DeviceId="hermes-check", Version="0.6.0"'
        ),
        "Content-Type": "application/json",
    }


def call(method: str, path: str, body=None, token: str = KEY):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, headers=headers(token), method=method)
    try:
        with urllib.request.urlopen(req, timeout=25) as resp:
            raw = resp.read().decode()
            return resp.status, (json.loads(raw) if raw.strip() else None)
    except urllib.error.HTTPError as err:
        try:
            return err.code, json.loads(err.read().decode())
        except Exception:
            return err.code, None


def check(label: str, ok: bool, detail: str = "") -> None:
    mark = "OK   " if ok else "ÉCHEC"
    suffix = f" — {detail}" if detail else ""
    print(f"  [{mark}] {label}{suffix}")
    if not ok:
        failures.append(label)


user_id = None
playlist_id = None

try:
    print("=== Compte de test (jetable) ===")
    status, created = call("POST", "/Users/New", {"Name": TMP_USER})
    user_id = (created or {}).get("Id")
    check("création du compte de test", status == 200 and bool(user_id), f"HTTP {status}")
    if not user_id:
        sys.exit("impossible de créer le compte de test")

    # Un compte neuf s'authentifie avec un mot de passe vide.
    status, auth = call("POST", "/Users/AuthenticateByName", {"Username": TMP_USER, "Pw": ""})
    token = (auth or {}).get("AccessToken")
    check("authentification par jeton utilisateur", bool(token), f"HTTP {status}")
    if not token:
        sys.exit("impossible d'obtenir un jeton utilisateur")
    token = str(token)

    print("=== Préparation ===")
    status, items = call("GET", f"/Items?IncludeItemTypes=Audio&Recursive=true&Limit=4&UserId={user_id}", token=token)
    tracks = [i["Id"] for i in (items or {}).get("Items", [])]
    check("morceaux disponibles", len(tracks) == 4, f"{len(tracks)} trouvés")
    if len(tracks) < 2:
        sys.exit("pas assez de morceaux pour tester les playlists")

    print("=== Playlists ===")
    status, created = call(
        "POST", "/Playlists",
        {"Name": "ZZ Check", "Ids": [], "MediaType": "Audio", "UserId": user_id},
        token=token,
    )
    playlist_id = (created or {}).get("Id")
    check("POST /Playlists renvoie un Id", status == 200 and bool(playlist_id), f"HTTP {status}")

    status, _ = call("POST", f"/Playlists/{playlist_id}/Items?ids={','.join(tracks)}&userId={user_id}", token=token)
    check("POST /Playlists/{id}/Items (ajout)", status in (200, 204), f"HTTP {status}")

    def entries():
        _, payload = call("GET", f"/Playlists/{playlist_id}/Items?UserId={user_id}", token=token)
        return [(i.get("PlaylistItemId"), i["Id"], i.get("Name")) for i in (payload or {}).get("Items", [])]

    current = entries()
    check("GET /Playlists/{id}/Items renvoie les entrées", len(current) == len(tracks), f"{len(current)} entrées")
    check("chaque entrée porte un PlaylistItemId", all(e[0] for e in current),
          "sans lui, retrait et réordonnancement sont impossibles")

    first_entry, first_name = current[0][0], current[0][2]
    status, body = call("POST", f"/Playlists/{playlist_id}/Items/{first_entry}/Move/1", token=token)
    after = [name for _, _, name in entries()]
    check("Move/{n} réordonne", status in (200, 204) and after[0] != first_name,
          f"« {first_name} » -> position {after.index(first_name) if first_name in after else '?'}")
    if status >= 400:
        print(f"        réponse : {str(body)[:200]}")

    status, _ = call("DELETE", f"/Playlists/{playlist_id}/Items?entryIds={first_entry}", token=token)
    check("DELETE entryIds retire une entrée",
          status in (200, 204) and len(entries()) == len(tracks) - 1,
          f"HTTP {status}")

    # Renommage : on renvoie la liste RÉELLE des morceaux. POST /Playlists/{id}
    # remplace le contenu de la playlist par le champ Ids, donc un appel qui
    # enverrait Ids=[] la viderait sans erreur. Ce test ne vérifiait que le nom,
    # ce qui laissait passer exactement ce piège — d'où le second contrôle.
    current_ids = [e[1] for e in entries()]
    status, body = call("POST", f"/Playlists/{playlist_id}", {
        "Name": "ZZ Check renommée", "Ids": current_ids,
        "Users": [{"UserId": user_id, "CanEdit": True}],
    }, token=token)
    _, payload = call("GET", f"/Items?Ids={playlist_id}&UserId={user_id}", token=token)
    shown = (payload or {}).get("Items", [{}])[0].get("Name")
    check("POST /Playlists/{id} renomme", status in (200, 204) and shown == "ZZ Check renommée",
          f"nom = {shown!r}")
    check("le renommage CONSERVE les morceaux", len(entries()) == len(current_ids),
          f"{len(entries())} entrée(s) après renommage, {len(current_ids)} avant")
    if status >= 400:
        print(f"        réponse : {str(body)[:200]}")

    print("=== Déclaration de lecture ===")
    _, before = call("GET", f"/Items?Ids={tracks[0]}&UserId={user_id}", token=token)
    ud_before = (((before or {}).get("Items") or [{}])[0].get("UserData") or {})
    plays_before = ud_before.get("PlayCount", 0)

    body = {
        "ItemId": tracks[0], "PositionTicks": 0, "IsPaused": False, "IsMuted": False,
        "CanSeek": True, "PlayMethod": "DirectStream", "VolumeLevel": 100,
    }
    status, _ = call("POST", "/Sessions/Playing", body, token=token)
    check("POST /Sessions/Playing accepté", status in (200, 204), f"HTTP {status}")

    _, sessions = call("GET", "/Sessions", token=token)
    mine = [s for s in (sessions or []) if s.get("DeviceId") == "hermes-check"]
    check("le serveur voit la session en lecture", bool(mine),
          f"« {(mine[0].get('NowPlayingItem') or {}).get('Name')} »" if mine else "session absente")

    status, _ = call("POST", "/Sessions/Playing/Progress", {**body, "PositionTicks": 60_000_000}, token=token)
    check("POST /Sessions/Playing/Progress accepté", status in (200, 204), f"HTTP {status}")

    status, _ = call("POST", "/Sessions/Playing/Stopped", body, token=token)
    check("POST /Sessions/Playing/Stopped accepté", status in (200, 204), f"HTTP {status}")

    _, after_items = call("GET", f"/Items?Ids={tracks[0]}&UserId={user_id}", token=token)
    ud_after = (((after_items or {}).get("Items") or [{}])[0].get("UserData") or {})
    # Mesuré, appel par appel, sur un morceau remis à zéro au préalable : c'est le
    # PREMIER rapport (/Sessions/Playing) qui fait passer le morceau en « joué »
    # (PlayCount +1, Played=true), pas le Stopped. C'est le serveur qui décide —
    # un client Jellyfin normal se comporte pareil. Ce n'est donc pas un échec du
    # test, mais le test doit remettre l'état en place, ce qui est vérifié après.
    if ud_after.get("PlayCount", 0) != plays_before:
        print(f"  [INFO ] Jellyfin compte une écoute dès le rapport /Sessions/Playing "
              f"(PlayCount {plays_before} -> {ud_after.get('PlayCount', 0)}). "
              f"L'état est rétabli et vérifié juste après.")

    # Ce test ne doit RIEN laisser derrière lui : on remet l'état d'écoute exact
    # d'avant (Jellyfin peut compter une lecture sur un simple rapport de position).
    status, _ = call("POST", f"/UserItems/{tracks[0]}/UserData", {
        "PlayCount": plays_before,
        "Played": ud_before.get("Played", False),
        "PlaybackPositionTicks": ud_before.get("PlaybackPositionTicks", 0),
        "LastPlayedDate": ud_before.get("LastPlayedDate"),
    }, token=token)
    _, restored = call("GET", f"/Items?Ids={tracks[0]}&UserId={user_id}", token=token)
    ud_restored = (((restored or {}).get("Items") or [{}])[0].get("UserData") or {})
    check("état d'écoute du morceau restauré",
          status in (200, 204) and ud_restored.get("PlayCount", 0) == plays_before,
          f"HTTP {status}, PlayCount {ud_restored.get('PlayCount')}")

    print("=== Requêtes de lecture de l'application ===")
    status, _ = call("GET", f"/Users/{user_id}/Items/Resume?MediaType=Audio&Limit=5", token=token)
    check("Reprendre la lecture (/Items/Resume)", status == 200, f"HTTP {status}")

    status, _ = call("GET", f"/Users/{user_id}/Items?Filters=IsPlayed&IncludeItemTypes=Audio"
                            f"&Recursive=true&SortBy=DatePlayed&SortOrder=Descending&Limit=5",
                     token=token)
    check("Historique (Filters=IsPlayed)", status == 200, f"HTTP {status}")

    status, _ = call("GET", f"/Users/{user_id}/Items?Filters=IsFavorite&Recursive=true"
                            f"&IncludeItemTypes=Audio", token=token)
    check("Favoris (Filters=IsFavorite)", status == 200, f"HTTP {status}")

finally:
    print("=== Nettoyage ===")
    if playlist_id:
        status, _ = call("DELETE", f"/Items/{playlist_id}")
        print(f"  playlist de test supprimée (HTTP {status})")
    if user_id:
        status, _ = call("DELETE", f"/Users/{user_id}")
        print(f"  compte de test supprimé (HTTP {status})")
        _, users = call("GET", "/Users")
        left = [u["Name"] for u in (users or []) if u.get("Name") == TMP_USER]
        check("aucun résidu sur le serveur", not left, str(left))

print()
if failures:
    print(f"BILAN : {len(failures)} ÉCHEC(S) — " + " ; ".join(failures))
    sys.exit(1)
print("BILAN : tout est conforme.")
