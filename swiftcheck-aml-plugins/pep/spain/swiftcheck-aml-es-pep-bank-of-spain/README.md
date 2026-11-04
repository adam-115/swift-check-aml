# es_pep_bank_of_spain – Banque d'Espagne

## Ce que fait ce parser

Il lit les deux pages et, sous « Composición », chaque fiche « nom puis fonction » : gouverneur, sous-gouverneure, consejeros, secrétaire générale du Trésor et vice-présidente de la CNMV (membres de droit du Conseil). Une personne qui siège aux deux organes reçoit les deux fonctions.

## Contexte

| | |
|---|---|
| Bloc du référentiel | **ES-d** (loi 10/2010, art. 14 ; liste de la Commission, JO C/2023/724) |
| Source | Pages « Consejo de Gobierno » et « Comisión Ejecutiva » de `bde.es` |
| Rétention | 24 mois après la fin des fonctions (loi 10/2010, art. 14.9) |

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `es_pep_bank_of_spain` |
| Plugin-Id | `es-pep-bank-of-spain` |
| Type de source | PLUGIN_MANAGED (WEB_PAGE) |
| Fréquence | 168 h |
| Paramètres | `{"councilUrl":"https://www.bde.es/wbe/es/sobre-banco/organizacion/organos-rectores/consejo-gobierno/","executiveUrl":"https://www.bde.es/wbe/es/sobre-banco/organizacion/organos-rectores/comision-ejecutiva/"}` |

Données brutes archivées : `consejo-gobierno.html` et `comision-ejecutiva.html`.

## Ce qui est produit (FtM)

`Person`, `Position` (Gobernador, Subgobernador, Miembro del Consejo de Gobierno, Miembro de la Comisión Ejecutiva) et `Occupancy` (statut `current`).

## Limites

- Les photos des fiches sont une photo de groupe de la Junta : elles ne sont pas reprises (pas de photo devinée).
- Les directeurs généraux, qui assistent sans voix délibérative, ne sont pas repris.
- Une personne qui quitte le Conseil disparaît de la page : le core la garde pendant la rétention du pays (24 mois). Pas de date de début.

## Tests

`EsPepBankOfSpainParserTest` sur des extraits réels des deux pages : 10 membres du Conseil, directeurs écartés, double fonction, échec sans gouverneur.

Construire : `mvn -pl swiftcheck-aml-plugins/pep/spain/swiftcheck-aml-es-pep-bank-of-spain -am package`.
