# lu_pep_europarl – Députés européens élus au Luxembourg

## Ce que fait ce parser

Il lit la liste officielle de **tous les députés européens en fonction**, publiée par le Parlement européen. Il ne garde que ceux élus au Luxembourg (6 députés) et les convertit en entités FtM.

## Contexte

| | |
|---|---|
| Pays | Luxembourg |
| Catégorie PEP | **LU-EU** « Luxembourgeois exerçant une fonction européenne » (classe 1). Un député européen est aussi membre d'un organe législatif (directive (UE) 2015/849, art. 3, point 9 b). |
| Fonction couverte | Député au Parlement européen élu au Luxembourg |
| Rétention | 12 mois : la liste ne contient que les députés en fonction. Un député qui quitte le Parlement reste publié 12 mois grâce à la rétention du core. |

Le parser est rangé dans `pep/luxembourg` parce qu'il ne publie que des élus luxembourgeois. Un parser pour un autre pays pourra lire le même fichier avec un autre filtre.

## Source

| | |
|---|---|
| Éditeur | Parlement européen |
| Adresse | `https://www.europarl.europa.eu/meps/en/full-list/xml` |
| Format | XML, UTF-8, une fiche `<mep>` par député, tous pays confondus |
| Licence | Réutilisation autorisée : citer « © Union européenne, Parlement européen » |

Extrait :

```xml
<mep>
  <fullName>Marc ANGEL</fullName>
  <country>Luxembourg</country>
  <politicalGroup>Group of the Progressive Alliance of Socialists and Democrats in the European Parliament</politicalGroup>
  <id>202073</id>
  <nationalPoliticalGroup>Parti ouvrier socialiste luxembourgeois</nationalPoliticalGroup>
</mep>
```

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `lu_pep_europarl` |
| Plugin-Id | `lu-pep-europarl` |
| Type de source | `HTTP_FILE` |
| Nature de la source | T1 – Fichier open data (XML) |
| Fréquence | 24 h |
| Rétention | 12 mois |
| Paramètres | aucun |
| Source PEP | « Parlement européen – liste des députés » |

## Ce qui est produit (FtM)

| Entité | ID | Contenu |
|---|---|---|
| `Person` | `lu-pep-europarl-<id du Parlement>`, ex. `lu-pep-europarl-202073` | nom, prénom (les mots en majuscules forment le nom), pays `lu`, parti national (`political`), lien vers sa fiche (`sourceUrl`), topic `role.pep` |
| `Position` | `lu-pep-europarl-member` | « Membre du Parlement européen élu au Luxembourg », topic `gov.legislative` |
| `Occupancy` | id + fonction | statut `current`, groupe politique européen |

C'est le seul parser luxembourgeois dont l'ID repose sur un **identifiant officiel**, celui du Parlement européen. Il ne change pas si l'orthographe du nom change.

## Règles et erreurs

- Les fiches d'un autre pays sont ignorées : le filtre porte sur `<country>Luxembourg</country>`.
- En configuration `PLUGIN_MANAGED`, le plugin s'arrête avec un message qui demande de choisir `HTTP_FILE`.

## Limites connues

- Le fichier ne donne **ni date de naissance ni genre**.
- Les **anciens** eurodéputés ne sont pas couverts. Ils sont disponibles par l'API du Parlement européen (législatures précédentes), à prévoir dans un parser séparé.
- Le commissaire européen luxembourgeois est une autre fonction de LU-EU. Sa source est listée dans « Sources PEP ».

## Tests

`LuPepEuroparlParserTest`, sur un extrait réel du XML (`src/test/resources/europarl-meps.xml`, avec des députés de plusieurs pays). Il vérifie :
- un député élu au Luxembourg devient PEP, avec son ID officiel ;
- les députés des autres pays sont ignorés ;
- `parse` et `readSourceRecords` lisent le fichier donné par le core.

## Construire

```bash
mvn -pl swiftcheck-aml-plugins/pep/luxembourg/swiftcheck-aml-lu-pep-europarl -am package
```
