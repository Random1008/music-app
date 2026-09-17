#!/usr/bin/env python3
"""Sauvegarde hors site de Hermes Music vers le VPS.

Pourquoi hors site : la bibliothèque vit sur un seul disque, dans un NAS auquel
le propriétaire n'a pas d'accès physique. Une copie sur le même disque ne protège
de rien. Le VPS est une autre machine, dans un autre bâtiment.

Déroulé :

  1. demande à Jellyfin de fabriquer SA propre archive de sauvegarde. C'est le
     seul moyen d'obtenir une base SQLite cohérente : copier `jellyfin.db` à chaud
     avec ses fichiers -wal et -shm peut produire une base corrompue. L'archive
     atterrit dans la configuration, donc elle part avec le reste.
  2. copie en incrémental (rsync) la musique, la configuration, le dépôt et les
     secrets vers une sauvegarde datée sur le VPS. Les fichiers inchangés sont
     liés par lien physique à la sauvegarde précédente : chaque sauvegarde semble
     complète et ne coûte presque rien en espace.
  3. VÉRIFIE que la copie est réelle, et pas seulement que rsync a renvoyé 0 :
       - nombre de morceaux identique des deux côtés ;
       - empreintes SHA-256 vérifiées PAR LE VPS sur un échantillon tiré au
         hasard (`sha256sum -c` exécuté là-bas) ;
       - test de restauration : des fichiers sont réellement RAPATRIÉS et
         comparés octet par octet, et l'archive Jellyfin est ouverte et testée ;
       - cohérence avec ce que l'index Jellyfin annonce.
  4. supprime les sauvegardes au-delà du nombre à conserver.

Une sauvegarde jamais restaurée n'est pas une sauvegarde : d'où l'étape 3.

Usage :
    tools/backup.py                 # sauvegarde complète + vérification
    tools/backup.py --dry-run       # montre, ne copie rien
    tools/backup.py --verify-only   # ne copie rien, revérifie la dernière
    tools/backup.py --keep 14       # conserve 14 sauvegardes (défaut 7)

Sortie : 0 si la sauvegarde est vérifiée, 1 sinon. Journal :
~/.local/state/hermes-music/backup.log
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import random
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HOME = os.path.expanduser("~")
MUSIC = os.path.join(HOME, "Music")
CONFIG = os.path.join(ROOT, "config", "jellyfin")
JELLYFIN_BACKUPS = os.path.join(CONFIG, "data", "data", "backups")
SECRETS = [os.path.join(ROOT, ".env"), os.path.join(HOME, ".config", "hermes-music")]
STATE = os.path.join(HOME, ".local", "state", "hermes-music")
LOG = os.path.join(STATE, "backup.log")
STATUS = os.path.join(STATE, "backup-status.json")
REMOTE_BASE = os.environ.get("REMOTE_BASE", "~/backups/hermes-music")
SSH_HOST = os.environ.get("BACKUP_SSH_HOST", "backup-vps")   # raccourci ~/.ssh/config

# La base SQLite vivante est exclue : l'archive Jellyfin (étape 1) la remplace par
# une copie cohérente. L'inclure exposerait à une base corrompue et inutilisable.
EXCLUDES_JELLYFIN = ["data/data/jellyfin.db", "data/data/jellyfin.db-wal",
                     "data/data/jellyfin.db-shm", "log/", "transcodes/"]
EXCLUDES_PROJET = ["config/", ".venv/", "__pycache__/", "*.pyc", "design/",
                   "web/apk/*.apk"]


def log(message: str) -> None:
    stamp = dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    line = "[%s] %s" % (stamp, message)
    print(line, flush=True)
    os.makedirs(STATE, exist_ok=True)
    with open(LOG, "a", encoding="utf-8") as fh:
        fh.write(line + "\n")


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)


def ssh(cmd: str, timeout: int = 180, **kw):
    return run(["ssh", "-o", "BatchMode=yes", SSH_HOST, cmd], timeout=timeout, **kw)


def load_env() -> dict:
    env = {}
    path = os.path.join(ROOT, ".env")
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip().strip('"').strip("'")
    return env


def jellyfin_call(path: str, method: str = "GET", body=None):
    env = load_env()
    base = env.get("JELLYFIN_URL", "http://127.0.0.1:8096")
    if "jellyfin:" in base:
        base = "http://127.0.0.1:8096"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        base.rstrip("/") + path, data=data, method=method,
        headers={
            "Authorization": 'MediaBrowser Token="%s", Client="Hermes Backup", '
                             'Device="NAS", DeviceId="hermes-backup", Version="1.0"'
                             % env.get("JELLYFIN_API_KEY", ""),
            "Content-Type": "application/json",
        })
    with urllib.request.urlopen(req, timeout=300) as resp:
        raw = resp.read().decode("utf-8", "replace")
    return json.loads(raw) if raw.strip() else None


# --------------------------------------------------------------------------- #
# 1. archive cohérente de Jellyfin
# --------------------------------------------------------------------------- #

def jellyfin_backup(essais: int = 3) -> str:
    """Demande l'archive à Jellyfin, avec réessais.

    Le serveur peut répondre 500 de façon passagère (scan en cours, verrou sur la
    base). Trois tentatives espacées valent mieux qu'un échec définitif.
    """
    dernier = None
    for tentative in range(1, essais + 1):
        try:
            payload = jellyfin_call("/Backup/Create?Options=All", "POST", {})
            zip_path = (payload or {}).get("Path") or ""
            local = os.path.join(CONFIG, zip_path.replace("/config/", "", 1))
            if not os.path.exists(local):
                raise RuntimeError("archive annoncée mais introuvable : %s" % local)
            log("archive Jellyfin : %s (%.1f Mo) — tentative %d"
                % (os.path.basename(local), os.path.getsize(local) / 1e6, tentative))
            break
        except Exception as exc:  # noqa: BLE001
            dernier = exc
            log("  archive Jellyfin : tentative %d/%d échouée (%s)"
                % (tentative, essais, str(exc)[:120]))
            time.sleep(5 * tentative)
    else:
        raise RuntimeError("archive Jellyfin impossible après %d tentatives : %s"
                           % (essais, str(dernier)[:200]))

    # On ne garde que les deux plus récentes : elles partent dans la sauvegarde,
    # inutile d'en accumuler une par jour dans la configuration.
    if os.path.isdir(JELLYFIN_BACKUPS):
        archives = sorted((f for f in os.listdir(JELLYFIN_BACKUPS)
                           if f.startswith("jellyfin-backup-") and f.endswith(".zip")),
                          reverse=True)
        for old in archives[2:]:
            os.remove(os.path.join(JELLYFIN_BACKUPS, old))
            log("  ancienne archive locale supprimée : %s" % old)
    return local


# --------------------------------------------------------------------------- #
# 2. copie incrémentale
# --------------------------------------------------------------------------- #

def rsync_copy(label: str, src: str, dest: str, link_dest: str, excludes, dry: bool) -> None:
    """[dest] DOIT porter le préfixe « hôte: » — sans lui, rsync croit à une copie
    local et tente d'écrire sur le NAS (le chemin distant n'y existe pas), avec une
    erreur trompeuse : « mkdir … Permission denied ». Panne rencontrée quatre fois
    avant d'être isolée. [link_dest], lui, reste un chemin SANS préfixe : rsync
    l'interprète du côté distant.

    --mkpath : rsync crée les niveaux manquants du chemin distant.
    """
    if ":" not in dest.split("/")[0]:
        raise RuntimeError("destination rsync sans préfixe d'hôte : %s" % dest)
    cmd = ["rsync", "-a", "--delete", "--numeric-ids", "--partial", "--mkpath", "--stats"]
    if link_dest:
        cmd += ["--link-dest", link_dest]
    for pattern in excludes:
        cmd += ["--exclude", pattern]
    if dry:
        cmd += ["--dry-run", "--itemize-changes"]
    cmd += [src, dest]
    result = run(cmd, timeout=7200)
    if result.returncode != 0:
        # Diagnostic immédiat : que voit le VPS à cet instant précis ?
        remote_dir = os.path.dirname(dest.split(":", 1)[-1].rstrip("/"))
        probe = ssh("ls -la %s 2>&1 | head -8; id" % remote_dir)
        raise RuntimeError("rsync %s a échoué (code %d) : %s\n--- état vu du VPS (%s) ---\n%s"
                           % (label, result.returncode, (result.stderr or "")[-300:],
                              remote_dir, probe.stdout.strip()))
    match = re.search(r"Number of regular files transferred: ([\d,]+)", result.stdout)
    count = int(match.group(1).replace(",", "")) if match else -1
    if count == 0:
        detail = "à jour"
    elif count > 0:
        detail = "%d fichier(s) transféré(s)" % count
    else:
        detail = "terminé"
    log("  %-18s %s" % (label, detail))


# --------------------------------------------------------------------------- #
# 3. vérification — le cœur du script
# --------------------------------------------------------------------------- #

def sha256(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def verify(snapshot: str) -> list:
    """Renvoie les problèmes trouvés. Liste vide = tout est vérifié."""
    problems = []

    local_mp3 = sum(1 for _, _, files in os.walk(MUSIC) for f in files if f.endswith(".mp3"))
    # -type f est indispensable : la bibliothèque contient un DOSSIER nommé
    # daniel.mp3, et « find -name '*.mp3' » le compte comme un morceau — ce qui
    # fabrique un écart de 1 avec le décompte local et déclenche une fausse alerte.
    remote_mp3 = int(ssh("find %s/music -type f -name '*.mp3' | wc -l" % snapshot).stdout.strip() or 0)
    log("  morceaux : %d sur le disque / %d sur le VPS" % (local_mp3, remote_mp3))
    if local_mp3 != remote_mp3:
        problems.append("nombre de morceaux différent (%d vs %d)" % (local_mp3, remote_mp3))

    sample = []
    for root_dir, _, files in os.walk(MUSIC):
        for f in files:
            if f.endswith(".mp3"):
                full = os.path.join(root_dir, f)
                sample.append((full, os.path.join("music", os.path.relpath(full, MUSIC))))
    random.shuffle(sample)
    sample = sample[:12]

    if sample:
        # Le VPS relit SES fichiers et confirme les empreintes calculées ici.
        # La comparaison porte sur les octets, pas sur des tailles ou des dates.
        expectation = "".join("%s  %s\n" % (sha256(full), rel) for full, rel in sample)
        checked = ssh("cd %s && sha256sum -c -" % snapshot,
                      timeout=900, input=expectation)
        lines = checked.stdout.splitlines()
        ok = sum(1 for l in lines if l.endswith(": OK"))
        failed = [l for l in lines if l.endswith(": FAILED")]
        for line in failed[:3]:
            log("      %s" % line)
        log("  empreintes : %d/%d identiques sur l'échantillon" % (ok, len(sample)))
        if ok != len(sample):
            problems.append("empreintes différentes sur %d fichier(s)"
                            % (len(sample) - ok))

    # Test de restauration RÉEL : on rapatrie et on compare octet par octet.
    if sample:
        tmp = tempfile.mkdtemp(prefix="restore_")
        restored = 0
        try:
            for full, rel in sample[:3]:
                target = os.path.join(tmp, os.path.basename(rel))
                res = run(["scp", "-q", "-o", "BatchMode=yes",
                           "%s:%s/%s" % (SSH_HOST, snapshot, rel), target], timeout=300)
                if res.returncode != 0:
                    problems.append("rapatriement impossible : %s" % rel)
                elif sha256(target) == sha256(full):
                    restored += 1
                else:
                    problems.append("fichier rapatrié différent : %s" % rel)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
        log("  restauration réelle : %d/%d fichier(s) récupérés et identiques"
            % (restored, len(sample[:3])))
        if restored == 0:
            problems.append("aucun fichier n'a pu être restauré")

    # L'archive Jellyfin est lisible, saine, et arrivée intacte.
    if os.path.isdir(JELLYFIN_BACKUPS):
        archives = sorted(f for f in os.listdir(JELLYFIN_BACKUPS) if f.endswith(".zip"))
        if archives:
            newest = os.path.join(JELLYFIN_BACKUPS, archives[-1])
            try:
                with zipfile.ZipFile(newest) as zf:
                    corrupt = zf.testzip()
                    names = zf.namelist()
                if corrupt:
                    problems.append("archive Jellyfin corrompue (%s)" % corrupt)
                else:
                    log("  archive Jellyfin : %d fichier(s), saine" % len(names))
                remote_zip = "%s/jellyfin-config/data/data/backups/%s" % (snapshot, archives[-1])
                size_local = os.path.getsize(newest)
                size_remote = ssh("stat -c %%s %s" % remote_zip).stdout.strip()
                if size_remote != str(size_local):
                    problems.append("archive Jellyfin : taille différente sur le VPS "
                                    "(%s vs %d)" % (size_remote or "absente", size_local))
            except zipfile.BadZipFile:
                problems.append("archive Jellyfin illisible")

    # Le disque ne doit pas contenir moins de morceaux que l'index Jellyfin.
    try:
        total = (jellyfin_call("/Items?IncludeItemTypes=Audio&Recursive=true&Limit=1")
                 or {}).get("TotalRecordCount", 0)
        log("  Jellyfin annonce %d morceaux, le disque en a %d" % (total, local_mp3))
        if total and local_mp3 < total:
            problems.append("moins de morceaux sur le disque (%d) que dans l'index (%d)"
                            % (local_mp3, total))
    except Exception as exc:  # noqa: BLE001
        log("  contrôle Jellyfin ignoré : %s" % str(exc)[:120])

    return problems


# --------------------------------------------------------------------------- #

def prune(keep: int) -> None:
    out = ssh("ls -1d %s/20* 2>/dev/null | sort -r" % REMOTE_BASE).stdout
    snapshots = [s.strip() for s in out.splitlines() if s.strip()]
    for old in snapshots[keep:]:
        ssh("rm -rf %s" % old, timeout=1200)
        log("  ancienne sauvegarde supprimée : %s" % os.path.basename(old))
    log("  %d sauvegarde(s) conservée(s) sur %d" % (min(len(snapshots), keep), len(snapshots)))


def write_status(snapshot: str, files: int, problems: list) -> int:
    payload = {
        "date": dt.datetime.now().isoformat(timespec="seconds"),
        "snapshot": snapshot,
        "files": files,
        "problems": problems,
        "ok": not problems,
    }
    os.makedirs(STATE, exist_ok=True)
    with open(STATUS, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=1)
    if problems:
        log("ÉCHEC : %d problème(s) — %s" % (len(problems), " ; ".join(problems)))
        return 1
    log("OK — sauvegarde vérifiée : %d fichiers, restauration réellement testée" % files)
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--keep", type=int, default=7, help="sauvegardes conservées")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--verify-only", action="store_true")
    ap.add_argument("--skip-jellyfin-backup", action="store_true")
    args = ap.parse_args()

    log("=" * 66)
    log("sauvegarde%s%s" % (" (essai à blanc)" if args.dry_run else "",
                            " (vérification seule)" if args.verify_only else ""))

    if ssh("echo ok").stdout.strip() != "ok":
        log("ÉCHEC : VPS injoignable en SSH (raccourci 'backup-vps')")
        return 1

    if args.verify_only:
        out = ssh("ls -1d %s/20* 2>/dev/null | sort -r" % REMOTE_BASE).stdout
        found = [s.strip() for s in out.splitlines() if s.strip()]
        if not found:
            log("ÉCHEC : aucune sauvegarde à vérifier")
            return 1
        log("vérification de %s" % found[0])
        return write_status(found[0],
                            int(ssh("find %s -type f | wc -l" % found[0]).stdout.strip() or 0),
                            verify(found[0]))

    # Un hoquet de Jellyfin ne doit pas annuler la sauvegarde des fichiers : on
    # note le problème, on continue, et le code de sortie restera non nul.
    problemes_amont = []
    if not args.skip_jellyfin_backup:
        try:
            jellyfin_backup()
        except Exception as exc:  # noqa: BLE001
            problemes_amont.append("archive Jellyfin impossible : %s" % str(exc)[:150])
            log("ATTENTION : archive Jellyfin impossible — la sauvegarde des fichiers "
                "continue quand même")

    stamp = dt.datetime.now().strftime("%Y-%m-%d_%H%M")
    snapshot = "%s/%s" % (REMOTE_BASE, stamp)
    previous = ssh("ls -1d %s/20* 2>/dev/null | sort -r | head -1" % REMOTE_BASE).stdout.strip()

    log("destination : %s%s" % (snapshot,
                                "" if not previous else "   (liens vers %s)"
                                % os.path.basename(previous)))
    # Seul le dossier de la sauvegarde est créé ici, et il est VÉRIFIÉ. Le reste
    # de l'arborescence est laissé à rsync (--mkpath) : pré-créer les sous-dossiers
    # en plus faisait échouer l'un ou l'autre des deux mécanismes selon l'ordre
    # d'arrivée, panne reproduite trois fois. Isolation faite : « parent absent
    # sans --mkpath -> échec », « parent absent avec --mkpath -> OK », « parent
    # présent -> OK ». Un seul mécanisme, celui qui marche.
    if not args.dry_run:
        ssh("mkdir -p %s && chmod 700 %s %s" % (snapshot, snapshot, REMOTE_BASE))
        if snapshot not in ssh("ls -d %s" % snapshot).stdout:
            log("ÉCHEC : dossier de sauvegarde non créé : %s" % snapshot)
            return 1
        log("  dossier de destination prêt : %s" % snapshot)

    for label, src, excludes in (
        ("music", MUSIC + "/", []),
        ("jellyfin-config", CONFIG + "/", EXCLUDES_JELLYFIN),
        ("projet", ROOT + "/", EXCLUDES_PROJET),
    ):
        # Le préfixe « hôte: » n'est QUE pour rsync (les appels ssh, eux, reçoivent
        # un chemin nu). Son oubli fait écrire rsync en local, avec une erreur
        # trompeuse — d'où le contrôle dans rsync_copy().
        rsync_copy(label, src, "%s:%s/%s/" % (SSH_HOST, snapshot, label),
                   ("%s/%s/" % (previous, label)) if previous else "", excludes, args.dry_run)

    # Les secrets mélangent un fichier (.env) et un dossier : on distingue les
    # deux, sinon rsync traite le fichier comme un dossier et échoue.
    for name in SECRETS:
        label = os.path.basename(name)
        if os.path.isdir(name):
            dest = "%s:%s/secrets/%s/" % (SSH_HOST, snapshot, label)
            src = name.rstrip("/") + "/"
            link = ("%s/secrets/%s/" % (previous, label)) if previous else ""
        else:
            dest = "%s:%s/secrets/" % (SSH_HOST, snapshot)
            src = name
            link = ("%s/secrets/" % previous) if previous else ""
        rsync_copy("secret:" + label, src, dest, link, [], args.dry_run)
    if not args.dry_run:
        ssh("chmod -R go-rwx %s/secrets" % snapshot)

    if args.dry_run:
        log("essai à blanc terminé : rien n'a été copié, rien n'a été supprimé")
        return 0

    log("taille : %s" % ssh("du -sh %s | cut -f1" % snapshot).stdout.strip())
    log("vérification")
    problems = verify(snapshot)
    prune(args.keep)
    files = int(ssh("find %s -type f | wc -l" % snapshot).stdout.strip() or 0)
    return write_status(snapshot, files, problems + problemes_amont)


if __name__ == "__main__":
    sys.exit(main())
