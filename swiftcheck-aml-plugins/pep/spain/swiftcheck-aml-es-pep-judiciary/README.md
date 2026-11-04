# es_pep_judiciary – Hautes juridictions (Wikidata)

## Ce que fait ce parser

Le Tribunal constitutionnel est protégé par un test de navigateur (non contourné) et poderjudicial.es n'a pas de page de composition lisible. Wikidata complète les nominations du BOE pour les titulaires nommés avant la période lue. Le plugin ne garde que les mandats qui ont une preuve de date dans la fenêtre de rétention (fin de mandat récente, ou début après `openSince` pour un mandat sans fin) ; un titulaire mort est écarté.

## Contexte

| | |
|---|---|
| Bloc du référentiel | **ES-c** (loi 10/2010, art. 14 ; liste de la Commission, JO C/2023/724) |
| Source | Wikidata (SPARQL) : magistrats et président du Tribunal constitutionnel, président du Tribunal suprême |
| Rétention | 24 mois après la fin des fonctions (loi 10/2010, art. 14.9) |

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `es_pep_judiciary` |
| Plugin-Id | `es-pep-judiciary` |
| Type de source | PLUGIN_MANAGED (SPARQL) |
| Fréquence | 168 h |
| Paramètres | `{"endpoint":"https://query.wikidata.org/sparql","monthsAfterEnd":"24","delayMs":"2000","kind":"positions","positions":"Q64624734 Q28518041 Q29934477","positionLabel":"{subject}","organization":"Hautes juridictions","topics":"gov.judicial gov.national","openSince":"2012-01-01"}` |

Données brutes archivées : `holders.json` (réponse SPARQL) et `query.rq` (la requête envoyée).

## Ce qui est produit (FtM)

`Person` (QID Wikidata, nom, naissance à la précision de Wikidata, genre, nationalité `es`), `Position`, `Occupancy` (début, fin, statut `current`/`ended`).

## Limites

- **Wikidata peut être en retard** : au 03/11/2026 il donne encore Francisco Marín Castán comme président du Tribunal suprême, sans date de fin. Contrôler la fiche après un changement de président.
- Pas de liste des magistrats et présidents de chambre du Tribunal suprême, ni du CGPJ.
- **Données collaboratives** : un changement de titulaire non saisi dans Wikidata n'est pas vu. Pas de photo.

## Tests

Test sur la réponse réelle de Wikidata du 04/11/2026 (`holders.json`) : conversion, titulaire connu, rétention de 24 mois, réponse vide, requête protégée contre l'injection, précision de la date de naissance. Le test `snapshotLive` rafraîchit la réponse avec `-Dlive.snapshot=<dossier>`.

Construire : `mvn -pl swiftcheck-aml-plugins/pep/spain/swiftcheck-aml-es-pep-judiciary -am package`.
