# Hermes Music — application Android

Application Android native (Kotlin + Jetpack Compose) qui consomme **Jellyfin**
comme source de vérité : bibliothèque, artistes, albums, morceaux, recherche,
favoris, playlists, historique, lecture. Aucune donnée musicale n'est dupliquée
côté app — sauf les morceaux explicitement téléchargés pour l'écoute hors-ligne.

Interface en français, design « Nocturne » repris de la maquette validée
(accent `#7C5CFF`, fond `#08090F`, surfaces `#1F2130`/`#171926`, texte `#E9E9ED`,
libellés monospace en majuscules).

## Ce que fait l'application

| Écran | Contenu |
|---|---|
| Connexion | adresse du serveur, utilisateur, mot de passe (jamais conservé) |
| Serveur injoignable | explication, **Réessayer**, déconnexion, et ce qui reste écoutable hors-ligne |
| Accueil | **Reprendre la lecture**, récemment écouté, albums récents, favoris, playlists, derniers ajouts |
| Recherche | morceaux, albums, artistes — instantanée, avec **filtres par catégorie** |
| Bibliothèque | Albums / Artistes / Morceaux / **Favoris** |
| Page album | pochette, artiste, année, durée, Lecture, Aléatoire, favori, ajout à une playlist, **téléchargement** |
| Page artiste | photo (ou pochette du premier album), ses albums, ses titres les plus écoutés |
| **Playlists** | créer, renommer, supprimer, ajouter/retirer des morceaux, **réordonner** |
| Mini-lecteur | permanent au-dessus des onglets : jaquette, titre, lecture/pause, suivant, progression |
| Lecteur plein écran | seek, précédent/suivant, aléatoire, répétition, favori, **file d'attente**, ajout de la file à une playlist |
| **Paramètres** | serveur, utilisateur, déconnexion, **thème sombre/clair**, **5 accents**, accès au hors-ligne |
| **Hors-ligne** | morceaux téléchargés, espace occupé, lecture sans réseau, suppression |

La lecture continue en arrière-plan, écran verrouillé, et survit au changement
d'écran : le lecteur vit dans un service, pas dans l'interface.

## Hors-ligne : ce qui est réellement stocké

Les morceaux téléchargés sont écrits dans le dossier privé de l'application
(`filesDir/music/`), avec un index JSON (`downloads.json`). La lecture utilise
alors une URI `file://`, que le `DefaultDataSource` de Media3 lit directement —
inutile de mettre en place le cache de téléchargement d'ExoPlayer ni une base de
données pour quelques dizaines de morceaux.

Deux conséquences à connaître :

* un morceau téléchargé est **toujours** préféré au flux réseau, même en ligne
  (moins de données, démarrage instantané) ;
* **désinstaller l'application efface les téléchargements** (stockage privé).

L'URL de flux n'est pas stockée dans l'index : elle dépend de l'adresse du
serveur, qui peut changer. Seul le nom du fichier est conservé.

## Reprise de session

Android peut tuer le processus à tout moment. La file et la position courantes
sont donc écrites toutes les ~5 s dans `filesDir/player_session.json`
(`player/SessionStore.kt`). Au lancement, la file est restaurée **à l'arrêt** :
l'utilisateur retrouve ce qu'il écoutait et appuie sur lecture. Sans ce fichier,
rouvrir l'app repartait d'une file vide.

## Thème clair et accent

`Nocturne` n'est plus un objet de constantes : chaque couleur est exposée par un
accesseur qui lit un état Compose. Changer le thème ou l'accent recompose donc
automatiquement les écrans qui utilisent `Nocturne.*`, sans réécrire les vues.
Le mode et l'accent sont persistés dans DataStore et appliqués au démarrage.

## Où le projet est compilé, et pourquoi pas ici

Le serveur de développement (le NAS) est **Linux** et n'a aucun outil Android :
pas de JDK, pas de SDK, et les outils officiels (aapt2 notamment) ne sont publiés
que pour x86_64. La compilation se fait donc sur un **hôte de build x86_64**
(JDK 17 + SDK Android), et l'APK est rapatrié pour être installé sur le téléphone.

