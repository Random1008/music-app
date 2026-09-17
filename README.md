# Hermes Music

Plateforme personnelle de streaming musical auto-hébergée.

- **Jellyfin** — bibliothèque, streaming, playlists, historique, favoris
- **Importateur** (Python + yt-dlp + FFmpeg) — import de morceaux depuis des sources autorisées
- **Application Android** (Kotlin + Jetpack Compose) — client de lecture
- **Client web** (`web/`) — même bibliothèque depuis un navigateur
- **`music`** — administration du serveur depuis le terminal
- **`import-playlist`** — import d'une playlist Spotify / Apple Music / YouTube Music

## État

- [x] V0.1 — Infrastructure (Docker + Jellyfin + bibliothèque + accès local)
- [x] V0.2 — Importateur (yt-dlp + FFmpeg + organisation + scan Jellyfin)
- [ ] V0.2b — Interface **web** d'importation (progression, historique) — reste à faire
- [x] V0.3 — Android (connexion + bibliothèque + recherche + lecture)
- [x] V0.4 — Player (mini-player + file d'attente + lecture en arrière-plan)
- [x] V0.5 — Playlists (création, édition, réordonnancement) + favoris
- [x] V0.6 — Interface avancée (accueil, albums, artistes, historique)
- [x] V0.7 — Accès distant (Tailscale Funnel, HTTPS Let's Encrypt)
- [x] V0.8 — Mode hors-ligne (téléchargement, écoute sans réseau)
- [x] Connecteurs — import de playlists Spotify / Apple Music / YouTube Music
- [x] Sauvegarde hors site, surveillance, et exposition publique corrigée
- [x] V1.0 — Production : tests unitaires, sauvegarde vérifiée par restauration, surveillance

## Architecture

```
Android ──HTTPS──► Jellyfin ──► Bibliothèque (NAS)
                       ▲
                       │ scan
                  Importateur (yt-dlp + FFmpeg)
```

Détail des choix techniques : `docs/architecture.md`.
Adresses d'accès (locale, tailnet, publique) : `docs/access.md`.

## Démarrage (serveur)

```bash
cp .env.example .env      # adapter les chemins et identifiants
docker compose up -d      # Jellyfin sur http://<hôte>:8096
```

Premier lancement : créer le compte administrateur, puis ajouter une
bibliothèque « Musique » pointant sur `/music/Artists`.

## Administration depuis le terminal

La commande `music` gère les comptes Jellyfin sans passer par l'interface web.
Elle est livrée dans `tools/music` :

```bash
ln -s "$PWD/tools/music" ~/.local/bin/music   # installation (une fois)
```

```text
music adduser <nom> [motdepasse]   crée un compte Jellyfin et lui donne un mot de passe
music users                        liste les comptes
music passwd  <nom> [motdepasse]   change le mot de passe d'un compte
music deluser <nom> [-y]           supprime un compte
music help                         aide
```

Comportements à connaître :

* Sans mot de passe sur la ligne de commande, il est **demandé masqué** (donc
  absent de l'historique du shell et de `ps`).
* `adduser` **vérifie réellement** le compte à la fin : il s'authentifie avec et
  affiche le nombre d'albums visibles. Un compte créé mais inutilisable le dit.
* Un mot de passe de moins de 8 caractères déclenche un avertissement : le
  serveur est joignable depuis Internet.
* `deluser` refuse de supprimer le **dernier compte administrateur** (cela
  enfermerait dehors), et exige `-y` quand il n'y a pas de terminal.
* Ne jamais utiliser `ResetPassword` dans l'API Jellyfin pour définir un mot de
  passe : ce champ **efface** le mot de passe au lieu de le poser. `tools/music`
  envoie `CurrentPw` vide, ce qui fonctionne sur un compte neuf comme sur un
  compte existant.

Vérification des routes Jellyfin utilisées par l'application (playlists,
déclaration des lectures, historique) — utile après une montée de version du
serveur :

```bash
python3 tools/check-jellyfin-api.py    # crée un compte jetable, teste, nettoie
```

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

Import d'une playlist YouTube Music (`import_youtube.py`) : les titres sont
récupérés par **URL directe** (pas de recherche approximative) et téléchargés
**sans cookies** — le compte n'est sollicité que pour lire la playlist. Limite
connue : certains titres sont protégés par un *PO token* et restent hors de
portée de yt-dlp, même avec des cookies.

## Connecteurs de playlists

Importer une playlist ou un album Spotify / Apple Music / YouTube Music dans la
bibliothèque, depuis le terminal :

```bash
tools/import-playlist "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"
tools/import-playlist "https://music.apple.com/fr/album/..." --dry-run
tools/import-playlist "https://music.youtube.com/playlist?list=..." --limit 5
```

Les trois services ne fournissent que **la liste des titres** : l'audio vient
toujours de YouTube, par yt-dlp. Aucun audio n'est pris chez Spotify ou Apple
Music, dont le flux est chiffré.

La commande ne retélécharge que ce qui manque, range les morceaux comme le reste
de la bibliothèque, crée la playlist dans l'ordre d'origine, la partage avec les
autres comptes, puis **recontrôle le résultat côté serveur** avant de dire
« terminé ». La relancer est sans danger.

Les playlists et albums **publics** des trois services se lisent sans aucun
compte. Les listes privées demandent un compte — c'est possible pour YouTube Music
(cookies), impossible pour Apple Music (jeton développeur payant) et pour Spotify
(jeton OAuth ; l'export CSV d'Exportify reste la solution de repli).

Détail de ce qui marche, de ce qui est impossible et des pièges vérifiés :
`docs/connecteurs.md`.

La même chose est disponible dans le navigateur, sur le port non exposé :
`http://<nas>:8092/import` — un champ, deux boutons (Prévisualiser / Importer),
et le journal de progression en direct.

## Sauvegarde et surveillance

```bash
tools/backup.py              # sauvegarde hors site + vérification réelle
tools/backup.py --verify-only   # revérifie la dernière sans rien copier
```

La copie part sur le VPS (autre machine, autre bâtiment) : bibliothèque,
configuration Jellyfin — dont une **archive de base cohérente** produite par
Jellyfin lui-même —, dépôt, secrets, et le keystore qui signe les APK.

Le script ne se contente pas de vérifier que rsync a renvoyé 0 : il compare les
nombres, fait relire les empreintes SHA-256 **par le VPS**, **rapatrie réellement
quelques fichiers** et les compare octet par octet, et ouvre l'archive Jellyfin.
Premier passage : 2,6 Go. Passe suivante : 22 secondes.

Deux tâches planifiées (04:30 pour la sauvegarde, toutes les 6 h pour la
surveillance) n'écrivent **que** s'il y a un problème — le silence veut dire que
tout va bien. La surveillance contrôle aussi que le port public ne sert que la
page APK.

**Exposition** : le Funnel Tailscale ne publie que le port 8091, qui ne sert plus
que `/apk/`. Le client web, la page d'import et le pont Jellyfin sont sur le port
8092, non exposé. Auparavant, le port public servait tout — dont un pont qui
injectait la clé API, ce qui rendait la bibliothèque lisible par n'importe qui.

Procédure de restauration, pièges et détails : `docs/exploitation.md`.

## Documentation

- `docs/architecture.md` — choix techniques
- `docs/app-ui-spec.md` — spécification UI/UX de l'application Android
- `docs/access.md` — adresses d'accès et exposition publique
- `docs/connecteurs.md` — import de playlists Spotify / Apple Music / YouTube Music
- `docs/exploitation.md` — sauvegarde, restauration, surveillance, exposition
- `android/README.md` — application Android : fonctionnalités, structure, tests
- `web/README.md` — client web
