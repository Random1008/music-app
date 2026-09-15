# Hermes Music — client web connecté (prototype V0.3)

Interface web qui **réutilise le design « Nocturne »** de la maquette validée
(`../design/`) mais tire **toutes ses données de la vraie instance Jellyfin** :
vraies pochettes, vrais titres, vrais artistes, et **lecture audio réelle**
(streaming depuis Jellyfin).

Sert à valider l'UX et la correspondance avec l'API Jellyfin **avant** d'écrire
l'application Android (Kotlin/Compose), et pourra servir de client web de secours.

## Lancer

```bash
cd web
python3 server.py            # écoute sur 0.0.0.0:8091 (WEB_PORT pour changer)
```

Puis ouvrir `http://<ip-du-serveur>:8091/`.

## Architecture

```
navigateur ── /            ──> fichier statique (index.html, app.css, app.js)
          └─ /jf/<chemin>  ──> proxy ──> Jellyfin (auth injectée côté serveur)
```

* `server.py` — pont HTTP, **stdlib uniquement**. Il ajoute l'en-tête
  d'authentification Jellyfin et relaie `Range` (nécessaire au seek audio).
  La clé API **ne quitte jamais le serveur** : le navigateur ne la voit pas.
* `app.js` — état, rendu et lecteur. Aucun framework, aucune dépendance.
* `app.css` — design « Nocturne » (fond `#08090f`, surfaces `#1F2130/#171926`,
  texte `#E9E9ED`, secondaire `#9397AB`, accent `#7C5CFF`, libellés monospace).

## Ce qui fonctionne réellement

| Fonction | État |
|---|---|
| Connexion Jellyfin (clé API, côté serveur) | ✅ |
| Accueil (dernier ajout / reprise, albums récents, artistes, ajouts récents) | ✅ |
| Bibliothèque (albums / artistes / morceaux / favoris) | ✅ |
| Recherche instantanée (morceaux, albums, artistes) | ✅ |
| Fiche album (métadonnées + pistes + lecture + aléatoire) | ✅ |
| Fiche artiste (albums + morceaux les plus écoutés) | ✅ |
| Lecteur plein écran (disque + anneau de progression, seek, shuffle, repeat) | ✅ |
| Mini-lecteur persistant + file d'attente | ✅ |
| Lecture audio réelle (flux Jellyfin, `Range` → 206) | ✅ |
| Favoris (écriture réelle dans Jellyfin) | ✅ |
| Session de lecture visible dans Jellyfin (`/Sessions`) | ✅ |
| Playlists | ⛔ aucune playlist dans Jellyfin (écran vide volontaire) |

## API Jellyfin utilisée

| Besoin | Endpoint |
|---|---|
| Utilisateurs | `GET /Users` |
| Albums / morceaux | `GET /Items?IncludeItemTypes=MusicAlbum\|Audio&Recursive=true&Fields=…` |
| Artistes | `GET /Artists?Recursive=true&SortBy=SortName` |
| Pistes d'un album | `GET /Items?ParentId=<albumId>&IncludeItemTypes=Audio&SortBy=ParentIndexNumber,IndexNumber` |
| Album d'un artiste | `GET /Items?ArtistIds=<id>&IncludeItemTypes=MusicAlbum` |
| Recherche | `GET /Items?searchTerm=…&IncludeItemTypes=Audio,MusicAlbum,MusicArtist` |
| Favoris | `GET /Users/<uid>/Items?Filters=IsFavorite` · `POST\|DELETE /Users/<uid>/FavoriteItems/<id>` |
| Pochette | `GET /Items/<id>/Images/Primary?maxHeight=…` |
| Audio | `GET /Audio/<id>/stream?static=true&UserId=<uid>` |
| Session / historique | `POST /Sessions/Playing[/Stopped]` |

## Pièges connus (rencontrés)

* `JELLYFIN_URL` du `.env` vaut `http://jellyfin:8096` = **nom de service Docker** :
  injoignable depuis l'hôte. Le pont utilise `127.0.0.1:8096` par défaut.
* `/Audio/<id>/universal` renvoie du **transcodé** (`video/mp2t`, sans `Range`)
  → mauvaise piste pour un seek. Utiliser `/Audio/<id>/stream?static=true`.
* Beaucoup d'items **n'ont pas de pochette** (artistes importés sans photo) :
  Jellyfin renvoie 404. On teste `ImageTags.Primary` / `AlbumPrimaryImageTag`
  **avant** de requêter l'image.
* CSS : `#player{display:flex}` écrase `[hidden]` → le lecteur vide recouvrait
  l'écran et bloquait les clics. D'où la règle `[hidden]{display:none!important}`.
