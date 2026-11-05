# es_pep_regional_governments – Présidents des communautés autonomes (Wikidata)

## Ce que fait ce parser

Les 17 sites de gouvernements autonomes n'ont pas de format commun. Wikidata donne le président de chaque communauté avec ses dates de mandat. Le plugin ne garde que les mandats qui ont une preuve de date dans la fenêtre de rétention (fin de mandat récente, ou début après `openSince` pour un mandat sans fin) ; un titulaire mort est écarté.

## Contexte

| | |
|---|---|
| Bloc du référentiel | **ES-CCAA** (loi 10/2010, art. 14 ; liste de la Commission, JO C/2023/724) |
| Source | Wikidata (SPARQL) : propriété « chef du gouvernement » (`P6`) des 16 communautés autonomes |
| Rétention | 24 mois après la fin des fonctions (loi 10/2010, art. 14.9) |

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `es_pep_regional_governments` |
| Plugin-Id | `es-pep-regional-governments` |
| Type de source | PLUGIN_MANAGED (SPARQL) |
| Fréquence | 168 h |
| Paramètres | `{"endpoint":"https://query.wikidata.org/sparql","monthsAfterEnd":"24","delayMs":"2000","kind":"heads","entities":"Q3908 Q3934 Q3946 Q3995 Q4040 Q5705 Q5720 Q5727 Q5739 Q5748 Q5756 Q5772 Q5777 Q5783 Q5813 Q107356467","properties":"P6","positionLabel":"Président de {subject}","topics":"gov.executive gov.regional","openSince":"2015-01-01"}` |

Données brutes archivées : `holders.json` (réponse SPARQL) et `query.rq` (la requête envoyée).

## Ce qui est produit (FtM)

`Person` (QID Wikidata, nom, naissance à la précision de Wikidata, genre, nationalité `es`), `Position`, `Occupancy` (début, fin, statut `current`/`ended`).

## Limites

- Ni Ceuta ni Melilla (villes autonomes).
- Les conseillers (consejeros) et les hauts cargos régionaux ne sont pas couverts : il faudrait les bulletins officiels régionaux (non faits).
- **Données collaboratives** : un changement de titulaire non saisi dans Wikidata n'est pas vu. Pas de photo.

## Tests

Test sur la réponse réelle de Wikidata du 04/11/2026 (`holders.json`) : conversion, titulaire connu, rétention de 24 mois, réponse vide, requête protégée contre l'injection, précision de la date de naissance. Le test `snapshotLive` rafraîchit la réponse avec `-Dlive.snapshot=<dossier>`.

Construire : `mvn -pl swiftcheck-aml-plugins/pep/spain/swiftcheck-aml-es-pep-regional-governments -am package`.
