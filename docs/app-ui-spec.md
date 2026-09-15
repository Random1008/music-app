# Hermes Music — Spécification UI/UX (application Android)

Ce document décrit l'interface à concevoir pour l'application Android
**Hermes Music**. L'app est un client de lecture pour une bibliothèque musicale
personnelle servie par Jellyfin (elle n'héberge pas la musique elle-même).

## Stack cible

- Kotlin + Jetpack Compose (Material 3)
- Architecture modulaire : `app/`, `core/` (network, database, player, common),
  `data/` (jellyfin, repository, models), `feature/` (home, search, library,
  album, artist, playlist, player, settings)
- Lecteur : Media3 / ExoPlayer (streaming + local, lecture en arrière-plan,
  notification, écran verrouillé, Bluetooth)

## Navigation principale — 4 onglets

1. **Accueil**
2. **Recherche**
3. **Bibliothèque**
4. **Playlists**

Un **mini-player persistant** reste visible au-dessus de la barre d'onglets,
sur tous les écrans.

```
┌───────────────────────────────┐
│ Hermes Music                  │
│                               │
│ Bonjour                       │
│                               │
│ ▶ Reprendre la lecture        │
│                               │
│ Albums récents                │
│ [Album] [Album] [Album]       │
│                               │
│ Artistes                      │
│ [Artist] [Artist] [Artist]    │
│                               │
├───────────────────────────────┤
│ ♫ Titre en cours          ▶   │
├───────────────────────────────┤
│   Accueil  Recherche  Biblio  │
└───────────────────────────────┘
```

## Écrans

### Accueil
Reprendre la lecture · albums récents · récemment ajouté · artistes populaires ·
playlists · favoris · récemment joué. Recommandations simples issues uniquement
de la bibliothèque personnelle (pas de moteur complexe).

### Recherche
Recherche globale instantanée (morceaux, artistes, albums, playlists), rapide.
Filtres par type, tri. États : repos, saisie, résultats, aucun résultat.

### Bibliothèque
Sections/onglets : Albums · Artistes · Morceaux · Favoris. Tri et chargement
progressif (pagination / lazy loading), cache des images.

### Page Album
```
┌───────────────────────────────┐
│           [ COVER ]           │
│ Album Name                    │
│ Artist Name                   │
│ 2026 • 12 titres • 42 min     │
│                               │
│ ▶ Lecture       🔀 Aléatoire  │
│                               │
│ 01 Titre un             3:42  │
│ 02 Titre deux           4:01  │
│ 03 Titre trois          3:28  │
└───────────────────────────────┘
```
Actions : lecture, shuffle, favori, ajout à une playlist.

### Page Artiste
Image · nom · albums · morceaux · favoris.

### Lecteur (plein écran)
```
┌───────────────────────────────┐
│             ↓                 │
│         [ COVER ]             │
│ Titre                         │
│ Artiste                       │
│                               │
│ ─────────●─────────────       │
│ 1:42                    4:12  │
│                               │
│      ◀     ▶     ▶            │
│                               │
│ 🔀               🔁            │
└───────────────────────────────┘
```
Fonctions : play/pause, précédent/suivant, seek, barre de progression, volume,
shuffle, repeat, file d'attente, favori, ajout à une playlist.

### Mini-player (persistant)
Jaquette · titre · artiste · play/pause · accès au lecteur complet.

### Playlists
Créer · renommer · supprimer · ajouter/retirer/réordonner des morceaux ·
lecture · shuffle. Utilise les playlists Jellyfin côté serveur.

### Favoris
Ajout/retrait, synchronisé avec Jellyfin.

### Connexion serveur
URL Jellyfin · utilisateur · mot de passe. Ne jamais stocker le mot de passe en
clair (jeton d'accès réutilisé ensuite).

### Paramètres
Thème sombre/clair · accent personnalisable · taille de texte · téléchargements.

## Design

- **Sombre par défaut**, minimaliste, moderne, fluide
- Utilisable à **une main** : cibles tactiles ≥ 44 dp
- Hiérarchie par la typographie et l'espace, pas par la décoration
- Animations discrètes et utiles, respect de « réduire les animations »
- Thème clair + couleur d'accent configurables
- Contraste accessible, libellés de boutons explicites

## Hors-ligne (V0.8)

Téléchargement local de morceaux/albums/playlists accessibles à l'utilisateur,
lecture sans connexion, affichage de l'espace occupé.
