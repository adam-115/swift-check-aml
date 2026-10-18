# Plugins de listes (PF4J)

Un plugin = un JAR = une liste (UE, ONU, PEP Luxembourg…). Il **convertit** les données de sa source en
entités FollowTheMoney (FtM), et rien d'autre. Le core fait tout le reste.

```
Core (IngestionService)                                  Plugin (JAR)
───────────────────────                                  ────────────
1. récupère la source (HTTP / fichier local)
   → SHA-256, archive, table source_file
   → identique à la dernière publication ? UNCHANGED
2. appelle parse(ctx) ─────────────────────────────────► lit ctx.inputFile()
                                                         écrit le NDJSON FtM dans ctx.outputFile()
                       ◄──────────────────────────────── renvoie un ParserResult (bilan)
3. contrôles qualité (vide, -20 %, rejets > 1 %, JSON invalide…) → BLOCKED si problème
4. comparaison avec la version publiée → table dataset_change
5. publication dans Yente (dossier data + manifest + rechargement)
```

## Écrire un plugin (exemple : `swiftcheck-aml-eu`)

1. Copier le module `swiftcheck-aml-eu`, changer `artifactId` et `plugin.id` dans le `pom.xml`, puis ajouter
   le module dans le `pom.xml` racine.
2. Écrire une classe `@Extension` qui implémente `Iparser` :

```java
@Extension
public class MaListeParser implements Iparser {

    @Override
    public ParserResult parse(ParserContext ctx) throws Exception {
        FtmDatasetBuilder dataset = new FtmDatasetBuilder("ma-liste", "sanction");   // préfixe d'ID, topic
        try (InputStream in = Files.newInputStream(ctx.inputFile())) {
            XmlRecordReader.readEach(in, "fiche", fiche -> {
                FtmEntity person = dataset.newEntity("Person", fiche.attribute("id"));
                person.addValue("name", fiche.childText("nom"));
                person.addValue("nationality", fiche.childText("pays"));     // "France" → "fr" automatiquement
                person.addValue("birthDate", fiche.childText("naissance"));  // "15/03/1965" → "1965-03-15"
                dataset.addEntity(person);
            });
        }
        dataset.writeNdjsonFile(ctx.outputFile());
        return dataset.toConversionResult();
    }
}
```

3. Tester avec un petit extrait réel du fichier dans `src/test/resources` (voir `EuSanctionsParserTest`).

4. Rendre le plugin testable dans l'application (bouton « Tester ») : relire la source **sans la convertir** et
   lister chaque élément criblable (personne, organisation, navire…) avec l'ID et le nom que Yente doit connaître.

```java
    @Override
    public List<SourceRecord> readSourceRecords(ParserContext ctx) throws Exception {
        FtmDatasetBuilder ids = new FtmDatasetBuilder("ma-liste");   // même préfixe que parse() : mêmes ID
        List<SourceRecord> records = new ArrayList<>();
        try (InputStream in = Files.newInputStream(ctx.inputFile())) {
            XmlRecordReader.readEach(in, "fiche", fiche -> records.add(new SourceRecord(
                    ids.buildEntityId(fiche.attribute("id")), "Person", fiche.childText("nom"), "id=" + fiche.attribute("id"))));
        }
        return records;
    }
```

   Le core cherche ensuite chaque élément dans Yente et classe ceux qui manquent :
   `NOT_CONVERTED` (absent du fichier publié : bug du plugin), `NOT_INDEXED` (inconnu de Yente),
   `NOT_MATCHED` (indexé mais introuvable par son nom), `YENTE_ERROR`. Sans cette méthode, le test est impossible.

## Règles

- **Aucun état** : pas de champ modifié entre deux appels de `parse`.
- **Ne jamais** écrire dans le dossier de Yente, modifier le manifest ni accéder à la base : c'est le rôle du core.
- **Préfixe d'ID stable** : ne jamais le changer une fois la liste publiée (les alertes y sont rattachées).
- **Source `PLUGIN_MANAGED`** (SPARQL, API, SQL…) : `ctx.inputFile()` est `null`. Le plugin récupère ses données
  lui-même et **doit** enregistrer chaque réponse brute dans `ctx.rawDir()` (ex. `page1.json`), sinon
  l'exécution est bloquée. Les paramètres saisis dans le parser (requête, URL…) sont dans `ctx.parameters()`.