```
NAS (Linux)                    Hôte de build (x86_64)
  code source ── git push ──▶ clone ── ./gradlew assembleDebug ──▶ APK
    ▲                                                              │
    └────────────────────── rapatriement ◀─────────────────────────┘
```

Le dépôt contient le **wrapper Gradle** (`gradlew`) : sur l'hôte de build il faut
seulement `export ANDROID_HOME=…` et `export JAVA_HOME=<un JDK avec javac>`
(le JDK 21 par défaut sur Debian est parfois un JRE sans compilateur).

## Versions (vérifiées dans les dépôts, pas supposées)

| Composant | Version | Note |
|---|---|---|
| JDK | 17 | exigé par AGP 9.x |
| Gradle | 9.6.1 | via `./gradlew` |
| Android Gradle Plugin | 9.4.0 | |
| Kotlin | 2.4.20 | + plugin Compose compiler 2.4.20 |
| compileSdk | 37 | plateformes versionnées en mineur : `android-37.0`, `android-37.2` |
| targetSdk | 36 | on n'opte pas dans les comportements d'Android 17 |
| minSdk | 26 | Android 8+ |
| Compose BOM | 2026.09.00 | |
| Media3 (ExoPlayer) | 1.11.1 | |
| Retrofit | 3.0.0 | + converter-kotlinx-serialization 3.0.0 |
| OkHttp | 5.5.0 | |
| kotlinx.serialization | 1.11.0 | |
| Coil | 3.6.2 | |
| DataStore | 1.2.1 | |

## Structure (module unique, discipline de packages)

```
app/src/main/java/fr/hermesmusic/
├── HermesApp.kt        Application + ImageLoader Coil partagé
├── MainActivity.kt     point d'entrée Compose + état « serveur injoignable »
├── core/
│   ├── AppGraph.kt     injection MANUELLE (pas de Hilt/Koin) + session + OkHttp
│   └── Theme.kt        design Nocturne réactif (thème clair, accents)
├── data/
│   ├── SettingsStore.kt  DataStore : URL, jeton, userId, DeviceId, apparence
│   ├── MusicRepository.kt accès Jellyfin (requêtes, playlists, favoris, URL)
│   └── Downloads.kt      index des fichiers locaux + téléchargeur séquentiel
├── network/
│   ├── JellyfinApi.kt    interface Retrofit
│   └── JellyfinModels.kt DTO
├── player/
│   ├── PlaybackService.kt   MediaSessionService — le lecteur VIT ici, pas dans l'UI
│   ├── PlayerConnection.kt  télécommande (MediaController) + état exposé à l'UI
│   ├── PlaybackReporter.kt  déclaration des lectures à Jellyfin
│   └── SessionStore.kt      reprise de session (file + position)
└── ui/
    ├── LoginScreen.kt  connexion (le mot de passe n'est jamais conservé)
    ├── Shell.kt        navigation 4 onglets + mini-lecteur + pile d'écrans
    ├── Screens.kt      Accueil, Recherche, Bibliothèque, Playlists
    ├── Detail.kt       pages album et artiste
    ├── Playlists.kt    page playlist, ajout à une playlist, dialogues
    ├── Settings.kt     Paramètres + Hors-ligne
    ├── PlayerScreen.kt lecteur plein écran + file d'attente
    ├── States.kt       écran « serveur injoignable »
    └── Format.kt       formatage pur (durées, tailles) — testable sur la JVM
```

Un seul module tant que l'app reste de cette taille : découper en
`core/ data/ feature/` maintenant ne ferait qu'ajouter du temps de build et du
câblage pour un projet à un seul développeur.

## Tests

29 tests unitaires JVM (`./gradlew testDebugUnitTest`), volontairement centrés
sur ce qui casse en silence :

| Fichier | Ce qui est vérifié |
|---|---|
| `JfItemTest` | conversion des ticks, repli du nom d'artiste, pochette de morceau, favori |
| `FormatTest` | durées (`3:42`), positions négatives, tailles (`1,4 Go`) |
| `MusicRepositoryTest` | paramètres exacts des requêtes Jellyfin, URL de flux `static=true`, pochette plafonnée, nom de playlist vide refusé |
| `PlaybackReporterTest` | un « démarrage » par morceau, point de reprise à 10 s, pause immédiate, mise en mémoire tampon non déclarée, panne réseau avalée |
| `DownloadIndexTest` | aller-retour de l'index, index corrompu toléré |

