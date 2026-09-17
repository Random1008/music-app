#!/usr/bin/env python3
"""Hermes Music — serveur web : deux ports, deux niveaux d'exposition.

POURQUOI DEUX PORTS
Le Funnel Tailscale publie le port 8091 sur Internet. Ce port servait auparavant
tout le serveur, y compris le pont /jf/* qui injecte la clé API côté serveur :
n'importe qui connaissant l'adresse pouvait donc lire toute la bibliothèque sans
le moindre mot de passe. Mesuré, pas supposé :

    https://<nas>:10000/            -> 200   (client web complet)
    https://<nas>:10000/jf/Users    -> 200   (liste des comptes, sans authentification)

D'où la séparation :

    8091  EXPOSÉ publiquement -> sert UNIQUEMENT /apk/ (la page d'installation
          et le fichier APK). Tout le reste répond 404. Rien de sensible.
    8092  NON exposé (tailnet et réseau local seulement) -> client web, pont
          /jf/*, et la page d'importation.

Le port 8092 n'est joignable ni depuis Internet (aucune redirection de port sur
la box, seul le Funnel expose quelque chose) ni par le Funnel, qui ne pointe que
sur 8091.

    http://<nas>:8092/          client web
    http://<nas>:8092/import    import d'une playlist Spotify / Apple / YouTube

Aucune dépendance externe (stdlib uniquement).
"""
import http.server
import json
import os
import socketserver
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

sys.path.insert(0, os.path.join(ROOT, 'importer'))


def load_env():
    env = {}
    path = os.path.join(ROOT, '.env')
    if os.path.exists(path):
        for line in open(path):
            line = line.strip()
            if line and not line.startswith('#') and '=' in line:
                k, v = line.split('=', 1)
                env[k.strip()] = v.strip().strip('"').strip("'")
    return env


ENV = load_env()
KEY = os.environ.get('JELLYFIN_API_KEY') or ENV.get('JELLYFIN_API_KEY', '')
UPSTREAM = os.environ.get('JELLYFIN_UPSTREAM', 'http://127.0.0.1:8096').rstrip('/')
PUBLIC_PORT = int(os.environ.get('WEB_PUBLIC_PORT', '8091'))
APP_PORT = int(os.environ.get('WEB_APP_PORT', '8092'))
AUTH = 'MediaBrowser Token=%s, Device=hermes-music-web, DeviceId=hermes-music-web, Version=0.8.0' % KEY
IMPORT_CLI = os.path.join(ROOT, 'tools', 'import-playlist')

try:                                  # détection du service pour refuser tôt un lien invalide
    from connect import detect as detect_service
except Exception:                     # noqa: BLE001
    detect_service = None

RELAY_HEADERS = ('Content-Type', 'Content-Length', 'Content-Range', 'Accept-Ranges',
                 'Cache-Control', 'ETag', 'Last-Modified')

# --------------------------------------------------------------------------- #
# Import en tâche de fond : un seul à la fois, journal conservé pour l'affichage
# --------------------------------------------------------------------------- #

JOBS: dict = {}
JOB_LOCK = threading.Lock()
RUNNING = {'id': ''}                 # identifiant du dernier import lancé ('' = aucun)
MAX_LINES = 500


def job_append(job_id: str, line: str) -> None:
    """Ajoute une ligne au journal d'un import, lu ensuite par /import/state."""
    with JOB_LOCK:
        job = JOBS.get(job_id)
        if not job:
            return
        job['lines'].append(line)
        if len(job['lines']) > MAX_LINES:
            del job['lines'][:-MAX_LINES]


