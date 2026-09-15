#!/usr/bin/env python3
"""Hermes Music — pont web <-> Jellyfin (prototype connecté).

Rôle :
  * sert l'interface web (ce dossier) ;
  * proxy /jf/* -> Jellyfin en injectant la clé API CÔTÉ SERVEUR.

La clé API ne quitte jamais le serveur : le navigateur ne la voit pas.
Aucune dépendance externe (stdlib uniquement).
"""
import http.server
import os
import socketserver
import sys
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)


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
# JELLYFIN_URL du .env = http://jellyfin:8096 (nom de service Docker, injoignable depuis l'hôte)
UPSTREAM = os.environ.get('JELLYFIN_UPSTREAM', 'http://127.0.0.1:8096').rstrip('/')
PORT = int(os.environ.get('WEB_PORT', '8091'))
AUTH = 'MediaBrowser Token=%s, Device=hermes-music-web, DeviceId=hermes-music-web, Version=0.3.0' % KEY

RELAY_HEADERS = ('Content-Type', 'Content-Length', 'Content-Range', 'Accept-Ranges',
                 'Cache-Control', 'ETag', 'Last-Modified')


class Handler(http.server.SimpleHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def __init__(self, *a, **kw):
        super().__init__(*a, directory=HERE, **kw)

    def log_message(self, format, *args):
        # Pas de log par requete : les pochettes et l'audio generent des milliers
        # de lignes, et un pipe de sortie non lu se remplit -> le serveur se bloque
        # et n'accepte plus aucune connexion. On ne trace que les erreurs.
        try:
            code = int(args[0])
        except (IndexError, ValueError):
            code = 200
        if code >= 400:
            sys.stderr.write('[web] %s %s\n' % (self.address_string(), format % args))

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
        if self.path == '/':
            self.path = '/index.html'
        return super().do_GET()

    def do_POST(self):
        if self.path == '/jf' or self.path.startswith('/jf/'):
            return self._proxy('POST')
        self.send_error(405)

    def do_DELETE(self):
        if self.path == '/jf' or self.path.startswith('/jf/'):
            return self._proxy('DELETE')
        self.send_error(405)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == '__main__':
    if not KEY:
        print('ATTENTION: JELLYFIN_API_KEY absente (ni env ni .env)', file=sys.stderr)
    print('Hermes Music web -> %s' % UPSTREAM)
    print('Ecoute sur http://0.0.0.0:%d' % PORT)
    Server(('0.0.0.0', PORT), Handler).serve_forever()
