# Hermes Music

Plateforme personnelle de streaming musical (auto-hébergée).

Architecture : **Jellyfin** (bibliothèque + streaming) + **importateur yt-dlp/FFmpeg**
+ **application Android** (Kotlin/Compose).

Le stockage est sur le NAS le NAS (`~/Music`, volume1 3,6 To).

## État

- [x] V0.1 — Infrastructure (Docker + Jellyfin + bibliothèque + accès local)
- [ ] V0.2 — Importateur (yt-dlp + FFmpeg + organisation + scan)
- [ ] V0.3 — Android (connexion + bibliothèque + recherche + lecture)
- [ ] V0.4 — Player (mini-player + queue + background)
- [ ] V0.5 — Playlists + favoris
- [ ] V0.6 — Interface avancée
- [ ] V0.7 — Accès distant (HTTPS + reverse proxy)
- [ ] V0.8 — Mode hors-ligne
- [ ] V1.0 — Production (tests + sauvegardes + monitoring)

## Démarrage rapide (V0.1)

```bash
cp .env.example .env   # puis adapter
docker compose up -d
```

Jellyfin est alors accessible sur `http://<ip-du-nas>:8096`.

Au premier lancement : créer le compte administrateur, puis ajouter une
bibliothèque « Musique » pointant sur `/music/Artists`.

## Structure du projet

```
docker-compose.yml   # services (Jellyfin, puis importer/caddy)
config/              # config runtime (Jellyfin…) — non versionné
data/                # données runtime (file importateur) — non versionné
importer/            # V0.2 — service d'importation yt-dlp/FFmpeg
android/             # V0.3 — application Android
scripts/             # utilitaires (scan, sauvegarde)
docs/                # documentation
```

Voir `docs/architecture.md` pour les choix techniques.
