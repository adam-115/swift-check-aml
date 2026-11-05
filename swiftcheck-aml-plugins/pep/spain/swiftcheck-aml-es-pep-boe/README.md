# es_pep_boe – Nominations et cessations du BOE

## Ce que fait ce parser

Il lit chaque jour le sommaire du BOE, garde la section II.A (« Autoridades y personal – Nombramientos, situaciones e incidencias ») et reconnaît dans les titres des décrets royaux la formule « se nombra/designa/promueve… a don X » (début de fonction) et « se dispone/declara el cese de don X como… » (fin de fonction, avec la vraie date). Le titre du décret qui nomme tous les ministres d'un coup ne donne pas les noms : le texte est alors téléchargé.

## Contexte

| | |
|---|---|
| Bloc du référentiel | **ES-a, ES-c, ES-d, ES-e, ES-f** (loi 10/2010, art. 14 ; liste de la Commission, JO C/2023/724) |
| Source | API de données ouvertes du BOE (`boe.es/datosabiertos/api/boe/sumario/AAAAMMJJ`, JSON) |
| Rétention | 24 mois après la fin des fonctions (loi 10/2010, art. 14.9) |

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `es_pep_boe` |
| Plugin-Id | `es-pep-boe` |
| Type de source | PLUGIN_MANAGED (OFFICIAL_GAZETTE) |
| Fréquence | 24 h |
| Paramètres | `{"archiveMonths":"26","sections":"2A","delayMs":"250","monthsAfterEnd":"24"}` |

Données brutes archivées : `sumario-AAAA-MM.json` (un fichier par mois, les articles des sections demandées ; un jour sans BOE est noté `NO_BOE`) et `doc-ID.xml` (texte des décrets qui nomment plusieurs personnes). Les jours déjà lus ne sont plus demandés ; les deux derniers jours sont toujours relus.

## Ce qui est produit (FtM)

`Person` (nom, pays `es`, fonction, « Ancien : … » pour un sortant ; `role.diplo` pour les ambassadeurs), `Position` (une par fonction, organisation et thèmes `gov.*`), `Occupancy` (début, fin, statut `current`/`ended`, lien vers l'article du BOE).

## Limites

- **Le BOE ne donne que les changements.** Un titulaire en fonction depuis avant le début de l'archive (26 mois par défaut) n'y figure pas : les parsers d'institution (`es_pep_congreso`, `es_pep_bank_of_spain`…) les couvrent. Le premier passage lit environ 800 jours (quelques minutes).
- Les fonctions reconnues sont dans `boe-functions.json` (dans le plugin) : ministres, secrétaires d'État, sous-secrétaires, directeurs généraux, délégués du Gouvernement, hautes juridictions, procureurs en chef, Banque d'Espagne, Tribunal de Cuentas, ambassadeurs, officiers généraux (général de division et au-dessus) et quelques régulateurs. Directeurs de cabinet, doyens des tribunaux, procureurs provinciaux et généraux de brigade sont volontairement exclus (art. 14.4 : niveau intermédiaire).
- Les titres qui ne suivent pas la formule (par exemple « se nombran Magistrados… ») ne donnent aucun événement.
- Un titulaire est identifié par son nom (le BOE ne donne ni identifiant ni naissance) : deux homonymes se fondraient.

## Tests

`BoeTitlesTest` (titres réels d'octobre 2025 et novembre 2023) et `EsPepBoeParserTest` (sommaires réels : cessation et nomination d'une même personne, ambassadeurs, promotion militaire, ministres du décret d'ensemble, rétention, téléchargement par jour avec cache et arrêt après trois échecs).

Construire : `mvn -pl swiftcheck-aml-plugins/pep/spain/swiftcheck-aml-es-pep-boe -am package`.
