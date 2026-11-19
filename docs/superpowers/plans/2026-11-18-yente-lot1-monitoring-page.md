# Lot 1 — Page de monitoring Yente : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal :** un écran « Yente » montre si chaque dataset publié est chargé et indexé dans la bonne version, le
pourcentage global, l'avancement d'une indexation en cours, et l'historique d'indexation d'un dataset.

**Architecture :** dans le core, un nouveau paquet `com.swiftcheck.aml.yente` :
- un client HTTP lit l'API Yente (`/healthz`, `/readyz`, `/catalog`) et Elasticsearch (index, alias, journal
  `yente-audit-log-1`, verrou `yente-locks`, santé, disque) ;
- une fonction pure calcule l'état de chaque dataset ;
- un service assemble le tout en un instantané, mis en cache 3 s.

L'écran Angular interroge `GET /api/yente/status` toutes les 5 s pendant une indexation, toutes les 30 s sinon.
Aucune alerte, aucun log, aucun redémarrage : ce sont les lots 2 et 3.

**Tech Stack :** Java 17, Spring Boot, Jackson, `java.net.http.HttpClient`, JUnit 5 (+ `com.sun.net.httpserver.HttpServer`
pour simuler Yente et Elasticsearch, comme `YenteReindexTest`), Liquibase (MySQL), Angular + Tailwind.

**Spec :** `docs/superpowers/specs/2026-11-18-yente-monitoring-design.md` (§6.1, §6.2, §6.3 lignes GET, §6.4, §7 lot 1).

## Global Constraints

- **Aucun commit** : l'utilisateur commite lui-même. Les étapes finissent par « lister les fichiers modifiés ».
- Liquibase : nouveau changeSet `db/changelog/changes/157-yente-monitoring-config.xml`, jamais d'identifiant
  numérique, jamais de chemin `D:/`. Un changeSet qui utilise un paramètre `${…}` porte
  `<validCheckSum>ANY</validCheckSum>`. Ne modifier aucun changeSet existant.
- Noms Elasticsearch dérivés de `yenteIndexName` (défaut `yente`) : alias `<nom>-entities`, index
  `<nom>-entities-<dataset>-<hash>-<version>`, journal `<nom>-audit-log-1`, verrou `<nom>-locks`. Le dataset est
  la partie entre `<nom>-entities-` et le premier `-` suivant, et la version est la partie après le dernier `-`
  (même découpage que `parse_index_name` de Yente).
