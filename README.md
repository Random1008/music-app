# Hermes Music

Plateforme personnelle de streaming musical auto-hébergée.

- **Jellyfin** — bibliothèque, streaming, playlists, historique, favoris
- **Importateur** (Python + yt-dlp + FFmpeg) — import de morceaux depuis des sources autorisées
- **Application Android** (Kotlin + Jetpack Compose) — client de lecture

## État

- [x] V0.1 — Infrastructure (Docker + Jellyfin + bibliothèque + accès local)
- [x] V0.2 — Importateur (yt-dlp + FFmpeg + organisation + scan Jellyfin)
- [ ] V0.2b — Interface web d'importation
- [ ] V0.3 — Android (connexion + bibliothèque + recherche + lecture)
- [ ] V0.4 — Player (mini-player + queue + background)
- [ ] V0.5 — Playlists + favoris
- [ ] V0.6 — Interface avancée (home, albums, artistes, historique)
- [ ] V0.7 — Accès distant (HTTPS + reverse proxy)
- [ ] V0.8 — Mode hors-ligne
- [ ] V1.0 — Production (tests + sauvegardes + monitoring)

## Architecture

```
Android ──HTTPS──► Jellyfin ──► Bibliothèque (NAS)
                       ▲
                       │ scan
                  Importateur (yt-dlp + FFmpeg)
```

## Démarrage (serveur)

```bash
cp .env.example .env      # adapter les chemins et identifiants
docker compose up -d      # Jellyfin sur http://<hôte>:8096
```

Premier lancement : créer le compte administrateur, puis ajouter une
bibliothèque « Musique » pointant sur `/music/Artists`.

## Importateur

```bash
cd importer
python3 -m venv .venv && ./.venv/bin/pip install -r requirements.txt
# 1. exporter ses playlists Spotify en CSV (outil type Exportify) dans ~/Downloads
python3 consolidate.py                 # fusionne les CSV -> library/tracks.json
YTDLP=./.venv/bin/yt-dlp \
  JELLYFIN_API_KEY=xxx \
  python3 importer.py                  # télécharge, tague, range, scanne
```

Pré-requis : `ffmpeg` + un runtime JavaScript (`node` ou `deno`) pour yt-dlp.

Fonctions : recherche YouTube + requête de repli + réessais ; support cookies
(`YTDLP_COOKIES`) pour les vidéos restreintes ; URL directe par morceau
(`library/overrides.json`) ; conversion MP3 320k + tags + pochette ; rangement
`Artists/Artiste/Album/NN - Titre.mp3` ; reprise sur échec (`library/status.json`).

## Documentation

- `docs/architecture.md` — choix techniques
- `docs/app-ui-spec.md` — spécification UI/UX de l'application Android
