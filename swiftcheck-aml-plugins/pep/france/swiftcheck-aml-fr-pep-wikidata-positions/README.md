# fr_pep_wikidata_positions – Fonctions sans source officielle (Wikidata)

## Ce que fait ce parser

Il lit dans Wikidata les titulaires actuels et récents de fonctions PEP françaises **à titulaire unique** qui n'ont pas de source officielle exploitable. Les fonctions sont données par leur QID dans le paramètre `positions` : en ajouter une ne demande pas de code.

**Premier usage : le chef d'état-major de la Marine** (Q2961994), que `fr_pep_army` ne couvre pas.

- La page trouvée sur defense.gouv.fr (`/marine/chef-detat-major-marine`) est un article du magazine *Cols bleus*. Elle présente encore l'amiral Nicolas Vaujour, remplacé le 15/07/2026. Elle ne doit pas être utilisée.
- Wikidata donne bien l'amiral **Christophe Cluzel depuis le 15/07/2026**, et l'amiral Vaujour jusqu'à cette date, qui reste PEP 12 mois.

## Contexte

| | |
|---|---|
| Catégorie PEP | **FR-f** « Chef d'état-major des armées, de l'armée de Terre, de la Marine ou de l'armée de l'Air et de l'Espace » (avec `fr_pep_army` pour les trois autres) |
| Source | Point SPARQL de Wikidata, licence CC0 |
| Rétention | Titulaire actuel et titulaires sortis depuis moins de 12 mois (`monthsAfterEnd`) |

## Choix du titulaire actuel

Une seule personne occupe la fonction à la fois. Wikidata garde l'historique, parfois sans dates : Jean-Louis Battet, CEMM de 2001 à 2005, y est enregistré **sans aucune date**. La règle de `fr_pep_political_parties` (garder un mandat sans date) en ferait le titulaire actuel. Ici, pour chaque fonction :

1. **Mandat terminé** : gardé comme ancien s'il a pris fin depuis moins de 12 mois, écarté sinon.
2. **Mandats sans fin** : le titulaire actuel est celui au rang « préféré » s'il y en a un, sinon celui qui a commencé le plus tard. Un mandat sans date ne passe jamais devant un mandat daté. Les autres mandats sans fin sont considérés comme remplacés.

## Ce qui n'a pas été fait avec Wikidata : les anciens membres du Gouvernement

Le plan prévoyait de compléter la HATVP par les fonctions de ministre terminées depuis moins d'un an dans Wikidata. Mesure du 30/10/2026 :

- **1 seul** ancien ministre trouvé par les 104 fonctions de ministre françaises ;
- **2 à 6** membres seulement rattachés à chacun des gouvernements Bayrou, Lecornu I et Lecornu II (qualificatif P5054), sur une trentaine chacun.

Wikidata ne décrit pas assez les gouvernements récents. La source officielle est le Journal officiel : les décrets « relatif à la composition du Gouvernement ».

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `fr_pep_wikidata_positions` |
| Plugin-Id | `fr-pep-wikidata-positions` |
| Type de source | `PLUGIN_MANAGED` (SPARQL) |
| Fréquence | 168 h |
| Paramètres | `{"endpoint": "https://query.wikidata.org/sparql", "positions": "Q2961994", "monthsAfterEnd": "12"}` |

Donnée brute archivée : `holders.json` (réponse SPARQL).

## Ce qui est produit (FtM)

- `Person` : nom, QID, date de naissance à la précision de Wikidata, genre, pays `fr`, fonction (« Ancien : … » pour un sortant), topic `role.pep`.
- `Position` : une par fonction.
- `Occupancy` : début, fin, statut `current` ou `ended`.

Une exécution sans titulaire actuel échoue (fonction mal configurée ou réponse vide).

## Limites

- **Données collaboratives** : un changement de titulaire non saisi dans Wikidata n'est pas vu. Il faut donc contrôler, en particulier après un conseil des ministres de nominations.
- **Pour les fonctions à titulaire unique seulement** : pour une assemblée ou un conseil, la règle garderait une seule personne.

## Tests

`FrPepWikidataPositionsParserTest`, sur la réponse réelle du 30/10/2026 :

- titulaire actuel, ancien titulaire de moins d'un an, mandat sans date écarté ;
- règle du rang préféré puis du début le plus récent ;
- requête protégée contre un paramètre qui n'est pas un QID ;
- réponse vide.
