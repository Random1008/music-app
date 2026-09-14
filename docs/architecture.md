# Hermes Music — Architecture

## Environnement réel (constaté)

- **Hôte** : un NAS, Debian 12 (bookworm), Linux (linux).
- **Rôle de l'hôte** : il EST le NAS et le serveur (pas de NAS distant à monter).
- **Ressources** : 8 cœurs, 7,5 Go RAM, volume1 = 3,6 To (3,5 To libres) monté sur `/home` (`/volume1/@home`).
- **Hermes** : s'exécute dans un conteneur sur le NAS, avec accès au socket Docker
  (peut créer/gérer les conteneurs du projet).
- **Outils dispo** : Docker 26.1 + Compose v2.26, FFmpeg/ffprobe 5.1, Python 3.11, git.
- **À installer** : yt-dlp (V0.2), image Jellyfin (pull via Docker).

## Choix techniques

- **Jellyfin** : image `linuxserver/jellyfin` (support Linux, contrôle PUID/PGID).
  Cœur de la bibliothèque, du streaming, des playlists, de l'historique et des favoris.
- **Stockage** : bibliothèque dans `~/Music` (volume1), exposée en
  lecture seule au conteneur sous `/music`.
- **Importateur** : service dédié (Python + yt-dlp + FFmpeg) — V0.2.
- **Reverse proxy** : Caddy (HTTPS automatique) — V0.7.
- **Android** : Kotlin + Jetpack Compose — V0.3.

## Arborescence bibliothèque (spec §5)

```
~/Music/
├── Artists/      ← racine de bibliothèque Jellyfin (Artiste/Album/Piste)
├── Playlists/    ← exports .m3u (futur)
├── Incoming/     ← zone de travail de l'importateur
├── Failed/       ← échecs (pour diagnostic)
└── Metadata/     ← jaquettes/métadonnées de l'importateur
```

Jellyfin est pointé uniquement sur `Artists/` pour ne pas indexer `Incoming/`
et `Failed/`.

## Flux de données

```
URL autorisée ─► Importateur (yt-dlp ─► FFmpeg ─► métadonnées)
        │
        └─► Music/Incoming ─► Music/Artists/... ─► scan Jellyfin
                                                          │
Android (Hermes Music) ◄── HTTPS ── Reverse proxy ── Jellyfin
```

## Ports

- 8096 : Jellyfin HTTP (local, V0.1). Sera masqué derrière Caddy en V0.7.

## Roadmap

V0.1 infra · V0.2 importateur · V0.3 Android · V0.4 player · V0.5 playlists ·
V0.6 interface · V0.7 HTTPS · V0.8 offline · V1.0 production.
