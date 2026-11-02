# be_pep_walloon_parliament – Membres du Parlement de Wallonie (Belgique)

## Ce que fait ce parser

Il lit l'**open data du Parlement de Wallonie** : un fichier JSON qui décrit les 75 députés en fonction. Pour chaque député, le fichier donne :

- l'identifiant, le nom, le prénom et la civilité ;
- le parti et la circonscription ;
- la fonction au bureau (président, vice-présidents, secrétaires) ;
- le portrait officiel.

Mesure du 02/11/2026 : 75 députés, dont 7 au bureau, 75 portraits officiels.

## Contexte

| | |
|---|---|
| Catégorie PEP | **BE-b** – Membre du Parlement de Wallonie ; « le Président du Parlement » (loi du 18/09/2017, Annexe IV, 2°) |
| Source | `https://www.parlement-wallonie.be/opendata/composition_dep_od.php?f=json` (aussi en CSV avec `f=csv`) |
| Rétention | 12 mois (gérée par le core) |

**Le fichier contient aussi l'adresse postale, le téléphone, l'e-mail et les réseaux sociaux des députés. Ces coordonnées ne sont jamais reprises** (règle : pas d'adresse personnelle des PEP), et un test le vérifie.

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `be_pep_walloon_parliament` |
| Plugin-Id | `be-pep-walloon-parliament` |
| Type de source | `PLUGIN_MANAGED` (fichier open data) |
| Paramètre | `{"url": "https://www.parlement-wallonie.be/opendata/composition_dep_od.php?f=json"}` |

## Ce qui est produit (FtM)

| Entité | ID | Contenu |
|---|---|---|
| `Person` | identifiant du député (`1072`) | nom, prénom, genre, pays `be`, fonctions, topic `role.pep` ; portrait officiel (« © Parlement de Wallonie ») |
| `Position` | `member`, et une par fonction au bureau | « Membre du Parlement de Wallonie », « Président du Parlement de Wallonie »…, topics `gov.state`, `gov.legislative` |
| `Occupancy` | identifiant + fonction | circonscription et parti (mandat de membre), statut `current` |

## Limites connues

- Pas de date de naissance ni de date de début de mandat dans le fichier.
- Les anciens députés ne sont pas couverts.

## Tests

`BePepWalloonParliamentParserTest`, sur le fichier réel du 02/11/2026 : députés, bureau, portraits, et aucune coordonnée personnelle publiée.
