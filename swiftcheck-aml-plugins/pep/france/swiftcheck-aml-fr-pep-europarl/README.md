# fr_pep_europarl – Députés européens élus en France

## Ce que fait ce parser

Il lit l'API open data du Parlement européen et garde les **députés européens élus en France en fonction** et ceux **sortis depuis moins de 12 mois**, avec leur date et leur lieu de naissance.

Mesure du 28/10/2026 : 81 en fonction, 1 sorti depuis moins d'un an, 3 sortis avant (écartés). 71 des 82 ont une date de naissance (l'API ne la donne pas pour les autres).

## Contexte

| | |
|---|---|
| Pays | France |
| Catégorie PEP | **FR-EU** « Français exerçant une fonction européenne » (liste des fonctions PEP des institutions de l'UE, JO C/2023/611) |
| Fonction couverte | Député au Parlement européen élu en France |
| Classe de risque | 1 |
| Rétention | 12 mois : appliquée par le parser aux députés déjà sortis, puis par le core |

Même source que [`lu_pep_europarl`](../../luxembourg/swiftcheck-aml-lu-pep-europarl), mais la fiche de chaque député est lue en plus : elle donne la naissance et les dates de mandat, ce qui permet de garder les députés sortis pendant la législature.

## Source

| | |
|---|---|
| Éditeur | Parlement européen |
| Députés en fonction | `https://data.europarl.europa.eu/api/v2/meps/show-current?country-of-representation=FR&format=application%2Fld%2Bjson` |
| Députés de la législature | `https://data.europarl.europa.eu/api/v2/meps?country-of-representation=FR&parliamentary-term=10&limit=500&format=application%2Fld%2Bjson` |
| Fiche d'un député | `https://data.europarl.europa.eu/api/v2/meps/{id}?format=application%2Fld%2Bjson` |
| Format | JSON-LD |

`termUrl` contient le numéro de législature (`10`) : **à changer à la prochaine législature** (2029).

Fiche : `bday`, `placeOfBirth`, `hasGender`, et dans `hasMembership` les mandats `role = def/ep-roles/MEMBER_PARLIAMENT` qui représentent la France (`…/country/FRA`), avec `memberDuring.startDate` et `endDate`.

## Configuration dans SwiftCheck (écran « Parsers »)

| Champ | Valeur |
|---|---|
| Nom du dataset | `fr_pep_europarl` |
| Plugin-Id | `fr-pep-europarl` |
| Type de source | `PLUGIN_MANAGED` : les deux listes, puis une fiche par député (≈ 85 appels, pause de 300 ms) |
| Nature de la source | T2 – API |
| Fréquence | 24 h |
| Paramètres | `currentUrl`, `termUrl`, `detailUrl` (avec `{id}`), `delayMs` (300), `monthsAfterEnd` (12) |

## Ce qui est produit (FtM)

| Entité | ID | Contenu |
|---|---|---|
| `Person` | `fr-pep-europarl-<identifiant>` (ex. `124760`) | nom (« Nicolas BAY »), prénom, nom de famille, date et lieu de naissance, genre, pays `fr`, lien vers la fiche europarl, topic `role.pep` |
| `Position` | `fr-pep-europarl-member` | « Député au Parlement européen élu en France », topics `gov.igo`, `gov.legislative` |
| `Occupancy` | identifiant + identifiant du mandat | début, fin, statut, groupe politique (députés en fonction) |

## Règles et erreurs

- Un ancien député dont aucun mandat ne s'est terminé dans les `monthsAfterEnd` derniers mois est écarté.
- Fiche non téléchargée : le député est gardé sans dates ni naissance, et cité dans le message (un ancien député est alors gardé, faute de date de fin).
- Aucun député en fonction ou paramètre manquant : l'exécution s'arrête (FAILED).

## Tests

`FrPepEuroparlParserTest`, sur des réponses réelles réduites à trois députés (`src/test/resources/europarl/`) : député en fonction, député sorti il y a 12 mois et demi gardé avec une rétention de 13 mois, fiches absentes, téléchargement simulé avec une erreur.

## Construire

```bash
mvn -pl swiftcheck-aml-plugins/pep/france/swiftcheck-aml-fr-pep-europarl -am package
```