- Le verrou est actif si `acquired_at` date de moins de 10 minutes (`LOCK_EXPIRATION_TIME` de Yente).
- Endpoints de lecture `GET /api/yente/status` et `GET /api/yente/datasets/{name}/events` : rôles `ADMIN` et
  `TESTER` (règle `/api/**` existante, pas d'annotation).
- Écran : route `/yente`, rôles `admin` et `tester`. Ajouter `"/yente", "/yente/**"` à `SpaForwardController.SPA_ROUTES`.
- Textes en français, style Tailwind des écrans existants (`status-badge`, cartes `rounded-2xl`).

## Review Focus

1. **Elasticsearch injoignable, mais Yente répond** (ou l'inverse) : l'écran s'affiche quand même, avec l'erreur
   dans l'en-tête et les colonnes concernées vides. Jamais de 500. → test `snapshotQuandElasticsearchEstInjoignable`
   (tâche 4).
2. **Dernier événement du journal = `cleanup_index_deleted`** : après chaque réindexation réussie, Yente supprime
   l'ancien index. Ce nettoyage ne doit ni masquer l'état ni passer pour un échec. → la requête du dernier
   événement exclut `cleanup_index_deleted`, test `dernierEvenementIgnoreLeNettoyage` (tâche 2).
3. **Prolongement de nom** (`nl_pep_parliament` / `nl_pep_parliament_history`) : chaque index doit être rattaché au
   bon dataset. → test `indicesRattachesAuBonDataset` (tâche 2).
4. **Fichier publié absent ou illisible** : « nombre non vérifié », pas d'exception. → test
   `compteInconnuSiFichierAbsent` (tâche 3).
5. **Nom de dataset hostile dans `/datasets/{name}/events`** : le nom est validé avec la même règle que
   `reindexDataset` avant d'entrer dans la requête Elasticsearch. → test `evenementsRefusentUnNomInvalide` (tâche 4).

---

### Task 1 : Configuration (adresse Elasticsearch, nom d'index)

**Files :**
- Create : `back/src/main/resources/db/changelog/changes/157-yente-monitoring-config.xml`
- Modify : `back/src/main/resources/db/changelog/db.changelog-master.xml` (include après le 156)
- Modify : `back/src/main/resources/application.properties` (paramètre Liquibase)
- Modify : `back/src/main/java/com/swiftcheck/aml/entities/ApplicationConfigEntity.java`
- Modify : `back/src/main/java/com/swiftcheck/aml/dto/ApplicationConfigDTO.java` (`from` et `toEntity`)
- Modify : `back/src/main/java/com/swiftcheck/aml/service/ApplicationConfigService.java` (`update` recopie les deux champs, comme les autres)
- Modify : `front/src/app/models/appTypes.ts` (`ApplicationConfigDTO`)
- Modify : `front/src/app/components/app-config/app-config.component.ts` (`yenteFields`)
- Test : `back/src/test/java/com/swiftcheck/aml/dto/ApplicationConfigDTOTest.java` (nouveau)

(Chemins relatifs à `swiftcheck-aml-core/`.)

**Interfaces :**
- Produces : `ApplicationConfigEntity.getYenteIndexUrl(): String` et `getYenteIndexName(): String` (et leurs setters). Mêmes champs dans les DTO Java et TS.

- [ ] **Step 1 : Écrire le test `ApplicationConfigDTOTest.yenteIndexAllerRetour`**

Créer une entité avec `yenteIndexUrl = "http://index:9200"` et `yenteIndexName = "yente"`. Vérifier que
`ApplicationConfigDTO.from(e).toEntity()` restitue les deux valeurs (`assertEquals`).

- [ ] **Step 2 : Lancer le test, il doit échouer**

Run : `mvn -q -pl back test -Dtest=ApplicationConfigDTOTest` (depuis `swiftcheck-aml-core`)
Expected : erreur de compilation (`setYenteIndexUrl` n'existe pas).

- [ ] **Step 3 : Ajouter les champs**
- Entité : `yenteIndexUrl` (javadoc : « Elasticsearch de Yente, ex. `http://index:9200` ») et `yenteIndexName` (javadoc : « valeur de `YENTE_INDEX_NAME`, `yente` par défaut »).
- DTO Java : les deux champs dans `from` et `toEntity`.
- Service : `update` recopie les deux champs.
- ChangeSet `157-yente-monitoring-config` :
  - `addColumn` sur `application_config` : `yente_index_url varchar(500)`, `yente_index_name varchar(100)` ;
  - `update` de toutes les lignes : `yente_index_url = ${yenteIndexUrl}`, `yente_index_name = 'yente'` ;
  - `<validCheckSum>ANY</validCheckSum>`, précondition `MARK_RAN` si la colonne existe déjà ;
  - commentaire XML en tête, dans le style du 156.
- Include dans le master.
- `application.properties` : `spring.liquibase.parameters.yenteIndexUrl=${YENTE_INDEX_URL:http://localhost:9200}`.
- Front : les deux champs dans l'interface TS. Dans `yenteFields` :
  - `yenteIndexUrl` : label « URL d'Elasticsearch (Yente) », aide « Index de Yente, lu par l'écran Yente (état et avancement de l'indexation). », placeholder `http://localhost:9200`, `required: true` ;
  - `yenteIndexName` : label « Nom d'index de Yente », aide « Valeur de YENTE_INDEX_NAME (yente par défaut). », placeholder `yente`, `required: true`.

- [ ] **Step 4 : Lancer le test, il doit passer**

Run : `mvn -q -pl back test -Dtest=ApplicationConfigDTOTest`
Expected : exit 0.

- [ ] **Step 5 : Vérifier le changeSet sur une base vide**

Run : `mvn -q -pl back package -DskipTests` (depuis `swiftcheck-aml-core`), puis `bash swiftcheck-aml-tools/liquibase-baseline/check-empty-db.sh` (depuis la racine)
Expected : sortie 0 (l'application démarre et Liquibase applique le 157).

- [ ] **Step 6 : Lister les fichiers modifiés (pas de commit)**

---

### Task 2 : Client Yente et Elasticsearch

**Files :**
- Create : `back/src/main/java/com/swiftcheck/aml/yente/YenteMonitorClient.java`
- Create : `back/src/main/java/com/swiftcheck/aml/yente/YenteModels.java` (records ci-dessous, en classes imbriquées publiques statiques)
- Test : `back/src/test/java/com/swiftcheck/aml/yente/YenteMonitorClientTest.java`
- Test resources : `back/src/test/resources/yente/` (`catalog.json`, `indices.json`, `aliases.json`, `last-events.json`, `events.json`, `locks.json`, `health.json`, `allocation.json`)

**Interfaces :**
- Consumes : `ApplicationConfigEntity.getYenteUrl()`, `getYenteIndexUrl()`, `getYenteIndexName()` (tâche 1).
- Produces (dans `YenteModels`) :
  - `record Health(boolean alive, boolean ready, long responseMs, String error)`
  - `record CatalogEntry(String name, boolean load, String version, String indexVersion, boolean indexCurrent)` : datasets seulement, sans les collections (entrées avec `children` non vide)
  - `record IndexInfo(String index, String dataset, String version, long docs, boolean aliased)`
  - `record ClusterInfo(String health, Long diskAvailableBytes, Integer diskUsedPercent)`
  - `record AuditEvent(String dataset, String type, String version, Instant at, String message)`
- Produces (`@Component YenteMonitorClient`, chaque méthode prend `ApplicationConfigEntity config`) :
  - `Health health(config)` : ne lève jamais d'exception (erreur dans `error`)
  - `List<CatalogEntry> catalog(config)`
  - `List<IndexInfo> indices(config)` : `_cat/indices/<nom>-entities-*?format=json&h=index,docs.count` croisé avec `_cat/aliases/<nom>-entities?format=json&h=index`
  - `ClusterInfo cluster(config)` : `_cluster/health` + `_cat/allocation?format=json&bytes=b`
  - `Map<String, AuditEvent> lastEvents(config)` : dernier événement par dataset, **hors `cleanup_index_deleted`**
  - `List<AuditEvent> events(config, String dataset, int limit)` : du plus récent au plus ancien, tous types
  - `Optional<Instant> activeLockSince(config)` : `acquired_at` du document de `<nom>-locks`, s'il date de moins de 10 min
  - `Optional<Instant> lastCompletedAt(config)` : dernier `reindex_completed`, tous datasets confondus
  - Toutes, sauf `health`, lèvent `IllegalStateException("Elasticsearch injoignable (<url>) : …")` ou `"Yente injoignable (<url>) : …"`.

- [ ] **Step 1 : Enregistrer les réponses de référence depuis le Yente local**

Lancer les requêtes ci-dessus contre `localhost:8000` et `localhost:9200`, et réduire chaque réponse à 3–6 entrées.
Les fixtures doivent garder au moins :
- `nl_pep_parliament` **et** `nl_pep_parliament_history` (ou une autre paire base/prolongement), avec leurs index ;
- un index non rattaché à l'alias (version plus récente du même dataset) ;
- dans le journal, un dataset dont le dernier événement brut est `cleanup_index_deleted`, précédé d'un `index_alias_rollover_complete` ;
- un `reindex_failed` ;
- un document de verrou.

Requêtes du journal (corps POST `_search`) :
- **Derniers événements :** `{"size":0,"query":{"bool":{"must_not":{"term":{"event_type.keyword":"cleanup_index_deleted"}}}},"aggs":{"d":{"terms":{"field":"dataset.keyword","size":5000},"aggs":{"last":{"top_hits":{"size":1,"sort":[{"timestamp":"desc"}]}}}}}}`
- **Historique d'un dataset :** `{"size":<limit>,"sort":[{"timestamp":"desc"}],"query":{"term":{"dataset.keyword":"<dataset>"}}}`

- [ ] **Step 2 : Écrire les tests (`HttpServer` qui sert les fixtures sur les chemins attendus)**

```java
@Test void indicesRattachesAuBonDataset()
// nl_pep_parliament et nl_pep_parliament_history ont chacun leurs index ; version = partie après le dernier « - » ;
// aliased vrai seulement pour les index listés par _cat/aliases ; docs = docs.count
@Test void catalogIgnoreLesCollections()          // « default » absent ; version, indexVersion, indexCurrent lus
@Test void dernierEvenementIgnoreLeNettoyage()     // le corps envoyé contient must_not cleanup_index_deleted ;
                                                   // le dataset concerné a pour dernier type index_alias_rollover_complete
@Test void evenementsDuPlusRecentAuPlusAncien()    // at décroissant, Instant.ofEpochMilli(timestamp)
@Test void verrouActifSeulementMoinsDeDixMinutes() // acquired_at = maintenant-2 min → présent ; maintenant-11 min → vide
@Test void healthNeLevePasQuandYenteEstArrete()    // URL vers un port fermé → alive=false, error non vide
@Test void elasticsearchInjoignableLeveUneErreurClaire() // indices() → IllegalStateException, message commence par « Elasticsearch injoignable »
```

Le fichier `locks.json` est généré dans le test avec un `acquired_at` relatif à maintenant.

- [ ] **Step 3 : Lancer les tests, ils doivent échouer**

Run : `mvn -q -pl back test -Dtest=YenteMonitorClientTest`
Expected : erreur de compilation (`YenteMonitorClient` n'existe pas).

- [ ] **Step 4 : Implémenter `YenteModels` et `YenteMonitorClient`**

Utiliser `HttpClient` (HTTP/1.1, connexion 5 s, requête 10 s) et `ObjectMapper` lisant des `JsonNode`. L'URL
d'Elasticsearch passe par la même fonction `trimSlash` que celle de `YenteManifestService` (la recopier, elle est privée).

- [ ] **Step 5 : Lancer les tests, ils doivent passer**

Run : `mvn -q -pl back test -Dtest=YenteMonitorClientTest`
Expected : exit 0.

- [ ] **Step 6 : Lister les fichiers modifiés (pas de commit)**

---

### Task 3 : État de chaque dataset et nombre d'entités attendu

**Files :**
- Create : `back/src/main/java/com/swiftcheck/aml/yente/DatasetState.java` (enum)
- Create : `back/src/main/java/com/swiftcheck/aml/yente/DatasetStateResolver.java`
- Create : `back/src/main/java/com/swiftcheck/aml/yente/ExpectedEntityCounter.java`
- Create : `back/src/main/java/com/swiftcheck/aml/dto/YenteDatasetStatusDTO.java`
- Test : `back/src/test/java/com/swiftcheck/aml/yente/DatasetStateResolverTest.java`, `ExpectedEntityCounterTest.java`

**Interfaces :**
- Consumes : `YenteModels.CatalogEntry`, `IndexInfo`, `AuditEvent` (tâche 2).
- Produces :
  - `enum DatasetState { NOT_PUBLISHED, NOT_IN_MANIFEST, NOT_LOADED, FAILED, NOT_SERVED, INDEXING, OUTDATED, COUNT_MISMATCH, OK, ORPHAN }`
  - `record DatasetInputs(String dataset, Long parserId, String parserName, boolean activeParser, boolean published, boolean inManifest, CatalogEntry catalog, IndexInfo served, IndexInfo building, AuditEvent lastEvent, Long expectedEntities)` : imbriqué dans `DatasetStateResolver`, avec des champs nullables
  - `static YenteDatasetStatusDTO DatasetStateResolver.resolve(DatasetInputs in)`
  - `YenteDatasetStatusDTO` (Lombok `@Getter @Setter`, comme les autres DTO) :
    - `dataset`, `parserId`, `parserName`, `state` (String = nom de l'enum)
    - `countVerified` (boolean), `expectedEntities` (Long), `indexedEntities` (Long)
    - `fileVersion`, `servedVersion` (String)
    - `progressPercent` (Integer)
    - `lastEventType` (String), `lastEventAt` (Instant), `lastEventMessage` (String)
  - `@Component ExpectedEntityCounter` : `Long count(Path file)` (null si absent ou illisible), qui compte les lignes non vides. Cache `ConcurrentHashMap` par chemin, invalidé si la date de modification ou la taille change.

Règles de `resolve` : on prend **la première qui s'applique**, dans l'ordre du tableau §6.2 du spec. Précisions :
- `NOT_PUBLISHED` : `activeParser && !published`.
- `NOT_IN_MANIFEST` : `activeParser && published && !inManifest`.
- `NOT_LOADED` : `inManifest && catalog == null`.
- `FAILED` : `lastEvent.type == "reindex_failed"`.
- `NOT_SERVED` : `served == null` et aucune indexation en cours.
- `INDEXING` : `building != null`, ou `lastEvent.type == "reindex_started"`. `progressPercent = min(99, building.docs*100/expected)` si `expected` est connu et `building` non nul, sinon null.
- `OUTDATED` : `catalog.indexCurrent == false`.
- `COUNT_MISMATCH` : `expected` connu et `served.docs != expected`.
- `ORPHAN` : `!activeParser && !inManifest` (servi par Yente, mais ni parser actif ni entrée dans le manifest).
- Sinon `OK`. `countVerified = expected != null`.
- `progressPercent = 100` pour `OK` et `COUNT_MISMATCH`.

- [ ] **Step 1 : Écrire `DatasetStateResolverTest`, un test par état**

Le nom de chaque test est l'état (`notPublished`, `notInManifest`, … `orphan`), plus :
- `okSansNombreAttenduEstNonVerifie` : `state == "OK"`, `countVerified == false` ;
- `indexingCalculeLePourcentagePlafonne` : `building.docs=6100`, `expected=8513` → 71 ; `building.docs=8513` → 99 ;
- `echecPrioritaireSurIndexation` : `lastEvent=reindex_failed` avec `building` présent → `FAILED`.

- [ ] **Step 2 : Écrire `ExpectedEntityCounterTest`**
- `compteLesLignesNonVides` : `"a\n\nb\n"` → 2 ;
- `compteInconnuSiFichierAbsent` : → null ;
- `recompteQuandLeFichierChange` : on écrit 2 lignes, on compte, on réécrit 3 lignes avec une date différente → 3.

- [ ] **Step 3 : Lancer les tests, ils doivent échouer**

Run : `mvn -q -pl back test -Dtest='DatasetStateResolverTest,ExpectedEntityCounterTest'`
Expected : erreur de compilation.

- [ ] **Step 4 : Implémenter l'enum, le DTO, `DatasetStateResolver` et `ExpectedEntityCounter`**

- [ ] **Step 5 : Lancer les tests, ils doivent passer**

Run : `mvn -q -pl back test -Dtest='DatasetStateResolverTest,ExpectedEntityCounterTest'`
Expected : exit 0.

- [ ] **Step 6 : Lister les fichiers modifiés (pas de commit)**

---

### Task 4 : Instantané et endpoints

**Files :**
- Create : `back/src/main/java/com/swiftcheck/aml/yente/YenteMonitorService.java`
- Create : `back/src/main/java/com/swiftcheck/aml/dto/YenteStatusDTO.java`, `YenteEventDTO.java`
- Modify : `back/src/main/java/com/swiftcheck/aml/repositories/ParserExecutionRepository.java`
- Modify : `back/src/main/java/com/swiftcheck/aml/service/YenteManifestService.java` (lecture publique des noms du manifest)
- Modify : `back/src/main/java/com/swiftcheck/aml/controllers/YenteController.java`
- Test : `back/src/test/java/com/swiftcheck/aml/yente/YenteMonitorServiceTest.java`

**Interfaces :**
- Consumes : tâches 2 et 3 ; `ParserRepository.findAll()` ; `ApplicationConfigService.current()`.
- Produces :
  - `ParserExecutionRepository` : `@Query("select distinct e.parser.id from ParserExecution e where e.status in :statuses") Set<Long> findParserIdsWithStatusIn(@Param("statuses") Collection<ParserExecutionStatus> statuses)`. Appelé avec `AlertService.PUBLISHED` (`SUCCESS`, `UNCHANGED` ; rendre la constante `public`).
  - `YenteManifestService.datasetNames(ApplicationConfigEntity): Set<String>` : datasets du manifest, sans les collections. Ensemble vide si le fichier est absent.
  - `YenteManifestService.isValidDatasetName(String): boolean` (statique, publique), réutilisée par `reindexDataset` et par `events`.
  - `YenteStatusDTO` :
    - `checkedAt` (Instant) ;
    - `yenteAlive`, `yenteReady` (boolean), `yenteResponseMs` (long), `yenteError` (String) ;
    - `clusterHealth` (String), `diskAvailableBytes` (Long), `diskUsedPercent` (Integer), `indexError` (String) ;
    - `indexing` (boolean), `lockSince`, `lastCompletedAt` (Instant) ;
    - `okCount`, `expectedCount` (int), `percent` (Integer) ;
    - `datasets` (`List<YenteDatasetStatusDTO>`).
  - `YenteEventDTO` : `type`, `version`, `at`, `message`.
  - `YenteMonitorService.snapshot(): YenteStatusDTO` (cache 3 s, `synchronized`) et `events(String dataset, int limit): List<YenteEventDTO>` (`IllegalStateException` « Nom de dataset invalide » si le nom est refusé ; `limit` borné à 1–200).
  - `GET /api/yente/status` → `snapshot()` ; `GET /api/yente/datasets/{name}/events?limit=50` → `events(name, limit)`.

Assemblage de `snapshot()` :
- Datasets = union des parsers actifs, du manifest, du catalog et des index.
- Fichier attendu : `Path.of(yenteDataFolder, d, d + ".ndjson")`.
- `served` = index rattaché à l'alias (le plus récent s'il y en a plusieurs) ; `building` = index non rattaché de version supérieure à `served`.
- Chaque source est appelée dans son propre `try` :
  - Yente en échec : `yenteError` est renseigné et `catalog` reste vide ;
  - Elasticsearch en échec : `indexError` est renseigné, les index, événements et le verrou restent vides.
  - Dans les deux cas, les états restent calculés avec ce qui est connu.
- `expectedCount` = datasets dont l'état n'est ni `NOT_PUBLISHED` ni `ORPHAN` ; `okCount` = état `OK` ; `percent` = `okCount*100/expectedCount` (null si `expectedCount == 0`).
- `indexing` = verrou actif ou au moins un dataset `INDEXING`.
- Tri : états graves d'abord (ordre de l'enum, `ORPHAN` en dernier), puis par nom.

- [ ] **Step 1 : Écrire `YenteMonitorServiceTest`**

Utiliser des doublures Mockito de `YenteMonitorClient`, `ParserRepository`, `ParserExecutionRepository`, `ApplicationConfigService` et `YenteManifestService`, ainsi qu'un `@TempDir` pour les fichiers. Tests :
- `pourcentageExclutNonPubliesEtOrphelins` : 3 datasets OK, 1 OUTDATED, 1 NOT_PUBLISHED, 1 ORPHAN → `expectedCount=4`, `okCount=3`, `percent=75` ;
- `snapshotQuandElasticsearchEstInjoignable` : `indices()` lève une exception → pas d'exception, `indexError` commence par « Elasticsearch injoignable », `yenteReady` vrai, les datasets restent listés ;
- `snapshotEnCacheTroisSecondes` : deux appels de suite → `client.catalog` appelé une seule fois ;
- `indexEnConstructionDetecte` : index servi v1, index non rattaché v2 → état `INDEXING`, `indexing=true` ;
- `evenementsRefusentUnNomInvalide` : `"../x"` → `IllegalStateException`, client jamais appelé.

- [ ] **Step 2 : Lancer, ça doit échouer**

Run : `mvn -q -pl back test -Dtest=YenteMonitorServiceTest`
Expected : erreur de compilation.

- [ ] **Step 3 : Implémenter**

Requête du repository, méthodes publiques de `YenteManifestService` (avec `reindexDataset` qui utilise désormais `isValidDatasetName`), DTO, service, endpoints. Mettre à jour la javadoc de `YenteController` (liste des endpoints).

- [ ] **Step 4 : Lancer toute la suite du back**

Run : `mvn -q -pl back test`
Expected : exit 0.

- [ ] **Step 5 : Vérifier sur le Yente local**

Lancer le back, puis `GET /api/yente/status` avec un jeton, ou le test d'intégration manuel de la tâche 5.
Expected : environ 250 datasets, `percent` proche de 100, `yenteReady=true`, `clusterHealth` renseigné.

- [ ] **Step 6 : Lister les fichiers modifiés (pas de commit)**

---

### Task 5 : Écran « Yente »

**Files :**
- Create : `front/src/app/components/yente-monitor/yente-monitor.component.ts`, `.html`
- Modify : `front/src/app/models/appTypes.ts` (`YenteStatusDTO`, `YenteDatasetStatusDTO`, `YenteEventDTO`, type `YenteDatasetState`)
- Modify : `front/src/app/services/yente.service.ts` (`status()`, `events(name, limit = 50)`)
- Modify : `front/src/app/app.routes.ts` (route `yente`, rôles `admin`, `tester`)
- Modify : `front/src/app/app.component.html` (lien « Yente » juste après « Alertes », même gabarit, icône serveur/base de données)
- Modify : `back/src/main/java/com/swiftcheck/aml/controllers/SpaForwardController.java` (`"/yente", "/yente/**"`)

**Interfaces :**
- Consumes : `GET /api/yente/status`, `GET /api/yente/datasets/{name}/events`, `POST /api/yente/datasets/{name}/reindex`, `POST /api/yente/reindex` ; `KeycloakService.isAdmin()`.

Contenu de l'écran (§6.4 du spec, sans logs ni redémarrage) :
- **En-tête, 4 cartes :**
  - Yente : prêt / pas prêt / injoignable, avec le temps de réponse ;
  - Elasticsearch : vert / jaune / rouge, ou injoignable ;
  - Disque : Go libres et % utilisé ;
  - Indexation : « en cours depuis HH:MM » si `lockSince`, sinon « dernière fin à HH:MM ».
- **Barre de progression globale :** « `okCount` / `expectedCount` datasets à jour — `percent` % ».
- **Boutons :** « Tout reconstruire » (`*ngIf="isAdmin"`, même confirmation que l'écran Parsers) et « Rafraîchir ».
- **Filtres :** Tous | Problèmes (`NOT_IN_MANIFEST`, `NOT_LOADED`, `FAILED`, `NOT_SERVED`, `OUTDATED`, `COUNT_MISMATCH`) | En cours (`INDEXING`) | OK | Non publiés | Orphelins, chacun avec son compteur. Plus une recherche texte sur le dataset et le parser.
- **Tableau :**
  - colonnes : Dataset, Parser (lien `/parsers/:id/executions`), État (badge), Avancement, Dernier événement (type traduit + heure) ;
  - « Avancement » : barre `progressPercent` pour `INDEXING`, sinon « `indexedEntities` / `expectedEntities` », ou « `indexedEntities` (non vérifié) ».
- **Clic sur une ligne → panneau latéral :**
  - versions fichier / servie, entités attendues / indexées, message du dernier événement ;
  - historique (`events`, 50) ;
  - bouton « Réindexer ce dataset » (`reindexDataset`), affiché si `parserId` est renseigné.
- **Libellés des états :**

  | État | Libellé |
  |---|---|
  | `OK` | À jour |
  | `INDEXING` | Indexation |
  | `OUTDATED` | En attente |
  | `FAILED` | Échec |
  | `NOT_SERVED` | Non servi |
  | `NOT_LOADED` | Non chargé |
  | `NOT_IN_MANIFEST` | Absent du manifest |
  | `COUNT_MISMATCH` | Nombre différent |
  | `NOT_PUBLISHED` | Jamais publié |
  | `ORPHAN` | Orphelin |

  Couleurs : vert, bleu, ambre, rouge pour les quatre premiers ; rouge pour les trois suivants ; ambre pour `COUNT_MISMATCH` ; gris pour les deux derniers.
- **Types d'événements :**

  | Type | Libellé |
  |---|---|
  | `reindex_started` | Début |
  | `reindex_completed` | Fin |
  | `index_alias_rollover_complete` | Mis en service |
  | `reindex_failed` | Échec |
  | `cleanup_index_deleted` | Ancien index supprimé |

- **Rafraîchissement :** `setTimeout` relancé après chaque réponse, 5 s si `indexing`, sinon 30 s. Arrêt dans `ngOnDestroy`. Pas de chevauchement de requêtes.
- **Erreurs :** une erreur HTTP s'affiche dans un bandeau rouge, et les dernières données restent visibles.

- [ ] **Step 1 : Types, service, route, lien de menu, `SPA_ROUTES`**

- [ ] **Step 2 : Composant et gabarit**

- [ ] **Step 3 : Compiler**

Run : `npm run build` (depuis `swiftcheck-aml-core/front`)
Expected : `Application bundle generation complete`, aucune erreur.

- [ ] **Step 4 : Vérifier dans le navigateur (back + front lancés, Yente local)**

L'utilisateur se connecte. Ensuite, vérifier :
- l'écran `/yente` affiche les 4 cartes et le pourcentage global, et le filtre « Problèmes » liste les datasets en anomalie ;
- après « Réindexer ce dataset » sur `fr_pep_senate` : la ligne passe à « En attente », puis « Indexation », puis « À jour » en moins de 3 minutes, sans recharger la page ;
- un rafraîchissement du navigateur sur `/yente` garde l'écran (`SPA_ROUTES`) ;
- à 375 px de large, rien ne déborde horizontalement : le tableau défile dans son cadre.

- [ ] **Step 5 : Lister les fichiers modifiés (pas de commit)**
