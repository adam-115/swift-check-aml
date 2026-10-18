# lu_pep_municipal – Bourgmestres et échevins (Luxembourg)

## Ce que fait ce parser

Il lit la composition des **collèges des bourgmestre et échevins** des 100 communes du Luxembourg, soit environ 380 élus. Il garde les mandats en cours et ceux terminés depuis moins de 5 ans, puis les convertit en entités FtM.

## Contexte

Les élus communaux **ne figurent pas** dans la liste officielle des fonctions PEP du Luxembourg. Ils sont repris pour deux raisons :

1. **L'approche par les risques**, comme le font les fournisseurs du marché.
2. **Le règlement (UE) 2024/1624 (AMLR)**, applicable en juillet 2027. Il ajoute aux PEP les dirigeants des collectivités de **50 000 habitants ou plus**. Au Luxembourg, seule la Ville de Luxembourg est concernée.

| | |
|---|---|
| Pays | Luxembourg |
| Catégories | **LU-AMLR** (Ville de Luxembourg, classe 3) et **LU-MUN** (autres communes, approche par les risques, classe 4) |
| Fonctions couvertes | Bourgmestre ; Échevin ; Bourgmestre ou échevin de la Ville de Luxembourg |
| Rétention | 12 mois (gérée par le core), en plus du filtre `yearsAfterEnd` |

## Source

| | |
|---|---|
| Éditeur | Ministère des Affaires intérieures |
| Jeu de données | « Collège des bourgmestre et échevins depuis les élections communales 2023 », sur data.public.lu |
| Adresse | `https://data.public.lu/fr/datasets/r/067d11cc-29a8-4f0c-bf35-f9d4ac1818ec` |
| Format | CSV, **encodage Windows-1252**, une ligne par mandat, dates au format `27-Oct-23` |
| Licence | Open data (data.public.lu) : citer le ministère des Affaires intérieures |
| Mise à jour | À chaque changement dans un collège. **L'adresse changera après les élections communales de 2029.** |

Extrait :

```csv
COM_CODE,COM_LABEL,MAN_LABEL,ELU_LAST_NAME,ELU_FIRST_NAME,ELU_SEX,COAC_START_DATE,COAC_END_DATE
C003,Beaufort,Bourgmestre,Nosbusch,Jean-Luc,M,27-Oct-23,
C066,Luxembourg,Échevin,Wilmes,Serge,M,06-Jul-23,16-Nov-23
```

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `lu_pep_municipal` |
| Plugin-Id | `lu-pep-municipal` |
| Type de source | `HTTP_FILE` |
| Nature de la source | T1 – Fichier open data |
| Fréquence | 24 h |
| Rétention | 12 mois |
| Paramètres | `{"yearsAfterEnd": "5"}` (facultatif, 5 par défaut) |
| Source PEP | « Collèges des bourgmestre et échevins » |

## Ce qui est produit (FtM)

| Entité | ID | Contenu |
|---|---|---|
| `Person` | `lu-pep-municipal-<nom>-<prénom>-<code commune>` | nom, prénom, genre, pays `lu`, topic `role.pep` |
| `Position` | fonction + code commune, ex. « Bourgmestre de Beaufort » | pays `lu`, commune (`subnationalArea`), topic `gov.muni` |
| `Occupancy` | un par mandat | dates de début et de fin, statut `current` ou `ended` |

Un élu qui change de fonction dans sa commune, par exemple échevin puis bourgmestre, garde le même ID de personne. Ses lignes sont réunies en une seule `Person`.

## Règles et erreurs

- Une fonction autre que « Bourgmestre » ou « Échevin » arrête l'exécution, avec le numéro de ligne.
- Une date illisible arrête l'exécution. Une date de fin perdue transformerait un ancien mandat en mandat en cours.

## Limites connues

- Le fichier ne donne ni identifiant ni date de naissance : l'ID repose sur le nom, le prénom et la commune. Deux homonymes de la même commune seraient fusionnés.
- Un élu présent dans deux communes, ou aussi député, reste plusieurs entités (une par commune, une par dataset).
- Seuls les mandats depuis les élections communales de 2023 sont présents.

## Tests

`LuPepMunicipalParserTest`, sur un extrait réel (`src/test/resources/municipal-councils.csv`). Il vérifie :
- la fonction de bourgmestre reliée à sa commune ;
- la lecture de « Échevin » (accent, encodage Windows-1252) et de la date de fin au format `d-MMM-yy` ;
- l'absence de valeur rejetée ;
- l'arrêt sur une date illisible.

## Construire

```bash
mvn -pl swiftcheck-aml-plugins/pep/luxembourg/swiftcheck-aml-lu-pep-municipal -am package
```
