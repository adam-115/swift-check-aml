# lu_pep_legilux – Hautes fonctions nommées au Journal officiel (Luxembourg)

## Ce que fait ce parser

Il interroge **Legilux**, le Journal officiel du Luxembourg, et lit les publications de **nomination, de renouvellement et de démission** aux hautes fonctions de l'État. Les institutions concernées sont le Conseil d'État, les hautes juridictions, la Cour des comptes, la Banque centrale (BCL), la CSSF et le Commissariat aux assurances.
Pour chaque personne, il reconstitue ses mandats (début, fin, statut) et les publie en entités FtM.

C'est le seul parser du Luxembourg qui donne aussi les **anciens titulaires** de ces fonctions, depuis 2017.

**Résultat réel (18 octobre 2026)** :

| | |
|---|---|
| Actes lus | 1 623 |
| Nominations, renouvellements et démissions de hautes fonctions | 170 |
| Personnes publiées | **83** |
| Mandats | 12 `current`, 30 `ended`, 63 `unknown` |
| Actes atypiques | 2 (nominations collectives, « sont nommés ») |
| Durée | environ 5 minutes, surtout le téléchargement des textes |

## Contexte

| | |
|---|---|
| Pays | Luxembourg |
| Liste officielle | Liste des fonctions PEP du Luxembourg, JO C/2023/724 |
| Catégories couvertes (en partie) | **LU-b** Conseil d'État · **LU-d** Cour constitutionnelle, Cour de cassation, présidence de la Cour supérieure de justice, Parquet général, Cour administrative · **LU-e** Cour des comptes, direction de la BCL · **LU-i** direction de la CSSF et du Commissariat aux assurances |
| Base légale | Directive (UE) 2015/849, art. 3, point 9 b), d) et e) ; catégorie i ajoutée par le Luxembourg |
| Rétention | 12 mois (gérée par le core), en plus du filtre `yearsAfterEnd` |

**Couverture partielle** : seuls les titulaires nommés, renouvelés ou démissionnaires **depuis 2017** sont connus. Un conseiller d'État nommé en 2015 et toujours en fonction n'apparaît pas tant qu'aucun acte ne le concerne. Les pages de composition des institutions, listées dans l'écran « Sources PEP », restent nécessaires pour couvrir 100 % des titulaires.

## Source

| | |
|---|---|
| Éditeur | Service central de législation (SCL), Legilux |
| Point SPARQL | `https://data.legilux.public.lu/sparqlendpoint` (ontologie JOLux) |
| Textes | Un XML Akoma Ntoso par publication, à l'adresse `https://data.legilux.public.lu/filestore/eli/<ELI>/fr/xml/…`, déduite de l'identifiant ELI de l'acte |
| Licence | Données publiques du Journal officiel : citer « Journal officiel du Grand-Duché de Luxembourg, legilux.public.lu » |
| Période | **Depuis 2017** : avant, les publications n'ont pas de texte XML exploitable |
| Volume | Environ 1 600 publications depuis 2017, dont environ 1 430 « Magistrature » |

La requête SPARQL (dans `LegiluxDownloader.QUERY`) sélectionne les **publications administratives** (type `PA`) dont le titre commence par « Conseil d'État », « Magistrature », « Cour des comptes », « Banque centrale du Luxembourg », « Commission de surveillance du secteur financier » ou « Commissariat aux assurances », et contient « nomination » ou « démission ».

Tournures reconnues (`AppointmentReader`), relevées sur les 1 623 actes :

