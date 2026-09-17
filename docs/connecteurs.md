# Connecteurs de playlists

Importer une playlist d'un service de streaming dans Hermes Music, sans compte
payant et sans rien contourner.

    tools/import-playlist "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"

## La règle, en une phrase

**Spotify, Apple Music et YouTube Music servent uniquement à donner la LISTE DES
TITRES.** L'audio vient toujours de YouTube, par yt-dlp, exactement comme pour le
reste de la bibliothèque. Aucun audio n'est pris chez Spotify ou Apple Music :
leur flux est chiffré, et on ne contourne pas ça.

## Ce que chaque service sait donner

| Service | Playlist publique | Album public | Liste privée (compte) |
|---|---|---|---|
| YouTube Music | oui, sans compte | oui | oui, avec cookies |
| Spotify | oui, sans compte | oui | non |
| Apple Music | oui, sans compte | oui | non |

Tout est **mesuré**, pas supposé (17 septembre 2026) :

    Spotify   playlist « Today's Top Hits »      -> 50 titres lus
    Apple     playlist « Today's Hits »          -> 50 titres lus
    Apple     album « Been By Now - Single »     ->  1 titre lu
    YouTube   playlist publique (100 titres)     -> 100 titres lus
    lien SoundCloud                              -> refusé proprement

### Comment, alors que les API exigent un compte

- **Spotify** : l'API Web exige un jeton OAuth (impossible ici, le tableau de bord
  développeur n'est pas accessible au propriétaire du compte). Mais la page
  d'intégration `open.spotify.com/embed/...` embarque la liste complète des pistes
  dans son bloc `__NEXT_DATA__`. C'est ce bloc qui est lu.
- **Apple Music** : l'API officielle exige un jeton de développeur (compte payant).
  La page publique, elle, contient la liste complète des pistes dans son bloc
  `serialized-server-data`. Bonus : Apple publie les pochettes en 3000×3000 sur un
  gabarit `{w}x{h}bb.{f}` — on demande du 1000×1000, très au-dessus des miniatures
  YouTube utilisées jusqu'ici.
- **YouTube Music** : rien à extraire, `yt-dlp --flat-playlist` fait le travail.
  Comme il donne l'identifiant de chaque vidéo, le téléchargement se fait ensuite
  par **URL directe** : pas de recherche floue, donc pas de mauvais morceau.

### Ce qui n'est PAS possible, et pourquoi

- **Bibliothèque personnelle Apple Music** : il faut un jeton de développeur Apple,
  c'est-à-dire un compte développeur payant. Aucun contournement n'est tenté.
- **Bibliothèque personnelle Spotify** (« Morceaux likés », playlists privées) :
  il faut un jeton OAuth. Solution de repli déjà en place : l'export CSV via
  Exportify, puis `importer/consolidate.py`.
- **Morceaux « likés » YouTube Music** : possible, il faut les cookies du compte
  (voir plus bas).

## Utilisation

    tools/import-playlist <lien> [options]

    --name NOM       nom de la playlist dans Hermes Music (défaut : nom d'origine)
    --limit N        ne traiter que les N premiers titres
    --dry-run        montrer ce qui serait fait, ne rien télécharger
    --no-playlist    importer les morceaux sans créer de playlist Jellyfin
    --user NOM       compte Jellyfin à utiliser
    --yes            ne pas demander de confirmation

La commande, en six étapes :

1. lit la liste des titres chez le service ;
2. compare avec la bibliothèque Jellyfin et **ne retélécharge que ce qui manque** —
   un morceau déjà présent est réutilisé, pas dupliqué ;
3. télécharge sur YouTube, tague (titre, artiste, album, pochette) et range dans
   `Artists/<artiste>/<album>/NN - <titre>.mp3` ;
4. déclenche un scan Jellyfin et **attend que les morceaux soient réellement
   indexés** (Jellyfin indexe en tâche de fond) ;
5. crée la playlist dans l'ordre d'origine et la partage avec les autres comptes ;
6. **recontrôle le résultat côté serveur** et refuse de dire « terminé » si un
   morceau manque.

Relancer la même commande est sans danger : les morceaux déjà là sont ignorés et
seules les entrées absentes sont ajoutées à la playlist.

## Compte Jellyfin dédié

Les routes d'écriture de playlists de Jellyfin **refusent la clé d'API** (HTTP 400)
et exigent un jeton utilisateur. La commande utilise donc un compte dédié,
`hermes-import`, non administrateur — ce qui évite de stocker le mot de passe du
propriétaire et laisse le compte principal tranquille.

- créé avec `music adduser hermes-import <mot de passe>` (qui vérifie la connexion
  réelle avant de dire « fait ») ;
- identifiants dans `.env` (`JELLYFIN_USER`, `JELLYFIN_PASSWORD`), fichier **non
  versionné** et en `chmod 600` ;
- le jeton obtenu est mis en cache dans `~/.config/hermes-music/jellyfin-token.json`
  (600), donc le mot de passe n'est utilisé qu'une fois ;
- `import-playlist --user random` permet d'utiliser un autre compte au besoin ;
  sans `--user`, c'est `JELLYFIN_USER` qui sert.

## Playlists YouTube Music privées

Lister « Morceaux likés » demande les cookies du compte :

1. exporter les cookies YouTube dans un fichier Netscape ;
2. le garder hors du dépôt et en `chmod 600` :
   `~/.config/hermes-music/cookies.txt` ;
3. lancer avec `YTDLP_COOKIES=/chemin/vers/cookies.txt tools/import-playlist "https://music.youtube.com/playlist?list=LM"`.

L'URL `list=LM` correspond à « Morceaux likés » du compte connecté. **À révoquer
côté Google quand tu n'en as plus besoin** (déconnexion de l'appareil dans les
paramètres de sécurité du compte).

## Pièges vérifiés

Ces trois-là ont été rencontrés en vrai, pas recopiés d'une documentation :

1. **`POST /Playlists/{id}` avec `Ids: []` VIDE la playlist.** Ce champ remplace le
   contenu ; il n'y a aucun message d'erreur (HTTP 204). Mesure contrôlée sur une
   playlist de 2 morceaux : création → 2 morceaux ; POST avec `Ids=[]` → 0 morceau ;
   POST avec les vrais identifiants → 2 morceaux. Conséquence : tout appel de
   partage renvoie la liste réelle, et `tools/check-jellyfin-api.py` vérifie
   maintenant que le renommage **conserve** les morceaux.
2. **La clé d'API n'incarne aucun utilisateur** (`GET /Users/Me` → 400). Elle
   suffit pour lire, pas pour écrire dans les playlists ni pour `/UserItems*`.
3. **Jellyfin compte une écoute dès le premier rapport `/Sessions/Playing`**, même
   sans écouter le morceau jusqu'au bout. Mesuré appel par appel : `Playing` →
   `PlayCount` 0 → 1. C'est le serveur qui décide ; un client Jellyfin normal fait
   pareil. À savoir : un morceau survolé apparaît quand même dans « Récemment
   écouté ».

## Fichiers

    importer/connect/__init__.py     détection du service + point d'entrée
    importer/connect/common.py       normalisation des titres, requêtes HTTP
    importer/connect/spotify.py      page /embed/ -> __NEXT_DATA__
    importer/connect/apple_music.py  page publique -> serialized-server-data
    importer/connect/youtube_music.py yt-dlp --flat-playlist
    importer/jellyfin.py             client Jellyfin (lecture + playlists)
    tools/import-playlist            commande d'import
    importer/library/playlists/      dernières listes lues, pour trace
