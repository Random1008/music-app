# Accès au serveur — adresses et exposition

## Adresses

| Usage | Adresse | Depuis où |
|---|---|---|
| **Jellyfin (public)** | `https://dh4300plus-59ae.tailb74e62.ts.net` | Partout, sans VPN (HTTPS, certificat Let's Encrypt) |
| **Page d'installation de l'app (publique)** | `https://dh4300plus-59ae.tailb74e62.ts.net:10000/apk/` | Partout, sans VPN |
| APK — lien direct | `.../apk/hermes-music-<version>-debug.apk` | idem |

Les adresses **internes** (IP du tailnet, IP du réseau local) ne sont pas écrites
ici : elles dépendent du réseau et n'ont rien à faire dans un dépôt public. Elles
se retrouvent sur place (`tailscale ip -4`, `ip route`).

## Configuration Tailscale

Nom public du serveur (celui qui sert le Funnel) :

```
dh4300plus-59ae.tailb74e62.ts.net
```

Exposition via Funnel (`tailscale serve status`) :

| Port | Cible locale | Visibilité |
|---|---|---|
| 443 | `127.0.0.1:8096` (Jellyfin) | **public** |
| 10000 | `127.0.0.1:8091` (page d'installation + APK) | **public** |
| 8443 | `127.0.0.1:5800` (noVNC) | tailnet seulement |

Tailscale Funnel n'accepte que les ports **443, 8443 et 10000**.

Commandes utiles :

```bash
tailscale serve status          # routes actuelles
tailscale funnel --https=443 off   # couper une exposition publique
tailscale funnel --bg --https=<port> http://127.0.0.1:<port_local>
```

## Avertissements de sécurité

* Les ports 443 et 10000 sont accessibles à **tout Internet** : robots et scanners
  inclus. La seule barrière est le mot de passe Jellyfin.
* L'unique compte Jellyfin est **administrateur**. Pour tout usage exposé, créer un
  compte **non-admin** et réserver l'admin à l'administration.
* Maintenir l'image Jellyfin à jour (`docker compose pull && docker compose up -d`).
* Pour un accès « sur invitation » plutôt que public, mettre **Cloudflare Access**
  devant — prévu en V0.7.
* Ne jamais exposer Docker, SSH, ni l'interface d'administration du NAS.

## Migration Netbird → Tailscale

Tailscale remplace Netbird comme accès distant. Netbird reste installé le temps de
la vérification ; son retrait (root requis) se fait **après** avoir confirmé
l'accès par Tailscale :

```bash
sudo systemctl stop netbird
sudo systemctl disable netbird
sudo apt-get remove --purge -y netbird
```
