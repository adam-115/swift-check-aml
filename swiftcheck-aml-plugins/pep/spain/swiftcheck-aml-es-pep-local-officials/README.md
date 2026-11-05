# es_pep_local_officials – Maires des grandes communes et chefs-lieux (Wikidata)

## Ce que fait ce parser

Le registre national des élus locaux est introuvable (portail en 503/404, jeu de données éteint). Wikidata donne les maires avec leurs dates. Le niveau local est PEP en Espagne dès aujourd'hui. Le plugin ne garde que les mandats qui ont une preuve de date dans la fenêtre de rétention (fin de mandat récente, ou début après `openSince` pour un mandat sans fin) ; un titulaire mort est écarté.

## Contexte

| | |
|---|---|
| Bloc du référentiel | **ES-LOC** (loi 10/2010, art. 14 ; liste de la Commission, JO C/2023/724) |
| Source | Wikidata (SPARQL) : maires (`P6`) des communes de plus de 50 000 habitants et des chefs-lieux de province |
| Rétention | 24 mois après la fin des fonctions (loi 10/2010, art. 14.9) |

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `es_pep_local_officials` |
| Plugin-Id | `es-pep-local-officials` |
| Type de source | PLUGIN_MANAGED (SPARQL) |
| Fréquence | 168 h |
| Paramètres | `{"endpoint":"https://query.wikidata.org/sparql","monthsAfterEnd":"24","delayMs":"2000","kind":"mayors","minPopulation":"50000","positionLabel":"Maire de {subject} (Alcalde)","topics":"gov.muni","openSince":"2019-01-01"}` |

Données brutes archivées : `holders.json` (réponse SPARQL) et `query.rq` (la requête envoyée).

## Ce qui est produit (FtM)

`Person` (QID Wikidata, nom, naissance à la précision de Wikidata, genre, nationalité `es`), `Position`, `Occupancy` (début, fin, statut `current`/`ended`).

## Limites

- **55 maires** pour ≈ 150 communes concernées (mesure du 03/11/2026).
- Aucun conseiller municipal.
- Source à chercher : ministère de la Politique territoriale, sinon les communautés autonomes.
- **Données collaboratives** : un changement de titulaire non saisi dans Wikidata n'est pas vu. Pas de photo.

## Tests

Test sur la réponse réelle de Wikidata du 04/11/2026 (`holders.json`) : conversion, titulaire connu, rétention de 24 mois, réponse vide, requête protégée contre l'injection, précision de la date de naissance. Le test `snapshotLive` rafraîchit la réponse avec `-Dlive.snapshot=<dossier>`.

Construire : `mvn -pl swiftcheck-aml-plugins/pep/spain/swiftcheck-aml-es-pep-local-officials -am package`.
