# es_pep_parties_unions – Dirigeants des partis et syndicats (Wikidata)

## Ce que fait ce parser

Quinze organisations configurées : PSOE, PP, Vox, Sumar, ERC, Junts, EH Bildu, PNV, BNG, Coalición Canaria, Podemos, CCOO, UGT, CEOE et Cepyme. Le plugin ne garde que les mandats qui ont une preuve de date dans la fenêtre de rétention (fin de mandat récente, ou début après `openSince` pour un mandat sans fin) ; un titulaire mort est écarté.

## Contexte

| | |
|---|---|
| Bloc du référentiel | **ES-h, ES-SIND** (loi 10/2010, art. 14 ; liste de la Commission, JO C/2023/724) |
| Source | Wikidata (SPARQL) : président ou secrétaire général (`P488`, `P3320`, `P3975`) de quinze organisations |
| Rétention | 24 mois après la fin des fonctions (loi 10/2010, art. 14.9) |

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `es_pep_parties_unions` |
| Plugin-Id | `es-pep-parties-unions` |
| Type de source | PLUGIN_MANAGED (SPARQL) |
| Fréquence | 168 h |
| Paramètres | `{"endpoint":"https://query.wikidata.org/sparql","monthsAfterEnd":"24","delayMs":"2000","kind":"heads","entities":"Q138198 Q185088 Q15630787 Q112098527 Q150068 Q97155800 Q1029761 Q43093 Q885433 Q1104327 Q16059622 Q2001535 Q833196 Q393165 Q5782715","properties":"P488 P3320 P3975","positionLabel":"Dirigeant de {subject}","topics":"gov.national","openSince":"2015-01-01"}` |

Données brutes archivées : `holders.json` (réponse SPARQL) et `query.rq` (la requête envoyée).

## Ce qui est produit (FtM)

`Person` (QID Wikidata, nom, naissance à la précision de Wikidata, genre, nationalité `es`), `Position`, `Occupancy` (début, fin, statut `current`/`ended`).

## Limites

- Un à deux dirigeants par organisation : pas les comités exécutifs.
- CEOE et Cepyme n'ont pas de dirigeant daté dans Wikidata.
- **Données collaboratives** : un changement de titulaire non saisi dans Wikidata n'est pas vu. Pas de photo.

## Tests

Test sur la réponse réelle de Wikidata du 04/11/2026 (`holders.json`) : conversion, titulaire connu, rétention de 24 mois, réponse vide, requête protégée contre l'injection, précision de la date de naissance. Le test `snapshotLive` rafraîchit la réponse avec `-Dlive.snapshot=<dossier>`.

Construire : `mvn -pl swiftcheck-aml-plugins/pep/spain/swiftcheck-aml-es-pep-parties-unions -am package`.
