# Lot 0 — Réindexer seulement ce qui a changé : plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal :** une publication ne réindexe plus que son dataset (au lieu des 250). On ajoute « Réindexer ce
dataset », et l'actuel bouton devient « Tout reconstruire » (admin seulement).

**Architecture :** Yente 5.5.0 passe en mode automatique (`YENTE_AUTO_REINDEX=true`, `YENTE_CRONTAB=*/2 * * * *`).
Toutes les 2 minutes, il ne réindexe que les datasets dont le fichier `.ndjson` a changé de date de modification.
Le core cesse d'appeler `POST /updatez` après publication. Réindexer un dataset revient à mettre à jour la date
de modification de son fichier. `/updatez`, qui est forcé, ne sert plus qu'à « Tout reconstruire ».

**Tech Stack :** Java 17, Spring Boot, JUnit 5 (`com.sun.net.httpserver.HttpServer` pour simuler Yente, comme
dans `YenteReindexTest`), Angular + Tailwind, Docker Compose.

**Spec :** `docs/superpowers/specs/2026-11-18-yente-monitoring-design.md` (§6.0 et §7, lot 0).

Les lots 1 à 3 du spec (écran, surveillance, logs et redémarrage) auront chacun leur plan, écrit une fois ce lot livré.

## Global Constraints

