# fr_pep_rca – Membres de la famille des PEP français (Wikidata)

## Ce que fait ce parser

Il interroge Wikidata pour trouver les PEP français vivants, puis leurs proches vivants au sens du **II de l'article R. 561-18 du CMF** : conjoint, concubin ou partenaire de PACS, enfants et leurs conjoints, père et mère. **Les frères et sœurs ne sont pas repris** (ils le sont au Luxembourg, qui suit l'AMLR). Reprise de [`lu_pep_rca`](../../luxembourg/swiftcheck-aml-lu-pep-rca).

Mesure du 28/10/2026 : 904 PEP français dans Wikidata, dont 98 avec des proches connus ; 143 proches, 144 liens familiaux, 0 valeur rejetée (environ 55 s).

## Contexte

| | |
|---|---|
| Catégorie PEP | **FR-RCA** « Membres de la famille et proches associés » |
| Fonction couverte | Membre de la famille d'une PEP : **partielle** (familles décrites dans Wikidata seulement) |
| Rétention | PEP en fonction ou sortis depuis moins d'un an (`yearsAfterEnd` = 1), puis rétention du core (12 mois) |

## Quelles PEP ? Une liste autorisée

En France, « toute fonction dont la juridiction (P1001) est la France », comme au Luxembourg, ramène surtout des fonctions **hors R. 561-18** (relevé du 28/10/2026 : 564 directeurs de recherche au CNRS, 175 professeurs des universités, 98 préfets, 70 conseillers municipaux, des commissaires de police, des conseillers prud'homaux…). Le parser travaille donc avec une **liste autorisée** :

- `positions` : Président de la République (Q191954), Premier ministre (Q1587677), président de l'Assemblée nationale (Q20968036), député (Q3044918), sénateur (Q14828018), Conseil constitutionnel (Q26268315, secrétaire général Q59156405), Conseil d'État (Q54996617, Q2994367, Q62129167), Cour de cassation (Q50823624, Q62078932, Q62086496, Q62076511), Cour des comptes (Q23956505, Q21500772, Q62129412, Q62131051), gouverneur de la Banque de France (Q7886203) ;
- `positionClasses` : toute fonction de juridiction France qui est une sorte de « ministre » (Q83307), soit les membres du Gouvernement.

Sans ces deux paramètres, le parser revient au mode luxembourgeois (`extraPositions`, `excludedPositions`).

## Configuration dans SwiftCheck (écran « Parsers »)

| Champ | Valeur |
|---|---|
| Nom du dataset | `fr_pep_rca` |
| Plugin-Id | `fr-pep-rca` |
| Type de source | `PLUGIN_MANAGED` (point SPARQL de Wikidata) |
| Nature de la source | T3 – SPARQL |
| Fréquence | 168 h |
| Paramètres | `{"endpoint": "https://query.wikidata.org/sparql", "yearsAfterEnd": "1", "country": "Q142", "positions": "Q191954 …", "positionClasses": "Q83307"}` |

Les proches sont demandés par lots de 400 PEP (au-delà, le serveur risque de dépasser son délai de 60 s).

## Ce qui est produit (FtM)

Pour chaque lien : la PEP (`Person`, `role.pep`, fonctions), le proche (`Person`, `role.rca`, date de naissance à la précision de Wikidata, genre, résumé « Conjoint d'un enfant de Hugues Portelli ») et le lien `Family`. Les identifiants reprennent le QID Wikidata. Seules les PEP qui ont au moins un proche sont écrites.

## Limites connues

- **Données collaboratives** : Wikidata peut contenir des erreurs. Exemple relevé le 28/10/2026 : « Jeff Tuche », personnage du film *Les Tuche*, enregistré comme président de la République française, avec sa famille fictive. Le parser publie ce que contient Wikidata ; ce cas est à signaler ou corriger dans Wikidata.
- Les proches associés (III de R. 561-18) ne sont pas décrits dans Wikidata : voir `fr_pep_associated_entities` (déclarations HATVP).

## Tests

`FrPepRcaParserTest`, sur un extrait des réponses réelles (six PEP, dix liens, dont un partenaire et le conjoint d'un enfant) et sur la construction des requêtes (liste autorisée, pas de fratrie, mode luxembourgeois conservé).
