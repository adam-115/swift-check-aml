# lu_pep_chamber – Députés en fonction (Luxembourg)

## Ce que fait ce parser

Il lit la liste officielle des **60 députés en fonction** à la Chambre des Députés du Luxembourg et la convertit en entités FollowTheMoney (FtM). Le core la publie ensuite dans Yente.
Chaque député devient une personne politiquement exposée (PEP), avec sa date de naissance, son parti et son mandat en cours.

## Contexte

| | |
|---|---|
| Pays | Luxembourg |
| Catégorie PEP | **LU-b** « Parlementaires et Conseil d'État » (directive (UE) 2015/849, art. 3, point 9 b) |
| Liste officielle | Liste des fonctions PEP du Luxembourg, JO C/2023/724 |
| Fonction couverte | Député (Chambre des Députés, 60 titulaires) |
| Classe de risque | 1 (la plus élevée) |
| Rétention | 12 mois : un député qui quitte la Chambre reste publié 12 mois (rétention gérée par le core) |

Les anciens députés sont lus par un autre parser, [`lu_pep_chamber_former`](../swiftcheck-aml-lu-pep-chamber-former/README.md).

## Source

| | |
|---|---|
| Éditeur | Chambre des Députés du Grand-Duché de Luxembourg |
| Jeu de données | n° 105 « Liste des Députés actifs », sur data.public.lu |
| Adresse | `https://data.public.lu/fr/datasets/r/a5b6e7f9-b488-41a6-9bd1-be913a5c35b5` (lien stable vers la dernière version) |
| Format | CSV, séparateur `,`, **encodage Windows-1252**, une ligne par député |
| Licence | CC BY : citer « Chambre des Députés du Grand-Duché de Luxembourg, data.public.lu » |
| Mise à jour | À chaque changement de composition de la Chambre |

Extrait :

```csv
per_titre,pph_nom,pph_prenom,date_debut_depute,rattachement_abrv,rattachement_type,pph_date_naissance,address,phone_ext,phone_mobile,email,derniere_circonscription
"Madame","Adehm","Diane","2023-10-24 00:00:00","CSV","Groupe politique","1970-09-13","","","","","Circonscription Centre"
```

## Configuration dans SwiftCheck (écran « Parsers »)

| Champ | Valeur |
|---|---|
| Nom du dataset | `lu_pep_chamber` |
| Plugin-Id | `lu-pep-chamber` |
| Type de source | `HTTP_FILE` : le core télécharge le fichier, le plugin le lit seulement |
| Nature de la source | T1 – Fichier open data |
| Adresse | voir ci-dessus |
| Fréquence | 24 h |
| Rétention | 12 mois |
| Paramètres | aucun |
| Source PEP | « Chambre des Députés – députés en fonction » |

## Ce qui est produit (FtM)

Chaque ligne du CSV produit trois entités :

| Entité | ID | Contenu |
|---|---|---|
| `Person` | `lu-pep-chamber-<nom>-<prénom>-<date de naissance>` | nom, prénom, date de naissance, genre (« Madame » → `female`), pays `lu`, parti (`political`), topic `role.pep` |
| `Position` | `lu-pep-chamber-deputy` (la même pour tous) | « Député à la Chambre des Députés du Luxembourg », topics `gov.national`, `gov.legislative` |
| `Occupancy` | nom + prénom + naissance + fonction + date de début | lien personne → fonction, date de début, statut `current`, groupe politique |

Le fichier ne donne pas d'identifiant de député : l'ID repose sur le nom, le prénom et la date de naissance.
Les adresses, téléphones et e-mails ne sont **pas repris** : ils ne servent pas au criblage.

## Règles et erreurs

- Une date illisible (début de mandat ou naissance) arrête l'exécution (FAILED) : le core ne publie pas de liste fausse.
- Si le parser est configuré en « Géré par le plugin » (`PLUGIN_MANAGED`), aucun fichier n'est reçu. Le plugin s'arrête alors avec un message qui demande de choisir `HTTP_FILE`.

## Limites connues

- Un député qui serait aussi bourgmestre reste deux entités, une par dataset. Le rapprochement se fera par la résolution d'entités (table `resolution_judgement`).
- Les membres du Conseil d'État, de la même catégorie LU-b, ne sont pas dans ce fichier. Leurs sources sont dans l'écran « Sources PEP ».

## Tests

`LuPepChamberParserTest`, sur un extrait réel du fichier (`src/test/resources/chamber-current-deputies.csv`, 2 députés). Il vérifie :
- la personne : nom, date de naissance, genre, topic, et l'absence de l'adresse ;
- le mandat relié à la fonction partagée ;
- le nombre d'entités écrites (5) et l'absence de valeur rejetée ;
- les mêmes ID pour le test du parser (`readSourceRecords`) que pour la conversion.

## Construire

```bash
mvn -pl swiftcheck-aml-plugins/pep/luxembourg/swiftcheck-aml-lu-pep-chamber -am package
```

Copier ensuite `target/swiftcheck-aml-lu-pep-chamber-*.jar` dans le dossier des plugins.
