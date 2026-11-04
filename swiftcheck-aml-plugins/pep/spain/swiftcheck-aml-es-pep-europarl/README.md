# es_pep_europarl – Députés européens

## Ce que fait ce parser

Reprise de `be_pep_europarl` : la liste des 61 eurodéputés en fonction, la liste de la législature, puis la fiche de chacun (naissance, genre, mandats). Rétention de 24 mois.

## Contexte

| | |
|---|---|
| Bloc du référentiel | **ES-EU** (loi 10/2010, art. 14 ; liste de la Commission, JO C/2023/724) |
| Source | API open data du Parlement européen (`data.europarl.europa.eu`, `country-of-representation=ES`) |
| Rétention | 24 mois après la fin des fonctions (loi 10/2010, art. 14.9) |

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `es_pep_europarl` |
| Plugin-Id | `es-pep-europarl` |
| Type de source | PLUGIN_MANAGED (API) |
| Fréquence | 24 h |
| Paramètres | `{"monthsAfterEnd":"24","delayMs":"300"}` |

Données brutes archivées : `current.json`, `term.json`, `mep-ID.json` et `wikidata-images.tsv`.

## Ce qui est produit (FtM)

`Person` (naissance, genre, nationalité `es`), `Position`, `Occupancy` par mandat ; photo Wikidata (P1186) quand le député est identifié.

## Limites

- Ce que dit l'API : un député qui a quitté le Parlement disparaît de la liste courante, d'où la lecture de la liste de la législature.

## Tests

`EsPepEuroparlParserTest` sur des fiches réelles (Alicia Homs Ginel, Javier Nart, Ernest Urtasun).

Construire : `mvn -pl swiftcheck-aml-plugins/pep/spain/swiftcheck-aml-es-pep-europarl -am package`.
