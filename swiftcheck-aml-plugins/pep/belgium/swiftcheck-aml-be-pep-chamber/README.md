# be_pep_chamber – Membres de la Chambre des représentants (Belgique)

## Ce que fait ce parser

Il lit la liste des **150 députés en fonction** sur dekamer.be, puis la fiche de chacun : circonscription, groupe politique, lieu et date de naissance, début de la législature. Pour un suppléant, il prend la date d'entrée en fonction (« depuis le … »).

Mesure du 02/11/2026 : 150 députés, dont 149 avec leur date de naissance. La fiche de Carmen Ramlot donne seulement « Née à Vilvorde ». Durée : environ 2 min 30 (150 fiches, 300 ms entre deux appels).

## Contexte

| | |
|---|---|
| Catégorie PEP | **BE-b** – Membre de la Chambre des représentants (loi du 18/09/2017, Annexe IV, 2°) |
| Source | dekamer.be, pages HTML en ISO-8859-1 (pas d'open data) |
| Rétention | 12 mois (gérée par le core) |

## Lecture des fiches

La ligne de CV d'une fiche, par exemple :

```
Député de la circonscription électorale d'Anvers | Groupe politique : Ecolo-Groen | Né à Duffel le 08/09/1981 | …
```

Tournures réelles gérées :

- « Né le 16/05/1983 » (sans lieu) ;
- « Née à Vilvorde » (sans date) ;
- « circonscription électorale de Flandre orientale depuis le 23/10/2025 (suppléance de …) ».

Le nom vient de la liste (« Aerts Staf », nom puis prénom). La fiche (« Activité de Staf Aerts ») permet de retrouver la coupure exacte des noms composés (« De Knop Irina »).

**L'adresse e-mail et les autres coordonnées ne sont jamais reprises.**

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `be_pep_chamber` |
| Plugin-Id | `be-pep-chamber` |
| Type de source | `PLUGIN_MANAGED` (page web) |
| Paramètres | `listUrl` (liste `cvlist54`), `detailUrl` (fiche `cvview54`, `{key}`), `delayMs` (300) |

La législature (56) est dans les adresses : à changer après les élections de 2029.

## Ce qui est produit (FtM)

| Entité | ID | Contenu |
|---|---|---|
| `Person` | clé du député à la Chambre (`07757`) | nom, prénom, date et lieu de naissance, genre, pays `be`, fiche source, topic `role.pep` |
| `Position` | `member` | « Membre de la Chambre des représentants », topics `gov.national`, `gov.legislative` |
| `Occupancy` | clé + début | début du mandat, circonscription, groupe politique, statut `current` |

## Limites connues

- Les anciens députés ne sont pas couverts (lot 3 : Moniteur belge, ou législatures précédentes du site).
- Pas de photo sur les fiches.

## Tests

`BePepChamberParserTest`, sur la liste réelle et six fiches réelles (02/11/2026). Les tests couvrent :

- la naissance ;
- les noms composés ;
- la date d'entrée d'un suppléant ;
- les tournures incomplètes ;
- l'absence de coordonnées dans les données publiées ;
- le téléchargement.
