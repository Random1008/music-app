#!/usr/bin/env python3
"""Client Jellyfin minimal, pour l'import de playlists.

Deux points appris à la dure et respectés ici :

1. Le nom de service Docker (`http://jellyfin:8096`) ne résout pas depuis
   l'extérieur du conteneur : on force 127.0.0.1.
2. Les routes d'ÉCRITURE de playlists refusent la clé d'API (400) et exigent un
   JETON UTILISATEUR. Ce module s'authentifie donc avec un compte réel
   (/Users/AuthenticateByName) et met le jeton en cache dans
   ~/.config/hermes-music/jellyfin-token.json, en 600 et hors du dépôt.

La clé d'API suffit en revanche pour toutes les LECTURES (bibliothèque, scan).
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from connect.common import norm_key  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOKEN_PATH = os.path.expanduser("~/.config/hermes-music/jellyfin-token.json")
CLIENT = "Hermes Music Import"
VERSION = "0.8.0"


def load_env() -> None:
    path = os.path.join(ROOT, ".env")
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))


load_env()
BASE = os.environ.get("JELLYFIN_URL", "http://127.0.0.1:8096")
if "jellyfin:" in BASE:
    BASE = "http://127.0.0.1:8096"
BASE = BASE.rstrip("/")
KEY = os.environ.get("JELLYFIN_API_KEY", "")


def _req(method: str, path: str, body=None, token=None):
    """Renvoie (statut, charge utile). token=None -> clé d'API."""
    token = KEY if token is None else token
    data = json.dumps(body).encode() if body is not None else None
    headers = {
        "Authorization": ('MediaBrowser Token="%s", Client="%s", Device="NAS", '
                          'DeviceId="hermes-import", Version="%s"' % (token, CLIENT, VERSION)),
    }
    if data:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return resp.status, (json.loads(raw) if raw.strip() else None)
    except urllib.error.HTTPError as err:
        raw = err.read().decode("utf-8", "replace")
        try:
            return err.code, json.loads(raw)
        except json.JSONDecodeError:
            return err.code, raw[:300]
    except urllib.error.URLError as err:
        raise RuntimeError("serveur Jellyfin injoignable (%s) : %s" % (BASE, err.reason))


# --------------------------------------------------------------------------- #
# Authentification utilisateur
# --------------------------------------------------------------------------- #

def _load_cache() -> dict:
    try:
        with open(TOKEN_PATH, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, json.JSONDecodeError):
        return {}


def _save_cache(username: str, token: str, user_id: str) -> None:
    os.makedirs(os.path.dirname(TOKEN_PATH), exist_ok=True)
    tmp = TOKEN_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump({"username": username, "token": token, "user_id": user_id}, fh)
    os.chmod(tmp, 0o600)
    os.replace(tmp, TOKEN_PATH)


def authenticate(username: str, password: str):
    """(token, user_id). Réutilise le jeton en cache s'il est encore valide."""
    cached = _load_cache()
    if cached.get("username") == username and cached.get("token"):
        status, _ = _req("GET", "/Users/Me", token=cached["token"])
        if status == 200:
            return cached["token"], cached["user_id"]
    status, payload = _req("POST", "/Users/AuthenticateByName",
                           {"Username": username, "Pw": password}, token="")
    if status != 200 or not isinstance(payload, dict):
        raise RuntimeError("authentification Jellyfin refusée (HTTP %s) — "
                           "vérifie le nom d'utilisateur et le mot de passe" % status)
    token = payload.get("AccessToken")
    user_id = ((payload.get("User") or {}).get("Id"))
    if not token or not user_id:
        raise RuntimeError("réponse d'authentification incomplète")
    _save_cache(username, token, user_id)
    return token, user_id


def forget_token() -> None:
    try:
        os.remove(TOKEN_PATH)
    except OSError:
        pass


# --------------------------------------------------------------------------- #
# Bibliothèque
# --------------------------------------------------------------------------- #

FIELDS = "Artists,Album,AlbumArtist,RunTimeTicks,ProductionYear"


def library_index(user_id: str | None = None) -> dict:
    """{clé normalisée -> morceau Jellyfin} pour toute la musique."""
    index = {}
    start = 0
    while True:
        path = ("/Items?IncludeItemTypes=Audio&Recursive=true&Fields=%s&StartIndex=%d&Limit=500"
                % (FIELDS, start))
        if user_id:
            path += "&UserId=" + user_id
        status, page = _req("GET", path)
        if status != 200 or not isinstance(page, dict):
            raise RuntimeError("lecture de la bibliothèque refusée (HTTP %s)" % status)
        items = page.get("Items") or []
        for it in items:
            artist = it.get("Artists") or [it.get("AlbumArtist")] or [""]
            if isinstance(artist, list):
                artist = artist[0] if artist else ""
            index.setdefault(norm_key(artist or "", it.get("Name") or ""), it)
        if len(items) < 500:
            break
        start += 500
    return index


