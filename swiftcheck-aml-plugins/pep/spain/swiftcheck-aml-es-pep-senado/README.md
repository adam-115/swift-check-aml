# es_pep_senado – Sénateurs (Wikidata)

## Ce que fait ce parser

Le site et l'open data du Sénat (`senado.es`) répondent **HTTP 403** (protection non contournée). En attendant un accès ou un export, le parser lit les sénateurs que Wikidata rattache à la XVe législature. Le plugin ne garde que les mandats qui ont une preuve de date dans la fenêtre de rétention (fin de mandat récente, ou début après `openSince` pour un mandat sans fin) ; un titulaire mort est écarté.

## Contexte

| | |
|---|---|
| Bloc du référentiel | **ES-b** (loi 10/2010, art. 14 ; liste de la Commission, JO C/2023/724) |
| Source | Wikidata (SPARQL) : les sénateurs de la XVe législature (`P39` = sénateur d'Espagne, qualificatif `P2937` = XVe législature) |
| Rétention | 24 mois après la fin des fonctions (loi 10/2010, art. 14.9) |

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `es_pep_senado` |
| Plugin-Id | `es-pep-senado` |
| Type de source | PLUGIN_MANAGED (SPARQL) |
| Fréquence | 168 h |
| Paramètres | `{"endpoint":"https://query.wikidata.org/sparql","monthsAfterEnd":"24","delayMs":"2000","kind":"positions","positions":"Q19323171","terms":"Q118800859 Q121224921","positionLabel":"Sénateur (Senador)","organization":"Senado","topics":"gov.legislative gov.national","openSince":"2023-01-01"}` |

Données brutes archivées : `holders.json` (réponse SPARQL) et `query.rq` (la requête envoyée).

## Ce qui est produit (FtM)

`Person` (QID Wikidata, nom, naissance à la précision de Wikidata, genre, nationalité `es`), `Position`, `Occupancy` (début, fin, statut `current`/`ended`).

## Limites

- **Couverture d'environ 13 %** : 34 sénateurs sur 265 sont décrits dans Wikidata (mesure du 03/11/2026). La couverture est déclarée `PARTIAL` dans l'écran « Couverture PEP ».
- Le Sénat est dissous depuis le 05/10/2026 : les sénateurs de la XVe législature sont des anciens PEP, 24 mois.
- **Données collaboratives** : un changement de titulaire non saisi dans Wikidata n'est pas vu. Pas de photo.

## Tests

Test sur la réponse réelle de Wikidata du 04/11/2026 (`holders.json`) : conversion, titulaire connu, rétention de 24 mois, réponse vide, requête protégée contre l'injection, précision de la date de naissance. Le test `snapshotLive` rafraîchit la réponse avec `-Dlive.snapshot=<dossier>`.

Construire : `mvn -pl swiftcheck-aml-plugins/pep/spain/swiftcheck-aml-es-pep-senado -am package`.
