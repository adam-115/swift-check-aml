# fr_pep_eu_commission – Commissaire européen français

## Ce que fait ce parser

Il lit la **fiche officielle du membre français de la Commission européenne** sur commission.europa.eu et la convertit en entités FollowTheMoney (FtM).

Mesure du 28/10/2026 : Stéphane Séjourné, vice-président exécutif (2024-2029), prospérité et stratégie industrielle.

## Contexte

| | |
|---|---|
| Pays | France |
| Catégorie PEP | **FR-EU** « Français exerçant une fonction européenne » (JO C/2023/611) |
| Fonction couverte | Membre français de la Commission européenne (1 titulaire) |
| Classe de risque | 1 |
| Rétention | 12 mois : le commissaire sortant reste publié un an (rétention gérée par le core) |

## Source

| | |
|---|---|
| Éditeur | Commission européenne |
| Page | `https://commission.europa.eu/about/organisation/college-commissioners/stephane-sejourne_fr` |
| Format | page HTML |

```html
<meta name="description" content="Stéphane Séjourné est le commissaire français chargé de …">
<h1 class="ecl-page-header__title"><span>Stéphane Séjourné</span></h1>
<p class="ecl-page-header__description">VICE-PRÉSIDENT EXÉCUTIF (2024-2029) | Prospérité et stratégie industrielle
```

La page du collège ne donne pas la nationalité des commissaires. C'est donc la **fiche du commissaire français qui est configurée**, et le parser vérifie que sa description le présente comme français (« commissaire français », « French », « from France »). Une autre fiche est refusée. Le parser `lu_pep_eu_commission` reconnaît son commissaire par son nom ; ce n'est pas repris ici.

**À chaque nouvelle Commission, l'adresse change** : il faut la remplacer dans l'écran « Parsers ».

## Configuration dans SwiftCheck (écran « Parsers »)

| Champ | Valeur |
|---|---|
| Nom du dataset | `fr_pep_eu_commission` |
| Plugin-Id | `fr-pep-eu-commission` |
| Type de source | `HTTP_FILE` : le core télécharge la page, le plugin la lit seulement |
| Nature de la source | T5 – Page web officielle |
| Adresse | voir ci-dessus |
| Fréquence | 168 h |
| Paramètres | aucun |

## Ce qui est produit (FtM)

| Entité | ID | Contenu |
|---|---|---|
| `Person` | `fr-pep-eu-commission-<nom>` | nom, prénom, pays et nationalité `fr`, fonctions (« Vice-président exécutif – Prospérité et stratégie industrielle »), topic `role.pep` |
| `Position` | `fr-pep-eu-commission-member` | « Membre français de la Commission européenne », topics `gov.igo`, `gov.executive` |
| `Occupancy` | nom + `member` + année | année de début, statut `current`, rôle et portefeuille |

## Règles et erreurs

- Fiche qui ne présente pas un commissaire français, ou sans nom ni fonction : l'exécution s'arrête (FAILED).

## Limites connues

- La fiche ne donne ni la date de naissance ni la date exacte de prise de fonctions (seulement l'année du mandat).
- Les autres fonctions européennes de la catégorie FR-EU (CJUE, Cour des comptes européenne, BCE, SEAE) ne sont pas couvertes.

## Tests

`FrPepEuCommissionParserTest`, sur les fiches réelles de Stéphane Séjourné et de Kaja Kallas (refusée : Estonie).

## Construire

```bash
mvn -pl swiftcheck-aml-plugins/pep/france/swiftcheck-aml-fr-pep-eu-commission -am package
```
