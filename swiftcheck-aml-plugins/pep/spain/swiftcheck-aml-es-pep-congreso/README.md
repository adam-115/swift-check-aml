# es_pep_congreso – Députés du Congreso

## Ce que fait ce parser

Il lit la page de l'open data, y trouve les deux liens JSON dont le nom change à chaque mise à jour (`DiputadosActivos__AAAAMMJJhhmmss.json`, `DiputadosDeBaja__….json`), les enregistre et produit les députés en fonction et ceux sortis depuis moins de 24 mois (article 14.9 de la loi 10/2010).

## Contexte

| | |
|---|---|
| Bloc du référentiel | **ES-b** (loi 10/2010, art. 14 ; liste de la Commission, JO C/2023/724) |
| Source | Open data du Congreso (`congreso.es/es/opendata/diputados`, deux fichiers JSON) |
| Rétention | 24 mois après la fin des fonctions (loi 10/2010, art. 14.9) |

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `es_pep_congreso` |
| Plugin-Id | `es-pep-congreso` |
| Type de source | PLUGIN_MANAGED (OPEN_DATA_FILE) |
| Fréquence | 24 h |
| Paramètres | `{"pageUrl":"https://www.congreso.es/es/opendata/diputados","monthsAfterEnd":"24"}` |

Données brutes archivées : `DiputadosActivos.json`, `DiputadosDeBaja.json` et `opendata-diputados.html`.

## Ce qui est produit (FtM)

`Person` (nom, prénom, nom de famille, parti, pays et nationalité `es`), `Position` (« Diputado al Congreso de los Diputados »), `Occupancy` (début, fin, circonscription, groupe parlementaire).

## Limites

- Ni date de naissance ni identifiant dans la source : le rapprochement se fait sur le nom. La biographie, qui peut citer la situation familiale, n'est pas reprise.
- Au 03/11/2026, 213 députés ont une date de départ le 06/10/2026 (dissolution des Cortes, élections le 29/11/2026) : le message de l'exécution le signale. Ils restent PEP 24 mois.
- Pas de photo : l'open data n'en donne pas.

## Tests

`EsPepCongresoParserTest` sur les fichiers réels du 03/11/2026 (sans la biographie) : actifs, sortis, rétention de 24 et de 12 mois, liens datés du nom de fichier.

Construire : `mvn -pl swiftcheck-aml-plugins/pep/spain/swiftcheck-aml-es-pep-congreso -am package`.
