# Hermes Music — Architecture

## Vue d'ensemble

```
                    ┌────────────────────┐
                    │    ANDROID APP     │
                    │  (client Jellyfin) │
                    └─────────┬──────────┘
                              │ HTTPS
                              ▼
                     ┌────────────────┐
                     │    JELLYFIN    │
                     │ auth, biblio,  │
                     │ streaming,     │
                     │ playlists,     │
                     │ historique     │
                     └────────┬───────┘
                              │
                              ▼
                    Bibliothèque (/music/Artists)
                              ▲
                              │ scan
                    ┌─────────┴─────────┐
                    │    IMPORTATEUR    │
                    │ yt-dlp + FFmpeg   │
                    └───────────────────┘
```

Hébergement : conteneurs Docker (`docker-compose.yml`) sur un hôte Linux
(Debian). Le stockage (bibliothèque) est un volume local exposé au conteneur
Jellyfin en lecture seule.

## Choix techniques

- **Jellyfin** — image `linuxserver/jellyfin` (contrôle PUID/PGID).
  C'est la source de vérité : bibliothèque, streaming, playlists, historique,
  favoris. Aucun backend custom ne duplique ces fonctions.
- **Stockage** — bibliothèque dans `${MUSIC_LIBRARY_PATH}` (défaut `/srv/music`),
  exposée en lecture seule au conteneur sous `/music`.
- **Importateur** — Python + yt-dlp + FFmpeg. yt-dlp est un importateur, jamais
  un serveur de streaming : une fois le fichier dans la bibliothèque, Jellyfin
  s'occupe de la lecture.
- **Reverse proxy** — Caddy (HTTPS automatique) — V0.7.
- **Android** — Kotlin + Jetpack Compose, client de l'API Jellyfin — V0.3.

## Arborescence bibliothèque

```
${MUSIC_LIBRARY_PATH}/
├── Artists/      ← racine de bibliothèque Jellyfin (Artiste/Album/Piste)
├── Playlists/    ← exports .m3u (futur)
├── Incoming/     ← zone de travail de l'importateur
├── Failed/       ← échecs (diagnostic)
└── Metadata/     ← jaquettes / métadonnées
```

Jellyfin n'est pointé que sur `Artists/` pour ne pas indexer `Incoming/` et `Failed/`.

## Ports

- 8096 : Jellyfin HTTP (accès local). Masqué derrière Caddy (HTTPS) en V0.7.

## Sécurité

- Aucun secret dans Git : `.env` (identifiants, clés API) et `config/` (la base
  Jellyfin : comptes, empreintes de mots de passe, clé d'API) sont ignorés.
- Les listes de pistes et l'état des imports, sous `importer/library/`, sont
  versionnés : le dépôt public les publie volontairement.

## Roadmap

V0.1 infra · V0.2 importateur · V0.3 Android · V0.4 player · V0.5 playlists ·
V0.6 interface · V0.7 HTTPS · V0.8 offline · V1.0 production.