def find_playlist(name: str, user_id: str | None = None) -> str:
    path = "/Items?IncludeItemTypes=Playlist&Recursive=true&Limit=500"
    if user_id:
        path += "&UserId=" + user_id
    _, page = _req("GET", path)
    target = (name or "").strip().lower()
    for it in (page if isinstance(page, dict) else {}).get("Items") or []:
        if (it.get("Name") or "").strip().lower() == target:
            return it["Id"]
    return ""


def scan() -> None:
    if not KEY:
        print("  [scan] pas de clé d'API : scan sauté", flush=True)
        return
    status, _ = _req("POST", "/Library/Refresh")
    print("  [scan] bibliothèque Jellyfin en cours de mise à jour (HTTP %s)" % status, flush=True)


def wait_for_keys(keys, user_id: str, timeout: int = 300, interval: int = 6) -> tuple[dict, list]:
    """Attend que Jellyfin ait indexé les morceaux fraîchement importés.

    Jellyfin indexe en tâche de fond : juste après un scan, les fichiers sont sur
    le disque mais absents de l'API. On boucle jusqu'à ce que TOUTES les clés
    attendues soient visibles (ou expiration du délai).
    """
    wanted = set(keys)
    deadline = time.time() + timeout
    index = {}
    while True:
        index = library_index(user_id)
        missing = [k for k in wanted if k not in index]
        if not missing or time.time() > deadline:
            return index, missing
        print("  [index] %d morceau(x) encore en cours d'indexation…" % len(missing), flush=True)
        time.sleep(interval)


# --------------------------------------------------------------------------- #
# Playlists (écriture : jeton utilisateur obligatoire)
# --------------------------------------------------------------------------- #

def create_playlist(name: str, ids, user_id: str, token: str) -> str:
    status, payload = _req("POST", "/Playlists",
                           {"Name": name, "Ids": list(ids), "MediaType": "Audio",
                            "UserId": user_id}, token=token)
    playlist_id = (payload or {}).get("Id") if isinstance(payload, dict) else None
    if not playlist_id:
        raise RuntimeError("création de playlist refusée (HTTP %s) : %s"
                           % (status, str(payload)[:200]))
    return playlist_id


def add_items(playlist_id: str, ids, user_id: str, token: str) -> int:
    if not ids:
        return 0
    query = urllib.parse.urlencode({"ids": ",".join(ids), "userId": user_id})
    status, payload = _req("POST", "/Playlists/%s/Items?%s" % (playlist_id, query), token=token)
    if status not in (200, 204):
        raise RuntimeError("ajout à la playlist refusé (HTTP %s) : %s"
                           % (status, str(payload)[:200]))
    return len(ids)


def playlist_entries(playlist_id: str, user_id: str, token: str) -> list:
    _, payload = _req("GET", "/Playlists/%s/Items?UserId=%s" % (playlist_id, user_id), token=token)
    return (payload if isinstance(payload, dict) else {}).get("Items") or []


def update_playlist(playlist_id: str, name: str, ids, user_id: str, token: str,
                    open_access: bool = True, others=None) -> bool:
    """Renomme et/ou partage une playlist SANS perdre ses morceaux.

    PIÈGE VÉRIFIÉ : `POST /Playlists/{id}` REMPLACE le contenu de la playlist par
    le champ `Ids`. Un appel de « partage », qui enverrait naturellement
    `Ids: []`, VIDE donc la playlist — sans le moindre message d'erreur (HTTP 204).

    Mesure contrôlée sur une playlist de 2 morceaux :
        création puis lecture immédiate          -> 2 morceaux
        POST /Playlists/{id} avec Ids=[]         -> 0 morceau (vidée)
        POST /Playlists/{id} avec les vrais Ids  -> 2 morceaux (contenu préservé)

    C'est le piège dans lequel est tombée la première version de
    tools/import-playlist : elle a annoncé « 0 entrée » au lieu d'1. Conséquence :
    on renvoie TOUJOURS la liste réelle et complète des morceaux, et la commande
    recontrôle le résultat côté serveur avant de dire « terminé ».
    """
    users = [{"UserId": user_id, "CanEdit": True}]
    users += [{"UserId": u, "CanEdit": False} for u in (others or []) if u != user_id]
    status, _ = _req("POST", "/Playlists/%s" % playlist_id, {
        "Name": name, "Ids": list(ids), "OpenAccess": open_access, "Users": users,
    }, token=token)
    return status in (200, 204)


def playlists_for_user(user_id: str) -> list:
    """Playlists visibles par ce compte : sert à vérifier qu'un partage a pris."""
    _, page = _req("GET", "/Items?IncludeItemTypes=Playlist&Recursive=true"
                          "&UserId=%s&Limit=500" % user_id)
    return (page if isinstance(page, dict) else {}).get("Items") or []


def users() -> list:
    _, payload = _req("GET", "/Users")
    return payload if isinstance(payload, list) else []
