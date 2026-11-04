# es_pep_state_enterprises – Dirigeants d'entreprises publiques (Wikidata)

## Ce que fait ce parser

Il n'existe pas de liste nominative unique des organes des entreprises publiques. Sept entités sont configurées : SEPI, Correos, Renfe, Adif, RTVE, Indra et Hispasat. Le plugin ne garde que les mandats qui ont une preuve de date dans la fenêtre de rétention (fin de mandat récente, ou début après `openSince` pour un mandat sans fin) ; un titulaire mort est écarté.

## Contexte

| | |
|---|---|
| Bloc du référentiel | **ES-g** (loi 10/2010, art. 14 ; liste de la Commission, JO C/2023/724) |
| Source | Wikidata (SPARQL) : président ou directeur général (`P488`, `P169`, `P1037`) de sept entités |
| Rétention | 24 mois après la fin des fonctions (loi 10/2010, art. 14.9) |

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `es_pep_state_enterprises` |
| Plugin-Id | `es-pep-state-enterprises` |
| Type de source | PLUGIN_MANAGED (SPARQL) |
| Fréquence | 168 h |
| Paramètres | `{"endpoint":"https://query.wikidata.org/sparql","monthsAfterEnd":"24","delayMs":"2000","kind":"heads","entities":"Q1436777 Q776605 Q2476154 Q358752 Q54829 Q1661823 Q1425548","properties":"P488 P169 P1037","positionLabel":"Dirigeant de {subject}","topics":"gov.soe","openSince":"2015-01-01"}` |

Données brutes archivées : `holders.json` (réponse SPARQL) et `query.rq` (la requête envoyée).

## Ce qui est produit (FtM)

`Person` (QID Wikidata, nom, naissance à la précision de Wikidata, genre, nationalité `es`), `Position`, `Occupancy` (début, fin, statut `current`/`ended`).

## Limites

- Sept entités sur plusieurs centaines ; seulement le président ou le directeur général.
- À étudier : la SEPI, le Patrimonio del Estado et l'inventaire des entités du secteur public (IGAE).
- **Données collaboratives** : un changement de titulaire non saisi dans Wikidata n'est pas vu. Pas de photo.

## Tests

Test sur la réponse réelle de Wikidata du 04/11/2026 (`holders.json`) : conversion, titulaire connu, rétention de 24 mois, réponse vide, requête protégée contre l'injection, précision de la date de naissance. Le test `snapshotLive` rafraîchit la réponse avec `-Dlive.snapshot=<dossier>`.

Construire : `mvn -pl swiftcheck-aml-plugins/pep/spain/swiftcheck-aml-es-pep-state-enterprises -am package`.
