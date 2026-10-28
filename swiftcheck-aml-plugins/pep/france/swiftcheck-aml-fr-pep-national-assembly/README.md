# fr_pep_national_assembly – Députés (France)

## Ce que fait ce parser

Il lit l'archive open data de l'Assemblée nationale et garde les **députés en fonction** (577 sièges), les **députés sortis depuis moins de 12 mois** et la **présidence de l'Assemblée**. Il les convertit en entités FollowTheMoney (FtM), que le core publie dans Yente.

## Contexte

| | |
|---|---|
| Pays | France |
| Catégorie PEP | **FR-b** « Députés et sénateurs » (CMF, art. R. 561-18 ; directive (UE) 2015/849, art. 3, point 9 b) |
| Liste officielle | Liste des fonctions PEP de la France, JO C/2023/724 |
| Fonctions couvertes | Député, Ancien député (sorti depuis moins de 12 mois), Président de l'Assemblée nationale |
| Classe de risque | 1 (la plus élevée) |
| Rétention | 12 mois (R. 561-18) : appliquée par le parser aux députés déjà sortis, puis par le core à ceux qui sortiront |

Mesure du 27/10/2026 : 574 députés en fonction et 58 sortis depuis moins d'un an (la plupart entrés au Gouvernement).

## Source

| | |
|---|---|
| Éditeur | Assemblée nationale |
| Jeu de données | AMO30 « tous acteurs, tous mandats, tous organes » (historique depuis la XIe législature) |
| Adresse | `https://data.assemblee-nationale.fr/static/openData/repository/17/amo/tous_acteurs_mandats_organes_xi_legislature/AMO30_tous_acteurs_tous_mandats_tous_organes_historique.json.zip` |
| Format | ZIP d'environ 14 Mo : un fichier JSON par acteur (`json/acteur/PA….json`) et par organe (`json/organe/PO….json`) |
| Licence | Licence Ouverte / Open Licence (Etalab 2.0) : citer « Assemblée nationale, data.assemblee-nationale.fr » |
| Mise à jour | Chaque jour |

Le fichier CSV « Députés en exercice » ne suffit pas : il ne donne ni la date de naissance ni les députés sortis.
L'adresse contient le numéro de législature (`17`) : **à changer à la prochaine législature**.

Extrait d'un acteur :

```json
{"acteur": {"uid": {"#text": "PA721908"},
  "etatCivil": {"ident": {"civ": "Mme", "prenom": "Yaël", "nom": "Braun-Pivet"},
                "infoNaissance": {"dateNais": "1970-12-07", "villeNais": "Nancy"}},
  "mandats": {"mandat": [
    {"uid": "PM843467", "typeOrgane": "ASSEMBLEE", "dateDebut": "2024-07-07", "dateFin": null,
     "election": {"lieu": {"departement": "Yvelines", "numCirco": "5"}}},
    {"typeOrgane": "BUREAU", "infosQualite": {"codeQualite": "Président"}, "dateFin": null},
    {"typeOrgane": "GP", "organes": {"organeRef": "PO845407"}, "dateFin": null}]}}}
```

## Configuration dans SwiftCheck (écran « Parsers »)

| Champ | Valeur |
|---|---|
| Nom du dataset | `fr_pep_national_assembly` |
| Plugin-Id | `fr-pep-national-assembly` |
| Type de source | `HTTP_FILE` : le core télécharge l'archive, le plugin la lit seulement |
| Nature de la source | T1 – Fichier open data |
| Adresse | voir ci-dessus |
| Fréquence | 24 h |
| Rétention | 12 mois |
| Paramètres | `{"monthsAfterEnd": "12"}` (facultatif) : durée pendant laquelle un député sorti est gardé |

## Ce qui est produit (FtM)

| Entité | ID | Contenu |
|---|---|---|
| `Person` | `fr-pep-national-assembly-<PA…>` (identifiant de l'Assemblée) | nom, prénom, date et lieu de naissance, genre (« Mme » → `female`), pays `fr`, groupe politique (`political`, ex. `SOC`), fonctions, lien vers la fiche, topic `role.pep` |
| `Position` | `…-deputy` et `…-president` | « Député à l'Assemblée nationale », « Président de l'Assemblée nationale », topics `gov.national`, `gov.legislative` |
| `Occupancy` | `<PA…>` + identifiant du mandat (`PM…`) | lien personne → fonction, début, fin, statut `current` / `ended`, circonscription (« Gironde, 4e circonscription »), groupe politique |

Seuls les mandats en cours ou terminés depuis moins de `monthsAfterEnd` mois sont écrits.

## Règles et erreurs

- Une archive sans fichier `json/acteur/*.json` (mauvais format, page d'erreur) arrête l'exécution (FAILED).
- Une archive sans aucun député en fonction arrête aussi l'exécution : fichier incomplet.
- Un `monthsAfterEnd` qui n'est pas un nombre arrête l'exécution avec un message clair.

## Limites connues

- Un député entré au Gouvernement est aussi publié par `fr_pep_government`, avec un autre ID. Le rapprochement se fera par la résolution d'entités.
- La nationalité n'est pas donnée par la source.

## Tests

`FrPepNationalAssemblyParserTest`, sur un extrait réel de l'archive (`src/test/resources/amo30-extrait.json.zip` : 4 députés et leurs organes, 27/10/2026) : député en fonction, présidence, député sorti depuis moins d'un an gardé, député sorti en 2024 écarté, paramètre de rétention, archive invalide, `readSourceRecords`.

## Construire

```bash
mvn -pl swiftcheck-aml-plugins/pep/france/swiftcheck-aml-fr-pep-national-assembly -am package
```

Copier ensuite `target/swiftcheck-aml-fr-pep-national-assembly-*.jar` dans le dossier des plugins.
