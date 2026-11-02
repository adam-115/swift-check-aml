# fr_pep_local_enterprises – Dirigeants des sociétés contrôlées par une collectivité (France, FR-g)

## Ce que fait ce parser

Il produit les **dirigeants et administrateurs** (personnes physiques) des entités contrôlées par une collectivité, au-dessus d'un seuil de chiffre d'affaires :

- **sociétés d'économie mixte** locales et SEMOP (natures juridiques 5515, 5615) ;
- **sociétés publiques locales** (SPL, SPLA) ;
- **EPIC locaux** (4140, dont les offices publics de l'habitat) et **régies** dotées de la personnalité morale (4150).

Toutes les données viennent de l'**API Recherche d'entreprises** : registre national des entreprises et Sirene, Licence ouverte. Elle donne la liste des entités actives, leurs comptes publiés et leurs dirigeants.

Mesure du 02/11/2026, au seuil de 50 M€ : **2 323 dirigeants et administrateurs (2 270 personnes)** dans 154 entités, sur 2 777 actives. Elles se répartissent ainsi : 41 SEM, 8 SEM à directoire, 95 EPIC locaux, 10 régies. La lecture complète prend 1 min.

## Contexte

| | |
|---|---|
| Catégorie PEP | **FR-g** – Dirigeant ou administrateur d'une société contrôlée par une collectivité |
| Rétention | 12 mois (gérée par le core) |

## Le seuil (paramètre `minRevenue`)

Une entité est retenue si **le chiffre d'affaires de son dernier exercice publié dépasse `minRevenue`**. Sans comptes publiés, elle est retenue si sa **catégorie d'entreprise Insee** figure dans `sizeFallback` (ETI ou grande entreprise).

| Seuil | Entités | Dirigeants |
|---|---|---|
| 50 M€ (défaut, plan PEP France) | 154 | 2 358 |
| 10 M€ | 307 | 3 209 |
| 750 000 € | 679 | 5 097 |

**À valider juridiquement.** Le plan retient 50 M€. La HATVP soumet à déclaration les présidents et directeurs généraux des sociétés détenues majoritairement par des collectivités dès 750 000 € de chiffre d'affaires (loi n° 2013-907, art. 11, III). Changer le seuil ne demande aucune recompilation.

## Configuration dans SwiftCheck (écran « Parsers »)

| Champ | Valeur |
|---|---|
| Nom du dataset | `fr_pep_local_enterprises` |
| Plugin-Id | `fr-pep-local-enterprises` |
| Type de source | `PLUGIN_MANAGED` (API) |
| Fréquence | 168 h |
| Paramètres | `apiUrl`, `natures` (`5515,5615,4140,4150`), `splQueries` (`societe publique locale,SPL`), `minRevenue` (`50000000`), `sizeFallback` (`ETI,GE`) |

## Ce qui est produit (FtM)

Les entités sont les mêmes que dans `fr_pep_state_enterprises` : `Person` (topic `role.pep`), `Company` (SIREN, topic `gov.soe`), `Position`, `Occupancy` et `Directorship`.

Le contexte de chaque entité est indiqué dans sa description, par exemple « Société d'économie mixte contrôlée par une ou plusieurs collectivités (chiffre d'affaires 2022 : 470 M€) ».

Les personnes morales, les commissaires aux comptes et les liquidateurs sont écartés.

## Limites connues

- **Les SPL n'ont pas de nature juridique propre** : ce sont des SA (5599). Elles sont cherchées par leur nom (« publique locale », « SPL », « S.P.L »). Une SPL dont le nom ne le dit pas (ex. « EAU DU PONANT ») n'est pas reprise, car rien ne prouve que c'en est une. 266 SPL ont été trouvées ; aucune ne dépasse 50 M€.
- **Contrôle par une collectivité** : il est déduit de la nature juridique (SEM, SPL, régie). Le registre ne donne pas la répartition du capital.
- **Aucune date de fonction dans le registre** : un ancien administrateur non radié peut subsister.
- Beaucoup d'administrateurs sont des élus locaux qui représentent leur collectivité. Ils sont repris ici au titre de leur mandat d'administrateur, dans ce dataset seulement.

## Tests

`FrPepLocalEnterprisesParserTest`, sur des réponses réelles de l'API (02/11/2026). Les tests couvrent :

- le seuil de chiffre d'affaires ;
- le repli sur la catégorie Insee ;
- le paramètre `minRevenue` ;
- la reconnaissance des SPL par leur nom (variantes réelles du registre) ;
- les téléchargements page par page.
