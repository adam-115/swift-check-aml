# es_pep_eu_commission – Commissaire européenne

## Ce que fait ce parser

Reprise de `be_pep_eu_commission` : le core télécharge la fiche et le plugin la lit. La page du collège ne donne pas la nationalité : c'est la fiche du commissaire espagnol qui est configurée, et sa description doit le dire (« Spanish », « commissaire espagnole »). Une autre fiche est refusée.

## Contexte

| | |
|---|---|
| Bloc du référentiel | **ES-EU** (loi 10/2010, art. 14 ; liste de la Commission, JO C/2023/724) |
| Source | Fiche officielle sur `commission.europa.eu` (Teresa Ribera, vice-présidente exécutive, 2024-2029) |
| Rétention | 24 mois après la fin des fonctions (loi 10/2010, art. 14.9) |

## Configuration dans SwiftCheck

| Champ | Valeur |
|---|---|
| Nom du dataset | `es_pep_eu_commission` |
| Plugin-Id | `es-pep-eu-commission` |
| Type de source | HTTP_FILE (WEB_PAGE) |
| Fréquence | 168 h |
| Paramètres | aucun (adresse de la fiche dans le champ « source ») |

Données brutes archivées : La fiche HTML.

## Ce qui est produit (FtM)

`Person` (nom, nationalité `es`, fonction, portrait officiel), `Position`, `Occupancy`.

## Limites

- À chaque nouvelle Commission, l'adresse de la fiche change : la modifier dans l'écran « Parsers ». Le sortant est gardé par le core 24 mois.
- Ni date de naissance ni date exacte de prise de fonctions sur la fiche.

## Tests

`EsPepEuCommissionParserTest` sur les fiches réelles de Teresa Ribera, Kaja Kallas (refusée : Estonie) et Stéphane Séjourné (refusé : France).

Construire : `mvn -pl swiftcheck-aml-plugins/pep/spain/swiftcheck-aml-es-pep-eu-commission -am package`.
