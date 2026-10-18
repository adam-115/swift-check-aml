# eu_sanctions – Sanctions financières de l'Union européenne

## Ce que fait ce parser

Il lit la **liste consolidée des sanctions financières de l'UE**, publiée par la Commission européenne (fichier XML « Financial Sanctions Files »). Il convertit chaque personne ou organisation sanctionnée en entités FtM, avec la sanction et ses références juridiques.

C'est le parser de référence du projet : les parsers PEP suivent le même modèle (lecture du fichier reçu du core, écriture directe avec `FtmDatasetBuilder`).

## Contexte

| | |
|---|---|
| Portée | Union européenne : les sanctions s'appliquent dans tous les États membres |
| Type de liste | Sanctions (`list_type = AML`), pas une liste PEP |
| Base légale | Règlements du Conseil au titre de la politique étrangère et de sécurité commune (art. 215 TFUE) ; chaque fiche cite son règlement |
| Topic FtM | `sanction` |
| Rétention | **0** : une radiation s'applique tout de suite. Une personne retirée de la liste ne doit plus être signalée comme sanctionnée. |
| Couverture PEP | aucune fonction PEP, aucune source PEP |

## Source

| | |
|---|---|
| Éditeur | Commission européenne (FISMA), Financial Sanctions Database |
| Adresse | `https://webgate.ec.europa.eu/fsd/fsf/public/files/xmlFullSanctionsList_1_1/content?token=dG9rZW4tMjAxNw` |
| Format | XML, UTF-8, une fiche `<sanctionEntity>` par personne ou organisation |
| Licence | Réutilisation autorisée (décision 2011/833/UE) : citer « © Union européenne » |
| Mise à jour | À chaque modification de la liste, souvent plusieurs fois par semaine |

Le serveur de la Commission répond HTTP 500 à une requête qui accepte du HTML. Le core demande donc du XML (`Accept: application/xml`), voir `HttpDownloader`.

Structure simplifiée d'une fiche :

```xml
<sanctionEntity logicalId="13" euReferenceNumber="EU.27.28" unitedNationId="">
  <regulation programme="IRQ" entryIntoForceDate="..." publicationDate="..." numberTitle="...">
    <publicationUrl>http://eur-lex...</publicationUrl>
  </regulation>
  <subjectType code="person"/>
  <nameAlias wholeName="..." firstName="..." lastName="..." gender="M" function="..." title="..."/>
  <citizenship countryIso2Code="RU"/>
  <birthdate birthdate="1965-03-15" city="..." countryIso2Code="RU"/>
  <address street="..." city="..." countryIso2Code="RU"><contactInfo key="EMAIL" value="..."/></address>
  <identification identificationTypeCode="passport" number="..."/>
</sanctionEntity>
```

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Fournisseur | `EU` – Union européenne |
| Nom du dataset | `eu_sanctions` |
| Plugin-Id | `eu-parser` |
| Type de source | `HTTP_FILE` |
| Nature de la source | T1 – Fichier open data (XML) |
| Fréquence | 1 h |
| Rétention | 0 |
| Paramètres | aucun |
| Source PEP | aucune |

## Ce qui est produit (FtM)

Chaque fiche produit deux entités :

| Entité | ID | Contenu |
|---|---|---|
| `Person` (sujet `person`) ou `Organization` (sujet `enterprise`) | `eu-fsd-<logicalId>`, ex. `eu-fsd-13` | voir ci-dessous, topic `sanction` |
| `Sanction` | `logicalId` + `sanction` | sujet sanctionné, autorité « European Union », référence UE (`authorityId`, ex. `EU.27.28`), référence ONU (`unscId`), programme (ex. `UKR`), dates d'entrée en vigueur et de publication, règlement (`provisions`), lien EUR-Lex, motifs |

Correspondance des champs :
- **Noms** : le premier `wholeName` devient le nom principal, les suivants des `alias`. Un alias marqué `strong="false"` par l'UE devient un `weakAlias`. Pour une personne, sont aussi repris le prénom, le nom, le titre, la fonction et le genre.
- **Nationalité** : `nationality` pour une personne, `jurisdiction` pour une organisation.
- **Naissance** : date complète, ou année / mois. Une année du calendrier islamique est gardée en note, sans fausse conversion. Sont aussi repris les bornes d'année, le lieu et le pays de naissance.
- **Adresse** : adresse complète, pays, téléphone, site web et e-mails (plusieurs e-mails séparés par `;`).
- **Documents** : passeport → `passportNumber`, numéro fiscal → `taxNumber`, registre → `registrationNumber`, IMO → `imoNumber`, SWIFT/BIC → `swiftBic`, tout autre document → `idNumber`.
- **Remarques** : celles de la fiche et de ses sous-éléments vont dans `notes`.

## Règles et erreurs

- Une fiche sans `logicalId` est ignorée : sans lui, aucun ID stable n'est possible.
- Les pays « UNKNOWN » et le code `00` ne sont pas repris.
- Le contrôle qualité du core bloque la publication si le nombre d'entités baisse de plus de 20 % (statut BLOCKED : l'ancienne liste reste publiée).

## Tests

`EuSanctionsParserTest`, sur un extrait de fichier (`src/test/resources/eu_sample.xml` : une personne, une organisation). Il vérifie :
- l'écriture du NDJSON, une entité par ligne, sans valeur rejetée ;
- un sujet et une sanction par fiche ;
- les noms, alias et alias faibles, le genre ;
- les pays, la naissance et les documents ;
- les références juridiques de la sanction ;
- l'adresse, les contacts et les identifiants de l'organisation ;
- les ID et noms renvoyés pour le test du parser (`readSourceRecords`).

## Construire

```bash
mvn -pl swiftcheck-aml-plugins/swiftcheck-aml-eu -am package
```
