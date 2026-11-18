# Monitoring de Yente depuis SwiftCheck — conception

Date : 2026-11-18 · Branche : `yent-monitoring`

## 1. Intention

SwiftCheck publie de nombreux datasets (≈ 250 aujourd'hui, un par parser) dans Yente, qui les indexe dans
Elasticsearch. L'utilisateur veut, **sans se connecter au serveur** :

1. s'assurer que **chaque dataset publié est bien chargé et indexé** dans Yente, dans la bonne version ;
2. suivre **l'avancement de l'indexation en pourcentage**, parce qu'elle prend parfois longtemps ;
3. être **prévenu** quand quelque chose ne va pas (dataset publié mais non indexé, échec d'indexation…) ;
4. piloter Yente : **réindexer (un dataset ou tout), voir les logs, redémarrer** ;
5. **réduire la durée des indexations** : aujourd'hui chaque publication reconstruit les 250 index (voir §6.0).

Il veut **les deux modes** : consulter l'état à la demande (un écran) **et** une surveillance continue qui
ouvre des alertes.

## 2. Objectifs, non-objectifs, anti-objectifs

**Objectifs**
- Écran « Yente » : état global, pourcentage global, état et pourcentage par dataset, historique d'indexation.
- Surveillance en tâche de fond qui ouvre et ferme automatiquement des alertes.
- Logs des conteneurs Yente et Elasticsearch lisibles depuis l'application.
- Redémarrage de Yente (et d'Elasticsearch) depuis l'application, avec confirmation et trace de qui l'a fait.

**Non-objectifs**
- Remplacer un outil de supervision généraliste (Prometheus, Grafana, Kibana).
- Gérer d'autres conteneurs que ceux de la stack yente.
- Notifications hors de l'application (e-mail, Slack) : pas dans cette version.

**Anti-objectifs** (échec même si « ça marche »)
- Donner au core un accès Docker complet (monter `docker.sock` dans le core) : c'est l'équivalent d'un accès root.
- Un écran qui affiche « 100 % » alors qu'un dataset publié n'est pas interrogeable (alias manquant, ancienne
  version encore servie, nombre d'entités différent).
- Une avalanche d'alertes pendant une réindexation normale ou un redémarrage volontaire.
- Ralentir le criblage : le monitoring ne doit pas charger Yente ni Elasticsearch de façon notable.

## 3. Situation actuelle (constatée le 2026-11-18)

- **Stacks** : core (`swift-check-docker/application`) et yente (`swift-check-docker/yente`) sont des stacks
  Docker séparées, sur le même VPS et le même réseau `swiftcheck_network`. Une partie pourra aller sur un autre
  serveur : les adresses entre stacks passent par des variables (pas de compose unique).
- **Déjà en place** : `YenteManifestService` écrit `manifest.yml` et appelle `POST /updatez` ;
  `YenteController` expose `POST /api/yente/reindex` ; `DatasetPublisher` écrit
  `<yenteDataFolder>/<dataset>/<dataset>.ndjson`.
- **Yente 5.5.0** : **un index par dataset**, nommé `yente-entities-<dataset>-<hash>-<version>`, rattaché à
  l'alias `yente-entities`. Exemple vérifié : `fr_pep_senate` → index `…-20261111191612`, 2 675 documents,
  fichier ndjson de 2 675 lignes.
- **`GET /catalog`** donne, pour chaque dataset : `version` (celle du fichier vu par Yente), `index_version`,
  `index_current`, `load`, `entities_url`. `entity_count` est `null` pour nos fichiers.
- **Journal d'indexation** dans l'index Elasticsearch `yente-audit-log-1` : événements `reindex_started`,
  `reindex_completed`, `reindex_failed`, `index_alias_rollover_complete`, `cleanup_index_deleted`, avec
  `dataset`, `dataset_version`, `index`, `message`, `timestamp` (ms). En local : 32 755 démarrages,
  32 740 fins, 13 échecs.
- **Verrou** : l'index `yente-locks` (vide au repos) contient un document pendant une indexation.
- **Alertes** : `ParserAlert` (types `FAILED`, `BLOCKED`, `STALE`) est rattachée à un parser, ouverte et résolue
  automatiquement par `AlertService` (tâche `@Scheduled`). Écran « Alertes » existant.
- **Droits** : rôles `ADMIN` et `TESTER` ; `/api/config/**` réservé à `ADMIN`, le reste de `/api/**` ouvert
  aux deux rôles.
- **Sécurité** : la stack yente publie `9200:9200` sans authentification (`xpack.security.enabled=false`).

## 4. Contraintes

- Liquibase : ne jamais modifier un changeSet déjà appliqué ; toute évolution de schéma passe par un nouveau
  changeSet (changeSets 156+ sans id numérique ni chemin `D:/`).
- Stacks séparées : toute adresse vers la stack yente (API, Elasticsearch, proxy Docker) est une variable,
  modifiable dans l'écran Configuration.
- Le core tourne sous Windows en développement et en conteneur Linux sur le VPS.
- Code et messages en français, comme le reste du projet.

## 5. Approches étudiées

| Approche | Principe | Pour | Contre |
|---|---|---|---|
| **A. Tout dans le core** *(choisie)* | Le core interroge l'API Yente et Elasticsearch ; un docker-socket-proxy restreint dans la stack yente sert les logs et le redémarrage | Aucun nouveau programme ; tout en Java à côté de `YenteManifestService` ; un changement de serveur = deux URL | Le core doit joindre Elasticsearch et le proxy par le réseau |
| B. Agent dans la stack yente | Un service dédié calcule l'état, lit les logs, redémarre ; le core relaie | Elasticsearch et Docker jamais exposés hors de la stack | Un programme de plus à écrire et déployer ; il doit quand même connaître les publications du core |
| C. Outils existants (Dozzle, Portainer) | Liens depuis l'application | Presque rien à coder | Ni pourcentage par dataset rapporté aux publications, ni alertes par parser : ne répond pas au besoin principal |

**Choix de l'utilisateur : A.**

## 6. Conception

### 6.0 Réindexer seulement ce qui a changé (lot 0)

**Constat dans le code de Yente 5.5.0** (lu dans le conteneur) :
- `POST /updatez` appelle `update_index(force=True)` (`yente/routers/admin.py`) : **tous** les datasets sont
  reconstruits, même inchangés. Il n'existe pas d'API pour réindexer un seul dataset.
- Le core appelle `/updatez` après **chaque** publication (`YenteManifestService.reloadYente`, appelé par
  `addDataset` et `removeDataset`, plus l'appel différé de `manifest-refresh-delay`). D'où les ≈ 32 700
  réindexations du journal.
- Sans `force`, `update_index` compare la version du fichier à celle de l'index et ignore les datasets à jour
  (`yente/search/indexer.py`, `index_entities` → « No update needed »). Pour un fichier local, la version est
  la **date de modification** du `.ndjson` (`yente/data/dataset.py`), sauf si le manifest fournit `last_export`.
  En fin de passage, `delete_old_indices` supprime les index des datasets retirés du manifest.
- Ce mode non forcé est lancé par la tâche `YENTE_CRONTAB` uniquement si `YENTE_AUTO_REINDEX=true`
  (`yente/app.py`, `refresh_catalog_cron_task`). Il est à `false` aujourd'hui : la tâche relit le manifest sans
  indexer.

**Changements** :
1. Stack yente : `YENTE_AUTO_REINDEX=true` (défaut dans le compose) et `YENTE_CRONTAB` passe à
   `*/2 * * * *` *(décision de l'utilisateur)* : toutes les 2 minutes, Yente relit le manifest et réindexe
   seulement les datasets dont le fichier a changé.
2. Core : `addDataset` / `removeDataset` **n'appellent plus `/updatez`**, et l'appel différé
   (`manifest-refresh-delay`, `reloadYenteAfterManifestRefresh`) disparaît. Écrire le fichier et le manifest
   suffit : prise en compte en 2 minutes au plus, plus la durée d'indexation du dataset.
3. **Réindexer un dataset** : nouveau `POST /api/yente/datasets/{name}/reindex`. Le core met la date de
   modification du `.ndjson` publié à maintenant (`Files.setLastModifiedTime`, avec les mêmes nouvelles
   tentatives que `DatasetPublisher` sous Windows). Yente y voit une nouvelle version et ne reconstruit que cet
   index au passage suivant. Erreur 409 si le dataset n'a pas de fichier publié dans `yenteDataFolder`.
4. **Tout reconstruire** : l'actuel `POST /api/yente/reindex` garde `/updatez` (forcé), réservé à la
   réparation. Le bouton est renommé « Tout reconstruire », avec une confirmation qui prévient de la durée.
5. Yente tient un verrou (`yente-locks`) pendant un passage : un dataset publié pendant une indexation longue
   est traité au passage suivant. L'écran l'affiche comme « en attente » (état `OUTDATED` sans indexation en
   cours, voir §6.2), et l'alerte `NOT_INDEXED` n'est levée qu'après `not-indexed-after`.

**À vérifier par l'implémentation** : tout ce qui attend aujourd'hui la fin d'une réindexation après une
publication (le bouton « Tester » d'un parser, `ParserTestService`, la propriété `manifest-refresh-delay`).
Ces endroits doivent désormais attendre que le dataset soit servi dans la version publiée. Ils peuvent
utiliser l'état calculé par `YenteMonitorService` (§6.2).

### 6.1 Configuration

Nouveaux champs dans `ApplicationConfigEntity` (nouveau changeSet), visibles dans l'écran Configuration,
section Yente :

| Champ | Défaut (variable d'env.) | Rôle |
|---|---|---|
| `yenteIndexUrl` | `YENTE_INDEX_URL`, `http://localhost:9200` | Elasticsearch de Yente |
| `yenteIndexPrefix` | `yente-entities` | Préfixe des index et nom de l'alias |
| `dockerProxyUrl` | `YENTE_DOCKER_PROXY_URL`, vide | Proxy Docker ; vide = logs et redémarrage désactivés (boutons masqués) |
| `yenteContainerName` | `yente-sanctions-api` | Conteneur Yente |
| `indexContainerName` | `yente-elasticsearch` | Conteneur Elasticsearch |

Réglages techniques dans `application.properties` : `swiftcheck.yente.monitor.check-interval` (PT5M),
`swiftcheck.yente.monitor.not-indexed-after` (PT30M), `swiftcheck.yente.monitor.restart-grace` (PT5M).

### 6.2 Calcul de l'état (`YenteMonitorService`)

Une méthode `snapshot()` produit l'état complet, utilisée à la fois par l'écran et par la surveillance.

**État global**
- Yente : `GET /healthz` (vivant) et `GET /readyz` (prêt), temps de réponse.
- Elasticsearch : `_cluster/health` (vert, jaune ou rouge), espace disque (`_cat/allocation`), comparé aux seuils
  de disque configurés.
- Indexation en cours : `yente-locks` non vide, ou un `reindex_started` sans fin pour au moins un dataset.
- Pourcentage global = datasets `OK` / datasets attendus. **Datasets attendus** = datasets des parsers actifs
  ayant au moins une publication réussie + datasets du manifest sans parser (par exemple `eu_sanctions`).
  Sont exclus du calcul, et affichés à part : `NOT_PUBLISHED` et `ORPHAN`. *(Choix de conception.)*
- Dernière indexation complète : heure du dernier `reindex_completed`.

**Liste des datasets** = union de quatre sources, rapprochées par nom de dataset :
1. parsers actifs (`Parser.datasetName`) : ce qui *devrait* être servi ;
2. `manifest.yml` : ce que le core a déclaré ;
3. `GET /catalog` : ce que Yente a lu (`version`, `index_version`, `index_current`) ;
4. index Elasticsearch `yente-entities-*` (+ alias) et journal `yente-audit-log-1`.

Pour chaque dataset :
- **Entités attendues** = nombre de lignes du fichier `.ndjson` publié, mis en cache par (date de modification,
  taille) pour ne pas relire 250 fichiers à chaque rafraîchissement.
- **Index servi** = l'index rattaché à l'alias pour ce dataset, avec sa version et son nombre de documents.
- **Index en construction** = un index de version plus récente non encore rattaché, ou un `reindex_started`
  sans `reindex_completed`/`reindex_failed` après lui ; pourcentage = documents écrits / entités attendues
  (plafonné à 99 % tant que le basculement d'alias n'a pas eu lieu).
- **Dernier événement** du journal (type, heure, message).

**États d'un dataset** (un seul, par ordre de gravité) :

| État | Condition |
|---|---|
| `NOT_PUBLISHED` | parser actif qui n'a encore jamais publié (information, hors pourcentage) |
| `NOT_IN_MANIFEST` | parser actif ayant publié, mais dataset absent du manifest |
| `NOT_LOADED` | dans le manifest mais absent du catalog de Yente (manifest pas encore relu) |
| `FAILED` | dernier événement = `reindex_failed` |
| `NOT_SERVED` | aucun index rattaché à l'alias (cas du bug du préfixe d'alias) |
| `INDEXING` | index en construction |
| `OUTDATED` | `index_current = false` et aucune indexation en cours |
| `COUNT_MISMATCH` | index servi à jour mais nombre de documents ≠ entités attendues |
| `OK` | index servi à jour, nombre de documents = entités attendues |
| `ORPHAN` | servi par Yente mais sans parser actif (information, pas une erreur) |

Les datasets historiques qui ne viennent pas d'un parser (par exemple `eu_sanctions`) apparaissent selon leur
présence dans le manifest et le catalog. Les entités attendues ne sont connues que si le fichier est dans
`yenteDataFolder`. Quand elles sont inconnues, un dataset servi à jour est `OK` avec la mention
« nombre non vérifié » : il compte dans le pourcentage, mais l'écran montre qu'on n'a pas pu comparer les
nombres. Le pourcentage de l'indexation en cours est alors remplacé par le nombre de documents écrits.
*(Choix de conception.)*

### 6.3 API (`/api/yente`)

| Méthode | Chemin | Rôle requis | Description |
|---|---|---|---|
| GET | `/status` | ADMIN, TESTER | `snapshot()` complet (global + datasets) |
| GET | `/datasets/{name}/events?limit=50` | ADMIN, TESTER | historique du journal d'indexation |
| POST | `/datasets/{name}/reindex` | ADMIN, TESTER | réindexe ce seul dataset (§6.0) |
| POST | `/reindex` | ADMIN | existant : « Tout reconstruire » (forcé) ; réservé à l'admin car long |
| GET | `/logs?container=yente\|index&tail=500&since=…` | ADMIN | lignes de log (texte) |
| POST | `/restart?container=yente\|index` | ADMIN | redémarrage via le proxy |
| GET | `/actions?limit=50` | ADMIN | historique des actions (réindexation, redémarrage) |

`container` est une **clé** (`yente` ou `index`) traduite en nom de conteneur depuis la configuration, jamais un
nom libre. Erreurs : 409 avec `{"message": "..."}`, comme aujourd'hui.

**Trace des actions** : nouvelle table `yente_action` (action, conteneur, utilisateur Keycloak, date, résultat,
message), alimentée **uniquement par les actions manuelles** (Réindexer ce dataset, Tout reconstruire, Redémarrer). Les
réindexations automatiques après publication n'y sont pas : elles figurent déjà dans le journal de Yente.
*(Choix de conception.)*

### 6.4 Écran « Yente » (route `/yente`, menu à côté de « Alertes »)

```
┌ Yente ─────────────────────────────────────────────────────────────────┐
│ ● Yente prêt  5.5.0     ● Elasticsearch vert   Disque 41 Go libres     │
│ Indexation en cours : 3 datasets     [Tout reconstruire] [Redémarrer▾] │
│ ████████████████████████░░░░  212 / 250 datasets à jour — 85 %         │
├────────────────────────────────────────────────────────────────────────┤
│ [Tous] [Problèmes 7] [En cours 3] [OK 212] [Orphelins 2]  🔎 dataset… │
│ Dataset                 Parser         État        Avancement   Dern.  │
│ fr_pep_local_enterpr.   FR entreprises INDEXING    ███░ 72 %    14:02  │
│ de_pep_landtage         DE Landtage    FAILED      —            13:55  │
│ fr_pep_senate           FR Sénat       OK          2 675/2 675  11/11  │
└────────────────────────────────────────────────────────────────────────┘
 Clic sur une ligne → panneau : versions (fichier / catalog / index servi),
 entités attendues / indexées, historique du journal, lien vers le parser,
 bouton [Réindexer ce dataset].
 Onglet « Logs » (ADMIN) : choix Yente / Elasticsearch, 500 dernières lignes,
 filtre texte, rafraîchissement automatique, niveau ERROR surligné.
```

- Rafraîchissement automatique : toutes les 5 s pendant une indexation, toutes les 30 s sinon.
- Redémarrer : menu « Yente » / « Elasticsearch », boîte de confirmation qui précise que le criblage sera
  indisponible une à deux minutes.
- Après un redémarrage ou une réindexation, l'écran affiche l'avancement sans recharger la page.
- L'écran reprend le style existant (Tailwind, `status-badge`, SweetAlert pour les confirmations).

### 6.5 Surveillance et alertes

Tâche `@Scheduled` (`check-interval`, 5 min) qui appelle `snapshot()` et met à jour les alertes.

- **Alertes par parser** (nouveaux `AlertType`) :
  - `NOT_INDEXED` : dataset publié depuis plus de `not-indexed-after` sans être servi à jour (`NOT_IN_MANIFEST`,
    `NOT_LOADED`, `NOT_SERVED`, `OUTDATED`) ;
  - `INDEX_FAILED` : état `FAILED` ;
  - `COUNT_MISMATCH` : état `COUNT_MISMATCH` constaté à deux passages de suite.
- **Alertes globales** (sans parser : `ParserAlert.parser` devient nullable, nouveau changeSet) :
  - `YENTE_DOWN` : `/readyz` en échec à deux passages de suite ;
  - `INDEX_UNHEALTHY` : cluster rouge, ou disque sous le seuil `high` ;
  - `ALIAS_MISSING` : au moins un dataset `NOT_SERVED` alors que son index existe.
- Résolution automatique dès que la condition disparaît (même logique que `AlertService.raise/resolveAll`).
- Pendant `restart-grace` après un redémarrage lancé depuis l'application, `YENTE_DOWN` n'est pas ouvert.
- L'écran « Alertes » affiche ces nouveaux types ; une alerte globale affiche « Yente » à la place du parser.

### 6.6 Logs et redémarrage : proxy Docker

Ajout dans `swift-check-docker/yente/docker-compose.yml` :

```yaml
  docker-proxy:
    image: tecnativa/docker-socket-proxy:<version figée>
    container_name: yente-docker-proxy
    environment:
      - CONTAINERS=1      # lecture des conteneurs et des logs
      - POST=0            # aucune écriture générale (pas de création de conteneur)
      - ALLOW_RESTARTS=1  # seul le redémarrage est autorisé, même avec POST=0
      # tout le reste à 0 (images, volumes, exec, réseaux…)
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
      - ./docker-proxy/haproxy.cfg:<chemin de la config dans l'image>:ro   # liste blanche par nom
    restart: unless-stopped
    networks:
      - yente_admin_network   # réseau dédié core ↔ proxy ; aucun port publié
```

**Restrictions** *(choix de conception, suite à la relecture)* :
- `POST=0` : avec `POST=1`, le proxy accepterait `POST /containers/create`, ce qui revient à un accès root (le
  contraire de l'anti-objectif). `ALLOW_RESTARTS=1` suffit pour redémarrer. À vérifier sur la version figée de
  l'image.
- **Liste blanche par nom** : une configuration haproxy personnalisée n'accepte que les chemins
  `/containers/yente-sanctions-api/…` et `/containers/yente-elasticsearch/…` (logs, restart). Sinon,
  `CONTAINERS=1` permettrait de lire la configuration (et les mots de passe en variables d'environnement) de
  tous les conteneurs du serveur (MySQL, Keycloak…). Le core vérifie aussi la clé `yente`/`index` de son côté.
- **Réseau dédié** `yente_admin_network` (externe, partagé seulement par le core et le proxy) : le proxy n'est
  pas sur `swiftcheck_network`, que rejoignent toutes les stacks (searxng, crawler-stealth…).

- Le core appelle `GET {dockerProxyUrl}/containers/{nom}/logs?stdout=1&stderr=1&tail=N&timestamps=1` et
  `POST {dockerProxyUrl}/containers/{nom}/restart?t=30`.
- Sans TTY, Docker renvoie les logs en flux multiplexé (en-tête de 8 octets par bloc) : le core le démultiplexe
  avant de renvoyer du texte.
- Si Yente part sur un autre serveur, le proxy part avec lui ; on change `dockerProxyUrl`. Il faut alors
  protéger l'accès réseau (pare-feu ou tunnel), car le proxy n'a pas d'authentification.

### 6.7 Sécurité d'Elasticsearch (même chantier)

Remplacer la publication `9200:9200` par `127.0.0.1:9200:9200` : le core y accède par le réseau Docker
(`http://index:9200`) et le port reste accessible en local pour le diagnostic, mais plus depuis Internet.
*(Proposition de ma part, à valider au déploiement.)*

## 7. Découpage

0. **Lot 0 — Réindexer seulement ce qui a changé** (§6.0) : `YENTE_AUTO_REINDEX=true`, suppression des
   appels à `/updatez` après publication, adaptation de ce qui attendait la fin de la réindexation, endpoint
   « réindexer un dataset » et renommage « Tout reconstruire ». Livrable seul, en premier. *(Décision de
   l'utilisateur.)* Le bouton par ligne arrive avec l'écran du lot 1 ; d'ici là l'endpoint peut être appelé
   depuis l'écran des parsers.
1. **Lot 1 — État et avancement** : configuration, `YenteMonitorService`, `GET /status` et `/events`, écran
   Yente sans logs ni redémarrage. Seul changement d'infrastructure : **image Yente figée sur
   `ghcr.io/opensanctions/yente:5.5.0`** au lieu de `latest` (décision de l'utilisateur), car le monitoring
   dépend du nom des index, du journal `yente-audit-log-1`, de `yente-locks` et des champs de `/catalog`
   vérifiés sur cette version. Toute montée de version se fera volontairement, après vérification du monitoring.
2. **Lot 2 — Surveillance** : tâche planifiée, nouveaux types d'alertes, `parser` nullable, écran Alertes.
3. **Lot 3 — Logs et redémarrage** : proxy Docker, `/logs`, `/restart`, table `yente_action`, onglet Logs,
   correction du port 9200.

Chaque lot est livrable et utile seul.

## 8. Laissé à l'implémentation

- Le compteur exact pour l'index en construction : `docs.count` dépend du rafraîchissement de l'index ;
  `_stats/indexing` (`index_total`) peut être plus fiable pendant l'écriture. À vérifier sur une vraie
  réindexation, puis prendre le plus juste.
- Le format exact des documents de `yente-locks`.
- Le client HTTP (le `java.net.http.HttpClient` existant suffit) et les DTO.
- La mise en cache de `snapshot()` (quelques secondes) si plusieurs écrans sont ouverts en même temps.
- Les tests : services testés contre des réponses JSON enregistrées depuis le Yente local (catalog, `_cat`,
  journal), démultiplexage des logs testé sur un flux d'exemple.
