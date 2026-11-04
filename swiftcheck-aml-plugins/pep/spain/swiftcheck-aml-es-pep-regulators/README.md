# es_pep_regulators – Organismes régulateurs

## Ce que fait ce parser

Il lit la page de chaque organisme configuré et en extrait les couples fonction/nom avec une expression à groupes nommés. Au 03/11/2026 seul le CSN a une page exploitable : président, vice-présidente et trois conseillers.

## Contexte

| | |
|---|---|
| Bloc du référentiel | **ES-f** (loi 10/2010, art. 14 ; liste de la Commission, JO C/2023/724) |
| Source | Une page par organisme, décrite dans `regulators.json` (Conseil de sécurité nucléaire : `csn.es/el-pleno/miembros-del-pleno`) |
| Rétention | 24 mois après la fin des fonctions (loi 10/2010, art. 14.9) |

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `es_pep_regulators` |
| Plugin-Id | `es-pep-regulators` |
| Type de source | PLUGIN_MANAGED (WEB_PAGE) |
| Fréquence | 168 h |
| Paramètres | `{"institutions":"csn"}` |

Données brutes archivées : `<clé>.html` par organisme.

## Ce qui est produit (FtM)

`Person`, `Position` (« Présidente – Consejo de Seguridad Nuclear »), `Occupancy` (statut `current`).

## Limites

- CNMV : HTTP 403 (protection non contournée) ; CNMC : organigramme sans noms ; AEPD : HTTP 500 ; Tribunal de Cuentas : page dynamique. Leurs nominations viennent du BOE (`es_pep_boe`).
- Pour ajouter un organisme : une entrée dans `regulators.json`, après avoir vérifié la page.

## Tests

`EsPepRegulatorsParserTest` sur un extrait réel de la page du CSN : cinq membres, nom abrégé réuni, page modifiée, organisme inconnu.

Construire : `mvn -pl swiftcheck-aml-plugins/pep/spain/swiftcheck-aml-es-pep-regulators -am package`.
