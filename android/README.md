# Hermes Music — application Android

Application Android native (Kotlin + Jetpack Compose) qui consomme **Jellyfin**
comme source de vérité : bibliothèque, artistes, albums, morceaux, recherche,
favoris, lecture. Aucune donnée musicale n'est dupliquée côté app.

Interface en français, thème sombre « Nocturne » repris de la maquette validée
(fond `#08090F`, surfaces `#1F2130`/`#171926`, accent `#7C5CFF`, texte `#E9E9ED`,
secondaire `#9397AB`, libellés monospace en majuscules).

## Ce que fait l'application aujourd'hui

| Écran | Contenu |
|---|---|
| Connexion | adresse du serveur, utilisateur, mot de passe (jamais conservé) |
| Accueil | compteurs de la bibliothèque, albums récents, derniers ajouts |
| Recherche | morceaux, albums, artistes — recherche instantanée |
| Bibliothèque | Albums / Artistes / Morceaux / **Favoris** |
| Page album | pochette, artiste, année, durée, Lecture, Aléatoire, cœur, pistes numérotées |
| Page artiste | photo (ou pochette du premier album), ses albums, ses titres les plus écoutés |
| Playlists | liste des playlists Jellyfin |
| Mini-lecteur | permanent au-dessus des onglets : jaquette, titre, lecture/pause, suivant, progression |
| Lecteur plein écran | seek, précédent/suivant, aléatoire, répétition, **favori**, **file d'attente** |
| File d'attente | liste de ce qui va suivre, saut direct à un morceau |
| Notification | commandes lecture/pause/suivant, écran verrouillé, casque Bluetooth |

La lecture continue quand l'application passe en arrière-plan, quand l'écran est
verrouillé et quand on change d'écran : le lecteur vit dans un service, pas dans
l'interface.

## Où le projet est compilé, et pourquoi pas ici

Le serveur de développement (le NAS) est **Linux** et n'a aucun outil Android :
pas de JDK, pas de SDK, et les outils officiels (aapt2 notamment) ne sont publiés
que pour x86_64. La compilation se fait donc sur un **hôte de build x86_64**
(8 cœurs, 22 Go de RAM, JDK 17 + SDK Android), et l'APK est rapatrié pour être
installé sur le téléphone.

```
NAS (Linux)                    Hôte de build (x86_64)
  code source ── git push ──▶ clone ── gradle assembleDebug ──▶ APK
    ▲                                                            │
    └──────────────────── rapatriement ◀──────────────────────────┘
```

## Versions (vérifiées dans les dépôts, pas supposées)

| Composant | Version | Note |
|---|---|---|
| JDK | 17 | exigé par AGP 9.x |
| Gradle | 9.6.1 | |
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
├── MainActivity.kt     point d'entrée Compose
├── core/
│   ├── AppGraph.kt     injection MANUELLE (pas de Hilt/Koin) + session + OkHttp
│   └── Theme.kt        design Nocturne
├── data/
│   ├── SettingsStore.kt  DataStore : URL, jeton, userId, DeviceId stable
│   └── MusicRepository.kt accès Jellyfin (requêtes, URL pochette, URL audio)
├── network/
│   ├── JellyfinApi.kt    interface Retrofit
│   └── JellyfinModels.kt DTO
├── player/
│   ├── PlaybackService.kt   MediaSessionService — le lecteur VIT ici, pas dans l'UI
│   ├── PlayerConnection.kt  télécommande (MediaController) + état exposé à l'UI
│   └── PlaybackReporter.kt  déclaration des lectures à Jellyfin
└── ui/
    ├── LoginScreen.kt  connexion (le mot de passe n'est jamais conservé)
    ├── Shell.kt        navigation 4 onglets + mini-lecteur
    ├── Screens.kt      Accueil, Recherche, Bibliothèque, Playlists
    ├── Detail.kt       pages album et artiste
    └── PlayerScreen.kt lecteur plein écran + file d'attente
```

Un seul module tant que l'app reste de cette taille : découper en
`core/ data/ feature/` maintenant ne ferait qu'ajouter du temps de build et du
câblage pour un projet à un seul développeur.

## Compiler

```bash
cd android
cp /chemin/vers/keystore.properties .   # hors dépôt (voir ci-dessous)
export ANDROID_HOME=$HOME/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew :app:assembleDebug
```

## Signature : le point à ne pas rater

Un keystore de développement **stable** est utilisé pour signer les builds debug
ET release. Sans cela, chaque compilation produit une signature différente et il
faut **désinstaller l'app** avant de réinstaller la nouvelle version — ce qui
efface les réglages et casse l'usage quotidien.

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
* **Un seul client OkHttp** partagé par Retrofit, Coil et le lecteur audio : mêmes
  délais, même en-tête d'authentification, même connexions.
* **Le lecteur ne vit pas dans l'interface.** Il tourne dans un
  `MediaSessionService` et l'UI ne garde qu'un `MediaController`. C'est ce qui fait
  que la musique survit au changement d'écran, à l'arrière-plan et au verrouillage
  — et qu'on ne recrée jamais le lecteur.
* **Déclaration des lectures.** L'app POSTe `/Sessions/Playing` au changement de
  morceau et `/Sessions/Playing/Progress` toutes les 10 s. Sans ces appels,
  Jellyfin ignore ce que l'app joue : pas d'historique, pas de « Reprendre la
  lecture », pas de compteur d'écoute. Un échec de déclaration n'interrompt
  jamais la musique (erreurs avalées volontairement).
* Lecture audio : `/Audio/<id>/stream?static=true` (renvoie `audio/mpeg` avec
  support du `Range`, donc un seek correct). ``/Audio/<id>/universal`` renvoie du
  transcodé sans `Range` — seek cassé, à éviter comme chemin principal.
* Pochettes toujours demandées avec un `maxHeight` raisonnable, jamais en pleine
  résolution.
* Navigation volontairement minimale : quatre onglets plus **un seul** écran
  empilé (album ou artiste) modélisé par un `StateFlow<Detail?>` dans `AppGraph`.
  Pas de bibliothèque de navigation pour deux cas d'usage.
