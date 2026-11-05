# es_pep_regional_parliaments – Députés des parlements autonomes (Wikidata)

## Ce que fait ce parser

Chaque parlement régional publie ses membres sous un format différent. Wikidata en décrit une partie, avec dates de mandat. Le plugin ne garde que les mandats qui ont une preuve de date dans la fenêtre de rétention (fin de mandat récente, ou début après `openSince` pour un mandat sans fin) ; un titulaire mort est écarté.

## Contexte

| | |
|---|---|
| Bloc du référentiel | **ES-CCAA** (loi 10/2010, art. 14 ; liste de la Commission, JO C/2023/724) |
| Source | Wikidata (SPARQL) : fonctions de député des 16 communautés (juridiction `P1001`, libellé « diputado/procurador/parlamentario »), mandats depuis 2022 |
| Rétention | 24 mois après la fin des fonctions (loi 10/2010, art. 14.9) |

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `es_pep_regional_parliaments` |
| Plugin-Id | `es-pep-regional-parliaments` |
| Type de source | PLUGIN_MANAGED (SPARQL) |
| Fréquence | 168 h |
| Paramètres | `{"endpoint":"https://query.wikidata.org/sparql","monthsAfterEnd":"24","delayMs":"2000","kind":"positions","jurisdictions":"Q3908 Q3934 Q3946 Q3995 Q4040 Q5705 Q5720 Q5727 Q5739 Q5748 Q5756 Q5772 Q5777 Q5783 Q5813 Q107356467","positionRegex":"^(diputad|procurador|parlamentari)","positionLabel":"{subject}","topics":"gov.legislative gov.regional","openSince":"2022-01-01"}` |

Données brutes archivées : `holders.json` (réponse SPARQL) et `query.rq` (la requête envoyée).

## Ce qui est produit (FtM)

`Person` (QID Wikidata, nom, naissance à la précision de Wikidata, genre, nationalité `es`), `Position`, `Occupancy` (début, fin, statut `current`/`ended`).

## Limites

- **Couverture d'environ un tiers** : 436 personnes sur ≈ 1 250 députés régionaux (mesure du 03/11/2026).
- Aucun parlement n'est lu à la source : brancher les open data régionaux (Catalogne, Andalousie…) un par un.
- **Données collaboratives** : un changement de titulaire non saisi dans Wikidata n'est pas vu. Pas de photo.

## Tests

Test sur la réponse réelle de Wikidata du 04/11/2026 (`holders.json`) : conversion, titulaire connu, rétention de 24 mois, réponse vide, requête protégée contre l'injection, précision de la date de naissance. Le test `snapshotLive` rafraîchit la réponse avec `-Dlive.snapshot=<dossier>`.

Construire : `mvn -pl swiftcheck-aml-plugins/pep/spain/swiftcheck-aml-es-pep-regional-parliaments -am package`.
