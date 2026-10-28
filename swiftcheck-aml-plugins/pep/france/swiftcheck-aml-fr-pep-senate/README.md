# fr_pep_senate – Sénateurs (France)

## Ce que fait ce parser

Il lit l'open data du Sénat et garde les **sénateurs en fonction**, les **sénateurs sortis depuis moins de 12 mois** et la **présidence du Sénat**, avec leur date de naissance. Il les convertit en entités FollowTheMoney (FtM), que le core publie dans Yente.

Mesure du 28/10/2026 : 348 sénateurs en fonction et 75 sortis depuis moins d'un an (renouvellement du 30/09/2026) ; présidence : Gérard Larcher. 423 personnes, toutes avec leur date de naissance.

## Contexte

| | |
|---|---|
| Pays | France |
| Catégorie PEP | **FR-b** « Députés et sénateurs » (CMF, art. R. 561-18 ; directive (UE) 2015/849, art. 3, point 9 b) |
| Fonctions couvertes | Sénateur, Ancien sénateur (sorti depuis moins de 12 mois), Président du Sénat |
| Classe de risque | 1 |
| Rétention | 12 mois (R. 561-18) : appliquée par le parser aux sénateurs déjà sortis, puis par le core à ceux qui sortiront |

## Source

| | |
|---|---|
| Éditeur | Sénat |
| Fichiers | `https://data.senat.fr/data/senateurs/ODSEN_GENERAL.csv` (un sénateur par ligne, depuis 1959) et `ODSEN_ELUSEN.csv` (un mandat par ligne) |
| Format | CSV, séparateur `,`, **Windows-1252**, précédé de lignes `%` (la requête SQL qui a produit le fichier) |
| Licence | Licence Ouverte / Open Licence (Etalab 2.0) : citer « Sénat, data.senat.fr » |

```csv
Matricule,Qualité,Nom usuel,Prénom usuel,État,Date naissance,Date de décès,Groupe politique,…,Circonscription,Fonction au Bureau du Sénat,…
86034E,M.,Larcher,Gérard,ACTIF,1949-09-14 00:00:00.0,,Les Républicains,,Aucune,Yvelines,Président du Sénat,…
```

## Configuration dans SwiftCheck (écran « Parsers »)

| Champ | Valeur |
|---|---|
| Nom du dataset | `fr_pep_senate` |
| Plugin-Id | `fr-pep-senate` |
| Type de source | `PLUGIN_MANAGED` : deux fichiers sont nécessaires, le plugin les télécharge et les dépose dans `rawDir` |
| Nature de la source | T1 – Fichier open data |
| Fréquence | 24 h |
| Paramètres | `{"generalUrl": "https://data.senat.fr/data/senateurs/ODSEN_GENERAL.csv", "mandatesUrl": "https://data.senat.fr/data/senateurs/ODSEN_ELUSEN.csv", "monthsAfterEnd": "12"}` |

## Ce qui est produit (FtM)

| Entité | ID | Contenu |
|---|---|---|
| `Person` | `fr-pep-senate-<matricule>` (ex. `86034E`) | nom, prénom, date de naissance, genre, pays `fr`, groupe politique, fonctions, lien vers la fiche senat.fr, topic `role.pep` |
| `Position` | `…-senator` et `…-president` | « Sénateur », « Président du Sénat », topics `gov.national`, `gov.legislative` |
| `Occupancy` | matricule + identifiant du mandat | début, fin, statut `current` / `ended`, circonscription, groupe politique |

## Règles et erreurs

- Seuls les mandats en cours ou terminés depuis moins de `monthsAfterEnd` mois sont écrits.
- Un ancien sénateur décédé n'est pas repris.
- La fonction au Bureau n'est reprise que pour un sénateur en fonction : pour un ancien, c'est sa dernière fonction, sans date.
- Date de fin de mandat ou de décès illisible, aucun sénateur en fonction, paramètre manquant : l'exécution s'arrête (FAILED).

## Limites connues

- Le groupe politique et la circonscription sont ceux de la liste générale (actuels, ou derniers connus pour un ancien sénateur), répétés sur chaque mandat.
- Les dates de la présidence du Sénat ne sont pas données par ces fichiers.
- Un sénateur ancien député (ex. Gabriel Amard, élu au Sénat le 01/10/2026) est aussi dans `fr_pep_national_assembly`, avec un autre ID.

## Tests

`FrPepSenateParserTest`, sur un extrait réel (`src/test/resources/senat/`, 6 sénateurs, 27/10/2026) : en fonction, sorti en septembre 2026, sortis il y a plus d'un an, décédé, présidence, paramètre de rétention, date illisible, téléchargement.

## Construire

```bash
mvn -pl swiftcheck-aml-plugins/pep/france/swiftcheck-aml-fr-pep-senate -am package
```