- `swiftcheck-aml-api` et `pf4j` restent en `provided` : c'est le core qui les fournit.

## Organisation des dossiers

```
swiftcheck-aml-plugins/
├── swiftcheck-aml-eu/                       sanctions financières de l'UE
└── pep/                                     personnes politiquement exposées
    └── luxembourg/                          un dossier par pays
        ├── swiftcheck-aml-lu-pep-chamber/   un dossier par source = un plugin
        └── ...
```

- **Pas de pom dans `pep/` ni dans les dossiers de pays** : chaque parser a son propre `pom.xml`, qui hérite
  directement du pom racine (`<relativePath>../../../../pom.xml</relativePath>`) pour garder les mêmes versions
  de Java et de PF4J que le core.
- **Nouveau parser** : créer son dossier dans le pays (ex. `pep/france/swiftcheck-aml-fr-pep-senat`), puis
  ajouter une ligne `<module>` dans le pom racine. Aucun autre plugin n'est modifié.
- **Un README par parser** : chaque dossier de parser a son `README.md`. Il décrit ce que fait le parser, son contexte
  (catégorie PEP, base légale, rétention), sa source (éditeur, adresse, format, licence), sa configuration dans
  SwiftCheck, les entités FtM produites, les erreurs, les limites connues et ses tests. Le README d'un parser existant
  sert de modèle.
- **Nouveau pays** : créer `pep/<pays>/` (nom du pays en minuscules, sans accent : `france`, `allemagne`…).

## Plugins disponibles

| Module | Plugin-Id | Type de source | Contenu |
|---|---|---|---|
| [`swiftcheck-aml-eu`](swiftcheck-aml-eu/README.md) | `eu-parser` | `HTTP_FILE` | Sanctions financières de l'UE (XML de la Commission) |
| [`pep/luxembourg/swiftcheck-aml-lu-pep-chamber`](pep/luxembourg/swiftcheck-aml-lu-pep-chamber/README.md) | `lu-pep-chamber` | `HTTP_FILE` | PEP Luxembourg – députés en fonction |
| [`pep/luxembourg/swiftcheck-aml-lu-pep-chamber-former`](pep/luxembourg/swiftcheck-aml-lu-pep-chamber-former/README.md) | `lu-pep-chamber-former` | `HTTP_FILE` | PEP Luxembourg – anciens députés |
| [`pep/luxembourg/swiftcheck-aml-lu-pep-government`](pep/luxembourg/swiftcheck-aml-lu-pep-government/README.md) | `lu-pep-government` | `HTTP_FILE` | PEP Luxembourg – membres du gouvernement |
| [`pep/luxembourg/swiftcheck-aml-lu-pep-europarl`](pep/luxembourg/swiftcheck-aml-lu-pep-europarl/README.md) | `lu-pep-europarl` | `HTTP_FILE` | PEP Luxembourg – députés européens |
| [`pep/luxembourg/swiftcheck-aml-lu-pep-municipal`](pep/luxembourg/swiftcheck-aml-lu-pep-municipal/README.md) | `lu-pep-municipal` | `HTTP_FILE` | PEP Luxembourg – bourgmestres et échevins |
| [`pep/luxembourg/swiftcheck-aml-lu-pep-legilux`](pep/luxembourg/swiftcheck-aml-lu-pep-legilux/README.md) | `lu-pep-legilux` | `PLUGIN_MANAGED` | PEP Luxembourg – hautes fonctions nommées au Journal officiel (Conseil d'État, hautes juridictions, Cour des comptes, BCL, CSSF, CAA) |

### PEP Luxembourg (`lu_pep_…`)

**Un parser par source de données** : chaque source a son propre plugin, son dataset Yente, son historique
d'exécutions, ses contrôles qualité et son test. Quand une source change de format, seul son parser échoue,
et l'on sait exactement lequel corriger.

Le périmètre de référence est la liste officielle des fonctions PEP que le Luxembourg a transmise à la Commission
européenne (art. 20 bis de la directive 2015/849, publiée au JO C/2023/724).

| Dataset (nom à saisir dans le parser) | Plugin-Id | Fonctions | Adresse de la source (`sourceLocation`) | Licence |
|---|---|---|---|---|
| `lu_pep_chamber` | `lu-pep-chamber` | b) députés en fonction, avec date de naissance (n° 105, CSV Windows-1252) | `https://data.public.lu/fr/datasets/r/a5b6e7f9-b488-41a6-9bd1-be913a5c35b5` | CC BY |
| `lu_pep_chamber_former` | `lu-pep-chamber-former` | b) anciens députés depuis 1999 (n° 106, CSV UTF-8) | `https://data.public.lu/fr/datasets/r/8fdbd601-6931-47b5-a027-aacd3b1e47f4` | CC BY |
| `lu_pep_government` | `lu-pep-government` | a) membres du gouvernement en fonction (page HTML) | `https://gouvernement.lu/fr/gouvernement.html` | — |
| `lu_pep_europarl` | `lu-pep-europarl` | b) eurodéputés élus au Luxembourg (XML, sans date de naissance) | `https://www.europarl.europa.eu/meps/en/full-list/xml` | réutilisation autorisée, citer la source |
| `lu_pep_municipal` | `lu-pep-municipal` | bourgmestres et échevins (hors liste officielle : approche par les risques ; AMLR 2027 : collectivités ≥ 50 000 hab.) | `https://data.public.lu/fr/datasets/r/067d11cc-29a8-4f0c-bf35-f9d4ac1818ec` | open data |

