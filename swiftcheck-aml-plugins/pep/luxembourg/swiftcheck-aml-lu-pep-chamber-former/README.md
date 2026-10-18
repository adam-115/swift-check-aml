# lu_pep_chamber_former – Anciens députés (Luxembourg)

## Ce que fait ce parser

Il lit la liste officielle des **anciens députés** de la Chambre des Députés du Luxembourg (depuis 1999). Il garde ceux dont le mandat s'est terminé il y a moins de 5 ans (réglable), puis les convertit en entités FtM avec le statut `ended`.

## Contexte

Une personne reste PEP **au moins 12 mois après la fin de ses fonctions** (directive (UE) 2015/849, art. 22). Beaucoup d'établissements appliquent une durée plus longue selon l'approche par les risques. Ce parser permet de retrouver un ancien député pendant cette période de vigilance.

| | |
|---|---|
| Pays | Luxembourg |
| Catégorie PEP | **LU-b** « Parlementaires et Conseil d'État » (directive (UE) 2015/849, art. 3, point 9 b) |
| Liste officielle | JO C/2023/724 |
| Fonction couverte | Ancien député |
| Rétention | 12 mois (gérée par le core), en plus du filtre `yearsAfterEnd` |

Les députés en fonction sont lus par [`lu_pep_chamber`](../swiftcheck-aml-lu-pep-chamber/README.md).

## Source

| | |
|---|---|
| Éditeur | Chambre des Députés du Grand-Duché de Luxembourg |
| Jeu de données | n° 106 « Liste des anciens Députés », sur data.public.lu |
| Adresse | `https://data.public.lu/fr/datasets/r/8fdbd601-6931-47b5-a027-aacd3b1e47f4` |
| Format | CSV, **UTF-8** (avec BOM), **une ligne par mandat** : une personne peut avoir plusieurs lignes |
| Licence | CC BY : citer la Chambre des Députés, data.public.lu |
| Période | Mandats depuis 1999 |

Extrait :

```csv
PERSON_TITLE,NAME,FIRSTNAME,START_DATE,END_DATE,POLITICAL_GROUP,BIRTH_DATE,POLITICAL_PARTY
Madame,Hansen,Martine,30/10/2018,24/10/2023,CSV,10/12/1965,CSV
Madame,Hansen,Martine,24/10/2023,17/11/2023,CSV,10/12/1965,CSV
```

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `lu_pep_chamber_former` |
| Plugin-Id | `lu-pep-chamber-former` |
| Type de source | `HTTP_FILE` |
| Nature de la source | T1 – Fichier open data |
| Fréquence | 24 h |
| Rétention | 12 mois |
| Paramètres | `{"yearsAfterEnd": "5"}` (facultatif, 5 par défaut) : un mandat terminé depuis plus longtemps est écarté |
| Source PEP | « Chambre des Députés – anciens députés » |

## Ce qui est produit (FtM)

| Entité | ID | Contenu |
|---|---|---|
| `Person` | `lu-pep-chamber-former-<nom>-<prénom>-<date de naissance>` | nom, prénom, date de naissance, genre, pays `lu`, parti, topic `role.pep` |
| `Position` | `lu-pep-chamber-former-deputy` | « Député à la Chambre des Députés du Luxembourg », topics `gov.national`, `gov.legislative` |
| `Occupancy` | un par mandat | dates de début et de fin, statut `ended`, groupe politique |

Les lignes d'une même personne ont le même ID : le dataset les réunit en **une seule** `Person`, avec un `Occupancy` par mandat.

## Règles et erreurs

- Un ancien mandat **sans date de fin** arrête l'exécution (FAILED). Sans cette date, il passerait pour un mandat en cours.
- Une date illisible arrête l'exécution.
- Une valeur de `yearsAfterEnd` qui n'est pas un nombre entier provoque un message d'erreur clair.

## Limites connues

- La date de naissance manque pour certains anciens députés, par exemple dans les mandats anciens. L'ID repose alors sur le nom et le prénom seulement.
- Un député réélu qui siège encore apparaît aussi dans `lu_pep_chamber`, avec un autre ID (préfixe différent).

## Tests

`LuPepChamberFormerParserTest`, sur un extrait réel (`src/test/resources/chamber-former-deputies.csv`). Il vérifie :
- la limite de 5 ans par défaut, et l'écart des mandats terminés depuis plus longtemps (avec une date du jour fixe) ;
- plusieurs lignes d'une même personne → une seule `Person` avec plusieurs mandats ;
- la conversion des dates `jj/mm/aaaa` ;
- l'arrêt sur un mandat sans date de fin.

## Construire

```bash
mvn -pl swiftcheck-aml-plugins/pep/luxembourg/swiftcheck-aml-lu-pep-chamber-former -am package
```