class BaseHandler(http.server.SimpleHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def __init__(self, *a, **kw):
        super().__init__(*a, directory=HERE, **kw)

    def log_message(self, format, *args):
        # Pas de log par requete : les pochettes et l'audio generent des milliers
        # de lignes, et un pipe de sortie non lu se remplit -> le serveur se bloque
        # et n'accepte plus aucune connexion. On ne trace que les erreurs.
        try:
            code = int(str(args[0]))
        except (IndexError, ValueError):
            code = 200
        if code >= 400:
            sys.stderr.write('[web:%d] %s %s\n' % (self.server.server_address[1],
                                                   self.address_string(), format % args))

    def send_json(self, payload, status=200):
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(body)


class PublicHandler(BaseHandler):
    """Port exposé publiquement : la page d'installation et l'APK, rien d'autre."""

    def _apk_only(self):
        if self.path.startswith('/apk'):
            if self.path in ('/apk', '/apk/'):
                self.path = '/apk/index.html'
            return super().do_GET()
        self.send_error(404, 'Seule la page /apk/ est exposee publiquement')
        return None

    def do_GET(self):
        return self._apk_only()

    def do_HEAD(self):
        return self._apk_only()

    def do_POST(self):
        self.send_error(405)

    def do_DELETE(self):
        self.send_error(405)


class AppHandler(BaseHandler):
    """Port non exposé : client web, pont Jellyfin, et import de playlists."""

    def _proxy(self, method):
        path = self.path[len('/jf'):] or '/'
        length = int(self.headers.get('Content-Length') or 0)
        body = self.rfile.read(length) if length else None
        req = urllib.request.Request(UPSTREAM + path, data=body, method=method)
        req.add_header('Authorization', AUTH)
        for h in ('Range', 'Content-Type', 'Accept'):
            if self.headers.get(h):
                req.add_header(h, self.headers[h])
        try:
            resp = urllib.request.urlopen(req, timeout=120)
        except urllib.error.HTTPError as e:
            resp = e
        except Exception as e:                     # Jellyfin injoignable -> 502 explicite
            self.send_error(502, 'Jellyfin injoignable: %s' % e)
            return
        self.send_response(resp.status)
        for h in RELAY_HEADERS:
            v = resp.headers.get(h)
            if v:
                self.send_header(h, v)
        if not resp.headers.get('Content-Length'):
            self.send_header('Connection', 'close')
            self.close_connection = True
        self.end_headers()
        try:
            while True:
                chunk = resp.read(65536)
                if not chunk:
                    break
                self.wfile.write(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            resp.close()

    def do_GET(self):
        if self.path == '/jf' or self.path.startswith('/jf/'):
            return self._proxy('GET')
        if self.path == '/import':
            self.path = '/import.html'
        if self.path == '/import/state':
            return self.import_state()
        if self.path == '/':
            self.path = '/index.html'
        return super().do_GET()

    def do_POST(self):
        if self.path == '/jf' or self.path.startswith('/jf/'):
            return self._proxy('POST')
        if self.path == '/import/start':
            return self.import_start()
        self.send_error(405)

    def do_DELETE(self):
        if self.path == '/jf' or self.path.startswith('/jf/'):
            return self._proxy('DELETE')
        self.send_error(405)

    # --- import ----------------------------------------------------------- #

    def import_state(self):
        with JOB_LOCK:
            job_id = RUNNING['id']
            job = JOBS.get(job_id) if job_id else None
            payload = dict(job) if job else {'id': '', 'running': False, 'lines': []}
        return self.send_json(payload)

    def import_start(self):
        length = int(self.headers.get('Content-Length') or 0)
        try:
            payload = json.loads(self.rfile.read(length).decode('utf-8') or '{}')
        except json.JSONDecodeError:
            return self.send_json({'erreur': 'requête illisible'}, 400)

        url = (payload.get('url') or '').strip()
        if not url or len(url) > 500:
            return self.send_json({'erreur': 'lien manquant ou trop long'}, 400)
        if detect_service is not None and detect_service(url) is None:
            return self.send_json(
                {'erreur': "lien non reconnu — attendu une playlist ou un album "
                           "Spotify, Apple Music ou YouTube Music"}, 400)

        with JOB_LOCK:
            current = JOBS.get(RUNNING['id'])
            if current and current.get('running'):
                return self.send_json({'erreur': 'un import est déjà en cours'}, 409)
            job_id = time.strftime('%Y%m%d-%H%M%S')
            JOBS[job_id] = {
                'id': job_id, 'running': True, 'code': -1, 'url': url,
                'dry_run': bool(payload.get('dry_run')),
                'name': payload.get('name') or '',
                'lines': ['Lancement…'], 'started': time.time(),
            }
            RUNNING['id'] = job_id
            for stale in sorted(JOBS)[:-5]:        # on ne garde que les 5 derniers
                JOBS.pop(stale, None)

        threading.Thread(target=run_import, args=(job_id, payload), daemon=True).start()
        return self.send_json({'id': job_id})


def run_import(job_id: str, payload: dict) -> None:
    """Exécute l'import dans un fil : la requête HTTP, elle, rend la main tout de suite."""
    cmd = [IMPORT_CLI, payload['url'], '--yes']
    if payload.get('name'):
        cmd += ['--name', payload['name']]
    if payload.get('limit'):
        cmd += ['--limit', str(int(payload['limit']))]
    if payload.get('dry_run'):
        cmd += ['--dry-run']

    code = -1
    try:
        proc = subprocess.Popen(cmd, cwd=ROOT, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, bufsize=1)
        assert proc.stdout is not None
        for line in proc.stdout:
            job_append(job_id, line.rstrip())
        code = proc.wait()
    except Exception as exc:                       # noqa: BLE001
        job_append(job_id, 'ERREUR : %s' % exc)
    job_append(job_id, 'TERMINÉ (code %d)' % code if code == 0
               else 'ÉCHEC (code %d)' % code)
    with JOB_LOCK:
        if job_id in JOBS:
            JOBS[job_id]['running'] = False
            JOBS[job_id]['code'] = code


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == '__main__':
    if not KEY:
        print('ATTENTION: JELLYFIN_API_KEY absente (ni env ni .env)', file=sys.stderr)
    if not os.access(IMPORT_CLI, os.X_OK):
        print('ATTENTION: %s non exécutable' % IMPORT_CLI, file=sys.stderr)

    print('Client web et import  -> http://0.0.0.0:%d  (NON exposé publiquement)' % APP_PORT)
    print('Page APK (publique)   -> http://0.0.0.0:%d/apk/  (seul port du Funnel)' % PUBLIC_PORT)

    public = Server(('0.0.0.0', PUBLIC_PORT), PublicHandler)
    threading.Thread(target=public.serve_forever, daemon=True).start()
    Server(('0.0.0.0', APP_PORT), AppHandler).serve_forever()