- Les adresses `data.public.lu/fr/datasets/r/<id>` sont stables : elles redirigent toujours vers la dernière
  version du fichier. Celle des collèges échevinaux changera après les élections communales de 2029.
- Chaque parser écrit directement ses entités avec `FtmDatasetBuilder`, comme `EuSanctionsParser` : par ligne
  de la source, une `Person` (topic `role.pep`), la fonction (`Position`, partagée : même ID) et le mandat
  (`Occupancy` : dates, statut `current` / `ended`) qui les relie.
- ID des personnes : identifiant officiel quand la source en donne un (Parlement européen, page du membre du
  gouvernement), sinon nom + prénom + date de naissance (Chambre) ou nom + prénom + commune (communes).
  Les lignes d'une même personne ont le même ID : le dataset les réunit en une seule `Person`.
  Entre deux sources, une même personne (ex. député-maire) reste deux entités, une par dataset.
- Paramètre `yearsAfterEnd` (JSON, facultatif, 5 par défaut) pour `lu_pep_chamber_former` et `lu_pep_municipal` :
  un mandat terminé depuis plus longtemps est écarté.
- Une date illisible ou une page sans membre arrête l'exécution (FAILED) au lieu de publier une liste fausse.
- Pas encore couverts (prochains parsers) : Conseil d'État, hautes juridictions, Cour des comptes, BCL,
  ambassadeurs, armée, CSSF / CAA / ILR, entreprises publiques, dirigeants des partis, organisations
  internationales, anciens membres du gouvernement et anciens eurodéputés, familles et proches associés.

## Construire et déployer

```bash
mvn -pl swiftcheck-aml-plugins/swiftcheck-aml-eu -am package
mvn -pl swiftcheck-aml-plugins/pep/luxembourg/swiftcheck-aml-lu-pep-chamber -am package
```

Copier `target/swiftcheck-aml-eu-*.jar` dans le dossier des plugins (`pf4j.plugins.dir`), puis :

- au démarrage du core, tous les JAR du dossier sont chargés ;
- sans redémarrer : `POST /api/ftm/plugins/load?path=<chemin du JAR>`. Si une version de même `Plugin-Id` est
  déjà chargée, elle est remplacée. Sous Windows, un JAR chargé est verrouillé : déposer la nouvelle version
  sous un **nouveau nom** (ex. `swiftcheck-aml-eu-1.0.1.jar`) ;
- relancer ensuite le parser avec `POST /api/parsers/{id}/run?force=true` pour reconvertir la source avec la
  nouvelle version (sinon l'exécution s'arrête en UNCHANGED si la source n'a pas changé).