| Événement | Tournures |
|---|---|
| Nomination | « est nommé(e) … », « a été nommé(e) … », « est désigné(e) … », avec ou sans « à la fonction de », « en tant que » |
| Renouvellement | « la nomination (ou le mandat) de … a été (ou est) renouvelé(e) », « est continué(e) dans sa fonction de », « est reconduit(e) dans sa fonction de » |
| Démission | « démission (honorable) de ses fonctions de … est (ou a été) accordée[, sur sa demande / pour cause de limite d'âge,] à … », « … est démissionné(e) de ses fonctions de … », « démission … accordée à Madame X, *fonction*, … » |
| Plusieurs fonctions | « de vice-président de la Cour supérieure de justice, de conseiller à la Cour de cassation et de membre de la Cour constitutionnelle » donne trois événements |

Exemples réels de textes :

```text
Conseil d'État - Nomination.
Par arrêté grand-ducal du 24 juillet 2026, Monsieur Stephen DE RON, maître en droit, est nommé membre
du Conseil d'État, avec effet au 15 septembre 2026.

Commission de surveillance du secteur financier - Direction - Nomination.
Par arrêté grand-ducal du 10 juillet 2026, la nomination de Monsieur Jean-Pierre FABER en tant que Directeur
à la Commission de surveillance du secteur financier, a été renouvelée pour une période de cinq ans à partir
du 1 er septembre 2026.

Conseil d'État - Démission.
Par arrêté grand-ducal du 29 juillet 2026, démission honorable de ses fonctions de membre du Conseil d'État
est accordée à Madame Deidre DU BOIS, avec effet au 15 septembre 2026.
```

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Fournisseur | `LU-SCL` – Service central de législation (Legilux) |
| Nom du dataset | `lu_pep_legilux` |
| Plugin-Id | `lu-pep-legilux` |
| Type de source | **`PLUGIN_MANAGED`** : le plugin interroge lui-même Legilux ; pas d'adresse de source |
| Nature de la source | T3 – Point d'accès SPARQL |
| Fréquence | 168 h (hebdomadaire) : peu de nominations, et on ne surcharge pas Legilux |
| Rétention | 12 mois |
| Paramètres | `{"endpoint": "https://data.legilux.public.lu/sparqlendpoint", "since": "2017-01-01", "yearsAfterEnd": "5"}` |
| Source PEP | « Legilux – Journal officiel (SPARQL) » |

- `endpoint` (obligatoire) : adresse du point SPARQL. Elle est configurée ici, jamais écrite dans le code.
- `since` : date des premiers actes lus (2017-01-01 par défaut).
- `yearsAfterEnd` : un mandat terminé depuis plus longtemps est écarté (5 ans par défaut).

### Données brutes archivées

À chaque exécution, le plugin dépose deux fichiers dans `rawDir`. Le core les signe (SHA-256) et les archive :

| Fichier | Contenu |
|---|---|
| `legilux-actes.csv` | Réponse du point SPARQL : `act`, `date`, `title` |
| `legilux-textes.zip` | Le XML officiel de chaque acte, une entrée par acte |

L'archive est construite de façon reproductible (entrées triées et datées du 1er janvier 1970). Une semaine sans nouvelle publication donne donc la même empreinte, et l'exécution s'arrête en `UNCHANGED`.
Le test du parser (`readSourceRecords`) relit ces deux fichiers sans appel réseau.

## Ce qui est produit (FtM)

| Entité | ID | Contenu |
|---|---|---|
| `Person` | `lu-pep-legilux-<prénom nom en minuscules>` | nom, prénom (les mots en majuscules forment le nom ; sans majuscules, le dernier mot), pays `lu`, actes sources (`sourceUrl`), topic `role.pep` |
| `Position` | une par fonction écrite, ex. « Conseiller à la Cour de cassation » | pays `lu`, topic `gov.national`, `gov.judicial` ou `gov.financial` |
| `Occupancy` | personne + fonction + acte | début, fin, statut, lien vers l'acte |

Reconstitution des mandats, acte par acte, dans l'ordre des dates d'effet :

| Acte | Effet |
|---|---|
| Nomination | ouvre un mandat. Début = date d'effet (« avec effet au », « à partir du », « à compter du »). Fin = « jusqu'au » s'il y en a un. |
| Renouvellement | prolonge le mandat ouvert. S'il n'en existe pas, crée un mandat **sans date de début** (première nomination avant 2017). Fin = date d'effet + durée (« pour une période de cinq ans »). |
| Nomination à une fonction déjà ouverte | ne crée pas de deuxième mandat (acte publié deux fois) |
| Démission | ferme les mandats ouverts de la même personne dans la même institution. Si le mandat est déjà fermé, par exemple quand une première démission a été « rapportée », la dernière date fait foi. S'il n'y a aucun mandat, en crée un terminé sans date de début. |

**Statut** du mandat :
- `ended` : la fin est passée ;
- `current` : la fin est à venir ;
- **`unknown`** : aucune fin connue. Un départ à la retraite ou une promotion n'a pas toujours d'acte de démission ; écrire `current` serait parfois faux.

### Hautes fonctions gardées

Seules les fonctions de `LuPepLegiluxParser.PEP_FUNCTIONS` sont gardées. Environ 5 % des actes « Magistrature » seulement concernent une haute juridiction ; les juges de première instance ne sont pas PEP.

| Clé | Fonction écrite reconnue |
|---|---|
| `conseil-etat` | membre, conseiller, président du Conseil d'État |
| `cour-cassation` | … à la Cour de cassation |
| `cour-superieure` | président ou vice-président de la Cour supérieure de justice |
| `parquet-general` | procureur général d'État (et adjoints) |
| `cour-administrative` | … à la Cour administrative |
| `cour-constitutionnelle` | … à la Cour constitutionnelle |
| `cour-des-comptes` | … à la Cour des comptes |
| `bcl-direction` | directeur (général) de la Banque centrale du Luxembourg |
| `regulateur-direction` | directeur, membre du comité de direction de la CSSF ou du Commissariat aux assurances |

Les fonctions d'appui ne sont **jamais** gardées : greffier, référendaire, attaché, secrétaire, stagiaire, pool de complément, remplaçant, suppléant.

## Règles et erreurs

- **Acte atypique** : un acte qui cite une haute institution mais dont la tournure n'est pas reconnue (ex. « Madame A et Monsieur B sont nommés… ») n'arrête pas l'exécution. Il est cité dans le message (les 20 premiers, sous la forme `pa-2026-10-03-b1234`), pour ajouter la tournure au parser.
- **Acte sans texte** : un acte dont le XML n'a pas pu être téléchargé est compté dans le message.
- **Aucune personne trouvée** : l'exécution échoue (FAILED), par exemple si la requête SPARQL ou la rédaction des actes a changé. Yente garde la version précédente.
- Erreurs réseau : 3 tentatives par requête. Une erreur 4xx du point SPARQL arrête l'exécution.

## Limites connues

- **Titulaires nommés avant 2017** absents, sauf s'ils ont été renouvelés ou ont démissionné depuis.
- **ID par prénom et nom** : les actes ne donnent ni identifiant ni date de naissance. Deux homonymes dans ces fonctions seraient réunis. Le risque est faible, mais réel.
- Une même personne présente dans un autre dataset (ex. un conseiller d'État ancien député) reste deux entités. Le rapprochement relève de la résolution d'entités.
- **Non couverts par Legilux** :
  - ambassadeurs (plus publiés depuis 2016) ;
  - officiers de l'armée ;
  - dirigeants d'entreprises publiques ;
  - conseil d'administration de l'ILR (publié en arrêté, pas en publication administrative).
- Les mandats sans fin connue ont le statut `unknown` : la date de départ réelle n'est pas publiée.

## Tests

`LuPepLegiluxParserTest`, sur **10 vrais actes** de Legilux (`src/test/resources/legilux/` : `actes.csv` et un XML par acte). Ils sont déposés dans `rawDir` comme le ferait le téléchargement, avec une date du jour fixe. Il vérifie :
- la nomination au Conseil d'État (date d'effet, statut `unknown`, découpage du nom) ;
- la démission sans nomination connue (mandat `ended` sans début) ;
- la nomination à la Cour de cassation ;
- les renouvellements CSSF, BCL et CAA (fin = effet + durée, statut `current`) ;
- la Cour des comptes avec « jusqu'au » et un nom sans majuscules ;
- les juges de première instance et les remplaçants non repris ;
- une démission qui ferme le mandat d'une nomination antérieure (texte construit sur le modèle réel) ;
- une nomination publiée deux fois et une démission « rapportée » puis remplacée : un seul mandat ;
- les autres tournures réelles : « a été continué », trois fonctions dans une démission, renouvellement sans « en tant que » ;
- un acte atypique listé sans bloquer ;
- l'échec quand aucune personne n'est trouvée ;
- les mêmes ID entre `parse` et `readSourceRecords` ;
- une phrase qui contient une nomination et une démission ;
- les fonctions d'appui jamais PEP ;
- le texte complet d'un paragraphe qui contient un lien `<ref>`.

## Construire

```bash
mvn -pl swiftcheck-aml-plugins/pep/luxembourg/swiftcheck-aml-lu-pep-legilux -am package
```