Ce qui n'est **pas** testé automatiquement : le rendu Compose, la lecture audio
réelle, et l'infrastructure (NAS, Tailscale) — vérifiés à la main.

## Compiler

```bash
cd android
cp /chemin/vers/keystore.properties .   # hors dépôt (voir ci-dessous)
export ANDROID_HOME=$HOME/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew :app:assembleDebug         # APK
./gradlew :app:testDebugUnitTest     # tests
```

## Signature : le point à ne pas rater

Un keystore de développement **stable** est utilisé pour signer les builds debug
ET release. Sans cela, chaque compilation produit une signature différente et il
faut **désinstaller l'app** avant de réinstaller la nouvelle version — ce qui
efface les réglages et les téléchargements.

* `keystore.properties` et le fichier `.jks` vivent **hors du dépôt** (`*.jks` et
  `android/keystore.properties` sont dans `.gitignore`).
* Empreinte SHA256 du certificat de dev : `71:F3:BF:DA:CA:B1:3F:5B:0E:D5:C3:65:4A:4C:65:64:BB:3D:BE:AB:74:3D:D1:FC:A7:06:96:E5:33:80:8A:7C`
* Elle doit rester identique à chaque build. Si elle change, la réinstallation
  exigera une désinstallation.

## Sécurité

* `allowBackup=false` : aucun jeton ne part dans une sauvegarde cloud.
* `network_security_config` : le trafic **en clair** n'est autorisé que vers le
  serveur personnel (Tailscale / réseau local), jamais globalement.
* Le mot de passe n'est jamais écrit sur le disque : seul le jeton d'accès Jellyfin
  est conservé (DataStore).
* Permissions limitées au strict nécessaire (INTERNET, lecture en arrière-plan,
  notifications).

## Notes d'implémentation

* **DeviceId stable** généré une fois et conservé : sans lui, Jellyfin crée des
  sessions « fantômes » et l'historique devient incohérent.
* **Un seul client OkHttp** partagé par Retrofit, Coil, le lecteur audio et les
  téléchargements : mêmes délais, même en-tête d'authentification.
* **Le lecteur ne vit pas dans l'interface.** Il tourne dans un
  `MediaSessionService` et l'UI ne garde qu'un `MediaController`. C'est ce qui fait
  que la musique survit au changement d'écran, à l'arrière-plan et au verrouillage.
  Corollaire : toutes les méthodes de `PlayerConnection` doivent être appelées
  depuis le fil principal (Media3 refuse le reste) — d'où les deux portées
  distinctes dans `AppGraph` (`appScope` pour le fond, `mainScope` pour le lecteur).
* **Déclaration des lectures.** L'app POSTe `/Sessions/Playing` au changement de
  morceau et `/Sessions/Playing/Progress` toutes les 10 s. Sans ces appels,
  Jellyfin ignore ce que l'app joue : pas d'historique, pas de « Reprendre la
  lecture », pas de compteur d'écoute. Un échec de déclaration n'interrompt
  jamais la musique (erreurs avalées volontairement).
* **Identifiants d'entrée de playlist.** Pour retirer ou déplacer un morceau,
  Jellyfin attend le `PlaylistItemId` de l'entrée, pas l'identifiant du morceau.
  Les deux sont donc distincts dans le code.
* Lecture audio : `/Audio/<id>/stream?static=true` (renvoie `audio/mpeg` avec
  support du `Range`, donc un seek correct). ``/Audio/<id>/universal`` renvoie du
  transcodé sans `Range` — seek cassé, à éviter comme chemin principal.
* Pochettes toujours demandées avec un `maxHeight` raisonnable, jamais en pleine
  résolution.
* Navigation volontairement minimale : quatre onglets plus **une seule** pile
  d'écrans empilés, modélisée par un `StateFlow<Detail?>` dans `AppGraph`
  (album, artiste, playlist, paramètres, hors-ligne). Pas de bibliothèque de
  navigation pour cinq cas d'usage.
* Les cibles tactiles sont à 44-48 dp (recommandation Android) et les boutons
  purement iconiques portent tous une description pour TalkBack.