- Yente figé sur `ghcr.io/opensanctions/yente:5.5.0` (au lieu de `latest`), comportement vérifié sur cette version.
- `YENTE_AUTO_REINDEX=true` et `YENTE_CRONTAB=*/2 * * * *` (décision de l'utilisateur : toutes les 2 minutes).
- `POST /api/yente/reindex` (« Tout reconstruire », forcé) : rôle `ADMIN` uniquement.
- `POST /api/yente/datasets/{name}/reindex` : rôles `ADMIN` et `TESTER`.
- Erreurs renvoyées en 409 `{"message": "..."}` via `IllegalStateException`, comme aujourd'hui.
- Code, commentaires et messages en français, dans le style du projet.
- `swift-check-docker/` est ignoré par git (fichiers du serveur) : ses modifications ne sont pas commitées, et le
  rapport final doit dire qu'il faut les reporter sur le VPS.

## Review Focus

1. Nom de dataset hostile dans l'URL (`../`, `/`, `\`, vide) : il ne doit jamais toucher un fichier hors de
   `yenteDataFolder`. Refus 409 attendu → test `reindexDatasetRefuseUnNomInvalide` (tâche 2).
2. Dataset sans fichier publié, ou absent du manifest : un message clair (« aucun fichier publié… »), pas de
   création de fichier vide → test `reindexDatasetRefuseUnDatasetInconnu` (tâche 2).
3. Fichier verrouillé sous Windows (Yente ou un antivirus le lit) : quelques nouvelles tentatives, comme
   `DatasetPublisher` → `AccessDeniedException` gérée dans la tâche 2 (pas testable simplement, à relire).
4. Retrait d'un dataset (parser désactivé) : sans `/updatez`, l'index doit quand même disparaître au passage
   suivant de Yente (`delete_old_indices`) → vérification manuelle, tâche 4, étape 5.
5. Rôle `TESTER` qui appelle « Tout reconstruire » : 403 côté back, et bouton masqué côté front → vérification
   manuelle, tâche 4, étape 6.

---

### Task 1 : Le core n'appelle plus `/updatez` après une publication

**Files :**
- Modify : `back/src/main/java/com/swiftcheck/aml/service/YenteManifestService.java`
- Modify : `back/src/main/resources/application.properties` (supprimer le bloc `swiftcheck.yente.manifest-refresh-delay` et son commentaire, lignes 55–57)
- Test : `back/src/test/java/com/swiftcheck/aml/service/YenteReindexTest.java`

(Chemins relatifs à `swiftcheck-aml-core/`.)

**Interfaces :**
- Produces : `addDataset(String, String, ApplicationConfigEntity)` et `removeDataset(String, ApplicationConfigEntity)` gardent leur signature, mais n'appellent plus Yente. `reindex(ApplicationConfigEntity)` est inchangé.
- Disparaissent : `reloadYente`, `reloadYenteAfterManifestRefresh`, le champ `manifestRefreshDelay`, `setManifestRefreshDelay`, `stopDelayedReload`, l'exécuteur `delayedReload`. Vérifier avec `grep` qu'aucun autre fichier ne les utilise (aujourd'hui, seul `YenteReindexTest`).

- [ ] **Step 1 : Remplacer le test `reindexationDiffereeSeulementQuandLeManifestChange` par celui-ci (même fichier)**

```java
/** Ajout, mise à jour et retrait d'un dataset : le manifest change, mais Yente n'est jamais appelé
 *  (YENTE_AUTO_REINDEX le réindexe seul à son prochain passage). */
@Test
void publierOuRetirerNAppellePasYente(@TempDir Path dir) throws Exception {
    // HttpServer sur /updatez qui incrémente `calls`, comme aujourd'hui
    service.addDataset("lu_pep_chamber", "PEP Luxembourg – députés", config);
    service.addDataset("lu_pep_chamber", "PEP Luxembourg – députés", config);
    Thread.sleep(300);
    assertEquals(0, calls.get());
    assertTrue(Files.readString(dir.resolve("manifest.yml")).contains("lu_pep_chamber"));

    service.removeDataset("lu_pep_chamber", config);
    Thread.sleep(300);
    assertEquals(0, calls.get());
    assertFalse(Files.readString(dir.resolve("manifest.yml")).contains("lu_pep_chamber"));
}
```

Supprimer `waitUntil` s'il n'est plus utilisé.

- [ ] **Step 2 : Lancer le test, il doit échouer**

Run : `mvn -q -pl back test -Dtest=YenteReindexTest` (depuis `swiftcheck-aml-core`)
Expected : FAIL. Il ne compile pas tant que `setManifestRefreshDelay` est encore appelé, ou `calls` vaut 1 ou 2.

- [ ] **Step 3 : Retirer de `YenteManifestService` les appels à Yente dans `addDataset` et `removeDataset`, ainsi que tout le mécanisme différé**

Mettre à jour la javadoc de la classe et des deux méthodes : Yente relit `manifest.yml` et réindexe seul les
datasets modifiés toutes les 2 minutes (`YENTE_AUTO_REINDEX=true`, `YENTE_CRONTAB=*/2 * * * *`). La javadoc
de `reindex` dit que c'est la reconstruction **complète et forcée** (« Tout reconstruire »). Supprimer les
imports devenus inutiles (`Executors`, `ScheduledExecutorService`, `TimeUnit`, `PreDestroy`, `Value`).

- [ ] **Step 4 : Supprimer la propriété `swiftcheck.yente.manifest-refresh-delay` et son commentaire dans `application.properties`**

- [ ] **Step 5 : Lancer les tests du back, ils doivent passer**

Run : `mvn -q -pl back test`
Expected : BUILD SUCCESS. `IngestionServiceTest` et `ParserTestServiceTest` utilisent `YenteManifestService` et doivent rester verts.

- [ ] **Step 6 : Commit**

```bash
git add swiftcheck-aml-core/back
git commit -m "feat(yente): ne plus forcer la réindexation complète après chaque publication"
```

---

### Task 2 : Réindexer un seul dataset

**Files :**
- Modify : `back/src/main/java/com/swiftcheck/aml/service/YenteManifestService.java`
- Modify : `back/src/main/java/com/swiftcheck/aml/controllers/YenteController.java`
- Test : `back/src/test/java/com/swiftcheck/aml/service/YenteDatasetReindexTest.java` (nouveau)

**Interfaces :**
- Consumes : `ApplicationConfigEntity.getYenteDataFolder()`, `getYenteManifestPath()`, et les méthodes privées `read`/`datasets` de la tâche 1.
- Produces :
  - `public void reindexDataset(String datasetName, ApplicationConfigEntity config)` dans `YenteManifestService`. Lève `IllegalStateException` avec un message affichable.
  - `POST /api/yente/datasets/{name}/reindex` → `Map<String, String>` `{"message": "..."}`.
  - `POST /api/yente/reindex` annoté `@PreAuthorize("hasRole('ADMIN')")`, comme `UserController`.

- [ ] **Step 1 : Écrire les tests qui échouent**

```java
class YenteDatasetReindexTest {
    // @TempDir dir ; config : yenteDataFolder = dir/data, yenteManifestPath = dir/manifest.yml, yenteContainerDataPath = /data
    // dataset publié = fichier dir/data/fr_pep_senate/fr_pep_senate.ndjson + service.addDataset("fr_pep_senate", ...)

    @Test void reindexDatasetAvanceLaDateDuFichier()
    // date du fichier fixée à 2026-01-01 avec Files.setLastModifiedTime ; après reindexDataset :
    // assertTrue(Files.getLastModifiedTime(file).toInstant().isAfter(Instant.now().minusSeconds(5)))
    // et le contenu du fichier est inchangé (assertEquals sur Files.readString)

    @Test void reindexDatasetRefuseUnDatasetInconnu()
    // "absent" (ni fichier ni manifest) → IllegalStateException, message contient "aucun fichier publié"
    // fichier présent mais dataset absent du manifest → IllegalStateException, message contient "manifest"
    // aucun fichier créé : assertFalse(Files.exists(dir/data/absent))

    @Test void reindexDatasetRefuseUnNomInvalide()
    // pour "../manifest", "a/b", "a\\b", "", " " → IllegalStateException, message contient "nom de dataset invalide"
    // la date de dir/manifest.yml est inchangée
}
```

- [ ] **Step 2 : Lancer les tests, ils doivent échouer**

Run : `mvn -q -pl back test -Dtest=YenteDatasetReindexTest`
Expected : FAIL (la méthode `reindexDataset` n'existe pas).

- [ ] **Step 3 : Implémenter `reindexDataset` dans `YenteManifestService`**

Les étapes, dans l'ordre :
1. Le nom doit correspondre à `^[a-z0-9_\-]+$`, sinon « Nom de dataset invalide : … ».
2. Le fichier attendu est `Path.of(yenteDataFolder, name, name + ".ndjson")` (même règle que `DatasetPublisher.publishedFile`). S'il n'existe pas : « Aucun fichier publié pour … : lancez d'abord le parser. »
3. Le dataset doit être présent dans le manifest (lu avec `read`/`datasets`), sinon « … n'est pas dans le manifest de Yente (parser désactivé ?) ».
4. Appeler `Files.setLastModifiedTime(file, FileTime.from(Instant.now()))`, avec jusqu'à 5 tentatives sur `AccessDeniedException` (2 s × numéro de tentative), comme `DatasetPublisher.replace`.
5. Journaliser `log.info("Réindexation de {} demandée …")`.

Méthode `synchronized`, comme les autres méthodes qui touchent au manifest.

- [ ] **Step 4 : Ajouter le point d'entrée dans `YenteController` et restreindre `reindex`**

```java
@PostMapping("/datasets/{name}/reindex")
public Map<String, String> reindexDataset(@PathVariable String name)
// message : "Réindexation de " + name + " demandée : Yente la prendra en compte à son prochain passage
//            (2 minutes au plus), puis l'indexera."
```

Annoter `reindex()` avec `@PreAuthorize("hasRole('ADMIN')")`. Changer son message : « Reconstruction complète
lancée : Yente reconstruit tous les index en tâche de fond (cela peut être long). » Mettre à jour la javadoc de la classe (liste des deux endpoints).

- [ ] **Step 5 : Lancer les tests du back, ils doivent passer**

Run : `mvn -q -pl back test`
Expected : BUILD SUCCESS.

- [ ] **Step 6 : Commit**

```bash
git add swiftcheck-aml-core/back
git commit -m "feat(yente): réindexer un seul dataset ; reconstruction complète réservée à l'admin"
```

---

### Task 3 : Front — « Réindexer dans Yente » par parser et « Tout reconstruire »

**Files :**
- Modify : `front/src/app/services/yente.service.ts`
- Modify : `front/src/app/components/parser/parser.component.ts` (`reindexYente`, ~l. 1107–1125)
- Modify : `front/src/app/components/parser/parser.component.html` (bouton d'en-tête l. 17–22 ; menu « ⋯ » l. 579–592)

**Interfaces :**
- Consumes : les endpoints de la tâche 2 ; `KeycloakService.isAdmin()`.
- Produces : `YenteService.reindexDataset(name: string): Observable<{ message: string }>` → `POST /api/yente/datasets/${encodeURIComponent(name)}/reindex`.

- [ ] **Step 1 : Ajouter `reindexDataset` dans `yente.service.ts`, et mettre à jour le commentaire de `reindex` (« reconstruction complète, forcée »)**

- [ ] **Step 2 : Composant parser**
- `reindexYente()` est renommée `rebuildYente()`. Nouvelle confirmation : « Reconstruire tous les index de Yente ? Tous les datasets sont réindexés, ce qui peut prendre longtemps. À réserver à une réparation : une publication est indexée automatiquement. »
- Nouvelle méthode `reindexDataset(parser: ParserDTO): void`, qui appelle `yenteService.reindexDataset(parser.datasetName)`. Elle affiche le message dans `reindexMessage`, ou l'erreur dans `errorMessage` (via `apiError`).
- Exposer `isAdmin`, via le `KeycloakService` déjà utilisé dans l'application, pour masquer le bouton d'en-tête.

- [ ] **Step 3 : Gabarit**
- Bouton d'en-tête : libellé « Tout reconstruire » (« Reconstruction… » pendant l'appel), `*ngIf="isAdmin"`. Infobulle : « Reconstruit tous les index de Yente (réparation). Les publications sont indexées automatiquement. »
- Menu « ⋯ », après « Forcer la republication » : entrée « Réindexer dans Yente » (`*ngIf="p.enabled"`), qui appelle `reindexDataset(p); closeMenu()`. Infobulle : « Yente réindexe seulement ce dataset, dans les 2 minutes ».

- [ ] **Step 4 : Compiler le front**

Run : `npm run build` (depuis `swiftcheck-aml-core/front`)
Expected : build terminé sans erreur.

- [ ] **Step 5 : Commit**

```bash
git add swiftcheck-aml-core/front/src
git commit -m "feat(yente): réindexer un parser depuis son menu ; « Tout reconstruire » pour l'admin"
```

---

### Task 4 : Configuration de Yente et vérification de bout en bout

**Files :**
- Modify : `yente/docker-compose.yml` (versionné, environnement local)
- Modify : `swift-check-docker/yente/docker-compose.yml` et `swift-check-docker/yente/.env` (serveur, non versionnés)

- [ ] **Step 1 : Dans les deux compose**
- `image: ghcr.io/opensanctions/yente:5.5.0`, avec un commentaire : version figée, car le monitoring dépend des index et du journal de cette version ; ne monter de version qu'après vérification.
- `YENTE_AUTO_REINDEX=true` (dans `swift-check-docker` : `${YENTE_AUTO_REINDEX:-true}`).
- `YENTE_CRONTAB=*/2 * * * *` (dans `swift-check-docker` : `${YENTE_CRONTAB:-*/2 * * * *}`).
- Remplacer le commentaire « Relit manifest.yml chaque minute (sans réindexer) » par : « Toutes les 2 minutes : relit manifest.yml et réindexe seulement les datasets dont le fichier a changé ».

- [ ] **Step 2 : Dans `swift-check-docker/yente/.env`, remplacer `YENTE_AUTO_REINDEX=false` par `YENTE_AUTO_REINDEX=true`**

Ne toucher à aucune autre ligne (le fichier contient le jeton).

- [ ] **Step 3 : Redémarrer Yente en local avec la nouvelle configuration**

Run : `docker compose up -d yente` (depuis le dossier de la stack qui fait tourner le conteneur local `yente-sanctions-api`)
Expected : `docker exec yente-sanctions-api pip show yente` affiche `Version: 5.5.0`, et `curl http://localhost:8000/readyz` renvoie `{"status":"ok"}`.

- [ ] **Step 4 : Vérifier qu'un seul dataset est réindexé**

Lancer le back, puis appeler `POST /api/yente/datasets/fr_pep_senate/reindex`, ou utiliser le menu « ⋯ » de ce parser. Attendre 3 minutes au plus, puis :
Run : `curl -s -H 'Content-Type: application/json' "http://localhost:9200/yente-audit-log-1/_search?size=20&sort=timestamp:desc" -d '{"query":{"range":{"timestamp":{"gte":"now-5m"}}}}'`
Expected : `reindex_started` et `reindex_completed` pour `fr_pep_senate` **uniquement**. Puis `curl -s "http://localhost:9200/_cat/indices/yente-entities-fr_pep_senate*?h=index,docs.count"` montre un seul index, de nouvelle version, toujours avec 2 675 documents.

- [ ] **Step 5 : Vérifier le retrait d'un dataset**

Désactiver un parser de test dans l'écran Parsers. Attendre 3 minutes au plus.
Expected : son index `yente-entities-<dataset>-*` a disparu de `_cat/indices`, et un `cleanup_index_deleted` apparaît dans le journal. S'il reste là, le signaler (hypothèse du spec invalidée) au lieu de rétablir `/updatez`.

- [ ] **Step 6 : Vérifier les droits**

Avec un compte `TESTER` : le bouton « Tout reconstruire » est absent, et `POST /api/yente/reindex` renvoie 403. Avec un compte `ADMIN` : il fonctionne et le journal montre des `reindex_started` pour tous les datasets.

- [ ] **Step 7 : Commit (fichier versionné seulement)**

```bash
git add yente/docker-compose.yml
git commit -m "chore(yente): figer 5.5.0, réindexation automatique incrémentale toutes les 2 minutes"
```

Dans le rapport final, rappeler de reporter `swift-check-docker/yente/docker-compose.yml` et `.env` sur le VPS, puis de lancer `docker compose up -d yente`.
