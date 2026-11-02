# be_pep_flemish_parliament – Membres du Parlement flamand (Belgique)

## Ce que fait ce parser

Il lit l'**API open data du Parlement flamand** : la liste des membres en fonction (`vv/huidige`), puis la fiche JSON de chacun (`vv/{id}`). La fiche donne la date et le lieu de naissance, le genre, la circonscription, le groupe et les mandats avec leurs dates.

Mesure du 02/11/2026 : 124 membres, tous avec leur date de naissance, 124 portraits officiels. Durée : environ 1 min 40 (124 fiches).

## Contexte

| | |
|---|---|
| Catégorie PEP | **BE-b** – Membre du Parlement flamand (loi du 18/09/2017, Annexe IV, 2°) |
| Source | API `ws.vlpar.be/e/opendata`, documentée par Swagger (`ws.vlpar.be/e/opendata/api`) |
| Licence | Gratis Open Data Licentie Vlaanderen |
| Rétention | 12 mois (gérée par le core) |

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `be_pep_flemish_parliament` |
| Plugin-Id | `be-pep-flemish-parliament` |
| Type de source | `PLUGIN_MANAGED` (API) |
| Paramètres | `{"listUrl": "https://ws.vlpar.be/e/opendata/vv/huidige", "detailUrl": "https://ws.vlpar.be/e/opendata/vv/{id}", "delayMs": "200"}` |

## Ce qui est produit (FtM)

| Entité | ID | Contenu |
|---|---|---|
| `Person` | identifiant du Parlement (`4383`) | nom, prénom, date et lieu de naissance, genre (`M`/`V`), pays `be`, fiche de l'API, topic `role.pep` ; portrait officiel déclaré comme photo (« © Vlaams Parlement ») |
| `Position` | `member` | « Membre du Parlement flamand », topics `gov.state`, `gov.legislative` |
| `Occupancy` | identifiant + début | début du mandat en cours (`datumvan` sans `datumtot`), circonscription, groupe, statut `current` |

L'adresse du bureau, l'e-mail et le site personnel ne sont jamais repris.

## Limites connues

- Les anciens membres ne sont pas encore repris : l'API les décrit aussi, c'est à ajouter.
- vlaamsparlement.be refuse les clients automatiques (HTTP 403). La source citée est donc la fiche de l'API.

## Tests

`BePepFlemishParliamentParserTest`, sur des réponses réelles de l'API (02/11/2026). Les tests couvrent :

- la naissance ;
- le mandat en cours, et non celui de 2019 ;
- les portraits ;
- l'absence de coordonnées ;
- le téléchargement.
